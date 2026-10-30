#!/usr/bin/env python3
"""
우리집 무전기 - NAS 중계 서버
- 외부 라이브러리 없이 파이썬 표준 라이브러리만 사용 (Docker 이미지가 단순해짐)
- 하나의 WebSocket 연결로 제어 메시지(JSON 텍스트)와 음성(바이너리)을 함께 주고받음
- 집 안에서는 한 번에 한 사람만 말할 수 있음 (무전기 방식)

환경 변수
  FAMILY_KEY   가족 공용 암호 (필수, 앱 설정과 같아야 함)
  MEMBERS      가족 이름 목록, 쉼표로 구분 (기본: 엄마,아빠,첫째,둘째,셋째)
               한 사람이 폰·PC·노트북 여러 기기로 동시에 접속할 수 있음
  PORT         포트 (기본: 8765)
  MAX_TALK_SEC 한 번에 말할 수 있는 최대 시간(초) (기본: 60)
"""
import asyncio
import base64
import hashlib
import json
import logging
import os
import struct
import time

FAMILY_KEY = os.environ.get("FAMILY_KEY", "")
MEMBERS = [m.strip() for m in os.environ.get("MEMBERS", "엄마,아빠,첫째,둘째,셋째").split(",") if m.strip()]
PORT = int(os.environ.get("PORT", "8765"))
MAX_TALK_SEC = float(os.environ.get("MAX_TALK_SEC", "60"))
PING_INTERVAL = 15
IDLE_TIMEOUT = 45
MAX_FRAME = 64 * 1024

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s", datefmt="%m-%d %H:%M:%S")
log = logging.info

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


class Closed(Exception):
    pass


class WS:
    """아주 작은 WebSocket 서버 측 구현 (RFC 6455, 텍스트/바이너리/ping/pong/close)."""

    def __init__(self, reader, writer):
        self.r = reader
        self.w = writer
        self.closed = False
        self.last_seen = time.monotonic()
        self.lock = asyncio.Lock()

    async def handshake(self):
        req = await asyncio.wait_for(self.r.readuntil(b"\r\n\r\n"), 10)
        headers = {}
        for line in req.decode("latin-1").split("\r\n")[1:]:
            if ":" in line:
                k, v = line.split(":", 1)
                headers[k.strip().lower()] = v.strip()
        key = headers.get("sec-websocket-key")
        if not key or "websocket" not in headers.get("upgrade", "").lower():
            self.w.write(b"HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n"
                         b"Connection: close\r\n\r\nwoorizip-radio ok\n")
            await self.w.drain()
            raise Closed()
        accept = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
        self.w.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                      "Connection: Upgrade\r\nSec-WebSocket-Accept: %s\r\n\r\n" % accept).encode())
        await self.w.drain()

    async def _send(self, opcode, payload: bytes):
        if self.closed:
            return
        n = len(payload)
        if n < 126:
            head = struct.pack("!BB", 0x80 | opcode, n)
        elif n < 65536:
            head = struct.pack("!BBH", 0x80 | opcode, 126, n)
        else:
            head = struct.pack("!BBQ", 0x80 | opcode, 127, n)
        async with self.lock:
            try:
                self.w.write(head + payload)
                await asyncio.wait_for(self.w.drain(), 5)
            except Exception:
                self.closed = True

    async def send_json(self, obj):
        await self._send(0x1, json.dumps(obj, ensure_ascii=False).encode())

    async def send_bytes(self, data: bytes):
        await self._send(0x2, data)

    async def ping(self):
        await self._send(0x9, b"")

    async def recv(self):
        """완성된 메시지 하나를 반환: (is_text, payload). 연결이 끊기면 Closed."""
        buf = b""
        msg_op = None
        while True:
            try:
                b1, b2 = await self.r.readexactly(2)
            except Exception:
                raise Closed()
            fin = b1 & 0x80
            op = b1 & 0x0F
            masked = b2 & 0x80
            n = b2 & 0x7F
            if n == 126:
                n = struct.unpack("!H", await self.r.readexactly(2))[0]
            elif n == 127:
                n = struct.unpack("!Q", await self.r.readexactly(8))[0]
            if n > MAX_FRAME:
                raise Closed()
            mask = await self.r.readexactly(4) if masked else b"\0\0\0\0"
            data = bytearray(await self.r.readexactly(n))
            for i in range(n):
                data[i] ^= mask[i % 4]
            self.last_seen = time.monotonic()
            if op == 0x8:  # close
                await self._send(0x8, b"")
                raise Closed()
            if op == 0x9:  # ping -> pong
                await self._send(0xA, bytes(data))
                continue
            if op == 0xA:  # pong
                continue
            if op in (0x1, 0x2):
                msg_op = op
                buf = bytes(data)
            elif op == 0x0:
                buf += bytes(data)
            if fin and msg_op is not None:
                return msg_op == 0x1, buf

    def close(self):
        self.closed = True
        try:
            self.w.close()
        except Exception:
            pass


