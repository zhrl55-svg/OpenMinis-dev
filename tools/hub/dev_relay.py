"""A minimal hub relay for local development.

It implements the routing half of nanoMuse Hub 0.31 — hello/welcome, devices,
call, result, event (both the call-scoped and the account-level push), ping —
so the executor can be exercised end to end without the real cloud.

**Dev only.** One process, memory only, no persistence, one static key. In
production this is `cloud/nanomuse_cloud/hub.py` (or a hardened rewrite); the
frame shapes are identical, so nothing on the executor side changes.

    python dev_relay.py --port 8787 --key nm_dev
"""

from __future__ import annotations

import argparse
import asyncio
import json
import time
from collections import defaultdict, deque
from dataclasses import dataclass, field
from typing import Any

from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed

import hubkit
from hubkit import brief, setup_logging

TAG = "relay"

CALL_TIMEOUT_S = 900.0          # contract §7
PUSH_PER_MINUTE = 30            # contract C4.3
PUSH_BODY_MAX = 16 * 1024
PUSH_STAGES = {"approval", "approval_result"}
FRAMES_PER_SECOND = 60
BYTES_PER_SECOND = 8 * 1024 * 1024


@dataclass
class Peer:
    ws: Any
    key: str
    device_id: str = ""
    name: str = ""
    kind: str = ""
    info: dict[str, Any] = field(default_factory=dict)
    online: bool = True
    last_seen: float = field(default_factory=time.time)
    pushes: deque = field(default_factory=deque)
    window_start: float = 0.0
    window_frames: int = 0
    window_bytes: int = 0
    warned: bool = False

    def describe(self) -> dict[str, Any]:
        return {
            "id": self.device_id,
            "name": self.name,
            "kind": self.kind,
            "os": self.info.get("os", ""),
            "version": self.info.get("version", ""),
            "actions": self.info.get("actions", []),
            "online": self.online,
            "last_seen": int(self.last_seen),
            "controllable": self.controllable,
            "ip": "",
        }

    @property
    def controllable(self) -> bool:
        """`hello` has no switch for this, so a device with no actions is off."""
        return self.kind != "web" and bool(self.info.get("actions"))


