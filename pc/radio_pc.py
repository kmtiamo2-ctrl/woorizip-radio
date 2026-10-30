"""
우리집 무전기 - Windows PC 버전
- 오른쪽 아래 시계 옆(트레이)에 아이콘으로 상주. 창을 닫아도 계속 듣고 있음
- 가족이 말하면 알림 풍선이 뜨고, Windows 기본 소리 장치(스피커/헤드셋)로 바로 들림
- 창에서 상대를 고르고 큰 버튼을 누르는 동안 말하기. 원하면 단축키(F9)로도 가능
- 집 주소로 안 되면 밖 주소(Tailscale/DDNS)로 자동 연결 (노트북을 밖에 가져갔을 때)
- Windows 시작 시 자동 실행 (트레이에만 조용히 켜짐)

필요한 패키지: sounddevice, pystray, Pillow, winotify, keyboard(선택)
"""
import base64
import json
import math
import os
import queue
import socket
import ssl
import struct
import sys
import threading
import time
from array import array
from collections import deque
from urllib.parse import urlparse

APP_NAME = "WoorizipRadio"
try:
    from version import __version__
except Exception:
    __version__ = "dev"
APP_TITLE = "우리집 무전기 " + __version__
RATE = 16000
FRAME_BYTES = 640  # 20ms, 16비트 모노
LIVE, CLIP = 1, 2
DEFAULT_MEMBERS = ["엄마", "아빠", "첫째", "둘째", "셋째"]

try:
    import sounddevice as sd
except Exception:
    sd = None
keyboard = None  # 단축키를 설정한 경우에만 로드 (메모리 절약)


# ---------------------------------------------------------------- 기록 (문제 생겼을 때 보내 주시면 원인 파악용)

def log(msg):
    try:
        path = os.path.join(data_dir(), "log.txt")
        if os.path.exists(path) and os.path.getsize(path) > 500_000:
            os.replace(path, path + ".old")
        with open(path, "a", encoding="utf-8") as f:
            f.write("%s %s\n" % (time.strftime("%m-%d %H:%M:%S"), msg))
    except Exception:
        pass


# ---------------------------------------------------------------- 설정

def data_dir():
    base = os.environ.get("APPDATA") or os.path.expanduser("~")
    d = os.path.join(base, APP_NAME)
    os.makedirs(d, exist_ok=True)
    return d


class Config:
    # 우리 집 기본값 (가족 전용 빌드). 설정 창에서 바꿀 수 있음
    DEFAULTS = {"host": "192.168.0.6", "out_host": "100.83.179.121", "name": "", "key": "105403", "hotkey": "",
                "quick": "네,아니요,알겠어요,지금 갈게요,조금 있다가요,지금은 못 해요",
                "target": "all", "dnd": False, "autostart": True}

    def __init__(self):
        self.data = dict(self.DEFAULTS)
        try:
            with open(os.path.join(data_dir(), "config.json"), encoding="utf-8") as f:
                saved = json.load(f)
            # 예전 버전이 빈 값으로 저장해 둔 항목은 기본값을 씀
            for k, v in saved.items():
                if k in self.DEFAULTS and not (isinstance(v, str) and v == "" and self.DEFAULTS[k]):
                    self.data[k] = v
        except Exception:
            pass
        self.data["dnd"] = False  # 방해금지 기능 제거됨

    def __getattr__(self, k):
        if k in Config.DEFAULTS:
            return self.data.get(k, Config.DEFAULTS[k])
        raise AttributeError(k)

    def set(self, **kw):
        self.data.update(kw)
        try:
            with open(os.path.join(data_dir(), "config.json"), "w", encoding="utf-8") as f:
                json.dump(self.data, f, ensure_ascii=False, indent=2)
        except Exception:
            pass

    @property
    def configured(self):
        return bool(self.host and self.name and self.key)


def parse_addr(addr):
    """'192.168.0.10', '192.168.0.10:8765', 'wss://xxx.synology.me' → (host, port, tls, path)"""
    a = addr.strip()
    if "://" not in a:
        a = "ws://" + a
    a = a.replace("https://", "wss://").replace("http://", "ws://")
    u = urlparse(a)
    tls = u.scheme == "wss"
    port = u.port or (443 if tls else 8765)
    return u.hostname, port, tls, (u.path or "/")


# ---------------------------------------------------------------- Windows 자동 실행

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"


def autostart_command():
    if getattr(sys, "frozen", False):
        return '"%s" --minimized' % sys.executable
    exe = sys.executable.replace("python.exe", "pythonw.exe")
    return '"%s" "%s" --minimized' % (exe, os.path.abspath(__file__))


def set_autostart(enabled):
    if os.name != "nt":
        return
    import winreg
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
        if enabled:
            winreg.SetValueEx(k, APP_NAME, 0, winreg.REG_SZ, autostart_command())
        else:
            try:
                winreg.DeleteValue(k, APP_NAME)
            except FileNotFoundError:
                pass


# ---------------------------------------------------------------- WebSocket (표준 라이브러리만 사용)

