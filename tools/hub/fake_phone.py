"""A stand-in for the Minis phone, used to prove the executor works.

It is not the Android app: it just connects as `kind:"phone"`, sends one call,
prints any approval card it is shown, optionally answers it, and prints the
result. That is enough to exercise contract A1 (read-only shell) and A2
(gated shell with an approval round trip).

    python fake_phone.py --target pc-ausa --cmd "ls ~"
    python fake_phone.py --target pc-ausa --cmd "rm -rf hubroot/x" --auto allow
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
import uuid

from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed

import hubkit


def call_id(prefix: str, seq: int) -> str:
    return f"{prefix}:{seq}:{uuid.uuid4().hex[:4]}"


async def main() -> int:
    parser = argparse.ArgumentParser(description="fake Minis phone")
    parser.add_argument("--relay", default="ws://127.0.0.1:8787/v1/hub")
    parser.add_argument("--key", default="nm_phone")
    parser.add_argument("--id", default="phone-fake")
    parser.add_argument("--name", default="Phone")
    parser.add_argument("--target", required=True, help="device id or name to call")
    parser.add_argument("--action", default="shell")
    parser.add_argument("--args", default="{}", help="JSON object of action arguments")
    parser.add_argument("--cmd", default="", help="shorthand for --action shell --args {\"command\":...}")
    parser.add_argument("--auto", choices=("allow", "deny", "none"), default="allow")
    parser.add_argument("--timeout", type=float, default=90.0)
    args = parser.parse_args()

    log = hubkit.setup_logging("phone")
    deadline = time.time() + args.timeout
    shell_id = call_id(args.id, 1)
    approve_id = call_id(args.id, 2)
    target: str | None = None
    done = asyncio.Event()
    exit_code = 1

    action = args.action
    call_args = json.loads(args.args or "{}")
    if args.cmd:
        action, call_args = "shell", {"command": args.cmd}

    async with connect(
        args.relay,
        additional_headers={"Authorization": f"Bearer {args.key}"},
        ping_interval=20,
        ping_timeout=20,
        max_size=64 * 1024 * 1024,
    ) as ws:
        await ws.send(
            json.dumps(
                {
                    "type": "hello",
                    "device": {
                        "id": args.id,
                        "name": args.name,
                        "kind": "phone",
                        "os": "fake",
                        "version": "fake-phone/0.1",
                        "actions": ["info"],
                    },
                }
            )
        )

        while not done.is_set() and time.time() < deadline:
            try:
                raw = await asyncio.wait_for(ws.recv(), timeout=max(1.0, deadline - time.time()))
            except asyncio.TimeoutError:
                log.error("timed out waiting for the relay")
                break
            except ConnectionClosed as exc:
                log.error("link closed: %s %s", exc.rcvd.code if exc.rcvd else "", exc)
                break

            frame = json.loads(raw)
            kind = frame.get("type")

            if kind == "welcome":
                devices = frame.get("devices") or []
                log.info("welcome: server=%s devices=%s", (frame.get("server") or {}).get("version"),
                         [(d.get("name"), d.get("id"), d.get("online")) for d in devices])
                for device in devices:
                    if args.target in (device.get("id"), device.get("name")):
                        target = device.get("id")
                        break
                if target is None:
                    log.error("target %r not in the device list", args.target)
                    break
                log.info("calling %s: %s %s", target, action, json.dumps(call_args, ensure_ascii=False))
                await ws.send(
                    json.dumps(
                        {
                            "type": "call",
                            "id": shell_id,
                            "to": target,
                            "action": action,
                            "args": call_args,
                        }
                    )
                )

            elif kind == "devices":
                pass

            elif kind == "event":
                body = frame.get("body") or {}
                stage = body.get("stage")
                if stage == "approval":
                    log.info(
                        "APPROVAL CARD risk=%s preview=%s timeout=%ss any_device=%s",
                        body.get("risk"), body.get("preview"), body.get("timeout"), body.get("any_device"),
                    )
                    if args.auto == "none":
                        continue
                    await asyncio.sleep(0.2)
                    allow = args.auto == "allow"
                    log.info("answering approval %s -> %s", body.get("approval_id"), allow)
                    await ws.send(
                        json.dumps(
                            {
                                "type": "call",
                                "id": approve_id,
                                "to": target,
                                "action": "approve",
                                "args": {"approval_id": body.get("approval_id"), "allow": allow, "scope": "once"},
                            }
                        )
                    )
                elif stage == "approval_result":
                    log.info("approval_result status=%s by=%s", body.get("status"), body.get("by"))

            elif kind == "result":
                if frame.get("id") == approve_id:
                    continue
                if frame.get("ok"):
                    body = frame.get("body") or {}
                    log.info("RESULT ok in %sms", body.get("duration_ms") or 0)
                    if "stdout" in body:
                        print("----- stdout -----")
                        print(str(body.get("stdout", "")).rstrip())
                        if body.get("stderr"):
                            print("----- stderr -----")
                            print(str(body["stderr"]).rstrip())
                        print("------------------")
                        print(f"exit_code={body.get('exit_code')} truncated={body.get('truncated', False)}")
                    else:
                        slim = {
                            k: (f"<{len(v)} base64 chars>" if k == "data" and isinstance(v, str) else v)
                            for k, v in body.items()
                        }
                        print(json.dumps(slim, ensure_ascii=False, indent=2)[:2000])
                    exit_code = 0
                else:
                    log.error("RESULT failed: %s — %s", frame.get("error"), frame.get("message"))
                    exit_code = 2
                done.set()

            elif kind == "error":
                log.error("relay error: %s — %s (id=%s)", frame.get("code"), frame.get("message"), frame.get("id"))
                if frame.get("id") == shell_id:
                    exit_code = 2
                    done.set()

            elif kind == "pong":
                pass

    return exit_code


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