class Relay:
    def __init__(self, keys: list[str], frame_limit: int):
        self.keys = set(keys)
        self.frame_limit = frame_limit
        self.log = setup_logging(TAG)
        self.peers: dict[str, Peer] = {}
        self.by_ws: dict[int, Peer] = {}
        self.pending: dict[str, dict[str, Any]] = {}
        self.done: deque = deque(maxlen=2000)
        self.owner: dict[str, str] = {}

    # ------------------------------------------------------------------ frames
    async def _send(self, ws: Any, frame: dict[str, Any]) -> None:
        try:
            await ws.send(json.dumps(frame, ensure_ascii=False))
        except (ConnectionClosed, RuntimeError):
            pass

    def _error(self, ws: Any, code: str, message: str, call_id: str | None = None) -> None:
        frame: dict[str, Any] = {"type": "error", "code": code, "message": message}
        if call_id:
            frame["id"] = call_id
        asyncio.create_task(self._send(ws, frame))

    def _devices(self) -> dict[str, Any]:
        return {"type": "devices", "devices": [p.describe() for p in self.peers.values()]}

    async def _broadcast_devices(self) -> None:
        frame = self._devices()
        for peer in list(self.peers.values()):
            await self._send(peer.ws, frame)

    # --------------------------------------------------------------- lifecycle
    async def handler(self, ws: Any) -> None:
        request = getattr(ws, "request", None)
        header = ""
        if request is not None:
            header = request.headers.get("Authorization", "")
        key = header[7:].strip() if header.lower().startswith("bearer ") else ""

        if key not in self.keys:
            self.log.warning("rejecting socket: bad key %r", brief(key, 40))
            await ws.close(4001, "bad_key")
            return

        peer = Peer(ws=ws, key=key)
        self.by_ws[id(ws)] = peer

        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=15)
        except asyncio.TimeoutError:
            self.log.warning("closing: no hello within 15s")
            await ws.close(4000, "hello expected")
            return
        except ConnectionClosed:
            return

        frame = self._parse(ws, raw)
        if frame is None:
            await ws.close(4000, "hello expected")
            return
        if frame.get("type") != "hello":
            await ws.close(4000, "hello expected")
            return

        device = frame.get("device") or {}
        device_id = str(device.get("id") or "")
        if not device_id:
            await ws.close(4002, "bad_device")
            return

        bound = self.owner.get(key)
        if bound and bound != device_id:
            self.log.warning("key already owns %s, refusing %s", bound, device_id)
            await ws.close(4002, "bad_device")
            return
        self.owner[key] = device_id

        previous = self.peers.get(device_id)
        if previous is not None and previous.ws is not ws:
            self.log.info("device %s reconnected; closing the old socket", device_id)
            await previous.ws.close(4003, "replaced")

        peer.device_id = device_id
        peer.name = str(device.get("name") or device_id)
        peer.kind = str(device.get("kind") or "computer")
        peer.info = dict(device)
        self.peers[device_id] = peer

        welcome = {
            "type": "welcome",
            "device_id": device_id,
            "devices": [p.describe() for p in self.peers.values()],
            "server": {
                "version": f"dev-relay/{hubkit.PROTO_VERSION}",
                "frame_limit": self.frame_limit,
                "time": int(time.time()),
                "features": ["event_push"],
            },
        }
        await self._send(ws, welcome)
        await self._broadcast_devices()
        self.log.info("hello from %s (%s) actions=%s", peer.name, device_id, peer.info.get("actions"))

        try:
            async for raw in ws:
                await self._on_frame(peer, raw)
        except ConnectionClosed:
            pass
        finally:
            peer.online = False
            peer.last_seen = time.time()
            if self.peers.get(peer.device_id) is peer:
                self.peers.pop(peer.device_id, None)
            self.by_ws.pop(id(ws), None)
            self.log.info("%s left", peer.name or peer.device_id)
            await self._broadcast_devices()

    def _parse(self, ws: Any, raw: str | bytes) -> dict[str, Any] | None:
        if isinstance(raw, bytes):
            self._error(ws, "bad_frame", "binary frames are not part of the protocol")
            return None
        if len(raw) > self.frame_limit:
            self._error(ws, "too_large", f"frame over {self.frame_limit} bytes")
            return None
        try:
            frame = json.loads(raw)
        except ValueError:
            self._error(ws, "bad_frame", "not JSON")
            return None
        if not isinstance(frame, dict):
            self._error(ws, "bad_frame", "not a frame object")
            return None
        return frame

    def _rate_ok(self, peer: Peer, size: int, ws: Any) -> bool:
        now = time.time()
        if now - peer.window_start >= 1.0:
            peer.window_start = now
            peer.window_frames = 0
            peer.window_bytes = 0
            peer.warned = False
        peer.window_frames += 1
        peer.window_bytes += size
        if peer.window_frames > FRAMES_PER_SECOND or peer.window_bytes > BYTES_PER_SECOND:
            if not peer.warned:
                peer.warned = True
                self._error(ws, "rate_limited", "slow down: 60 frames or 8MB a second")
            return False
        return True

    async def _on_frame(self, peer: Peer, raw: str | bytes) -> None:
        size = len(raw)
        if not self._rate_ok(peer, size, peer.ws):
            return
        frame = self._parse(peer.ws, raw)
        if frame is None:
            return
        peer.last_seen = time.time()
        kind = frame.get("type")

        if kind == "call":
            await self._on_call(peer, frame)
        elif kind == "result":
            await self._on_result(peer, frame)
        elif kind == "event":
            await self._on_event(peer, frame, raw)
        elif kind == "devices":
            await self._send(peer.ws, self._devices())
        elif kind == "rename":
            peer.name = str(frame.get("name") or peer.name)
            await self._broadcast_devices()
        elif kind == "forget":
            await self._on_forget(peer, str(frame.get("device_id") or ""))
        elif kind == "ping":
            await self._send(peer.ws, {"type": "pong"})
        elif kind == "hello":
            self._error(peer.ws, "bad_frame", "hello only once per connection")
        elif kind in ("pong", "profile", "sync", "working", "stop", "devices_ack"):
            pass
        else:
            self._error(peer.ws, "bad_frame", f"unknown frame type {kind!r}")

    async def _on_call(self, peer: Peer, frame: dict[str, Any]) -> None:
        call_id = str(frame.get("id") or "")
        to = str(frame.get("to") or "")
        action = str(frame.get("action") or "")
        args = frame.get("args") or {}

        if not call_id:
            self._error(peer.ws, "bad_frame", "call without id")
            return
        if to == peer.device_id:
            self._error(peer.ws, "self_call", "cannot call yourself", call_id)
            return
        if call_id in self.pending or any(entry == call_id for entry in self.done):
            self._error(peer.ws, "duplicate_call", f"call {call_id} is already known", call_id)
            return
        target = self.peers.get(to)
        if target is None:
            self._error(peer.ws, "device_offline", f"{to} is not connected", call_id)
            return
        if target.kind == "web":
            self._error(peer.ws, "not_controllable", "a web device is never a target", call_id)
            return
        if not target.controllable:
            self._error(peer.ws, "not_controllable", f"{target.name} has remote control off", call_id)
            return

        record = {"from": peer.device_id, "to": to, "action": action}
        self.pending[call_id] = record
        record["timer"] = asyncio.create_task(self._expire(call_id, peer.ws))
        self.log.info("call %s: %s -> %s %s", call_id, peer.name, target.name, brief(args, 120))
        await self._send(
            target.ws,
            {
                "type": "call",
                "id": call_id,
                "from": {"id": peer.device_id, "name": peer.name, "kind": peer.kind},
                "action": action,
                "args": args,
            },
        )

    async def _expire(self, call_id: str, caller_ws: Any) -> None:
        try:
            await asyncio.sleep(CALL_TIMEOUT_S)
        except asyncio.CancelledError:
            return
        if self.pending.pop(call_id, None) is not None:
            self.done.append(call_id)
            self.log.info("call %s timed out", call_id)
            self._error(caller_ws, "timeout", "nobody answered within 15 minutes", call_id)

    async def _on_result(self, peer: Peer, frame: dict[str, Any]) -> None:
        call_id = str(frame.get("id") or "")
        record = self.pending.pop(call_id, None)
        if record is None:
            self._error(peer.ws, "unknown_call", f"no pending call {call_id}", call_id)
            return
        record["timer"].cancel()
        self.done.append(call_id)
        caller = self.peers.get(record["from"])
        if caller is None:
            return
        out = dict(frame)
        out["from"] = {"id": peer.device_id, "name": peer.name, "kind": peer.kind}
        if not frame.get("ok"):
            self.log.info("call %s failed: %s", call_id, frame.get("error"))
        await self._send(caller.ws, out)

    async def _on_event(self, peer: Peer, frame: dict[str, Any], raw: str | bytes) -> None:
        call_id = frame.get("id")
        body = frame.get("body") or {}
        if call_id:
            record = self.pending.get(str(call_id))
            if record is None:
                self._error(peer.ws, "unknown_call", f"no pending call {call_id}", str(call_id))
                return
            other = record["to"] if peer.device_id == record["from"] else record["from"]
            target = self.peers.get(other)
            if target is None:
                return
            await self._send(
                target.ws,
                {
                    "type": "event",
                    "id": str(call_id),
                    "from": {"id": peer.device_id, "name": peer.name, "kind": peer.kind},
                    "body": body,
                },
            )
            return

        # account-level push (relay 0.31): no id, only approval stages, rate limited
        stage = body.get("stage")
        if stage not in PUSH_STAGES:
            self.log.debug("dropping push with stage %r", stage)
            return
        size = len(raw) if isinstance(raw, (bytes, str)) else 0
        if size > PUSH_BODY_MAX:
            self._error(peer.ws, "too_large", f"push bodies are capped at {PUSH_BODY_MAX} bytes")
            return
        now = time.time()
        while peer.pushes and now - peer.pushes[0] > 60:
            peer.pushes.popleft()
        if len(peer.pushes) >= PUSH_PER_MINUTE:
            self._error(peer.ws, "rate_limited", "30 pushes a minute")
            return
        peer.pushes.append(now)

        to = str(frame.get("to") or "*")
        targets = [p for p in self.peers.values() if to == "*" or p.device_id == to]
        for target in targets:
            if target.device_id == peer.device_id:
                continue
            await self._send(
                target.ws,
                {
                    "type": "event",
                    "from": {"id": peer.device_id, "name": peer.name, "kind": peer.kind},
                    "body": body,
                },
            )

    async def _on_forget(self, peer: Peer, device_id: str) -> None:
        if device_id in self.peers:
            self._error(peer.ws, "device_online", f"{device_id} is connected right now")
            return
        await self._broadcast_devices()


async def main() -> int:
    parser = argparse.ArgumentParser(description="Minis hub dev relay")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--key", action="append", dest="keys", default=[], help="repeatable: one key per device")
    parser.add_argument("--frame-limit", type=int, default=16 * 1024 * 1024)
    args = parser.parse_args()
    if not args.keys:
        args.keys = ["nm_dev"]

    relay = Relay(args.keys, args.frame_limit)
    async with serve(relay.handler, args.host, args.port, max_size=64 * 1024 * 1024):
        relay.log.info("listening on ws://%s:%d/v1/hub (keys=%s)", args.host, args.port, args.keys)
        await asyncio.Future()


if __name__ == "__main__":
    import sys

    try:
        sys.exit(asyncio.run(main()))
    except KeyboardInterrupt:
        sys.exit(0)
