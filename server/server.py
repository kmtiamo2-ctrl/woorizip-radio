#!/usr/bin/env python3
"""
우리집 무전기 - NAS 중계 서버
- 외부 라이브러리 없이 파이썬 표준 라이브러리만 사용 (Docker 이미지가 단순해짐)
- 하나의 WebSocket 연결로 제어 메시지(JSON 텍스트)와 음성(바이너리)을 함께 주고받음
- 집 안에서는 한 번에 한 사람만 말할 수 있음 (무전기 방식)
- 집에 있는 기기와 밖에 있는 부모 기기에는 바로 소리로 전달하고,
  밖에 있는 아이 기기와 접속이 끊긴 가족에게는 음성을 보관했다가 알림으로 전달함

환경 변수
  FAMILY_KEY   가족 공용 암호 (필수, 앱 설정과 같아야 함)
  MEMBERS      가족 이름 목록, 쉼표로 구분 (기본: 엄마,아빠,첫째,둘째,셋째)
               한 사람이 폰·PC·노트북 여러 기기로 동시에 접속할 수 있음
  PARENTS      밖에서도 바로 소리로 받는 가족 (기본: 엄마,아빠). 나머지는 밖에서 알림만 받음
  PORT         포트 (기본: 8765)
  MAX_TALK_SEC 한 번에 말할 수 있는 최대 시간(초) (기본: 60)
  KEEP_HOURS   보관한 음성·문자를 몇 시간까지 전달 시도할지 (기본: 24)

문자 메시지: 마이크를 쓸 수 없을 때 짧은 글로 보냄. 음성 클립과 같은 방식으로 보관·전달됨
  클라이언트 → {"type":"text","to":"all"|이름,"text":"..."}
  서버 → 받는 쪽 {"type":"text","id","from","mode","ts","text"} (받으면 clip_ack로 확인)

서버 → 클라이언트 바이너리 프레임 형식
  첫 바이트 1 = 실시간 음성 (뒤에 PCM 16kHz 모노 16비트)
  첫 바이트 2 = 보관 음성 클립 (뒤에 4바이트 클립 번호, 그 뒤 PCM 전체)
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
PARENTS = {m.strip() for m in os.environ.get("PARENTS", "엄마,아빠").split(",") if m.strip()}
MAX_TALK_SEC = float(os.environ.get("MAX_TALK_SEC", "60"))
KEEP_HOURS = float(os.environ.get("KEEP_HOURS", "24"))
MAX_QUEUE = 20
PING_INTERVAL = 30      # 감시 주기
QUIET_BEFORE_PING = 150 # 이만큼 조용한 연결에만 서버가 ping (평소엔 폰·PC가 스스로 신호를 보냄 → 배터리 절약)
IDLE_TIMEOUT = 330      # 이만큼 아무 신호가 없으면 끊긴 것으로 정리
MAX_FRAME = 64 * 1024
LIVE, CLIP = 1, 2

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


def is_private(ip):
    return ip.startswith(("192.168.", "10.", "127.")) or (
        ip.startswith("172.") and ip.split(".")[1].isdigit() and 16 <= int(ip.split(".")[1]) <= 31)


class Hub:
    """
    - 가족 한 명(이름)이 폰·PC·노트북 여러 기기로 동시에 접속할 수 있음
    - 기기마다 집(home)/밖(out) 상태가 있음. 앱이 알려주고, 없으면 접속 IP로 판단
    - 실시간으로 듣는 기기: 집에 있는 기기, 밖에 있는 부모 기기
    - 알림으로 받는 기기: 밖에 있는 아이 기기 → 말이 끝나면 음성 클립을 통째로 전달
    - 접속이 아예 없는 가족에게는 클립을 보관했다가 접속하면 전달
    """

    def __init__(self):
        self.conns = {}          # 이름 -> {WS, ...}
        self.speaker = None
        self.speaker_ws = None
        self.targets = []        # 이름들
        self.live = []           # 실시간으로 듣는 WS들
        self.mode = None
        self.talk_started = 0.0
        self.record = bytearray()
        self.queue = {m: [] for m in MEMBERS}   # 이름 -> [클립...]
        self.clip_seq = 0

    # ---- 상태 ----

    def online(self):
        return [m for m in MEMBERS if self.conns.get(m)]

    def where(self, name):
        """가족 한 명의 위치: 기기 하나라도 집에 있으면 home"""
        group = self.conns.get(name, ())
        if not group:
            return None
        return "home" if any(w.where == "home" for w in group) else "out"

    def all_ws(self):
        return [ws for group in self.conns.values() for ws in list(group)]

    def hears_live(self, ws):
        return ws.where == "home" or ws.name in PARENTS

    async def send_to(self, name, msg):
        for ws in list(self.conns.get(name, ())):
            await ws.send_json(msg)

    async def broadcast_presence(self):
        msg = {"type": "presence", "online": self.online(), "speaker": self.speaker,
               "where": {m: self.where(m) for m in self.online()}}
        for ws in self.all_ws():
            await ws.send_json(msg)

    def resolve_targets(self, name, to):
        """to: "all" | 이름 | [이름, ...] → (대상 목록, 모드) / 잘못되면 (None, None)"""
        if to == "all":
            return [m for m in MEMBERS if m != name], "all"
        names = to if isinstance(to, list) else [to]
        names = [n for n in names if isinstance(n, str) and n in MEMBERS and n != name]
        names = list(dict.fromkeys(names))
        if not names:
            return None, None
        return names, ("direct" if len(names) == 1 else "group")

    # ---- 말하기 ----

    async def start_talk(self, name, ws, to):
        if self.speaker and self.speaker_ws is not ws:
            await ws.send_json({"type": "talk_denied", "reason": "busy", "speaker": self.speaker})
            return
        targets, mode = self.resolve_targets(name, to)
        if targets is None:
            await ws.send_json({"type": "talk_denied", "reason": "offline", "to": to})
            return
        live = [w for t in targets for w in self.conns.get(t, ()) if self.hears_live(w)]
        self.speaker, self.speaker_ws, self.targets, self.live, self.mode = name, ws, targets, live, mode
        self.talk_started = time.monotonic()
        self.record = bytearray()
        log("송신 시작: %s -> %s (실시간 %d대)", name, "전체" if mode == "all" else ",".join(targets), len(live))
        await ws.send_json({"type": "talk_granted", "to": to,
                            "listeners": [m for m in targets if self.conns.get(m)]})
        for w in live:
            await w.send_json({"type": "incoming", "from": name, "mode": mode})
        await self.broadcast_presence()

    async def end_talk(self, ws, reason="done"):
        if self.speaker_ws is not ws:
            return
        name, targets, live, mode = self.speaker, self.targets, self.live, self.mode
        pcm = bytes(self.record)
        self.speaker = self.speaker_ws = None
        self.targets, self.live, self.mode, self.record = [], [], None, bytearray()
        log("송신 종료: %s (%s, %.1f초)", name, reason, len(pcm) / 32000)
        for w in live:
            await w.send_json({"type": "incoming_end", "from": name})
        if reason != "done" and not ws.closed:
            await ws.send_json({"type": "talk_stopped", "reason": reason})
        if len(pcm) >= 16000:  # 0.5초 이상 말했을 때만 클립으로 보관
            await self.deliver_clip(name, mode, targets, live, pcm)
        await self.broadcast_presence()

    async def relay_audio(self, ws, data):
        if self.speaker_ws is not ws:
            return
        if len(self.record) < int(MAX_TALK_SEC * 32000) + 64000:
            self.record += data
        frame = bytes([LIVE]) + data
        for w in self.live:
            await w.send_bytes(frame)

    # ---- 문자 메시지 ----

    async def send_text(self, name, ws, to, text):
        text = str(text).strip()[:200]
        if not text:
            return
        targets, mode = self.resolve_targets(name, to)
        if targets is None:
            log("문자 실패: %s -> %r (받을 사람이 없음)", name, to)
            await ws.send_json({"type": "text_failed", "to": to})
            return
        self.clip_seq += 1
        item = {"kind": "text", "id": self.clip_seq, "from": name, "mode": mode, "ts": time.time(), "text": text}
        log("문자: %s -> %s: %s", name, "전체" if mode == "all" else ",".join(targets), text)
        for t in targets:
            q = self.queue.setdefault(t, [])
            q.append(item)
            del q[:-MAX_QUEUE]
            await self.flush_queue(t)
        await ws.send_json({"type": "text_sent", "id": item["id"], "to": to, "text": text, "ts": int(item["ts"])})

    # ---- 보관 음성(클립) ----

    async def deliver_clip(self, sender, mode, targets, live, pcm):
        live_names = {w.name for w in live}
        for t in targets:
            if t in live_names:
                continue  # 실시간으로 들은 사람은 클립 불필요
            self.clip_seq += 1
            clip = {"kind": "clip", "id": self.clip_seq, "from": sender, "mode": mode, "ts": time.time(), "pcm": pcm}
            q = self.queue.setdefault(t, [])
            q.append(clip)
            del q[:-MAX_QUEUE]
            await self.flush_queue(t)

    async def flush_queue(self, name):
        q = self.queue.get(name) or []
        cutoff = time.time() - KEEP_HOURS * 3600
        q[:] = [c for c in q if c["ts"] >= cutoff]
        conns = list(self.conns.get(name, ()))
        for c in q:
            for w in conns:
                if c["id"] in w.sent_clips:
                    continue
                w.sent_clips.add(c["id"])
                if c.get("kind") == "text":
                    await w.send_json({"type": "text", "id": c["id"], "from": c["from"], "mode": c["mode"],
                                       "ts": int(c["ts"]), "text": c["text"]})
                    continue
                await w.send_json({"type": "clip", "id": c["id"], "from": c["from"], "mode": c["mode"],
                                   "ts": int(c["ts"]), "seconds": round(len(c["pcm"]) / 32000, 1)})
                await w.send_bytes(bytes([CLIP]) + struct.pack("!I", c["id"]) + c["pcm"])

    def ack_clip(self, name, clip_id):
        q = self.queue.get(name) or []
        q[:] = [c for c in q if c["id"] != clip_id]

    # ---- 연결 처리 ----

    async def handle(self, reader, writer):
        ws = WS(reader, writer)
        ws.name = None
        ws.where = "home"
        ws.device = ""
        ws.sent_clips = set()
        name = None
        peer = writer.get_extra_info("peername") or ("?", 0)
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
            ws.name = name
            where = hello.get("where")
            if where not in ("home", "out"):
                where = "home" if is_private(str(peer[0])) else "out"
            ws.where = where
            device = str(hello.get("device", ""))[:40]
            ws.device = device
            if device:  # 같은 기기가 다시 접속하면 예전 연결은 정리
                for old in list(self.conns.get(name, ())):
                    if old.device == device:
                        if self.speaker_ws is old:
                            await self.end_talk(old, "reconnected")
                        self.conns[name].discard(old)
                        self.live = [w for w in self.live if w is not old]
                        old.close()
            self.conns.setdefault(name, set()).add(ws)
            log("접속: %s [%s] %s(%s) — 기기 %d대", name, "집" if where == "home" else "밖", device, peer[0],
                len(self.conns[name]))
            await ws.send_json({"type": "welcome", "members": MEMBERS, "you": name, "where": where,
                                "parents": sorted(PARENTS)})
            await self.broadcast_presence()
            await self.flush_queue(name)
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
                elif t == "text":
                    await self.send_text(name, ws, msg.get("to", "all"), msg.get("text", ""))
                elif t == "clip_ack":
                    self.ack_clip(name, int(msg.get("id", 0)))
                elif t == "where":
                    new = msg.get("where")
                    if new in ("home", "out") and new != ws.where:
                        ws.where = new
                        log("위치 변경: %s -> %s", name, "집" if new == "home" else "밖")
                        await self.broadcast_presence()
                elif t == "ping":
                    await ws.send_json({"type": "pong"})
        except (Closed, asyncio.TimeoutError, asyncio.IncompleteReadError, ConnectionError):
            pass
        except Exception as e:
            log("오류(%s): %r", name, e)
        finally:
            ws.close()
            group = self.conns.get(name)
            if group and ws in group:
                group.discard(ws)
                if not group:
                    del self.conns[name]
                self.live = [w for w in self.live if w is not ws]
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
                    log("응답 없음, 연결 정리: %s", ws.name)
                    ws.close()
                elif now - ws.last_seen > QUIET_BEFORE_PING:
                    await ws.ping()
            if self.speaker_ws and now - self.talk_started > MAX_TALK_SEC:
                await self.end_talk(self.speaker_ws, "too_long")


async def main():
    if not FAMILY_KEY:
        raise SystemExit("FAMILY_KEY 환경 변수를 설정하세요.")
    hub = Hub()
    server = await asyncio.start_server(hub.handle, "0.0.0.0", PORT)
    log("우리집 무전기 서버 시작: 포트 %d, 가족 %s, 부모 %s", PORT, ", ".join(MEMBERS), ", ".join(sorted(PARENTS)))
    asyncio.create_task(hub.watchdog())
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
