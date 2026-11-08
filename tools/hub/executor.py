"""Minis hub executor — the computer side of contract ② (phone drives PC).

Speaks nanoMuse Hub 0.31 (docs/specs/hub-contract.md). Declares its actions in
`hello`, answers `call` frames, gates them through policy.Gate, and asks a human
before anything dangerous by relaying an `approval` event back down the call.

    python executor.py --config config.dev.json
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import io
import json
import mimetypes
import os
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
from collections import OrderedDict
from typing import Any

from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed

import hubkit
import policy
from hubkit import ActionError, Config, brief, fail, hello_frame, load_config, ok, setup_logging

TAG = "executor"

#: Close codes that mean "do not hammer the relay" (contract C1.5).
REFUSING_CODES = {4001: "bad key", 4002: "bad device"}


class Journal:
    """Append-only record of every call (contract C7.4, C9)."""

    def __init__(self, path: str):
        self.path = path
        parent = os.path.dirname(path)
        if parent:
            os.makedirs(parent, exist_ok=True)

    def write(self, **record: Any) -> None:
        record.setdefault("ts", hubkit.now_ms())
        try:
            with open(self.path, "a", encoding="utf-8") as fh:
                fh.write(json.dumps(record, ensure_ascii=False) + "\n")
        except OSError:
            pass


def _decode(raw: bytes) -> str:
    if not raw:
        return ""
    for encoding in ("utf-8", sys.getfilesystemencoding() or "utf-8", "gbk", "latin-1"):
        try:
            return raw.decode(encoding)
        except (UnicodeDecodeError, LookupError):
            continue
    return raw.decode("utf-8", errors="replace")


def _shell_argv(cfg: Config, command: str) -> list[str]:
    program = (cfg.shell or {}).get("program")
    args = (cfg.shell or {}).get("args")
    if program:
        return [program, *(args or ["-lc"]), command]
    if os.name == "nt":
        for candidate in (
            r"C:\Program Files\Git\bin\bash.exe",
            r"C:\Program Files\Git\usr\bin\bash.exe",
        ):
            if os.path.exists(candidate):
                return [candidate, "-lc", command]
        return ["cmd.exe", "/c", command]
    return ["/bin/sh", "-lc", command]


def _capture_screen(cfg: Config) -> dict[str, Any]:
    try:
        from PIL import ImageGrab
    except ImportError as exc:  # pragma: no cover - dependency is optional
        raise ActionError("not_allowed", f"screen capture unavailable: {exc}") from exc
    try:
        image = ImageGrab.grab()
    except Exception as exc:  # noqa: BLE001 - headless or locked session
        raise ActionError("not_allowed", f"screen capture failed: {exc}") from exc

    width, height = image.size
    long_edge = int((cfg.limits or {}).get("screen_long_edge", 1600))
    if max(width, height) > long_edge:
        scale = long_edge / float(max(width, height))
        image = image.resize((max(1, int(width * scale)), max(1, int(height * scale))))

    buffer = io.BytesIO()
    quality = int((cfg.limits or {}).get("screen_jpeg_quality", 80))
    image.convert("RGB").save(buffer, format="JPEG", quality=quality, optimize=True)
    payload = base64.b64encode(buffer.getvalue()).decode("ascii")
    return {"mime": "image/jpeg", "data": payload, "width": image.size[0], "height": image.size[1]}


class Executor:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.gate = policy.Gate(cfg)
        self.journal = Journal(cfg.journal)
        self.log = setup_logging(TAG, debug=False)
        self.ws = None
        self.frame_limit = 16 * 1024 * 1024
        self.devices: list[dict[str, Any]] = []
        self._approvals: dict[str, asyncio.Future] = {}
        self._done: OrderedDict[str, dict[str, Any]] = OrderedDict()
        self._inflight = 0
        self._stop = asyncio.Event()

    # ------------------------------------------------------------------ link
    async def run(self) -> int:
        backoff = 1.0
        while not self._stop.is_set():
            try:
                await self._session()
                backoff = 1.0
            except _Refused as exc:
                self.log.error("relay refused this device: %s", exc)
                return 2
            except ConnectionClosed as exc:
                code = exc.rcvd.code if exc.rcvd else None
                reason = (exc.rcvd.reason if exc.rcvd else "") or ""
                if code in REFUSING_CODES:
                    self.log.error("relay refused: %s (%s) — check api_key / device id", code, reason)
                    return 2
                self.log.warning("disconnected: %s %s", code, reason)
            except OSError as exc:
                self.log.warning("link error: %s", exc)
            except asyncio.CancelledError:
                raise
            finally:
                self._fail_pending("the link went away")
            if self._stop.is_set():
                break
            self.log.info("reconnecting in %.0fs", backoff)
            try:
                await asyncio.wait_for(self._stop.wait(), timeout=backoff)
            except asyncio.TimeoutError:
                pass
            backoff = min(backoff * 2, 60.0)
        return 0

    async def _session(self) -> None:
        self.log.info("connecting to %s as %s", self.cfg.relay, self.cfg.device_id)
        async with connect(
            self.cfg.relay,
            additional_headers={"Authorization": f"Bearer {self.cfg.api_key}"},
            ping_interval=20,
            ping_timeout=20,
            open_timeout=20,
            max_size=64 * 1024 * 1024,
        ) as ws:
            self.ws = ws
            await ws.send(json.dumps(hello_frame(self.cfg), ensure_ascii=False))
            welcome = json.loads(await asyncio.wait_for(ws.recv(), timeout=15))
            if welcome.get("type") != "welcome":
                raise _Refused(f"expected welcome, got {welcome.get('type')!r}")
            server = welcome.get("server") or {}
            self.frame_limit = int(server.get("frame_limit") or self.frame_limit)
            self.devices = welcome.get("devices") or []
            self.log.info(
                "welcome: id=%s server=%s frame_limit=%s features=%s devices=%s",
                welcome.get("device_id"),
                server.get("version"),
                self.frame_limit,
                server.get("features"),
                [d.get("name") for d in self.devices],
            )
            pinger = asyncio.create_task(self._pinger(), name="app-ping")
            try:
                async for raw in ws:
                    self._on_frame(raw)
            finally:
                pinger.cancel()
                self.ws = None

    async def _pinger(self) -> None:
        """Application-level keepalive (contract C1.4, 25 s)."""
        while True:
            await asyncio.sleep(25)
            await self._send({"type": "ping"})

    async def _send(self, frame: dict[str, Any]) -> None:
        ws = self.ws
        if ws is None:
            return
        try:
            await ws.send(json.dumps(frame, ensure_ascii=False))
        except ConnectionClosed:
            pass

    def _fail_pending(self, why: str) -> None:
        for approval_id, fut in list(self._approvals.items()):
            if not fut.done():
                fut.set_result((False, None, "aborted"))
            self._approvals.pop(approval_id, None)
        self.log.debug("pending approvals aborted: %s", why)

    # ----------------------------------------------------------------- frames
    def _on_frame(self, raw: str | bytes) -> None:
        if len(raw) > self.frame_limit:
            self.log.warning("dropping oversized inbound frame: %d bytes", len(raw))
            return
        try:
            frame = json.loads(raw)
        except ValueError:
            self.log.warning("bad json from relay")
            return
        if not isinstance(frame, dict):
            return
        kind = frame.get("type")
        if kind == "call":
            asyncio.create_task(self._handle_call(frame))
        elif kind == "devices":
            self.devices = frame.get("devices") or []
            self.log.debug("devices: %s", [d.get("name") for d in self.devices])
        elif kind == "pong":
            pass
        elif kind == "error":
            self.log.warning("relay error: %s %s (id=%s)", frame.get("code"), frame.get("message"), frame.get("id"))
        elif kind == "event":
            body = frame.get("body") or {}
            self.log.debug("event %s from %s", body.get("stage"), (frame.get("from") or {}).get("name"))

    # ------------------------------------------------------------------ calls
    async def _handle_call(self, frame: dict[str, Any]) -> None:
        call_id = str(frame.get("id") or "")
        sender = frame.get("from") or {}
        sender_id = str(sender.get("id") or "")
        sender_name = str(sender.get("name") or sender_id or "a device")
        action = str(frame.get("action") or "")
        args = frame.get("args") or {}
        if not isinstance(args, dict):
            args = {}

        if action == "approve":
            await self._on_approve(call_id, sender_id, args)
            return
        if action == "stop":
            await self._on_stop(call_id, sender_id, args)
            return

        if not self.cfg.remote_control:
            await self._send(fail(call_id, "not_allowed", "remote control is off on this device"))
            return

        if call_id in self._done:
            self.log.info("replayed call %s — returning the recorded result", call_id)
            await self._send(self._done[call_id])
            return

        if self._inflight >= self.cfg.max_concurrent_calls:
            await self._send(fail(call_id, "not_allowed", "executor is busy; try again shortly"))
            return

        started = time.time()
        decision = self.gate.decide(sender_id, action, args)
        self.log.info(
            "call %s from %s: %s -> %s (%s)",
            call_id, sender_name, action, decision.mode, decision.reason,
        )

        if decision.mode == policy.DENY:
            await self._send(fail(call_id, "not_allowed", f"denied: {decision.reason}"))
            self._record(call_id, sender_id, action, started, "denied", None, decision.reason)
            return

        if decision.mode == policy.ASK:
            allowed, approver, status = await self._ask(call_id, sender_name, action, args, decision)
            if not allowed:
                message = "denied by a person" if status == "denied" else f"approval {status}"
                await self._send(fail(call_id, "not_allowed", message))
                self._record(call_id, sender_id, action, started, status, None, decision.reason)
                return

        self._inflight += 1
        try:
            body = await self._dispatch(action, args)
            frame_out = ok(call_id, body)
            self._remember(call_id, frame_out)
            await self._send(frame_out)
            self._record(call_id, sender_id, action, started, "ok", body.get("exit_code"), decision.reason)
            self.log.info("call %s done in %.0fms", call_id, (time.time() - started) * 1000)
        except ActionError as exc:
            self._remember(call_id, fail(call_id, exc.code, exc.message))
            await self._send(fail(call_id, exc.code, exc.message))
            self._record(call_id, sender_id, action, started, f"error:{exc.code}", None, exc.message)
            self.log.warning("call %s failed: %s %s", call_id, exc.code, exc.message)
        except Exception as exc:  # noqa: BLE001 - never leave a caller hanging
            self.log.exception("call %s crashed", call_id)
            await self._send(fail(call_id, "not_allowed", f"executor error: {exc}"))
            self._record(call_id, sender_id, action, started, "crash", None, str(exc))
        finally:
            self._inflight -= 1

    # -------------------------------------------------------------- approvals
    async def _ask(
        self,
        call_id: str,
        sender_name: str,
        action: str,
        args: dict[str, Any],
        decision: policy.Decision,
    ) -> tuple[bool, dict[str, Any] | None, str]:
        approval_id = f"ap-{uuid.uuid4().hex[:12]}"
        loop = asyncio.get_running_loop()
        fut: asyncio.Future = loop.create_future()
        self._approvals[approval_id] = fut

        preview = f"{action} {brief(args)}"
        body = {
            "stage": "approval",
            "approval_id": approval_id,
            "preview": f"{action}: {brief(args, 160)}",
            "preview_zh": f"{action}：{brief(args, 160)}",
            "risk": decision.risk,
            "reason": decision.reason,
            "app": "hub-executor",
            "task": {"id": call_id, "title": f"remote {action}"},
            "chat": sender_name,
            "scopes": ["once"],
            "timeout": int(self.cfg.approval_timeout_s),
            "device": self.cfg.device_name,
            "any_device": True,
        }
        self.log.info("asking %s for approval: %s", sender_name, preview)
        await self._send({"type": "event", "id": call_id, "body": body})

        try:
            allowed, approver, status = await asyncio.wait_for(fut, timeout=self.cfg.approval_timeout_s)
        except asyncio.TimeoutError:
            allowed, approver, status = False, None, "timeout"
        finally:
            self._approvals.pop(approval_id, None)

        result_body: dict[str, Any] = {"stage": "approval_result", "approval_id": approval_id, "status": status}
        if approver:
            result_body["by"] = approver
        await self._send({"type": "event", "id": call_id, "body": result_body})
        self.log.info("approval %s -> %s%s", approval_id, status, f" by {approver}" if approver else "")
        return allowed, approver, status

    async def _on_approve(self, call_id: str, sender_id: str, args: dict[str, Any]) -> None:
        approval_id = str(args.get("approval_id") or "")
        allow = bool(args.get("allow"))
        fut = self._approvals.get(approval_id)
        if fut is None or fut.done():
            await self._send(fail(call_id, "unknown_call", "no such pending approval"))
            return
        fut.set_result((allow, {"id": sender_id}, "allowed" if allow else "denied"))
        await self._send(ok(call_id, {"approval_id": approval_id, "resolved": True}))

    async def _on_stop(self, call_id: str, sender_id: str, args: dict[str, Any]) -> None:
        # Contract C6.1: a stop never kills a command that already started —
        # killing a half-done `rm` is worse than letting it finish.
        await self._send(ok(call_id, {"stopped": False}))

    # --------------------------------------------------------------- dispatch
    async def _dispatch(self, action: str, args: dict[str, Any]) -> dict[str, Any]:
        handler = getattr(self, f"_do_{action.replace('.', '_')}", None)
        if handler is None:
            raise ActionError("not_allowed", f"{action!r} is not implemented by this executor")
        return await handler(args)

    def _path(self, value: str | None, default: str = ".") -> str:
        candidate = os.path.abspath(os.path.join(self.cfg.root, value or default))
        if not policy.path_is_inside(self.cfg.root, candidate):
            raise ActionError("not_allowed", "path is outside this executor's root")
        return candidate

    async def _do_info(self, args: dict[str, Any]) -> dict[str, Any]:
        return {
            "os": f"{sys.platform} {os.uname().release if hasattr(os, 'uname') else ''}".strip(),
            "host": os.environ.get("COMPUTERNAME") or os.environ.get("HOSTNAME") or "unknown",
            "home": os.path.expanduser("~"),
            "root": self.cfg.root,
            "cwd": self.cfg.root,
            "python": sys.version.split()[0],
            "device": self.cfg.device_id,
            "actions": list(self.cfg.actions),
        }

    async def _do_shell(self, args: dict[str, Any]) -> dict[str, Any]:
        command = str(args.get("command") or "")
        if not command.strip():
            raise ActionError("bad_frame", "shell needs args.command")
        cwd = self._path(str(args.get("cwd") or "."))
        if not os.path.isdir(cwd):
            raise ActionError("bad_frame", f"cwd does not exist: {cwd}")
        timeout_s = min(float(args.get("timeout_ms") or self.cfg.shell_timeout_s * 1000) / 1000.0, 300.0)
        argv = _shell_argv(self.cfg, command)

        flags = 0
        if os.name == "nt":
            flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        started = time.time()
        proc = await asyncio.create_subprocess_exec(
            *argv,
            cwd=cwd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            creationflags=flags,
        )
        try:
            out, err = await asyncio.wait_for(proc.communicate(), timeout=timeout_s)
            timed_out = False
        except asyncio.TimeoutError:
            timed_out = True
            if (self.cfg.shell or {}).get("kill_on_timeout", True):
                with_suppress = getattr(proc, "kill", None)
                try:
                    if with_suppress:
                        with_suppress()
                except ProcessLookupError:
                    pass
            out, err = await proc.communicate()

        stdout = _decode(out)
        stderr = _decode(err)
        truncated = False
        if len(stdout) > self.cfg.out_max:
            stdout = stdout[: self.cfg.out_max]
            truncated = True
        if len(stderr) > self.cfg.out_max:
            stderr = stderr[: self.cfg.out_max]
            truncated = True
        body: dict[str, Any] = {
            "exit_code": proc.returncode,
            "stdout": stdout,
            "stderr": stderr,
            "duration_ms": int((time.time() - started) * 1000),
        }
        if truncated:
            body["truncated"] = True
        if timed_out:
            body["timed_out"] = True
        return body

    async def _do_files(self, args: dict[str, Any]) -> dict[str, Any]:
        target = self._path(str(args.get("path") or "."))
        if os.path.isfile(target):
            raise ActionError("bad_frame", f"not a directory: {target}")
        if not os.path.isdir(target):
            raise ActionError("bad_frame", f"no such directory: {target}")
        limit = int(args.get("limit") or 200)
        entries: list[dict[str, Any]] = []
        total = 0
        with os.scandir(target) as it:
            for entry in it:
                total += 1
                if len(entries) >= limit:
                    continue
                try:
                    stat = entry.stat(follow_symlinks=False)
                    size = stat.st_size
                    mtime = int(stat.st_mtime)
                except OSError:
                    size, mtime = 0, 0
                kind = "dir" if entry.is_dir(follow_symlinks=False) else "link" if entry.is_symlink() else "file"
                entries.append({"name": entry.name, "kind": kind, "size": size, "mtime": mtime})
        entries.sort(key=lambda e: (e["kind"] != "dir", e["name"].lower()))
        body: dict[str, Any] = {"path": target, "entries": entries}
        if total > len(entries):
            body["truncated"] = True
            body["total"] = total
        return body

    async def _do_file_get(self, args: dict[str, Any]) -> dict[str, Any]:
        target = self._path(str(args.get("path") or ""))
        if not os.path.isfile(target):
            raise ActionError("bad_frame", f"no such file: {target}")
        size = os.path.getsize(target)
        if size > self.cfg.file_max:
            raise ActionError("too_large", f"{size} bytes exceeds the {self.cfg.file_max} byte limit; fetch in chunks")
        offset = max(0, int(args.get("offset") or 0))
        length = int(args.get("length") or 0) or (size - offset)
        with open(target, "rb") as fh:
            fh.seek(offset)
            chunk = fh.read(length)
        eof = offset + len(chunk) >= size
        mime = mimetypes.guess_type(target)[0] or "application/octet-stream"
        return {
            "path": target,
            "mime": mime,
            "size": size,
            "offset": offset,
            "data": base64.b64encode(chunk).decode("ascii"),
            "eof": eof,
        }

    async def _do_file_put(self, args: dict[str, Any]) -> dict[str, Any]:
        target = self._path(str(args.get("path") or ""))
        raw = args.get("data")
        if not isinstance(raw, str):
            raise ActionError("bad_frame", "file.put needs args.data (base64)")
        try:
            blob = base64.b64decode(raw, validate=True)
        except Exception as exc:  # noqa: BLE001
            raise ActionError("bad_frame", f"data is not valid base64: {exc}") from exc
        if len(blob) > self.cfg.file_max:
            raise ActionError("too_large", f"{len(blob)} bytes exceeds the {self.cfg.file_max} byte limit")
        digest = hashlib.sha256(blob).hexdigest()
        if os.path.exists(target) and os.path.getsize(target) == len(blob):
            with open(target, "rb") as fh:
                if hashlib.sha256(fh.read()).hexdigest() == digest:
                    return {"path": target, "size": len(blob), "sha256": digest, "unchanged": True}
        os.makedirs(os.path.dirname(target) or ".", exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=os.path.dirname(target) or ".", prefix=".hub-put-")
        try:
            with os.fdopen(fd, "wb") as fh:
                fh.write(blob)
            os.replace(tmp, target)
        except Exception:
            if os.path.exists(tmp):
                os.unlink(tmp)
            raise
        return {"path": target, "size": len(blob), "sha256": digest}

    async def _do_screen(self, args: dict[str, Any]) -> dict[str, Any]:
        return await asyncio.get_running_loop().run_in_executor(None, _capture_screen, self.cfg)

    async def _do_notify(self, args: dict[str, Any]) -> dict[str, Any]:
        title = str(args.get("title") or "minis")
        text = str(args.get("body") or "")
        self.log.info("notify: %s — %s", title, text)
        hook = (self.cfg.shell or {}).get("notify_command")
        if hook:
            await self._do_shell({"command": f"{hook} {json.dumps(title)} {json.dumps(text)}"})
            return {"delivered": True}
        return {"delivered": False, "reason": "no notify_command configured"}

    # ---------------------------------------------------------------- helpers
    def _remember(self, call_id: str, frame: dict[str, Any]) -> None:
        self._done[call_id] = frame
        while len(self._done) > 500:
            self._done.popitem(last=False)

    def _record(self, call_id: str, sender: str, action: str, started: float, status: str, exit_code, note: str) -> None:
        self.journal.write(
            call=call_id,
            sender=sender,
            action=action,
            status=status,
            exit_code=exit_code,
            duration_ms=int((time.time() - started) * 1000),
            note=note,
        )


class _Refused(Exception):
    pass


async def main() -> int:
    parser = argparse.ArgumentParser(description="Minis hub executor (computer side)")
    parser.add_argument("--config", default="config.json")
    parser.add_argument("--debug", action="store_true")
    args = parser.parse_args()

    cfg = load_config(args.config)
    if args.debug:
        import logging

        logging.getLogger(TAG).setLevel(logging.DEBUG)
    os.makedirs(cfg.root, exist_ok=True)
    executor = Executor(cfg)
    try:
        return await executor.run()
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