def _mask(data, m):
    n = len(data)
    if n == 0:
        return data
    mm = (m * (n // 4 + 1))[:n]
    return (int.from_bytes(data, "big") ^ int.from_bytes(mm, "big")).to_bytes(n, "big")


class WSClient:
    """집 주소 → 안 되면 밖 주소 순으로 연결하고, 끊기면 자동으로 다시 연결"""

    def __init__(self, cfg, on_text, on_bin, on_state):
        self.cfg = cfg
        self.on_text, self.on_bin, self.on_state = on_text, on_bin, on_state
        self.sock = None
        self.send_lock = threading.Lock()
        self.running = False
        self.kick = threading.Event()
        self.last_error = None
        self.where = None
        self.next_probe = 0.0
        self.last_rx = 0.0
        self.last_ping = 0.0

    def start(self):
        self.running = True
        threading.Thread(target=self._loop, daemon=True).start()
        threading.Thread(target=self._heartbeat, daemon=True).start()

    def _heartbeat(self):
        """20초마다 서버에 신호를 보내고, 60초 동안 아무 응답이 없으면 죽은 연결로 보고 다시 붙음"""
        while self.running:
            time.sleep(5)
            s = self.sock
            if not s or self.where != "connected":
                continue
            now = time.monotonic()
            if now - self.last_rx > 120:
                log("연결 응답 없음 2분 → 다시 연결")
                self.reconnect()
            elif now - self.last_ping > 30:
                self.last_ping = now
                self._send(0x9, b"")

    def reconnect(self):
        self._close()
        self.kick.set()

    def _close(self):
        s, self.sock = self.sock, None
        if s:
            try:
                s.close()
            except Exception:
                pass

    def _loop(self):
        backoff = 2
        while self.running:
            self.kick.clear()
            cfg = self.cfg
            if not cfg.configured:
                self.on_state(False, None, "설정에서 NAS 주소·이름·암호를 입력하세요")
                self.kick.wait(5)
                continue
            attempts = [("home", cfg.host)]
            if cfg.out_host.strip():
                attempts.append(("out", cfg.out_host))
            err = "NAS 서버에 연결할 수 없어요"
            for where, addr in attempts:
                if not self.running:
                    break
                self.on_state(False, None, "집 주소로 연결 중…" if where == "home" else "밖 주소로 연결 중…")
                self.last_error = None
                try:
                    s = self._open(addr)
                    self.sock = s
                    self.where = where
                    self.send_json({"type": "hello", "name": cfg.name, "key": cfg.key,
                                    "device": "pc-" + socket.gethostname(), "where": where})
                    backoff = 2
                    err = self._read_loop(s, where)
                except Exception:
                    pass
                self._close()
                if self.last_error or self.where == "connected":
                    break
            self.where = None
            self.on_state(False, None, self.last_error or err)
            if self.kick.wait(backoff):
                backoff = 2
            else:
                backoff = min(backoff * 2, 20)

    def _open(self, addr):
        host, port, tls, path = parse_addr(addr)
        s = socket.create_connection((host, port), timeout=5)
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        if tls:
            s = ssl.create_default_context().wrap_socket(s, server_hostname=host)
        key = base64.b64encode(os.urandom(16)).decode()
        req = ("GET %s HTTP/1.1\r\nHost: %s:%s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
               "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n" % (path, host, port, key))
        s.sendall(req.encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = s.recv(1024)
            if not chunk:
                raise ConnectionError()
            buf += chunk
        if b" 101 " not in buf.split(b"\r\n", 1)[0]:
            raise ConnectionError()
        s.settimeout(150)  # 우리가 30초마다 ping을 보내고 서버가 pong으로 답함. 이만큼 조용하면 끊긴 것
        return s

    def _recv_exact(self, s, n):
        buf = b""
        while len(buf) < n:
            chunk = s.recv(min(n - len(buf), 65536))
            if not chunk:
                raise ConnectionError()
            buf += chunk
        return buf

    def _read_loop(self, s, where):
        reason = "연결 끊김"
        msg_op, parts = None, []
        while self.running and self.sock is s:
            if where == "out" and time.monotonic() > self.next_probe:
                # 밖 주소로 붙어 있는 동안 1분마다 집 주소가 되는지 확인 → 되면 집 모드로 복귀
                self.next_probe = time.monotonic() + 60
                threading.Thread(target=self._probe_home, args=(s,), daemon=True).start()
            b1, b2 = self._recv_exact(s, 2)
            fin, op, n = b1 & 0x80, b1 & 0x0F, b2 & 0x7F
            if n == 126:
                n = struct.unpack("!H", self._recv_exact(s, 2))[0]
            elif n == 127:
                n = struct.unpack("!Q", self._recv_exact(s, 8))[0]
            data = self._recv_exact(s, n) if n else b""
            self.last_rx = time.monotonic()
            if op == 0x8:
                return reason
            if op == 0x9:
                self._send(0xA, data)
                continue
            if op == 0xA:
                continue
            if op in (1, 2):
                msg_op, parts = op, [data]
            elif op == 0:
                parts.append(data)
            if fin and msg_op:
                payload = b"".join(parts)
                if msg_op == 1:
                    msg = json.loads(payload.decode("utf-8"))
                    t = msg.get("type")
                    if t == "welcome":
                        self.where = "connected"
                        self.last_rx = self.last_ping = time.monotonic()
                        self.on_state(True, where, "집에서 연결됨" if where == "home" else "밖에서 연결됨")
                    elif t == "error":
                        reason = self.last_error = {
                            "auth": "가족 암호가 서버와 달라요",
                            "unknown_member": "이름이 서버의 가족 목록에 없어요"}.get(msg.get("reason"), "서버 오류")
                    self.on_text(msg)
                else:
                    self.on_bin(payload)
                msg_op = None
        return reason

    def _probe_home(self, current):
        try:
            host, port, _, _ = parse_addr(self.cfg.host)
            socket.create_connection((host, port), timeout=2).close()
        except Exception:
            return
        if self.sock is current:
            self.reconnect()   # 현재 연결을 끊으면 루프가 집 주소부터 다시 시도

    def _send(self, op, payload):
        s = self.sock
        if not s:
            return False
        n = len(payload)
        if n < 126:
            head = struct.pack("!BB", 0x80 | op, 0x80 | n)
        elif n < 65536:
            head = struct.pack("!BBH", 0x80 | op, 0x80 | 126, n)
        else:
            head = struct.pack("!BBQ", 0x80 | op, 0x80 | 127, n)
        m = os.urandom(4)
        try:
            with self.send_lock:
                s.sendall(head + m + _mask(payload, m))
            return True
        except Exception:
            return False

    def send_json(self, obj):
        return self._send(0x1, json.dumps(obj, ensure_ascii=False).encode("utf-8"))

    def send_bytes(self, data):
        return self._send(0x2, data)


# ---------------------------------------------------------------- 소리

def tone(freq, ms, vol=0.25):
    n = int(RATE * ms / 1000)
    a = array("h", (int(32767 * vol * math.sin(2 * math.pi * freq * i / RATE)) for i in range(n)))
    return a.tobytes()


BEEP_IN = tone(880, 150)
BEEP_OK = tone(1200, 120)
BEEP_NO = tone(300, 150) + bytes(1600) + tone(300, 150)


class Audio:
    """스피커는 항상 열어 두고(Windows 기본 장치 = 헤드셋 꽂으면 헤드셋), 마이크는 말할 때만 엶"""

    def __init__(self, on_frame):
        self.on_frame = on_frame
        self.q = deque()
        self.rem = b""
        self.waiting = True
        self.inp = None
        self.out = None
        self.error = None
        if sd is None:
            self.error = "sounddevice 패키지가 없어 소리를 쓸 수 없어요"
            return
        try:
            self.out = sd.RawOutputStream(samplerate=RATE, channels=1, dtype="int16",
                                          blocksize=FRAME_BYTES // 2, callback=self._out_cb)
            self.out.start()
        except Exception as e:
            self.error = "스피커를 열 수 없어요: %s" % e

    def _out_cb(self, outdata, frames, t, status):
        need = frames * 2
        if self.waiting and len(self.q) < 3:
            outdata[:] = bytes(need)
            return
        self.waiting = False
        buf = bytearray(self.rem)
        while len(buf) < need and self.q:
            buf += self.q.popleft()
        if len(buf) < need:
            self.waiting = not buf and not self.q
            buf += bytes(need - len(buf))
        outdata[:] = bytes(buf[:need])
        self.rem = bytes(buf[need:])

    def play(self, data):
        self.q.append(data)
        while len(self.q) > 25:
            self.q.popleft()

    def play_all(self, pcm):
        """클립 전체 재생 (큐 제한 없이). 앞에 알림음을 붙임"""
        self.q.clear()
        for chunk in (BEEP_IN, bytes(1600), pcm):
            for i in range(0, len(chunk), FRAME_BYTES):
                self.q.append(chunk[i:i + FRAME_BYTES])
        self.waiting = False

    def stop_playback(self):
        self.q.clear()
        self.rem = b""

    def effect(self, pcm):
        for i in range(0, len(pcm), FRAME_BYTES):
            self.q.append(pcm[i:i + FRAME_BYTES])
        self.waiting = False

    def start_mic(self):
        if sd is None:
            return False
        try:
            self.inp = sd.RawInputStream(samplerate=RATE, channels=1, dtype="int16",
                                         blocksize=FRAME_BYTES // 2,
                                         callback=lambda d, f, t, s: self.on_frame(bytes(d)))
            self.inp.start()
            return True
        except Exception:
            self.inp = None
            return False

    def stop_mic(self):
        s, self.inp = self.inp, None
        if s:
            try:
                s.stop()
                s.close()
            except Exception:
                pass


# ---------------------------------------------------------------- 무전기 동작

class Radio:
    def __init__(self, cfg, audio_factory=Audio):
        self.cfg = cfg
        self.lock = threading.RLock()
        self.connected = False
        self.where = None
        self.status = "연결 준비 중"
        self.members = list(DEFAULT_MEMBERS)
        self.parents = {"엄마", "아빠"}
        self.online = []
        self.member_where = {}
        self.talking = False
        self.requesting = False
        self.incoming_from = None
        self.incoming_mode = None
        self.notice = ""
        self.clips = []          # 최근 메시지 (새것이 앞): 음성 dict(kind=clip, file) 또는 문자 dict(kind=text, text)
        self.outbox = []         # 연결이 끊겨 있을 때 보낸 문자: 연결되면 자동 전송
        self._load_outbox()
        self.quick_replies = [q.strip() for q in cfg.quick.split(",") if q.strip()]
        self.pending = {}
        self.playing = None
        self.on_event = None     # ("incoming"|"clip"|"state", 정보) → 화면 알림용
        self.client = WSClient(cfg, self._on_text, self._on_bin, self._on_state)
        self.audio = audio_factory(self.client.send_bytes)
        if self.audio.error:
            self.notice = self.audio.error
        self.clip_dir = os.path.join(data_dir(), "clips")
        os.makedirs(self.clip_dir, exist_ok=True)
        self._load_clips()

    def start(self):
        self.client.start()

    @property
    def target(self):
        return self.cfg.target

    @property
    def is_parent(self):
        return self.cfg.name in self.parents

    @property
    def target_list(self):
        return [] if self.target == "all" else [t for t in self.target.split(",") if t]

    def target_json(self):
        return "all" if self.target == "all" else self.target_list

    def target_label(self):
        return "전체" if self.target == "all" else ", ".join(self.target_list)

    def set_target(self, t):
        """한 명으로 지정 (답장 등)"""
        with self.lock:
            if not self.talking:
                self.cfg.set(target=t)

    def toggle_target(self, name):
        """가족 버튼: 선택/해제 토글 (여러 명 가능). '전체'를 누르면 모두 해제"""
        with self.lock:
            if self.talking:
                return
            if name == "all":
                self.cfg.set(target="all")
                return
            cur = self.target_list
            if name in cur:
                cur.remove(name)
            else:
                cur.append(name)
            self.cfg.set(target=",".join(cur) if cur else "all")

    def _emit(self, kind, info=None):
        cb = self.on_event
        if cb:
            try:
                cb(kind, info)
            except Exception:
                pass

    # ---- 말하기 ----

    def start_talk(self):
        with self.lock:
            if self.talking or self.requesting:
                return
            if not self.connected:
                self.notice = "NAS에 연결되어 있지 않아요"
                self.audio.effect(BEEP_NO)
                return
            if self.incoming_from:
                self.notice = "%s님이 말하는 중이에요" % self.incoming_from
                self.audio.effect(BEEP_NO)
                return
            self.stop_clip()
            self.requesting = True
            if not self.client.send_json({"type": "talk_start", "to": self.target_json()}):
                self.requesting = False
                self.notice = "NAS에 연결되어 있지 않아요"
                self.audio.effect(BEEP_NO)

    def stop_talk(self):
        with self.lock:
            active = self.talking or self.requesting
            self.requesting = False
            if self.talking:
                self.talking = False
                self.audio.stop_mic()
            if active:
                self.client.send_json({"type": "talk_end"})

    # ---- 서버 메시지 ----

    def _on_text(self, msg):
        with self.lock:
            t = msg.get("type")
            if t == "welcome":
                self.members = msg.get("members") or self.members
                self.parents = set(msg.get("parents") or self.parents)
                self.notice = ""
                self._flush_outbox()
            elif t == "presence":
                self.online = msg.get("online") or []
                self.member_where = msg.get("where") or {}
            elif t == "talk_granted":
                if not self.requesting:
                    return
                self.requesting = False
                self.talking = True
                self.audio.effect(BEEP_OK)
                if not self.audio.start_mic():
                    self.notice = "마이크를 사용할 수 없어요"
                    self.stop_talk()
                else:
                    self.notice = "전체 방송 중" if msg.get("to") == "all" else "%s에게 말하는 중" % self.target_label()
            elif t == "talk_denied":
                self.requesting = False
                self.audio.effect(BEEP_NO)
                r = msg.get("reason")
                self.notice = ("%s님이 말하는 중이에요" % msg.get("speaker") if r == "busy"
                               else "%s님이 지금 접속해 있지 않아요" % msg.get("to") if r == "offline"
                               else "지금은 말할 수 없어요")
            elif t == "talk_stopped":
                self.talking = self.requesting = False
                self.audio.stop_mic()
                self.audio.effect(BEEP_NO)
                self.notice = "1분이 지나 자동으로 끝났어요" if msg.get("reason") == "too_long" else "송신이 끝났어요"
            elif t == "incoming":
                self.incoming_from, self.incoming_mode = msg.get("from"), msg.get("mode")
                self.stop_clip()
                if self.cfg.dnd:
                    self.notice = "방해금지 중 — %s님의 말을 받지 않았어요" % self.incoming_from
                else:
                    self.audio.effect(BEEP_IN)
                    self._emit("incoming", msg)
            elif t == "incoming_end":
                self.incoming_from = self.incoming_mode = None
            elif t == "clip":
                self.pending[int(msg.get("id", 0))] = msg
            elif t == "text":
                self._on_text_msg(msg)
            elif t == "text_sent":
                to = msg.get("to")
                if isinstance(to, list):
                    to = ", ".join(to)
                self._add_clip({"kind": "text", "id": int(msg.get("id", 0)), "from": self.cfg.name if to == "all" else to,
                                "mode": "all" if to == "all" else "direct", "ts": int(msg.get("ts") or time.time()),
                                "text": msg.get("text", ""), "mine": True})
                self.notice = "보냈어요"
            elif t == "text_failed":
                self.notice = "받을 사람이 없어요 (상대를 다시 골라 주세요)"
                log("문자 실패(서버): 받을 사람 없음 to=%r" % msg.get("to"))
                self.cfg.set(target="all")

    def _on_bin(self, data):
        if not data:
            return
        kind = data[0]
        if kind == LIVE:
            if self.incoming_from and not self.cfg.dnd:
                self.audio.play(data[1:])
        elif kind == CLIP and len(data) > 5:
            cid = struct.unpack("!I", data[1:5])[0]
            self._on_clip(cid, data[5:])

    def _on_state(self, connected, where, detail):
        log("연결 상태: %s %s %s" % ("연결됨" if connected else "끊김", where or "", detail))
        with self.lock:
            self.connected, self.where, self.status = connected, where, detail
            if not connected:
                if self.talking:
                    self.talking = False
                    self.audio.stop_mic()
                self.requesting = False
                self.incoming_from = self.incoming_mode = None
                self.online = []
                self.member_where = {}
        self._emit("state")

    # ---- 보관 음성(클립) ----

    def _load_clips(self):
        items = []
        for fn in os.listdir(self.clip_dir):
            p = fn[:-4].split("_") if fn.endswith(".pcm") else []
            if len(p) < 5:
                continue
            try:
                items.append({"kind": "clip", "id": int(p[0]), "from": p[1], "mode": p[2], "ts": int(p[3]),
                              "seconds": float(p[4]), "file": os.path.join(self.clip_dir, fn)})
            except Exception:
                pass
        # 문자 메시지 기록
        try:
            with open(os.path.join(data_dir(), "texts.json"), encoding="utf-8") as f:
                items += json.load(f)
        except Exception:
            pass
        items.sort(key=lambda c: -c["ts"])
        self.clips = items[:40]

    def _save_texts(self):
        try:
            with open(os.path.join(data_dir(), "texts.json"), "w", encoding="utf-8") as f:
                json.dump([c for c in self.clips if c.get("kind") == "text"][:30], f, ensure_ascii=False)
        except Exception:
            pass

    def _add_clip(self, clip):
        self.clips.insert(0, clip)
        self.clips.sort(key=lambda c: -c["ts"])
        for old in self.clips[40:]:
            if old.get("file"):
                try:
                    os.remove(old["file"])
                except Exception:
                    pass
        del self.clips[40:]
        if clip.get("kind") == "text":
            self._save_texts()

    # ---- 문자 메시지 (말할 수 없을 때) ----

    # ---- 보낼 문자 보관함: 연결이 없으면 들고 있다가 연결되는 즉시 보냄 ----

    def _outbox_path(self):
        return os.path.join(data_dir(), "outbox.json")

    def _load_outbox(self):
        try:
            with open(self._outbox_path(), encoding="utf-8") as f:
                self.outbox = json.load(f)[:50]
        except Exception:
            self.outbox = []

    def _save_outbox(self):
        try:
            with open(self._outbox_path(), "w", encoding="utf-8") as f:
                json.dump(self.outbox, f, ensure_ascii=False)
        except Exception:
            pass

    def _flush_outbox(self):
        """연결된 직후 호출: 밀린 문자를 순서대로 보냄"""
        if not self.outbox:
            return
        pending, self.outbox = self.outbox, []
        sent = 0
        for item in pending:
            if time.time() - item.get("ts", 0) > 24 * 3600 or item.get("from") not in (None, self.cfg.name):
                continue   # 하루 넘은 것, 다른 사용자 것은 버림
            if self.client.send_json({"type": "text", "to": item["to"], "text": item["text"]}):
                sent += 1
            else:
                self.outbox.append(item)
        self._save_outbox()
        log("밀린 문자 전송: %d개 (남음 %d개)" % (sent, len(self.outbox)))
        if sent:
            self.notice = "연결돼서 밀린 문자 %d개를 보냈어요" % sent

    def send_text(self, text, to=None):
        text = text.strip()
        if not text:
            return False
        target = to if to else self.target_json()
        if self.connected and self.client.send_json({"type": "text", "to": target, "text": text}):
            log("문자 보내기 -> %r: %s" % (target, text))
            return True
        # 연결이 없거나 보내는 중 끊김 → 보관했다가 연결되면 자동 전송
        self.outbox.append({"to": target, "text": text, "ts": time.time(), "from": self.cfg.name})
        del self.outbox[:-50]
        self._save_outbox()
        self.notice = "지금 연결이 없어서 보관했어요. 연결되면 자동으로 보내요 (%d개 대기)" % len(self.outbox)
        log("문자 보관(연결 없음, %s) -> %r: %s" % (self.status, target, text))
        self.client.reconnect()   # 혹시 죽은 연결이면 바로 다시 붙도록
        return True

    def _on_text_msg(self, msg):
        cid = int(msg.get("id", 0))
        self.client.send_json({"type": "clip_ack", "id": cid})
        sender, ts, text = msg.get("from"), int(msg.get("ts") or time.time()), msg.get("text", "")
        if any(c["from"] == sender and c["ts"] == ts and c.get("text") == text for c in self.clips):
            return
        clip = {"kind": "text", "id": cid, "from": sender, "mode": msg.get("mode"), "ts": ts, "text": text}
        self._add_clip(clip)
        if not self.cfg.dnd:
            self.audio.effect(BEEP_IN)
            self._emit("text", clip)

    def _on_clip(self, cid, pcm):
        with self.lock:
            meta = self.pending.pop(cid, None)
            if not meta:
                return
            self.client.send_json({"type": "clip_ack", "id": cid})
            ts = int(meta.get("ts") or time.time())
            sender, mode = meta.get("from"), meta.get("mode")
            if any(c["from"] == sender and c["ts"] == ts for c in self.clips):
                return
            seconds = float(meta.get("seconds") or len(pcm) / 32000)
            path = os.path.join(self.clip_dir, "%d_%s_%s_%d_%.1f.pcm" % (cid, sender, mode, ts, seconds))
            try:
                with open(path, "wb") as f:
                    f.write(pcm)
            except Exception:
                return
            del pcm  # 메모리에 들고 있지 않음. 들을 때 파일에서 읽음
            clip = {"kind": "clip", "id": cid, "from": sender, "mode": mode, "ts": ts, "seconds": seconds, "file": path}
            self._add_clip(clip)
            if self.cfg.dnd:
                return
            auto = self.where == "home" or self.is_parent
            if auto:
                self.play_clip(clip)
            self._emit("clip", {"clip": clip, "auto": auto})

    def clip_title(self, c):
        if c.get("mine"):
            return "내가 %s" % ("모두에게" if c["mode"] == "all" else "%s님에게" % c["from"])
        if c["mode"] == "all":
            return "%s님 전체 방송" % c["from"]
        if c["mode"] == "group":
            return "%s님이 몇 명에게" % c["from"]
        return "%s님이 나에게" % c["from"]

    def play_clip(self, clip):
        with self.lock:
            if self.incoming_from or self.talking or not clip.get("file"):
                return
            try:
                with open(clip["file"], "rb") as f:
                    pcm = f.read()
            except Exception:
                return
            self.playing = clip
            self.audio.play_all(pcm)
            threading.Timer(clip["seconds"] + 0.8, self._clip_done, [clip]).start()

    def _clip_done(self, clip):
        with self.lock:
            if self.playing is clip:
                self.playing = None
        self._emit("state")

    def stop_clip(self):
        if self.playing:
            self.playing = None
            self.audio.stop_playback()

    def delete_clip(self, clip):
        with self.lock:
            if self.playing is clip:
                self.stop_clip()
            self.clips = [c for c in self.clips if c is not clip]
            if clip.get("file"):
                try:
                    os.remove(clip["file"])
                except Exception:
                    pass
            else:
                self._save_texts()


# ---------------------------------------------------------------- 알림 풍선 / 트레이

def toast(title, text):
    try:
        from winotify import Notification
        Notification(app_id=APP_TITLE, title=title, msg=text, duration="short").show()
    except Exception:
        pass


def make_icon(color, size=64):
    """별 모양 아이콘 (color = 바탕색: 연결됨 초록 / 끊김 회색 / 기본 파랑)"""
    import math
    from PIL import Image, ImageDraw
    S = size * 4
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((S * 0.04, S * 0.04, S * 0.96, S * 0.96), radius=S * 0.22, fill=color)
    pts = []
    for i in range(10):
        r = S * 0.38 if i % 2 == 0 else S * 0.16
        a = -math.pi / 2 + i * math.pi / 5
        pts.append((S / 2 + r * math.cos(a), S * 0.52 + r * math.sin(a)))
    d.polygon(pts, fill=(255, 205, 0, 255))
    return img.resize((size, size), Image.LANCZOS)


# ---------------------------------------------------------------- 화면

def run_ui(start_minimized):
    import tkinter as tk
    from tkinter import messagebox

    cfg = Config()
    radio = Radio(cfg)

    BG, CARD, TXT, SUB = "#0F172A", "#1E293B", "#F1F5F9", "#94A3B8"
    ACCENT, GREEN, RED, BLUE = "#F59E0B", "#22C55E", "#EF4444", "#2563EB"
    F = "Malgun Gothic"

    root = tk.Tk()
    root.title(APP_TITLE)
    root.configure(bg=BG)
    try:
        from PIL import ImageTk
        _win_icon = ImageTk.PhotoImage(make_icon((37, 99, 235, 255), 64))
        root.iconphoto(True, _win_icon)
    except Exception:
        pass
    root.geometry("460x800")
    root.minsize(420, 640)

    # ---- 트레이 아이콘 ----
    tray = {"icon": None}

    def show_window(*_):
        events.put(("show", None))   # 트레이 스레드에서 불리므로 큐로 넘김

    def hide_window():
        root.withdraw()

    def quit_app(*_):
        events.put(("quit", None))

    def start_tray():
        try:
            import pystray
        except Exception:
            return
        menu = pystray.Menu(
            pystray.MenuItem("열기", show_window, default=True),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("완전히 종료", quit_app),
        )
        icon = pystray.Icon(APP_NAME, make_icon((37, 99, 235, 255)), APP_TITLE, menu)
        tray["icon"] = icon
        threading.Thread(target=icon.run, daemon=True).start()

    def set_tray(connected, where):
        icon = tray["icon"]
        if not icon:
            return
        try:
            icon.icon = make_icon((34, 197, 94, 255) if connected else (100, 116, 139, 255))
            icon.title = "%s · %s" % (APP_TITLE, ("집" if where == "home" else "밖") if connected else "연결 안 됨")
        except Exception:
            pass

    def flash():
        if os.name == "nt":
            try:
                import ctypes
                ctypes.windll.user32.FlashWindow(int(root.wm_frame(), 16), True)
            except Exception:
                pass

    # ---- 화면 요소 ----
    tk.Label(root, text=APP_TITLE, font=(F, 20, "bold"), fg=TXT, bg=BG).pack(anchor="w", padx=20, pady=(18, 0))  # 제목에 버전 표시
    status = tk.Label(root, text="", font=(F, 10), fg=SUB, bg=BG)
    status.pack(anchor="w", padx=20)
    incoming = tk.Label(root, text="", font=(F, 13, "bold"), fg="black", bg=ACCENT, pady=8)

    tk.Label(root, text="누구에게 말할까요? (여러 명 고를 수 있어요)", font=(F, 10), fg=SUB, bg=BG).pack(anchor="w", padx=20, pady=(14, 4))
    grid = tk.Frame(root, bg=BG)
    grid.pack(fill="x", padx=16)

    talk = tk.Label(root, text="", font=(F, 18, "bold"), fg="white", bg=BLUE, height=4, cursor="hand2")
    talk.pack(fill="x", padx=20, pady=(14, 6))
    talk.bind("<ButtonPress-1>", lambda e: radio.start_talk())
    talk.bind("<ButtonRelease-1>", lambda e: radio.stop_talk())
    notice = tk.Label(root, text="", font=(F, 11), fg=ACCENT, bg=BG, wraplength=400)
    notice.pack(pady=(0, 6))

    # 글로 보내기 (말할 수 없을 때)
    text_box = tk.Frame(root, bg=CARD)
    text_box.pack(fill="x", padx=20, pady=6)
    tk.Label(text_box, text="말하기 어려우면 글로 보내기", font=(F, 9), fg=SUB, bg=CARD).pack(anchor="w", padx=10, pady=(8, 2))
    chip_row = tk.Frame(text_box, bg=CARD)
    chip_row.pack(fill="x", padx=6)

    def send_text(t):
        if radio.send_text(t):
            entry.delete(0, "end")

    def render_chips():
        for c in chip_row.winfo_children():
            c.destroy()
        for q in radio.quick_replies:
            tk.Button(chip_row, text=q, font=(F, 9), command=lambda q=q: send_text(q)).pack(side="left", padx=2, pady=2)
    render_chips()
    entry_row = tk.Frame(text_box, bg=CARD)
    entry_row.pack(fill="x", padx=8, pady=(2, 8))
    entry = tk.Entry(entry_row, font=(F, 11))
    entry.pack(side="left", fill="x", expand=True)
    entry.bind("<Return>", lambda e: send_text(entry.get()))
    tk.Button(entry_row, text="보내기", font=(F, 10), command=lambda: send_text(entry.get())).pack(side="left", padx=(6, 0))

    # 메시지 목록: 입력칸 아래, 높이 고정 + 스크롤 (목록이 늘어나도 입력칸을 밀지 않음)
    clips_box = tk.Frame(root, bg=CARD)
    tk.Label(clips_box, text="메시지 (최근 것이 위)", font=(F, 10), fg=SUB, bg=CARD).pack(anchor="w", padx=10, pady=(8, 2))
    clips_canvas = tk.Canvas(clips_box, bg=CARD, highlightthickness=0, height=170)
    clips_scroll = tk.Scrollbar(clips_box, orient="vertical", command=clips_canvas.yview)
    clips_canvas.configure(yscrollcommand=clips_scroll.set)
    clips_scroll.pack(side="right", fill="y", pady=(0, 8))
    clips_canvas.pack(side="left", fill="both", expand=True, padx=(6, 0), pady=(0, 8))
    clips_list = tk.Frame(clips_canvas, bg=CARD)
    clips_win = clips_canvas.create_window((0, 0), window=clips_list, anchor="nw")
    clips_list.bind("<Configure>", lambda e: clips_canvas.configure(scrollregion=clips_canvas.bbox("all")))
    clips_canvas.bind("<Configure>", lambda e: clips_canvas.itemconfigure(clips_win, width=e.width))
    clips_canvas.bind("<MouseWheel>", lambda e: clips_canvas.yview_scroll(-1 if e.delta > 0 else 1, "units"))

    opts = tk.Frame(root, bg=CARD)
    opts.pack(fill="x", padx=20, pady=6, side="bottom")
    hotkey_label = tk.Label(opts, text="", font=(F, 9), fg=SUB, bg=CARD)
    hotkey_label.pack(anchor="w", padx=10, pady=(0, 4))
    tk.Button(opts, text="설정", font=(F, 10), command=lambda: open_settings()).pack(pady=(0, 8))

    # ---- 단축키 (선택) ----
    hk = {"down": False}

    def bind_hotkey():
        global keyboard
        key = cfg.hotkey.strip().lower().replace(" ", "")
        if not key:
            if keyboard is not None:
                try:
                    keyboard.unhook_all()
                except Exception:
                    pass
            hotkey_label.config(text="단축키 없음 (설정에서 정하면 다른 창에서도 말하기 가능)")
            return
        if keyboard is None:
            try:
                import keyboard as _kb
                keyboard = _kb
            except Exception:
                hotkey_label.config(text="단축키 기능을 쓸 수 없어요")
                return
        try:
            keyboard.unhook_all()
        except Exception:
            pass

        def down(_):
            if not hk["down"]:
                hk["down"] = True
                radio.start_talk()

        def up(_):
            hk["down"] = False
            radio.stop_talk()
        try:
            main_key = key.split("+")[-1]
            if "+" in key:
                keyboard.add_hotkey(key, down)          # 조합키 (예: ctrl+alt+r)
            else:
                keyboard.on_press_key(main_key, down)   # 단일키 (예: f9)
            keyboard.on_release_key(main_key, up)       # 마지막 키를 떼면 끝
            hotkey_label.config(text="[%s] 누르고 있는 동안 말하기 (다른 창에서도)" % key.upper())
        except Exception:
            hotkey_label.config(text="단축키 [%s]를 등록할 수 없어요" % key)

    # ---- 설정 창 ----
    def open_settings():
        show_window()
        w = tk.Toplevel(root)
        w.title("설정")
        w.configure(bg=BG)
        w.resizable(False, False)
        w.transient(root)
        w.grab_set()
        members = radio.members or DEFAULT_MEMBERS

        def who_picker(parent, title):
            tk.Label(parent, text=title, font=(F, 10), fg=SUB, bg=BG).pack(anchor="w", padx=16, pady=(12, 0))
            var = tk.StringVar(value=cfg.name)
            row = tk.Frame(parent, bg=BG)
            row.pack(anchor="w", padx=12)
            for n in members:
                tk.Radiobutton(row, text=n, value=n, variable=var, font=(F, 12), fg=TXT, bg=BG,
                               selectcolor=BG, activebackground=BG, activeforeground=TXT).pack(side="left", padx=6)
            return var

        # ---- 처음 실행: 누구 것인지만 고르면 끝 ----
        if not cfg.name:
            var = who_picker(w, "이 PC는 누구 것인가요?")

            def start():
                if not var.get():
                    messagebox.showerror("설정", "누구 것인지 골라 주세요", parent=w)
                    return
                cfg.set(name=var.get())
                try:
                    set_autostart(cfg.autostart)
                except Exception:
                    pass
                radio.client.reconnect()
                w.destroy()
            tk.Button(w, text="시작", font=(F, 11), width=12, command=start).pack(pady=16)
            return

        # ---- 평소 설정 ----
        tk.Label(w, text="이 PC: %s" % cfg.name, font=(F, 12, "bold"), fg=TXT, bg=BG).pack(anchor="w", padx=16, pady=(12, 0))
        fields = {}
        tk.Label(w, text="말하기 단축키 (비우면 없음)", font=(F, 9), fg=SUB, bg=BG).pack(anchor="w", padx=16, pady=(10, 0))
        row = tk.Frame(w, bg=BG)
        row.pack(anchor="w", padx=16, fill="x")
        hk_entry = tk.Entry(row, font=(F, 11), width=26)
        hk_entry.insert(0, cfg.hotkey)
        hk_entry.pack(side="left")

        def capture():
            """키(조합 가능)를 직접 눌러서 지정"""
            global keyboard
            if keyboard is None:
                try:
                    import keyboard as _kb
                    keyboard = _kb
                except Exception:
                    messagebox.showwarning("단축키", "단축키 기능을 쓸 수 없어요", parent=w)
                    return
            hk_entry.delete(0, "end")
            hk_entry.insert(0, "키를 누르세요…")

            def worker():
                try:
                    combo = keyboard.read_hotkey(suppress=False)
                except Exception:
                    combo = ""
                events.put(("hotkey_captured", (hk_entry, combo)))
            threading.Thread(target=worker, daemon=True).start()
        tk.Button(row, text="키 눌러서 지정", font=(F, 9), command=capture).pack(side="left", padx=(6, 0))
        tk.Button(row, text="없음", font=(F, 9), command=lambda: hk_entry.delete(0, "end")).pack(side="left", padx=(4, 0))
        tk.Label(w, text="예: F9, Scroll Lock, Ctrl+Alt+R. 게임·다른 프로그램과 겹치지 않는 키가 좋아요.",
                 font=(F, 8), fg=SUB, bg=BG).pack(anchor="w", padx=16)
        fields["hotkey"] = hk_entry

        tk.Label(w, text="빠른 답장 문구 (쉼표로 구분)", font=(F, 9), fg=SUB, bg=BG).pack(anchor="w", padx=16, pady=(10, 0))
        q_entry = tk.Entry(w, font=(F, 11), width=44)
        q_entry.insert(0, cfg.quick)
        q_entry.pack(padx=16)
        fields["quick"] = q_entry

        auto_var = tk.BooleanVar(value=cfg.autostart)
        tk.Checkbutton(w, text="Windows 시작 시 자동 실행 (트레이에 조용히 켜짐)", variable=auto_var, font=(F, 10),
                       fg=TXT, bg=BG, selectcolor=BG, activebackground=BG, activeforeground=TXT
                       ).pack(anchor="w", padx=12, pady=(12, 0))

        def shortcut():
            ok, msg = make_desktop_shortcut()
            (messagebox.showinfo if ok else messagebox.showwarning)("바로가기", msg + ("\n\n작업 표시줄에 두고 싶으면 그 바로가기를 작업 표시줄로 끌어다 놓으세요." if ok else ""), parent=w)
        tk.Button(w, text="바탕화면에 바로가기 만들기", font=(F, 9), command=shortcut).pack(anchor="w", padx=16, pady=(8, 0))

        def save():
            hk = hk_entry.get().strip()
            if hk.startswith("키를"):
                hk = cfg.hotkey
            cfg.set(hotkey=hk, quick=q_entry.get().strip() or Config.DEFAULTS["quick"], autostart=auto_var.get())
            try:
                set_autostart(cfg.autostart)
            except Exception as e:
                messagebox.showwarning("설정", "자동 실행 등록 실패: %s" % e, parent=w)
            bind_hotkey()
            radio.quick_replies = [q.strip() for q in cfg.quick.split(",") if q.strip()]
            render_chips()
            w.destroy()
        tk.Button(w, text="저장", font=(F, 10), width=12, command=save).pack(pady=(14, 6))

        # ---- 관리자 설정: 가족 암호를 알아야 열림 ----
        admin = tk.Frame(w, bg=BG)

        def open_admin():
            from tkinter import simpledialog
            pw = simpledialog.askstring("가족 암호", "가족 암호를 입력하세요", show="*", parent=w)
            if pw is None:
                return
            if pw != cfg.key:
                messagebox.showerror("설정", "암호가 달라요", parent=w)
                return
            admin_btn.pack_forget()
            admin.pack(fill="x", pady=(4, 10))
            a_var = who_picker(admin, "이 PC는 누구 것인가요? (바꾸면 바로 적용)")
            a_fields = {}
            for k, label in [("host", "집 주소"), ("out_host", "밖 주소 (Tailscale)"), ("key", "가족 암호")]:
                tk.Label(admin, text=label, font=(F, 9), fg=SUB, bg=BG).pack(anchor="w", padx=16, pady=(8, 0))
                e = tk.Entry(admin, font=(F, 11), width=44)
                e.insert(0, str(getattr(cfg, k)))
                e.pack(padx=16)
                a_fields[k] = e

            def save_admin():
                v = {k: e.get().strip() for k, e in a_fields.items()}
                v["name"] = a_var.get()
                if not (v["name"] and v["host"] and v["key"]):
                    messagebox.showerror("설정", "사용자·집 주소·암호는 비울 수 없어요", parent=w)
                    return
                cfg.set(**v)
                radio.client.reconnect()
                w.destroy()
            tk.Button(admin, text="변경 저장하고 다시 연결", font=(F, 10), command=save_admin).pack(pady=10)
        admin_btn = tk.Button(w, text="사용자·주소 변경 (가족 암호 필요)", font=(F, 9), command=open_admin)
        admin_btn.pack(pady=(0, 12))


    # ---- 오른쪽 아래 작은 팝업 (5초 뒤 사라짐, 마우스를 올리거나 누르면 유지 + 바로 답장) ----
    popup = {"win": None, "timer": None}

    def close_popup():
        if popup["timer"]:
            try:
                root.after_cancel(popup["timer"])
            except Exception:
                pass
            popup["timer"] = None
        if popup["win"]:
            try:
                popup["win"].destroy()
            except Exception:
                pass
            popup["win"] = None

    def show_popup(title, text, reply_to=None, seconds=5):
        close_popup()
        pw = tk.Toplevel(root)
        pw.overrideredirect(True)
        pw.attributes("-topmost", True)
        try:
            pw.attributes("-alpha", 0.96)
        except Exception:
            pass
        pw.configure(bg=ACCENT)
        inner = tk.Frame(pw, bg=CARD)
        inner.pack(padx=2, pady=2, fill="both", expand=True)
        head = tk.Frame(inner, bg=CARD)
        head.pack(fill="x", padx=12, pady=(10, 0))
        tk.Label(head, text=title, font=(F, 11, "bold"), fg=TXT, bg=CARD, anchor="w").pack(side="left")
        tk.Button(head, text="✕", font=(F, 9), bd=0, fg=SUB, bg=CARD, activebackground=CARD, command=close_popup).pack(side="right")
        if text:
            tk.Label(inner, text=text, font=(F, 11), fg=TXT, bg=CARD, wraplength=300, justify="left", anchor="w").pack(fill="x", padx=12, pady=(4, 0))
        reply_box = tk.Frame(inner, bg=CARD)

        def send_reply(t):
            t = t.strip()
            if t and reply_to and radio.send_text(t, to=reply_to):
                close_popup()
                show_popup("보냈어요", "%s님에게: %s" % (reply_to, t), seconds=2)

        def show_reply(*_):
            keep()
            if reply_box.winfo_ismapped() or not reply_to:
                return
            reply_box.pack(fill="x", padx=10, pady=(8, 10))
            chips = tk.Frame(reply_box, bg=CARD)
            chips.pack(fill="x")
            for q in radio.quick_replies[:6]:
                tk.Button(chips, text=q, font=(F, 9), command=lambda q=q: send_reply(q)).pack(side="left", padx=2, pady=2)
            erow = tk.Frame(reply_box, bg=CARD)
            erow.pack(fill="x", pady=(4, 0))
            ent = tk.Entry(erow, font=(F, 10))
            ent.pack(side="left", fill="x", expand=True)
            ent.bind("<Return>", lambda e: send_reply(ent.get()))
            tk.Button(erow, text="보내기", font=(F, 9), command=lambda: send_reply(ent.get())).pack(side="left", padx=(4, 0))
            ent.focus_set()
            place()

        def keep(*_):
            if popup["timer"]:
                try:
                    root.after_cancel(popup["timer"])
                except Exception:
                    pass
                popup["timer"] = None

        if reply_to:
            tk.Label(inner, text="클릭하면 바로 답장", font=(F, 8), fg=SUB, bg=CARD, anchor="w").pack(fill="x", padx=12, pady=(2, 8))
        else:
            tk.Frame(inner, bg=CARD, height=8).pack()
        for wdg in (pw, inner, head):
            wdg.bind("<Enter>", keep)
            wdg.bind("<Button-1>", show_reply)
        for child in inner.winfo_children():
            child.bind("<Button-1>", show_reply)
            child.bind("<Enter>", keep)

        def place():
            pw.update_idletasks()
            w_, h_ = max(pw.winfo_reqwidth(), 320), pw.winfo_reqheight()
            sw, sh = pw.winfo_screenwidth(), pw.winfo_screenheight()
            pw.geometry("%dx%d+%d+%d" % (w_, h_, sw - w_ - 16, sh - h_ - 60))
        place()
        popup["win"] = pw
        popup["timer"] = root.after(int(seconds * 1000), close_popup)

    # ---- 이벤트 (다른 스레드에서 옴 → 큐에 넣고 화면 스레드에서 처리. 다른 스레드에서 tkinter를 건드리면 멈출 수 있음) ----
    events = queue.Queue()
    radio.on_event = lambda kind, info: events.put((kind, info))

    def pump_events():
        while True:
            try:
                kind, info = events.get_nowait()
            except queue.Empty:
                return
            try:
                handle_event(kind, info)
            except Exception:
                pass

    def handle_event(kind, info):
        if True:
            if kind == "incoming":
                flash()
                who = info.get("from")
                show_popup("🔊 %s님이 말해요" % who, "전체 방송" if info.get("mode") == "all" else "나에게", reply_to=who, seconds=8)
            elif kind == "clip":
                c = info["clip"]
                flash()
                show_popup("🔊 " + radio.clip_title(c), "%d초 · %s" % (max(1, int(c["seconds"])),
                           "방금 재생했어요" if info["auto"] else "창을 열어 [듣기]를 누르세요"), reply_to=c["from"])
                if not info["auto"]:
                    events.put(("show", None))
            elif kind == "text":
                flash()
                show_popup("💬 " + radio.clip_title(info), info.get("text", ""), reply_to=info["from"])
            elif kind == "state":
                set_tray(radio.connected, radio.where)
            elif kind == "hotkey_captured":
                entry, combo = info
                try:
                    entry.delete(0, "end")
                    entry.insert(0, combo)
                except Exception:
                    pass
            elif kind == "show":
                root.deiconify(); root.lift(); root.focus_force()
            elif kind == "quit":
                radio.stop_talk()
                if tray["icon"]:
                    try:
                        tray["icon"].stop()
                    except Exception:
                        pass
                root.destroy()

    # ---- 화면 갱신 ----
    cache = {"buttons": None, "clips": None}

    def refresh():
        pump_events()
        try:
            while True:
                show_requests.get_nowait()
                events.put(("show", None))
        except queue.Empty:
            pass
        with radio.lock:
            me = cfg.name
            others = [m for m in radio.members if m != me]
            if radio.target != "all":
                kept = [t for t in radio.target_list if t in others]
                if len(kept) != len(radio.target_list):
                    cfg.set(target=",".join(kept) if kept else "all")
            sig = (tuple(others), tuple(radio.online), radio.target, tuple(sorted(radio.member_where.items())))
            if sig != cache["buttons"]:
                cache["buttons"] = sig
                for c in grid.winfo_children():
                    c.destroy()
                items = [("전체", "all", True)] + [(m, m, m in radio.online) for m in others]
                for i, (label, value, on) in enumerate(items):
                    sel = (radio.target == "all") if value == "all" else (value in radio.target_list)
                    place = ""
                    if value != "all" and on:
                        place = "\n" + ("밖" if radio.member_where.get(value) == "out" else "집")
                    text = label if value == "all" else ("● " if on else "○ ") + label + place
                    b = tk.Label(grid, text=text, font=(F, 11, "bold"), pady=6,
                                 fg="black" if sel else (TXT if on else SUB), bg=ACCENT if sel else CARD, cursor="hand2")
                    b.grid(row=i // 3, column=i % 3, sticky="nsew", padx=4, pady=4)
                    b.bind("<Button-1>", lambda e, v=value: radio.toggle_target(v))
                for c in range(3):
                    grid.columnconfigure(c, weight=1)

            if radio.connected:
                status.config(text="● %s · 지금 %s · 가족 %d명 접속 중" % (me, "집" if radio.where == "home" else "밖", len(radio.online)), fg=GREEN)
            else:
                status.config(text=radio.status, fg=SUB)

            if radio.incoming_from:
                incoming.config(text=("🔊 %s님 전체 방송 중" if radio.incoming_mode == "all"
                                      else "🔊 %s님이 나에게 말하는 중") % radio.incoming_from)
                if not incoming.winfo_ismapped():
                    incoming.pack(fill="x", padx=20, pady=(10, 0), after=status)
            elif incoming.winfo_ismapped():
                incoming.pack_forget()

            who = radio.target_label()
            if radio.talking:
                talk.config(text="🎙 말하는 중…\n떼면 끝", bg=RED)
            elif radio.requesting:
                talk.config(text="잠깐만요…", bg="#B45309")
            elif not radio.connected:
                talk.config(text="연결 대기 중", bg="#334155")
            else:
                talk.config(text="누르고 말하기\n(%s)" % who, bg=BLUE)
            notice.config(text=radio.notice)

            csig = tuple((c["id"], c["ts"], radio.playing is c) for c in radio.clips[:20])
            if csig != cache["clips"]:
                cache["clips"] = csig
                for c in clips_list.winfo_children():
                    c.destroy()
                if radio.clips:
                    if not clips_box.winfo_ismapped():
                        clips_box.pack(fill="x", padx=20, pady=6, after=text_box)
                    for c in radio.clips[:20]:
                        row = tk.Frame(clips_list, bg=CARD)
                        row.pack(fill="x", pady=2)
                        row.bind("<MouseWheel>", lambda e: clips_canvas.yview_scroll(-1 if e.delta > 0 else 1, "units"))
                        playing = radio.playing is c
                        when_ = time.strftime("%m/%d %H:%M", time.localtime(c["ts"]))
                        if c.get("kind") == "text":
                            desc = "%s\n💬 %s\n%s" % (radio.clip_title(c), c.get("text", ""), when_)
                        else:
                            desc = "%s%s\n%s · %d초" % ("🔊 " if playing else "", radio.clip_title(c), when_, max(1, int(c["seconds"])))
                        tk.Label(row, text=desc, font=(F, 9), fg=SUB if c.get("mine") else TXT, bg=CARD,
                                 justify="left", wraplength=240).pack(side="left", padx=4)
                        tk.Button(row, text="삭제", font=(F, 9), command=lambda c=c: radio.delete_clip(c)).pack(side="right", padx=2)
                        if not c.get("mine"):
                            tk.Button(row, text="답장", font=(F, 9),
                                      command=lambda c=c: (radio.set_target(c["from"]), entry.focus_set())).pack(side="right", padx=2)
                        if c.get("kind") != "text":
                            tk.Button(row, text="정지" if playing else "듣기", font=(F, 9),
                                      command=(lambda c=c, p=playing: radio.stop_clip() if p else radio.play_clip(c))).pack(side="right", padx=2)
                elif clips_box.winfo_ismapped():
                    clips_box.pack_forget()
        root.after(150, refresh)

    # 창 닫기 = 트레이로 (계속 듣기). 완전 종료는 트레이 메뉴에서
    def on_close():
        if tray["icon"]:
            hide_window()
            if not cache.get("tray_tip"):
                cache["tray_tip"] = True
                toast(APP_TITLE, "오른쪽 아래 시계 옆 아이콘에서 계속 듣고 있어요. 완전히 끄려면 아이콘 → 완전히 종료")
        else:
            quit_app()
    root.protocol("WM_DELETE_WINDOW", on_close)

    try:
        set_autostart(cfg.autostart)
    except Exception:
        pass
    start_tray()
    bind_hotkey()
    radio.start()
    refresh()
    if start_minimized and cfg.configured and tray["icon"]:
        root.withdraw()
    elif not cfg.configured:
        root.after(300, open_settings)
    root.mainloop()


GUARD_PORT = 47651
show_requests = queue.Queue()   # 두 번째 실행이 "창 열어 줘"라고 보낸 요청
_mutex_handle = None


def single_instance():
    """
    프로그램이 하나만 돌게 함. 이미 실행 중이면 그 프로그램에 '창 열어 줘'를 보내고 None을 돌려줌.
    Windows에서는 소켓 포트 검사가 믿을 수 없어서(같은 포트를 둘이 열 수 있음) 이름 있는 뮤텍스로 확인함.
    """
    global _mutex_handle
    already = False
    if os.name == "nt":
        try:
            import ctypes
            k32 = ctypes.windll.kernel32
            _mutex_handle = k32.CreateMutexW(None, False, "Local\\WoorizipRadio_single_instance")
            already = (k32.GetLastError() == 183)   # ERROR_ALREADY_EXISTS
        except Exception as e:
            log("뮤텍스 확인 실패: %s" % e)
    s = None
    if not already:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            if os.name == "nt":
                s.setsockopt(socket.SOL_SOCKET, getattr(socket, "SO_EXCLUSIVEADDRUSE", -5), 1)
            s.bind(("127.0.0.1", GUARD_PORT))
            s.listen(2)
        except OSError:
            s = None
            if os.name != "nt":
                already = True
    if already:
        try:
            c = socket.create_connection(("127.0.0.1", GUARD_PORT), timeout=1)
            c.sendall(b"show")
            c.close()
        except Exception:
            pass
        return None
    if s is None:
        return object()   # 뮤텍스는 잡았지만 '창 열어 줘' 통로는 못 만듦 → 그래도 실행

    def accept_loop():
        while True:
            try:
                conn, _ = s.accept()
                conn.settimeout(1)
                data = conn.recv(16)
                conn.close()
                if data.startswith(b"show"):
                    show_requests.put(True)
            except Exception:
                pass
    threading.Thread(target=accept_loop, daemon=True).start()
    return s


def make_desktop_shortcut():
    """바탕화면에 '우리집 무전기' 바로가기를 만듦. 같은 이름 파일을 덮어쓰므로 여러 번 눌러도 하나만 생김"""
    if os.name != "nt":
        return False, "Windows에서만 돼요"
    target = sys.executable if getattr(sys, "frozen", False) else os.path.abspath(__file__)
    try:
        import subprocess
        ps = ("$s=(New-Object -ComObject WScript.Shell).CreateShortcut([Environment]::GetFolderPath('Desktop')+'\\우리집 무전기.lnk');"
              "$s.TargetPath='%s';$s.WorkingDirectory='%s';$s.IconLocation='%s,0';$s.Description='우리집 무전기';$s.Save()"
              % (target.replace("'", "''"), os.path.dirname(target).replace("'", "''"), target.replace("'", "''")))
        r = subprocess.run(["powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", ps],
                           creationflags=0x08000000, timeout=20, capture_output=True, text=True)
        if r.returncode != 0:
            log("바로가기 만들기 실패: %s" % r.stderr.strip()[:200])
            return False, "바로가기를 만들지 못했어요"
        return True, "바탕화면에 '우리집 무전기' 바로가기를 만들었어요"
    except Exception as e:
        log("바로가기 만들기 실패: %s" % e)
        return False, "바로가기를 만들지 못했어요"


if __name__ == "__main__":
    _guard = single_instance()
    if _guard is None:
        sys.exit(0)   # 이미 실행 중 → 그쪽 창이 열림
    log("시작 %s %s" % (APP_TITLE, sys.argv[1:]))
    run_ui("--minimized" in sys.argv)