class Hub:
    """
    가족 한 명(이름)이 폰·PC·노트북 여러 기기로 동시에 접속할 수 있음.
    - 접속 표시는 이름 단위 (기기 하나라도 켜져 있으면 접속 중)
    - 말하기는 집 전체에서 한 번에 한 기기만
    - 누군가에게 말하면 그 사람의 모든 기기에서 들림. 말하는 사람 본인의 다른 기기에서는 안 들림
    """

    def __init__(self):
        self.conns = {}         # 이름 -> {WS, ...}
        self.speaker = None     # 지금 말하는 사람 이름
        self.speaker_ws = None  # 지금 말하는 기기
        self.targets = []       # 지금 듣는 사람 이름들
        self.mode = None        # "all" | "direct"
        self.talk_started = 0.0

    def online(self):
        return [m for m in MEMBERS if self.conns.get(m)]

    def all_ws(self):
        return [ws for group in self.conns.values() for ws in list(group)]

    async def send_to(self, name, msg):
        for ws in list(self.conns.get(name, ())):
            await ws.send_json(msg)

    async def broadcast_presence(self):
        msg = {"type": "presence", "online": self.online(), "speaker": self.speaker}
        for ws in self.all_ws():
            await ws.send_json(msg)

    async def start_talk(self, name, ws, to):
        if self.speaker and self.speaker_ws is not ws:
            await ws.send_json({"type": "talk_denied", "reason": "busy", "speaker": self.speaker})
            return
        if to == "all":
            targets = [m for m in self.online() if m != name]
            mode = "all"
        else:
            if to == name or not self.conns.get(to):
                await ws.send_json({"type": "talk_denied", "reason": "offline", "to": to})
                return
            targets = [to]
            mode = "direct"
        self.speaker, self.speaker_ws, self.targets, self.mode = name, ws, targets, mode
        self.talk_started = time.monotonic()
        log("송신 시작: %s -> %s", name, "전체" if mode == "all" else to)
        await ws.send_json({"type": "talk_granted", "to": to, "listeners": targets})
        for t in targets:
            await self.send_to(t, {"type": "incoming", "from": name, "mode": mode})
        await self.broadcast_presence()

    async def end_talk(self, ws, reason="done"):
        if self.speaker_ws is not ws:
            return
        name, targets = self.speaker, self.targets
        self.speaker, self.speaker_ws, self.targets, self.mode = None, None, [], None
        log("송신 종료: %s (%s)", name, reason)
        for t in targets:
            await self.send_to(t, {"type": "incoming_end", "from": name})
        if reason != "done" and not ws.closed:
            await ws.send_json({"type": "talk_stopped", "reason": reason})
        await self.broadcast_presence()

    async def relay_audio(self, ws, data):
        if self.speaker_ws is not ws:
            return
        for t in self.targets:
            for w in list(self.conns.get(t, ())):
                await w.send_bytes(data)

    async def handle(self, reader, writer):
        ws = WS(reader, writer)
        name = None
        try:
            await ws.handshake()
            is_text, raw = await asyncio.wait_for(ws.recv(), 10)
            hello = json.loads(raw) if is_text else {}
            if hello.get("type") != "hello" or hello.get("key") != FAMILY_KEY:
                await ws.send_json({"type": "error", "reason": "auth"})
                return
            name = hello.get("name")
            if name not in MEMBERS:
                await ws.send_json({"type": "error", "reason": "unknown_member", "members": MEMBERS})
                return
            device = str(hello.get("device", ""))[:40]
            ws.device = device
            if device:  # 같은 기기가 다시 접속하면 (Wi-Fi 끊김 후 재연결 등) 예전 연결은 정리
                for old in list(self.conns.get(name, ())):
                    if getattr(old, "device", None) == device:
                        if self.speaker_ws is old:
                            await self.end_talk(old, "reconnected")
                        self.conns[name].discard(old)
                        old.close()
            self.conns.setdefault(name, set()).add(ws)
            log("접속: %s %s(%s) — 기기 %d대", name, device, writer.get_extra_info("peername"),
                len(self.conns[name]))
            await ws.send_json({"type": "welcome", "members": MEMBERS, "you": name})
            await self.broadcast_presence()
            while True:
                is_text, data = await ws.recv()
                if not is_text:
                    await self.relay_audio(ws, data)
                    continue
                msg = json.loads(data)
                t = msg.get("type")
                if t == "talk_start":
                    await self.start_talk(name, ws, msg.get("to", "all"))
                elif t == "talk_end":
                    await self.end_talk(ws)
                elif t == "ping":
                    await ws.send_json({"type": "pong"})
        except (Closed, asyncio.TimeoutError, asyncio.IncompleteReadError, ConnectionError):
            pass
        except Exception as e:  # 예상치 못한 오류도 서버 전체는 계속 동작
            log("오류(%s): %r", name, e)
        finally:
            ws.close()
            group = self.conns.get(name)
            if group and ws in group:
                group.discard(ws)
                if not group:
                    del self.conns[name]
                log("접속 종료: %s (남은 기기 %d대)", name, len(group))
                if self.speaker_ws is ws:
                    await self.end_talk(ws, "disconnected")
                else:
                    await self.broadcast_presence()

    async def watchdog(self):
        while True:
            await asyncio.sleep(PING_INTERVAL)
            now = time.monotonic()
            for ws in self.all_ws():
                if now - ws.last_seen > IDLE_TIMEOUT:
                    log("응답 없음, 연결 정리")
                    ws.close()
                else:
                    await ws.ping()
            if self.speaker_ws and now - self.talk_started > MAX_TALK_SEC:
                await self.end_talk(self.speaker_ws, "too_long")


async def main():
    if not FAMILY_KEY:
        raise SystemExit("FAMILY_KEY 환경 변수를 설정하세요.")
    hub = Hub()
    server = await asyncio.start_server(hub.handle, "0.0.0.0", PORT)
    log("우리집 무전기 서버 시작: 포트 %d, 가족 %s", PORT, ", ".join(MEMBERS))
    asyncio.create_task(hub.watchdog())
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
