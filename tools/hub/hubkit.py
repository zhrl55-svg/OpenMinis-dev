"""Shared pieces for the Minis hub tools.

The wire format is nanoMuse Hub 0.31 as pinned in docs/specs/hub-contract.md.
Nothing here invents fields beyond what that contract lists.
"""

from __future__ import annotations

import json
import logging
import os
import socket
import sys
import time
from dataclasses import dataclass, field
from typing import Any

PROTO_VERSION = "0.1"


def now_ms() -> int:
    return int(time.time() * 1000)


def brief(value: Any, limit: int = 220) -> str:
    """One-line, size-capped rendering for logs and approval previews."""
    if isinstance(value, dict):
        slim = {}
        for k, v in value.items():
            if isinstance(v, str) and len(v) > 120:
                slim[k] = f"<{len(v)} chars>"
            else:
                slim[k] = v
        text = json.dumps(slim, ensure_ascii=False)
    else:
        text = str(value)
    return text if len(text) <= limit else text[: limit - 1] + "…"


def setup_logging(tag: str, debug: bool = False) -> logging.Logger:
    level = logging.DEBUG if debug else logging.INFO
    logging.basicConfig(
        level=level,
        format=f"%(asctime)s [{tag}] %(message)s",
        datefmt="%H:%M:%S",
        stream=sys.stdout,
    )
    return logging.getLogger(tag)


def default_device_id(prefix: str = "pc") -> str:
    host = (socket.gethostname() or "unknown").lower()
    clean = "".join(c if c.isalnum() else "-" for c in host).strip("-")[:24]
    return f"{prefix}-{clean}"


@dataclass
class Config:
    relay: str
    api_key: str
    device_id: str
    device_name: str
    kind: str = "computer"
    root: str = "."
    remote_control: bool = True
    actions: tuple[str, ...] = ()
    policy: dict[str, Any] = field(default_factory=dict)
    limits: dict[str, Any] = field(default_factory=dict)
    shell: dict[str, Any] = field(default_factory=dict)
    journal: str = ""

    @property
    def file_max(self) -> int:
        return int(self.limits.get("file_max", 8 * 1024 * 1024))

    @property
    def out_max(self) -> int:
        return int(self.limits.get("out_max", 200_000))

    @property
    def approval_timeout_s(self) -> float:
        return float(self.limits.get("approval_timeout_s", 600))

    @property
    def shell_timeout_s(self) -> float:
        return float(self.limits.get("shell_timeout_s", 120))

    @property
    def max_concurrent_calls(self) -> int:
        return int(self.limits.get("max_concurrent_calls", 4))


def _expand(value: Any) -> Any:
    if isinstance(value, str):
        return os.path.expandvars(os.path.expanduser(value))
    if isinstance(value, list):
        return [_expand(v) for v in value]
    if isinstance(value, dict):
        return {k: _expand(v) for k, v in value.items()}
    return value


def load_config(path: str) -> Config:
    with open(path, "r", encoding="utf-8") as fh:
        raw = json.load(fh)
    raw = _expand(raw)

    # Path base = the directory the configuration file lives in, NOT the
    # working directory: `device.root`, `journal` and every path the
    # `file_max` limit applies to are resolved against `base`, so the same
    # config behaves the same however the executor was started. Absolute
    # paths are used as they are. The executor's log file belongs here too
    # (the launcher redirects stdout next to the config) — one directory for
    # the log, the journal and the file root, so "it should be here but it
    # landed there" cannot happen.
    base = os.path.dirname(os.path.abspath(path))

    def here(value: str) -> str:
        return value if os.path.isabs(value) else os.path.abspath(os.path.join(base, value))

    device = raw.get("device") or {}
    root = here(device.get("root") or raw.get("root") or ".")
    device_id = str(device.get("id") or default_device_id())
    cfg = Config(
        relay=str(raw["relay"]),
        api_key=str(raw["api_key"]),
        device_id=device_id,
        device_name=str(device.get("name") or device_id),
        kind=str(device.get("kind") or "computer"),
        root=root,
        remote_control=bool(device.get("remote_control", True)),
        actions=tuple(raw.get("actions") or ()),
        policy=raw.get("policy") or {},
        limits=raw.get("limits") or {},
        shell=raw.get("shell") or {},
        journal=here(raw.get("journal") or "journal.jsonl"),
    )
    if not cfg.actions:
        raise SystemExit("config: `actions` is empty — declare what this device can do")
    return cfg


class ActionError(Exception):
    """A business-level failure answered through result{ok:false,error,...}."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def hello_frame(cfg: Config) -> dict[str, Any]:
    # `hello` has no field for the remote-control switch, so a device with it
    # off declares no actions: the relay reads that as "not controllable" and
    # refuses calls to it before they ever reach this process.
    return {
        "type": "hello",
        "device": {
            "id": cfg.device_id,
            "name": cfg.device_name,
            "kind": cfg.kind,
            "os": f"{sys.platform}",
            "version": f"minis-hub-executor/{PROTO_VERSION}",
            "actions": list(cfg.actions) if cfg.remote_control else [],
        },
    }


def ok(call_id: str, body: dict[str, Any]) -> dict[str, Any]:
    return {"type": "result", "id": call_id, "ok": True, "body": body}


def fail(call_id: str, code: str, message: str) -> dict[str, Any]:
    return {
        "type": "result",
        "id": call_id,
        "ok": False,
        "error": code,
        "message": message,
        "body": {},
    }
