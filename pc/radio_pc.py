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
import hashlib
import json
import re
import shutil
import subprocess
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
APP_TITLE = "우리집 " + __version__
RATE = 16000
FRAME_BYTES = 640  # 20ms, 16비트 모노
LIVE, CLIP = 1, 2
DEFAULT_MEMBERS = ["엄마", "아빠", "첫째", "둘째", "셋째"]
# 가족별 고정 아이콘·색 (방 버튼, 말하기 버튼, 받은 메시지 이름에 똑같이 씀)
MEMBER_STYLE = {"엄마": ("👩", "#F472B6"), "아빠": ("👨", "#60A5FA"), "첫째": ("🧒", "#34D399"),
                "둘째": ("🧒", "#FB923C"), "셋째": ("🧒", "#A78BFA")}
GROUP_STYLE = {"아이들": ("👧", "#2DD4BF"), "부모님": ("👫", "#818CF8")}
ALL_STYLE = ("🏠", "#F5B301")
DEFAULT_GROUPS = "아이들=첫째,둘째,셋째;부모님=엄마,아빠"


def member_style(name):
    return MEMBER_STYLE.get(name, ("🙂", "#9AA6BC"))


def blend(fg, bg, a):
    """두 색을 섞음 (a=0 → bg, 1 → fg). 대화 묶음 상자의 연한 바탕색에 사용"""
    f = [int(fg[i:i + 2], 16) for i in (1, 3, 5)]
    g = [int(bg[i:i + 2], 16) for i in (1, 3, 5)]
    return "#%02X%02X%02X" % tuple(int(g[i] + (f[i] - g[i]) * a) for i in range(3))


def parse_groups(text):
    """'아이들=첫째,둘째;부모님=엄마,아빠' → [(이름, [멤버...]), ...]"""
    out = []
    for part in str(text or "").split(";"):
        if "=" in part:
            name, mem = part.split("=", 1)
            members = [m.strip() for m in mem.split(",") if m.strip()]
            if name.strip() and members:
                out.append((name.strip(), members))
    return out

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
                "target": "all", "dnd": False, "autostart": True, "groups": DEFAULT_GROUPS}

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
        exe = installed_exe_path() if os.path.exists(installed_exe_path()) else sys.executable   # 항상 설치본을 가리킴
        return '"%s" --minimized' % exe
    exe = sys.executable.replace("python.exe", "pythonw.exe")
    return '"%s" "%s" --minimized' % (exe, os.path.abspath(__file__))


def set_autostart(enabled):
    if os.name != "nt":
        return
    import winreg
    with winreg.CreateKeyEx(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:   # 키가 없는 새 계정도 됨
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
        """30초마다 서버에 신호를 보내고, 2분 동안 아무 응답이 없으면 죽은 연결로 보고 다시 붙음"""
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
                s.shutdown(socket.SHUT_RDWR)   # 읽는 중인 스레드를 바로 깨움
            except Exception:
                pass
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
        self.receipts = {}       # 내가 보낸 메시지 mid -> {이름: sent|received|seen}
        self.seen_sent = set()   # 내가 '봤어요/들었어요'라고 알린 mid
        self.schedules = []      # 내 예약 메시지 목록 (서버가 줌)
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
        return "모두" if self.target == "all" else ", ".join(self.target_list)

    # ---- 대화방: 모두 / 가족 한 명 / 묶음(아이들·부모님) ----

    def rooms(self):
        """[(키, 이름, 멤버 목록 또는 None=모두, 아이콘, 색)]"""
        me = self.cfg.name
        others = [m for m in self.members if m != me]
        out = [("all", "모두", None, ALL_STYLE[0], ALL_STYLE[1])]
        for m in others:
            ic, col = member_style(m)
            out.append((m, m, [m], ic, col))
        for name, members in parse_groups(self.cfg.groups):
            members = [m for m in members if m in others]
            if len(members) >= 2:   # 나를 빼고 한 명뿐이면 그 사람 방과 같으니 생략
                ic, col = GROUP_STYLE.get(name, ("👪", "#9AA6BC"))
                out.append(("g:" + name, name, members, ic, col))
        return out

    def current_room(self):
        """지금 고른 상대에 해당하는 방. 방에 없는 조합이면 ('custom', '엄마, 첫째', [...], '👥', 회색)"""
        tl = self.target_list
        for room in self.rooms():
            if room[2] is None:
                if self.target == "all":
                    return room
            elif set(room[2]) == set(tl):
                return room
        return ("custom", ", ".join(tl), tl, "👥", "#9AA6BC")

    def select_room(self, key):
        with self.lock:
            if self.talking:
                return
            for k, _, members, _, _ in self.rooms():
                if k == key:
                    self.cfg.set(target="all" if members is None else ",".join(members))
                    return

    def room_clips(self, room):
        """그 방에 보이는 메시지: 모두 방은 전부, 나머지는 그 사람(들)과 주고받은 것"""
        members = room[2]
        if members is None:
            return list(self.clips)
        ms = set(members)
        out = []
        for c in self.clips:
            if c.get("mine"):
                if c.get("mode") != "all" and ms & set(str(c.get("from", "")).split(", ")):
                    out.append(c)
            elif c.get("from") in ms:
                out.append(c)
        return out

    def conv_of(self, c):
        """이 메시지가 '누구와의 대화'인지. 모두에게 보낸 글은 None(묶음 없음), 그 외는 (키, 꼬리표, 색)"""
        if c.get("mode") == "all":
            return None
        names = [n for n in str(c.get("from", "")).split(", ") if n] if c.get("mine") else [c.get("from")]
        if len(names) == 1:
            ic, col = member_style(names[0])
            return (names[0], "%s %s와의 대화" % (ic, names[0]), col)
        for room in self.rooms():
            if room[2] is not None and set(room[2]) == set(names):
                return (room[0], "%s %s에게" % (room[3], room[1]), room[4])
        return ("c:" + ",".join(sorted(names)), ", ".join(names) + "에게", "#9AA6BC")

    def unread_count(self, room):
        return sum(1 for c in self.room_clips(room)
                   if not c.get("mine") and c.get("kind") in ("text", "clip") and (c.get("mid") or 0) and c.get("mid") not in self.seen_sent)

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
            elif t == "talk_silent":   # 말소리가 없어서 가족에게 아무것도 안 나감 (버튼만 스친 경우 등)
                self.talking = self.requesting = False
                self.audio.stop_mic()
                self.notice = "소리가 안 들려 보내지 않았어요"
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
                self._add_clip({"kind": "text", "id": int(msg.get("id", 0)), "mid": int(msg.get("mid", 0)),
                                "from": self.cfg.name if to == "all" else to,
                                "mode": "all" if to == "all" else "direct", "ts": int(msg.get("ts") or time.time()),
                                "text": msg.get("text", ""), "mine": True})
                self.notice = "보냈어요"
            elif t == "talk_sent":
                to = msg.get("to") or []
                self._add_clip({"kind": "voice_sent", "id": int(msg.get("mid", 0)), "mid": int(msg.get("mid", 0)),
                                "from": self.cfg.name if msg.get("mode") == "all" else ", ".join(to),
                                "mode": "all" if msg.get("mode") == "all" else "direct", "ts": int(msg.get("ts") or time.time()),
                                "seconds": float(msg.get("seconds") or 0), "mine": True})
            elif t == "history":
                self._merge_history(msg.get("items") or [])
            elif t == "receipt":
                self.receipts[int(msg.get("mid", 0))] = msg.get("status") or {}
            elif t == "receipts":
                for it in msg.get("items") or []:
                    self.receipts[int(it.get("mid", 0))] = it.get("status") or {}
            elif t == "schedules":
                self.schedules = msg.get("items") or []
                self._emit("schedules")
            elif t == "schedule_failed":
                self.notice = "예약 실패: %s" % msg.get("reason", "")
                self._emit("schedules")
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
        self.clips = items[:self.MAX_CLIPS]

    MAX_CLIPS = 150

    def _save_texts(self):
        try:
            with open(os.path.join(data_dir(), "texts.json"), "w", encoding="utf-8") as f:
                json.dump([c for c in self.clips if c.get("kind") in ("text", "voice_sent")][:120], f, ensure_ascii=False)
        except Exception:
            pass

    def _add_clip(self, clip):
        self.clips.insert(0, clip)
        self.clips.sort(key=lambda c: -c["ts"])
        for old in self.clips[self.MAX_CLIPS:]:
            if old.get("file"):
                try:
                    os.remove(old["file"])
                except Exception:
                    pass
        del self.clips[self.MAX_CLIPS:]
        if clip.get("kind") in ("text", "voice_sent"):
            self._save_texts()

    def _merge_history(self, items):
        """서버가 보관한 문자 기록을 합침 (이미 있는 것은 mid로 건너뜀) → 폰·PC 어디서나 같은 대화"""
        have = {c.get("mid") for c in self.clips if c.get("mid")}
        added = 0
        for it in items:
            mid = int(it.get("mid") or 0)
            if not mid or mid in have:
                continue
            mine = it.get("from") == self.cfg.name
            to = it.get("to") or []
            clip = {"kind": "text", "id": mid, "mid": mid, "ts": int(it.get("ts") or 0), "text": str(it.get("text", "")),
                    "mode": it.get("mode") or "direct",
                    "from": (self.cfg.name if it.get("mode") == "all" else ", ".join(to)) if mine else it.get("from")}
            if mine:
                clip["mine"] = True
            self.clips.append(clip)
            have.add(mid)
            added += 1
        if added:
            self.clips.sort(key=lambda c: -c["ts"])
            del self.clips[self.MAX_CLIPS:]
            self._save_texts()
            log("대화 기록 %d개 합침" % added)

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
        with self.lock:
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
        if any(c.get("kind") == "text" and c["from"] == sender and c["ts"] == ts and c.get("text") == text for c in self.clips):
            return
        clip = {"kind": "text", "id": cid, "mid": int(msg.get("mid", 0)), "from": sender, "mode": msg.get("mode"), "ts": ts, "text": text}
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
            if any(c.get("kind") == "clip" and c["from"] == sender and c["ts"] == ts for c in self.clips):
                return
            seconds = float(meta.get("seconds") or len(pcm) / 32000)
            path = os.path.join(self.clip_dir, "%d_%s_%s_%d_%.1f.pcm" % (cid, sender, mode, ts, seconds))
            try:
                with open(path, "wb") as f:
                    f.write(pcm)
            except Exception:
                return
            del pcm  # 메모리에 들고 있지 않음. 들을 때 파일에서 읽음
            clip = {"kind": "clip", "id": cid, "mid": int(meta.get("mid", 0)), "from": sender, "mode": mode, "ts": ts, "seconds": seconds, "file": path}
            self._add_clip(clip)
            if self.cfg.dnd:
                return
            auto = self.where == "home" or self.is_parent
            if auto:
                self.play_clip(clip)
            self._emit("clip", {"clip": clip, "auto": auto})

    def mark_seen(self, clip):
        """받은 메시지를 봤다/들었다고 보낸 사람에게 알림 (한 번만)"""
        mid = clip.get("mid") or 0
        if not mid or clip.get("mine") or mid in self.seen_sent:
            return
        self.seen_sent.add(mid)
        if not self.client.send_json({"type": "seen", "mid": mid}):
            self.seen_sent.discard(mid)   # 연결이 없으면 다음 갱신 때 다시 시도

    def receipt_text(self, c):
        """내가 보낸 메시지의 가족별 확인 표시: ✓ 받음, ✓✓ 봄(들음)"""
        st = self.receipts.get(c.get("mid") or -1)
        if not st:
            return ""
        mark = {"sent": "·", "received": "✓", "seen": "✓✓"}
        return " · ".join("%s%s" % (n, mark.get(v, "·")) for n, v in st.items())

    def schedule_add(self, text, at, repeat, to=None):
        return self.client.send_json({"type": "schedule_add", "to": to if to else self.target_json(),
                                      "text": text, "at": int(at), "repeat": repeat})

    def schedule_delete(self, sid):
        return self.client.send_json({"type": "schedule_delete", "sid": int(sid)})

    def clip_title(self, c):
        if c.get("mine"):
            what = " (음성 %d초)" % max(1, int(c.get("seconds") or 0)) if c.get("kind") == "voice_sent" else ""
            return "내가 %s%s" % ("모두에게" if c["mode"] == "all" else "%s님에게" % c["from"], what)
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
            self.mark_seen(clip)

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


# ---------------------------------------------------------------- 자동 업데이트 (NAS에서 새 버전을 받아 두었다가 한가할 때 교체)

def version_number(v):
    return int(re.sub(r"\D", "", v or "") or 0)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(256 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


class Updater:
    """
    NAS 서버의 /update/latest.json 을 보고 새 버전이면 설치 파일을 받아 둠(sha256 확인).
    적용은 화면 쪽(refresh)이 한가할 때 함: 받은 exe를 --minimized 로 실행하면 그 exe가 지금 프로그램을 끄고
    프로그램 폴더를 교체한 뒤 트레이로 다시 켬 (설치 파일 setup.py가 처리).
    """
    FIRST_CHECK = 120          # 켜지고 2분 뒤 확인 (부팅 직후 트레이에서 조용히 교체되도록)
    CHECK_EVERY = 12 * 3600    # 그 뒤로는 PC를 오래 켜 두는 경우를 위해 12시간마다

    def __init__(self, radio):
        self.radio = radio
        self.ready = None        # (파일 경로, 버전 번호)
        self.ready_since = 0.0
        self.applied = False
        self.enabled = getattr(sys, "frozen", False) and version_number(__version__) > 0
        self.dir = os.path.join(app_root(), "update")

    def start(self):
        if self.enabled:
            threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        time.sleep(self.FIRST_CHECK)
        while True:
            try:
                self.check()
            except Exception as e:
                log("업데이트 확인 실패: %r" % e)
            time.sleep(self.CHECK_EVERY)

    def base_url(self):
        addr = self.radio.cfg.out_host if self.radio.where == "out" else self.radio.cfg.host
        host, port, tls, _ = parse_addr(addr)
        return "%s://%s:%d" % ("https" if tls else "http", host, port)

    def check(self):
        """새 버전이 있으면 받아 두고 True"""
        if not self.radio.connected:
            return False
        import urllib.request
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))   # 회사 PC의 프록시 설정을 타지 않게 (집 주소는 직접)
        base = self.base_url()
        with opener.open(base + "/update/latest.json", timeout=15) as r:
            info = json.loads(r.read().decode("utf-8"))
        pc = info.get("pc") or {}
        num = int(info.get("number") or 0)
        if not pc or num <= version_number(__version__):
            return False
        if self.ready and self.ready[1] == num and os.path.exists(self.ready[0]):
            return True
        os.makedirs(self.dir, exist_ok=True)
        dest = os.path.join(self.dir, os.path.basename(pc["file"]))
        if not (os.path.exists(dest) and sha256_file(dest) == pc["sha256"]):
            tmp = dest + ".part"
            h = hashlib.sha256()
            with opener.open(base + "/update/" + pc["file"], timeout=120) as r, open(tmp, "wb") as f:
                for chunk in iter(lambda: r.read(256 * 1024), b""):
                    f.write(chunk)
                    h.update(chunk)
            if h.hexdigest() != pc["sha256"]:
                os.remove(tmp)
                raise RuntimeError("받은 파일이 손상됨 (sha256 불일치)")
            os.replace(tmp, dest)
        self.ready, self.ready_since = (dest, num), time.monotonic()
        log("새 버전 받아 둠: v%d (%s)" % (num, dest))
        return True

    def apply(self):
        path, num = self.ready
        self.applied = True
        log("업데이트 적용: v%d" % num)
        subprocess.Popen([path, "--minimized", "--updated"], close_fds=True, cwd=os.path.dirname(path))

    def cleanup(self):
        """설치가 끝난 뒤(새 버전에서) 받아 둔 파일 정리"""
        try:
            for fn in os.listdir(self.dir):
                if version_number(fn) <= version_number(__version__) or fn.endswith(".part"):
                    os.remove(os.path.join(self.dir, fn))
        except Exception:
            pass


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


# ---------------------------------------------------------------- 화면 (customtkinter: 둥근 카드, 어두운 남색 + 앰버 포인트)

def run_ui(start_minimized, selftest=False):
    import tkinter as tk
    from tkinter import messagebox
    import customtkinter as ctk
    from PIL import ImageTk

    ctk.set_appearance_mode("dark")

    cfg = Config()
    radio = Radio(cfg)
    updater = Updater(radio)

    BG, CARD, CARD2, TXT, SUB = "#0B1220", "#141C2E", "#1C2740", "#F3F5F9", "#9AA6BC"
    AMBER, AMBER_D, ON_AMBER, GREEN, RED, BLUE, OUTLINE = "#F5B301", "#C98F00", "#1A1200", "#34D399", "#F26D6D", "#8FB3FF", "#2B3752"
    FAM = "Malgun Gothic"

    _fonts = {}

    def font(size, bold=False):   # 같은 글꼴은 한 번만 만듦 (목록을 다시 그릴 때 빨라짐)
        k = (size, bold)
        if k not in _fonts:
            _fonts[k] = ctk.CTkFont(family=FAM, size=size, weight="bold" if bold else "normal")
        return _fonts[k]

    root = ctk.CTk(fg_color=BG)
    root.title(APP_TITLE)

    def scaling(win):
        try:
            return float(ctk.ScalingTracker.get_window_scaling(win)) or 1.0
        except Exception:
            return 1.0

    # 창 크기는 논리 픽셀(배율 적용 전). 125%·150% 배율 노트북에서도 화면 안에 들어가게 높이를 맞춤
    avail = int(root.winfo_screenheight() / scaling(root)) - 90
    root.geometry("480x%d" % max(600, min(900, avail)))
    root.minsize(440, max(600, min(720, avail)))

    ico_path = os.path.join(data_dir(), "icon.ico")
    try:
        # customtkinter는 0.2초 뒤 제목줄 아이콘을 자기 로고로 바꿈 → iconbitmap을 먼저 불러 두면 안 바꿈
        make_icon((37, 99, 235, 255), 64).save(ico_path, format="ICO", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64)])
        root.iconbitmap(ico_path)
    except Exception:
        ico_path = None
    try:
        _win_icon = ImageTk.PhotoImage(make_icon((37, 99, 235, 255), 64))
        root.iconphoto(True, _win_icon)
    except Exception:
        pass

    def star_icon(win):
        """새 창(Toplevel)에도 별 아이콘. customtkinter가 0.2초 뒤 자기 로고로 바꾸므로 그 뒤에 다시 지정"""
        if ico_path:
            win.after(350, lambda: win.winfo_exists() and win.iconbitmap(ico_path))

    # ---- 이벤트 큐 (다른 스레드 → 화면 스레드) ----
    events = queue.Queue()
    radio.on_event = lambda kind, info: events.put((kind, info))

    # ---- 트레이 ----
    tray = {"icon": None}

    def show_window(*_):
        events.put(("show", None))

    def hide_window():
        root.withdraw()

    def quit_app(*_):
        events.put(("ask_quit", None))   # 아이들이 함부로 끄지 못하게 가족 암호를 물은 뒤 종료

    def start_tray():
        try:
            import pystray
        except Exception:
            return
        menu = pystray.Menu(
            pystray.MenuItem("열기", show_window, default=True),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("완전히 종료 (가족 암호)", quit_app),
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
            icon.title = "%s · %s" % (APP_TITLE, "연결됨" if connected else "연결 안 됨")
        except Exception:
            pass

    def flash():
        if os.name == "nt":
            try:
                import ctypes
                ctypes.windll.user32.FlashWindow(int(root.wm_frame(), 16), True)
            except Exception:
                pass

    # ---- 공통 위젯 ----
    def card(parent, pad=16):
        f = ctk.CTkFrame(parent, fg_color=CARD, corner_radius=18, border_width=1, border_color=OUTLINE)
        inner = ctk.CTkFrame(f, fg_color="transparent")
        inner.pack(fill="both", expand=True, padx=pad, pady=pad)
        return f, inner

    def section(parent, text):
        return ctk.CTkLabel(parent, text=text, font=font(12, True), text_color=SUB, anchor="w")

    def pill(parent, text, cmd, filled=True, width=90):
        return ctk.CTkButton(parent, text=text, command=cmd, font=font(13, filled), width=width, height=36, corner_radius=14,
                             fg_color=AMBER if filled else CARD2, hover_color=AMBER_D if filled else "#263654",
                             text_color=ON_AMBER if filled else TXT, border_width=0 if filled else 1, border_color=OUTLINE)

    def small(parent, text, cmd, color=BLUE):
        return ctk.CTkButton(parent, text=text, command=cmd, font=font(12), width=48, height=28, corner_radius=10,
                             fg_color="transparent", hover_color=CARD2, text_color=color)

    # ---- 본문: 위는 고정, 메시지 목록만 남은 높이를 채우며 스크롤 (목록 위에서 휠을 굴려도 다른 게 안 움직임) ----
    body = ctk.CTkFrame(root, fg_color="transparent")
    body.pack(fill="both", expand=True, padx=18, pady=(14, 16))

    # 머리글
    head = ctk.CTkFrame(body, fg_color="transparent")
    head.pack(fill="x")
    star_img = ctk.CTkImage(light_image=make_icon((37, 99, 235, 255), 128), dark_image=make_icon((37, 99, 235, 255), 128), size=(40, 40))
    ctk.CTkLabel(head, image=star_img, text="").pack(side="left", padx=(0, 12))
    titles = ctk.CTkFrame(head, fg_color="transparent")
    titles.pack(side="left", fill="x", expand=True)
    ctk.CTkLabel(titles, text="우리집", font=font(26, True), text_color=TXT, anchor="w").pack(anchor="w")
    status = ctk.CTkLabel(titles, text="", font=font(12), text_color=SUB, anchor="w")
    status.pack(anchor="w")
    ctk.CTkLabel(head, text=__version__, font=font(11), text_color=SUB).pack(side="right", anchor="n", pady=4)

    # 지금 말하는 사람 배너
    incoming = ctk.CTkLabel(body, text="", font=font(15, True), text_color=ON_AMBER, fg_color=AMBER, corner_radius=16, height=46)

    # 가족 선택
    section(body, "대화방  ·  누르면 그 방  ·  우클릭하면 몇 명만 골라 묶기").pack(anchor="w", pady=(20, 6))
    grid = ctk.CTkFrame(body, fg_color="transparent")
    grid.pack(fill="x")
    for c in range(4):
        grid.columnconfigure(c, weight=1, uniform="fam")

    # 말하기 버튼
    talk = ctk.CTkFrame(body, fg_color=AMBER, corner_radius=26, height=112)
    talk.pack(fill="x", pady=(16, 0))
    talk.pack_propagate(False)
    talk_title = ctk.CTkLabel(talk, text="", font=font(22, True), text_color=ON_AMBER)
    talk_title.pack(pady=(26, 0))
    talk_sub = ctk.CTkLabel(talk, text="", font=font(12), text_color=ON_AMBER)
    talk_sub.pack()
    for wdg in (talk, talk_title, talk_sub):
        wdg.bind("<ButtonPress-1>", lambda e: radio.start_talk())
        wdg.bind("<ButtonRelease-1>", lambda e: radio.stop_talk())
        wdg.configure(cursor="hand2")
    notice = ctk.CTkLabel(body, text="", font=font(12), text_color=AMBER, wraplength=400)
    notice.pack(pady=(6, 0))

    # 하단: 단축키 안내 + 설정 (먼저 아래쪽에 붙여 두고, 대화 카드가 남은 공간을 채움)
    foot = ctk.CTkFrame(body, fg_color="transparent")
    foot.pack(side="bottom", fill="x", pady=(8, 0))
    hotkey_label = ctk.CTkLabel(foot, text="", font=font(11), text_color=SUB, anchor="w", wraplength=320, justify="left")
    hotkey_label.pack(side="left", fill="x", expand=True)
    pill(foot, "설정", lambda: open_settings(), filled=False, width=70).pack(side="right")

    # ---- 대화 카드: 말풍선 목록(최신이 아래) + 빠른 답장 + 입력칸 ----
    msg_card, msg_inner = card(body, pad=12)
    msg_card.pack(fill="both", expand=True, pady=(12, 0))
    msg_head = section(msg_inner, "대화")
    msg_head.pack(anchor="w", padx=4)

    entry_row = ctk.CTkFrame(msg_inner, fg_color="transparent")
    entry_row.pack(side="bottom", fill="x", pady=(8, 0))
    entry = ctk.CTkEntry(entry_row, placeholder_text="메시지 입력  (Enter로 보내기)", font=font(15), height=44, corner_radius=16,
                         fg_color=BG, border_color=OUTLINE, text_color=TXT)
    entry.pack(side="left", fill="x", expand=True)

    def send_text(t):
        if radio.send_text(t):
            entry.delete(0, "end")
            chat["stick"] = True
    entry.bind("<Return>", lambda e: send_text(entry.get()))
    send_btn = pill(entry_row, "보내기", lambda: send_text(entry.get()), width=76)
    send_btn.pack(side="left", padx=(8, 0))
    pill(entry_row, "⏰", lambda: open_schedule(), filled=False, width=44).pack(side="left", padx=(6, 0))

    chip_row = ctk.CTkFrame(msg_inner, fg_color="transparent")
    chip_row.pack(side="bottom", fill="x", pady=(8, 0))

    def render_chips():
        for c in chip_row.winfo_children():
            c.destroy()
        for q in radio.quick_replies:
            ctk.CTkButton(chip_row, text=q, command=lambda q=q: send_text(q), font=font(12), height=30, corner_radius=15,
                          fg_color=CARD2, hover_color="#263654", text_color=TXT, border_width=1, border_color=OUTLINE,
                          width=10).pack(side="left", padx=(0, 6), pady=2)
    render_chips()

    msg_wrap = ctk.CTkFrame(msg_inner, fg_color="transparent")
    msg_wrap.pack(fill="both", expand=True, pady=(4, 0))
    msg_list = ctk.CTkScrollableFrame(msg_wrap, fg_color="transparent", scrollbar_button_color=CARD2, scrollbar_button_hover_color=OUTLINE)
    msg_list.pack(fill="both", expand=True)
    chat = {"stick": True, "last_sig": None}   # stick: 맨 아래를 따라감 (위로 올려 보는 중이면 False)

    def chat_canvas():
        return msg_list._parent_canvas

    def at_bottom():
        try:
            return chat_canvas().yview()[1] >= 0.985
        except Exception:
            return True

    def scroll_bottom(*_):
        chat["stick"] = True
        try:
            msg_list.update_idletasks()
            chat_canvas().yview_moveto(1.0)
        except Exception:
            pass
        new_btn.place_forget()

    new_btn = ctk.CTkButton(msg_wrap, text="새 메시지 ↓", command=scroll_bottom, font=font(12, True), height=30, corner_radius=15,
                            fg_color=AMBER, hover_color=AMBER_D, text_color=ON_AMBER, width=110)

    def on_wheel(_e=None):   # 사용자가 위로 올려 보면 자동 따라가기 멈춤, 다시 맨 아래로 내리면 재개
        root.after(50, lambda: chat.__setitem__("stick", at_bottom()) or (new_btn.place_forget() if chat["stick"] else None))
    root.bind_all("<MouseWheel>", on_wheel, add="+")
    root.bind_all("<Button-4>", on_wheel, add="+")
    root.bind_all("<Button-5>", on_wheel, add="+")

    def day_label(ts):
        d = time.localtime(ts)
        today = time.localtime()
        if (d.tm_year, d.tm_yday) == (today.tm_year, today.tm_yday):
            return "오늘"
        yd = time.localtime(time.time() - 86400)
        if (d.tm_year, d.tm_yday) == (yd.tm_year, yd.tm_yday):
            return "어제"
        return time.strftime("%m/%d (%a)", d).replace("Mon", "월").replace("Tue", "화").replace("Wed", "수").replace("Thu", "목").replace("Fri", "금").replace("Sat", "토").replace("Sun", "일")

    def bubble_menu(event, c):
        m = tk.Menu(root, tearoff=0, bg=CARD2, fg=TXT, activebackground=OUTLINE, activeforeground=TXT, font=("Malgun Gothic", 11))
        if c.get("kind") == "text":
            m.add_command(label="복사", command=lambda: (root.clipboard_clear(), root.clipboard_append(c.get("text", ""))))
        if not c.get("mine"):
            m.add_command(label="%s 방으로" % c["from"], command=lambda: radio.select_room(c["from"]))
        m.add_command(label="삭제", command=lambda: radio.delete_clip(c))
        try:
            m.tk_popup(event.x_root, event.y_root)
        finally:
            m.grab_release()

    # ---- 단축키 ----
    hk = {"down": False}

    def room_key(i):
        rs = radio.rooms()
        if 0 <= i < len(rs):
            radio.select_room(rs[i][0])
    for i in range(1, 9):
        root.bind("<Control-Key-%d>" % i, lambda e, i=i: room_key(i - 1))

    def bind_hotkey():
        global keyboard
        key = "+".join(k.strip() for k in cfg.hotkey.strip().lower().split("+") if k.strip())   # 'scroll lock'처럼 띄어쓴 이름 유지
        if not key:
            if keyboard is not None:
                try:
                    keyboard.unhook_all()
                except Exception:
                    pass
            hotkey_label.configure(text="단축키 없음 · 설정에서 정하면 다른 창에서도 말할 수 있어요")
            return
        if keyboard is None:
            try:
                import keyboard as _kb
                keyboard = _kb
            except Exception:
                hotkey_label.configure(text="단축키 기능을 쓸 수 없어요")
                return
        try:
            keyboard.unhook_all()
        except Exception:
            pass

        def down(*_):   # add_hotkey는 인자 없이, on_press_key는 이벤트를 넘겨 부름
            if not hk["down"]:
                hk["down"] = True
                radio.start_talk()

        def up(*_):
            hk["down"] = False
            radio.stop_talk()
        try:
            main_key = key.split("+")[-1]
            if "+" in key:
                keyboard.add_hotkey(key, down)
            else:
                keyboard.on_press_key(main_key, down)
            keyboard.on_release_key(main_key, up)
            hotkey_label.configure(text="[%s] 누르고 있는 동안 말하기 · 다른 창에서도" % key.upper())
        except Exception:
            hotkey_label.configure(text="단축키 [%s]를 등록할 수 없어요" % key)

    # ---- 오른쪽 아래 팝업 ----
    # 닫히는 규칙(0.15초마다 tick_popup이 확인):
    #  · 기본: seconds 뒤 닫힘
    #  · 마우스가 팝업 위에 있으면 잠시 더 두지만, 그래도 20초를 넘기지 않음 (팝업이 마우스 밑에 떠서 안 닫히던 문제)
    #  · 답장칸을 연 뒤에는 1분 동안 아무 입력이 없거나, 본 창을 클릭하면 닫힘
    popup = {"win": None, "created": 0.0, "deadline": 0.0, "replying": False, "last_active": 0.0, "hover_keeps": True}

    def close_popup():
        w, popup["win"] = popup["win"], None
        popup["replying"] = False
        if w:
            try:
                w.destroy()
            except Exception:
                pass

    def popup_hovered():
        pw = popup["win"]
        if not pw:
            return False
        try:
            x, y = root.winfo_pointerxy()
            px, py, w_, h_ = pw.winfo_rootx(), pw.winfo_rooty(), pw.winfo_width(), pw.winfo_height()
            return px <= x < px + w_ and py <= y < py + h_
        except Exception:
            return False

    def tick_popup():
        pw = popup["win"]
        if not pw:
            return
        try:
            alive = pw.winfo_exists()
        except Exception:
            alive = False
        if not alive:
            popup["win"] = None
            return
        now = time.monotonic()
        if popup["replying"]:
            if now - popup["last_active"] > 60:
                close_popup()
            return
        if now - popup["created"] > 20:
            close_popup()
            return
        if popup["hover_keeps"] and popup_hovered():
            popup["deadline"] = max(popup["deadline"], now + 2.0)
            return
        if now >= popup["deadline"]:
            close_popup()

    def popup_touch(*_):
        popup["last_active"] = time.monotonic()

    def show_popup(title, text, reply_to=None, seconds=5, clip=None):
        if popup["win"] and popup["replying"]:
            flash()   # 답장을 쓰는 중이면 팝업을 바꾸지 않음 (새 메시지는 목록에 있음)
            return
        close_popup()
        pw = ctk.CTkToplevel(root, fg_color=(member_style(reply_to)[1] if reply_to else AMBER))
        pw.overrideredirect(True)
        pw.attributes("-topmost", True)
        inner = ctk.CTkFrame(pw, fg_color=CARD, corner_radius=14)
        inner.pack(padx=2, pady=2, fill="both", expand=True)
        head_ = ctk.CTkFrame(inner, fg_color="transparent")
        head_.pack(fill="x", padx=14, pady=(12, 0))
        ctk.CTkLabel(head_, text=title, font=font(13, True), text_color=(member_style(reply_to)[1] if reply_to else TXT), anchor="w").pack(side="left")
        ctk.CTkButton(head_, text="✕", width=26, height=26, fg_color="transparent", hover_color=CARD2, text_color=SUB, command=close_popup).pack(side="right")
        if text:
            ctk.CTkLabel(inner, text=text, font=font(17), text_color=TXT, wraplength=380, justify="left", anchor="w").pack(fill="x", padx=14, pady=(6, 0))
        hint = ctk.CTkLabel(inner, text="클릭하면 바로 답장" if reply_to else "", font=font(10), text_color=SUB, anchor="w")
        hint.pack(fill="x", padx=14, pady=(2, 10))
        reply_box = ctk.CTkFrame(inner, fg_color="transparent")

        def send_reply(t):
            t = t.strip()
            if t and reply_to and radio.send_text(t, to=reply_to):
                # 새 창을 띄우지 않고 같은 팝업 안, 메시지 바로 아래에 '보냈어요'를 표시한 뒤 잠시 후 닫힘
                reply_box.pack_forget()
                ctk.CTkLabel(inner, text="✓ 보냈어요: %s" % t, font=font(12, True), text_color=GREEN, anchor="w").pack(fill="x", padx=14, pady=(2, 10))
                popup["replying"] = False
                popup["hover_keeps"] = False          # 마우스가 위에 있어도 2.5초 뒤 닫힘
                popup["deadline"] = time.monotonic() + 2.5
                place()

        def show_reply(*_):
            popup_touch()
            if clip:
                radio.mark_seen(clip)
            if reply_box.winfo_ismapped() or not reply_to:
                return
            popup["replying"] = True
            hint.pack_forget()
            reply_box.pack(fill="x", padx=12, pady=(6, 12))
            chips = ctk.CTkFrame(reply_box, fg_color="transparent")
            chips.pack(fill="x")
            for q in radio.quick_replies[:6]:
                ctk.CTkButton(chips, text=q, command=lambda q=q: send_reply(q), font=font(11), height=26, corner_radius=13, width=10,
                              fg_color=CARD2, hover_color="#263654", text_color=TXT).pack(side="left", padx=(0, 4), pady=2)
            erow = ctk.CTkFrame(reply_box, fg_color="transparent")
            erow.pack(fill="x", pady=(6, 0))
            ent = ctk.CTkEntry(erow, font=font(12), height=32, corner_radius=12, fg_color=BG, border_color=OUTLINE)
            ent.pack(side="left", fill="x", expand=True)
            ent.bind("<Return>", lambda e: send_reply(ent.get()))
            ent.bind("<Key>", popup_touch)
            pill(erow, "보내기", lambda: send_reply(ent.get()), width=64).pack(side="left", padx=(6, 0))
            ent.focus_set()
            place()

        for wdg in (pw, inner, head_, hint):
            wdg.bind("<Button-1>", show_reply)
        for child in inner.winfo_children():
            try:
                child.bind("<Button-1>", show_reply)
            except Exception:
                pass

        def place():
            # winfo_* 는 실제 픽셀, geometry()의 크기는 배율 적용 전 값이라 나눠서 넣음 (125%·150% 화면에서 잘리지 않게)
            pw.update_idletasks()
            sc = scaling(pw)
            w_, h_ = max(pw.winfo_reqwidth(), int(420 * sc)), pw.winfo_reqheight()
            sw, sh = pw.winfo_screenwidth(), pw.winfo_screenheight()
            pw.geometry("%dx%d+%d+%d" % (round(w_ / sc), round(h_ / sc), sw - w_ - 16, sh - h_ - 60))
        place()
        star_icon(pw)
        now = time.monotonic()
        popup.update(win=pw, created=now, deadline=now + seconds, replying=False, last_active=now, hover_keeps=True)

    def main_window_focused(*_):
        """본 창을 클릭하면 답장 중이던 팝업은 곧 닫힘"""
        if popup["win"] and popup["replying"]:
            popup["replying"] = False
            popup["deadline"] = time.monotonic() + 3
    root.bind("<FocusIn>", main_window_focused)

    # ---- 설정 ----
    def ask_password(parent, on_ok, prompt="가족 암호를 입력하세요"):
        d = ctk.CTkToplevel(parent, fg_color=BG)
        d.title("가족 암호")
        d.geometry("340x160")
        d.transient(parent)
        ctk.CTkLabel(d, text=prompt, font=font(13), text_color=TXT, wraplength=300).pack(pady=(18, 8))
        e = ctk.CTkEntry(d, show="*", font=font(14), width=200, justify="center")
        e.pack()
        e.focus_set()

        def ok(*_):
            if e.get() == cfg.key:
                d.destroy()
                try:
                    parent.grab_set()   # 설정 창의 '모달'을 되살림
                except Exception:
                    pass
                on_ok()
            else:
                messagebox.showerror("설정", "암호가 달라요", parent=d)
        e.bind("<Return>", ok)
        pill(d, "확인", ok, width=100).pack(pady=14)
        star_icon(d)
        d.grab_set()

    wins = {"settings": None}

    def open_settings():
        show_window()
        if wins["settings"] and wins["settings"].winfo_exists():
            wins["settings"].lift()
            return
        w = ctk.CTkToplevel(root, fg_color=BG)
        wins["settings"] = w
        w.title("설정")
        w.geometry("460x560")
        w.resizable(False, True)
        w.transient(root)
        star_icon(w)
        members = radio.members or DEFAULT_MEMBERS
        box = ctk.CTkScrollableFrame(w, fg_color=BG)
        box.pack(fill="both", expand=True, padx=8, pady=8)

        if not cfg.name:   # 처음 실행: 누구 것인지만
            section(box, "이 PC는 누구 것인가요?").pack(anchor="w", padx=12, pady=(14, 6))
            who = ctk.CTkSegmentedButton(box, values=members, font=font(14), height=40, selected_color=AMBER,
                                         selected_hover_color=AMBER_D, unselected_color=CARD2, text_color=TXT)
            who.pack(padx=12, fill="x")

            def start():
                n = who.get()
                if not n:
                    messagebox.showerror("설정", "누구 것인지 골라 주세요", parent=w)
                    return
                cfg.set(name=n)
                try:
                    set_autostart(cfg.autostart)
                except Exception:
                    pass
                radio.client.reconnect()
                w.destroy()
            pill(box, "시작", start, width=140).pack(pady=18)
            w.grab_set()
            return

        ctk.CTkLabel(box, text="이 PC: %s  ·  %s" % (cfg.name, __version__), font=font(16, True), text_color=TXT, anchor="w").pack(anchor="w", padx=12, pady=(10, 0))

        section(box, "말하기 단축키 (비우면 없음)").pack(anchor="w", padx=12, pady=(14, 4))
        row = ctk.CTkFrame(box, fg_color="transparent")
        row.pack(fill="x", padx=12)
        hk_entry = ctk.CTkEntry(row, font=font(13), height=36, corner_radius=12, fg_color=CARD, border_color=OUTLINE)
        hk_entry.insert(0, cfg.hotkey)
        hk_entry.pack(side="left", fill="x", expand=True)

        def capture():
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
        pill(row, "키 눌러서 지정", capture, filled=False, width=110).pack(side="left", padx=(6, 0))
        pill(row, "없음", lambda: hk_entry.delete(0, "end"), filled=False, width=50).pack(side="left", padx=(4, 0))
        ctk.CTkLabel(box, text="예: F9, Scroll Lock, Ctrl+Alt+R. 게임·다른 프로그램과 겹치지 않는 키가 좋아요.", font=font(10), text_color=SUB,
                     anchor="w", wraplength=420, justify="left").pack(anchor="w", padx=12)

        section(box, "빠른 답장 문구 (쉼표로 구분)").pack(anchor="w", padx=12, pady=(12, 4))
        q_entry = ctk.CTkEntry(box, font=font(13), height=36, corner_radius=12, fg_color=CARD, border_color=OUTLINE)
        q_entry.insert(0, cfg.quick)
        q_entry.pack(fill="x", padx=12)

        section(box, "대화방 묶음 (이름=가족,가족 · 묶음은 ;로 구분)").pack(anchor="w", padx=12, pady=(12, 4))
        g_entry = ctk.CTkEntry(box, font=font(13), height=36, corner_radius=12, fg_color=CARD, border_color=OUTLINE)
        g_entry.insert(0, cfg.groups)
        g_entry.pack(fill="x", padx=12)
        ctk.CTkLabel(box, text="예: 아이들=첫째,둘째,셋째;부모님=엄마,아빠;큰애들=첫째,둘째", font=font(10), text_color=SUB, anchor="w", wraplength=420, justify="left").pack(anchor="w", padx=12)

        auto_var = tk.BooleanVar(value=cfg.autostart)
        ctk.CTkSwitch(box, text="Windows 시작 시 자동 실행 (트레이에 조용히 켜짐)", variable=auto_var, font=font(13),
                      text_color=TXT, progress_color=AMBER).pack(anchor="w", padx=12, pady=(14, 0))

        def shortcut():
            ok, msg = make_desktop_shortcut()
            (messagebox.showinfo if ok else messagebox.showwarning)(
                "바로가기", msg + ("\n\n작업 표시줄에 두고 싶으면 그 바로가기를 작업 표시줄로 끌어다 놓으세요." if ok else ""), parent=w)
        row2 = ctk.CTkFrame(box, fg_color="transparent")
        row2.pack(fill="x", padx=12, pady=(10, 0))
        pill(row2, "바탕화면에 바로가기 만들기", shortcut, filled=False, width=200).pack(side="left")

        def check_update_now():
            if not radio.connected:
                messagebox.showinfo("업데이트", "서버에 연결된 뒤에 확인할 수 있어요", parent=w)
                return
            upd_btn.configure(text="확인 중…", state="disabled")

            def worker():
                try:
                    found = updater.check()
                    events.put(("update_checked", (found, None)))
                except Exception as e:
                    events.put(("update_checked", (False, str(e))))
            threading.Thread(target=worker, daemon=True).start()
        upd_btn = pill(row2, "지금 업데이트 확인", check_update_now, filled=False, width=150)
        upd_btn.pack(side="left", padx=(8, 0))
        wins["upd_btn"] = upd_btn

        def save():
            hk_ = hk_entry.get().strip()
            if hk_.startswith("키를"):
                hk_ = cfg.hotkey
            cfg.set(hotkey=hk_, quick=q_entry.get().strip() or Config.DEFAULTS["quick"], autostart=auto_var.get(),
                    groups=g_entry.get().strip() or DEFAULT_GROUPS)
            try:
                set_autostart(cfg.autostart)
            except Exception as e:
                messagebox.showwarning("설정", "자동 실행 등록 실패: %s" % e, parent=w)
            bind_hotkey()
            radio.quick_replies = [q.strip() for q in cfg.quick.split(",") if q.strip()]
            render_chips()
            w.destroy()
        pill(box, "저장", save, width=120).pack(pady=(16, 6))

        admin = ctk.CTkFrame(box, fg_color=CARD, corner_radius=14)

        def open_admin():
            admin_btn.pack_forget()
            admin.pack(fill="x", padx=12, pady=(4, 12))
            section(admin, "이 PC는 누구 것인가요? (바꾸면 바로 적용)").pack(anchor="w", padx=12, pady=(12, 4))
            a_who = ctk.CTkSegmentedButton(admin, values=members, font=font(13), selected_color=AMBER, selected_hover_color=AMBER_D,
                                           unselected_color=CARD2, text_color=TXT)
            a_who.set(cfg.name)
            a_who.pack(fill="x", padx=12)
            a_fields = {}
            for k, label in [("host", "집 주소"), ("out_host", "밖 주소 (Tailscale)"), ("key", "가족 암호")]:
                section(admin, label).pack(anchor="w", padx=12, pady=(10, 2))
                e = ctk.CTkEntry(admin, font=font(13), height=34, corner_radius=10, fg_color=BG, border_color=OUTLINE)
                e.insert(0, str(getattr(cfg, k)))
                e.pack(fill="x", padx=12)
                a_fields[k] = e

            def save_admin():
                v = {k: e.get().strip() for k, e in a_fields.items()}
                v["name"] = a_who.get()
                if not (v["name"] and v["host"] and v["key"]):
                    messagebox.showerror("설정", "사용자·집 주소·암호는 비울 수 없어요", parent=w)
                    return
                cfg.set(**v)
                radio.client.reconnect()
                w.destroy()
            pill(admin, "변경 저장하고 다시 연결", save_admin, width=180).pack(pady=12)
        admin_btn = ctk.CTkButton(box, text="사용자·주소 변경 (가족 암호 필요)", command=lambda: ask_password(w, open_admin), font=font(11),
                                  fg_color="transparent", hover_color=CARD, text_color=SUB, height=28)
        admin_btn.pack(pady=(0, 10))
        w.grab_set()

    # ---- 예약 메시지 ----
    sched = {"w": None, "list": None}

    def open_schedule():
        if sched["w"] and sched["w"].winfo_exists():
            sched["w"].lift()
            return
        w = ctk.CTkToplevel(root, fg_color=BG)
        w.title("예약 메시지")
        w.geometry("460x600")
        w.transient(root)
        star_icon(w)
        sched["w"] = w
        box = ctk.CTkFrame(w, fg_color="transparent")
        box.pack(fill="both", expand=True, padx=16, pady=14)
        ctk.CTkLabel(box, text="⏰ 예약 메시지", font=font(18, True), text_color=TXT, anchor="w").pack(anchor="w")
        ctk.CTkLabel(box, text="받는 사람: %s  (창에서 고른 상대)" % radio.target_label(), font=font(12), text_color=SUB, anchor="w").pack(anchor="w", pady=(2, 10))
        t_entry = ctk.CTkEntry(box, placeholder_text="내용", font=font(14), height=40, corner_radius=14, fg_color=CARD, border_color=OUTLINE)
        if entry.get().strip():
            t_entry.insert(0, entry.get().strip())
        t_entry.pack(fill="x")
        row = ctk.CTkFrame(box, fg_color="transparent")
        row.pack(fill="x", pady=(10, 0))
        now = time.localtime(time.time() + 600)
        hh = ctk.CTkOptionMenu(row, values=["%02d" % i for i in range(24)], width=70, font=font(13), fg_color=CARD2, button_color=OUTLINE, dropdown_font=font(12))
        hh.set("%02d" % now.tm_hour)
        hh.pack(side="left")
        ctk.CTkLabel(row, text=":", font=font(16), text_color=TXT).pack(side="left", padx=4)
        mm = ctk.CTkOptionMenu(row, values=["%02d" % i for i in range(0, 60, 5)], width=70, font=font(13), fg_color=CARD2, button_color=OUTLINE, dropdown_font=font(12))
        mm.set("%02d" % ((now.tm_min // 5) * 5))
        mm.pack(side="left")
        day = ctk.CTkSegmentedButton(row, values=["오늘", "내일"], font=font(12), selected_color=AMBER, selected_hover_color=AMBER_D,
                                     unselected_color=CARD2, text_color=TXT, width=120)
        day.set("오늘")
        day.pack(side="left", padx=(12, 0))
        rep_labels = [("한 번", "once"), ("매일", "daily"), ("평일", "weekdays"), ("주말", "weekends")]
        rep = ctk.CTkSegmentedButton(box, values=[l for l, _ in rep_labels], font=font(12), selected_color=AMBER, selected_hover_color=AMBER_D,
                                     unselected_color=CARD2, text_color=TXT)
        rep.set("한 번")
        rep.pack(fill="x", pady=(10, 0))

        def add():
            text = t_entry.get().strip()
            if not text:
                messagebox.showerror("예약", "내용을 입력하세요", parent=w)
                return
            h_, m_ = int(hh.get()), int(mm.get())
            lt = time.localtime()
            base = time.mktime((lt.tm_year, lt.tm_mon, lt.tm_mday, h_, m_, 0, 0, 0, -1))
            if day.get() == "내일":
                base += 86400
            repeat = dict(rep_labels)[rep.get()]
            if repeat == "once" and base < time.time() - 60:
                messagebox.showerror("예약", "이미 지난 시간이에요. '내일'을 고르세요", parent=w)
                return
            if not radio.schedule_add(text, base, repeat):
                messagebox.showwarning("예약", "지금 연결이 없어요. 연결된 뒤 다시 시도하세요", parent=w)
                return
            t_entry.delete(0, "end")
        pill(box, "예약 추가", add, width=140).pack(pady=(12, 4))
        sched["notice"] = ctk.CTkLabel(box, text="", font=font(12), text_color=RED, anchor="w")
        sched["notice"].pack(anchor="w")
        section(box, "내 예약 목록").pack(anchor="w")
        lst = ctk.CTkScrollableFrame(box, fg_color=CARD, corner_radius=14, height=220)
        lst.pack(fill="both", expand=True, pady=(4, 0))
        sched["list"] = lst
        render_schedules()
        radio.client.send_json({"type": "schedule_list"})

    def render_schedules():
        lst = sched.get("list")
        if not lst or not lst.winfo_exists():
            return
        try:   # 예약 실패 사유는 예약 창 안에 보여 줌
            sched["notice"].configure(text=radio.notice if radio.notice.startswith("예약 실패") else "")
        except Exception:
            pass
        for c in lst.winfo_children():
            c.destroy()
        items = radio.schedules
        if not items:
            ctk.CTkLabel(lst, text="예약이 없어요", font=font(12), text_color=SUB).pack(anchor="w", padx=8, pady=8)
            return
        rep_label = {"once": "한 번", "daily": "매일", "weekdays": "평일", "weekends": "주말"}
        for it in items:
            r = ctk.CTkFrame(lst, fg_color="transparent")
            r.pack(fill="x", padx=6, pady=3)
            to = it.get("to")
            to_s = "모두" if to == "all" else ", ".join(to if isinstance(to, list) else [str(to)])
            when = time.strftime("%m/%d %H:%M", time.localtime(it["at"]))
            ctk.CTkLabel(r, text="%s · %s · %s\n%s" % (when, rep_label.get(it["repeat"], ""), to_s, it["text"]), font=font(12),
                         text_color=TXT, justify="left", anchor="w").pack(side="left", padx=4)
            small(r, "삭제", lambda sid=it["sid"]: radio.schedule_delete(sid), color=RED).pack(side="right")

    # ---- 이벤트 처리 ----
    def pump_events():
        try:
            while True:
                req = show_requests.get_nowait()
                events.put(("quit", None) if req == "quit" else ("show", None))
        except queue.Empty:
            pass
        while True:
            try:
                kind, info = events.get_nowait()
            except queue.Empty:
                return
            try:
                handle_event(kind, info)
            except Exception as e:
                log("화면 이벤트 오류 %s: %s" % (kind, e))

    def handle_event(kind, info):
        if kind == "incoming":
            flash()
            who = info.get("from")
            show_popup("%s %s님이 말해요" % (member_style(who)[0], who), "전체 방송" if info.get("mode") == "all" else "나에게", reply_to=who, seconds=8)
        elif kind == "clip":
            c = info["clip"]
            flash()
            show_popup("🔊 " + radio.clip_title(c), "%d초 · %s" % (max(1, int(c["seconds"])), "방금 재생했어요" if info["auto"] else "창을 열어 [듣기]를 누르세요"),
                       reply_to=c["from"], clip=c)
            if not info["auto"]:
                events.put(("show", None))
        elif kind == "text":
            flash()
            show_popup("%s %s" % (member_style(info["from"])[0], radio.clip_title(info)), info.get("text", ""), reply_to=info["from"], clip=info)
        elif kind == "state":
            set_tray(radio.connected, radio.where)
        elif kind == "schedules":
            render_schedules()
        elif kind == "update_checked":
            found, err = info
            try:
                b = wins.get("upd_btn")
                if b and b.winfo_exists():
                    b.configure(text="지금 업데이트 확인", state="normal")
            except Exception:
                pass
            parent = wins["settings"] if wins.get("settings") and wins["settings"].winfo_exists() else root
            if err:
                messagebox.showwarning("업데이트", "확인하지 못했어요: %s" % err, parent=parent)
            elif found and updater.ready:
                if updater.enabled and messagebox.askyesno("업데이트", "새 버전 v%d 을 받았어요.\n지금 바로 설치할까요? (프로그램이 잠깐 꺼졌다 트레이로 다시 켜져요)" % updater.ready[1], parent=parent):
                    updater.apply()
                elif not updater.enabled:
                    messagebox.showinfo("업데이트", "새 버전 v%d 을 받아 두었어요" % updater.ready[1], parent=parent)
            else:
                messagebox.showinfo("업데이트", "지금이 최신 버전이에요 (%s)" % __version__, parent=parent)
        elif kind == "hotkey_captured":
            ent, combo = info
            try:
                ent.delete(0, "end")
                ent.insert(0, combo)
            except Exception:
                pass
        elif kind == "show":
            root.deiconify()
            root.lift()
            root.focus_force()
        elif kind == "ask_quit":
            root.deiconify()
            root.lift()
            root.focus_force()
            ask_password(root, lambda: events.put(("quit", None)), prompt="완전히 종료하려면 가족 암호를 입력하세요")
        elif kind == "quit":
            busy["quitting"] = True   # 창을 닫는 중에는 화면 갱신을 하지 않음
            radio.stop_talk()
            if tray["icon"]:
                try:
                    tray["icon"].stop()
                except Exception:
                    pass
            root.destroy()

    # ---- 화면 갱신 ----
    cache = {"buttons": None, "clips": None, "tray_tip": False}

    def room_button(parent, room, sel, unread):
        key, label, members, icon, color = room
        if members is None:
            sub = "%d명 접속" % len(radio.online)
        elif len(members) == 1:
            on = members[0] in radio.online
            sub = "●" if on else "꺼짐"   # 연결된 사람은 점만, 안 된 사람만 "꺼짐" (집/밖 글자는 안 씀)
        else:
            sub = "%d명" % len(members)
        if unread:
            sub += " · 새 글 %d" % unread
        b = ctk.CTkButton(parent, text="%s %s\n%s" % (icon, label, sub), command=lambda k=key: radio.select_room(k),
                          font=font(13, sel), height=56, corner_radius=16,
                          fg_color=color if sel else CARD, hover_color=color if sel else CARD2,
                          text_color="#101418" if sel else (color if (members is None or len(members) > 1 or members[0] in radio.online) else SUB),
                          border_width=0 if sel else 1, border_color=OUTLINE)
        if members is not None and len(members) == 1:   # 우클릭: 이 사람을 지금 고른 상대에 추가/제외 (몇 명만 묶기)
            b.bind("<Button-3>", lambda e, m=members[0]: radio.toggle_target(m))
        return b

    busy = {"refresh": False}

    def refresh():
        """0.15초마다 화면 갱신. 오류가 나도 멈추지 않고, 겹쳐 불리지 않게 함"""
        if busy["refresh"] or busy.get("quitting"):
            return
        busy["refresh"] = True
        try:
            _refresh_body()
        except Exception as e:
            log("화면 갱신 오류: %r" % e)
        finally:
            busy["refresh"] = False
            try:
                if root.winfo_exists():
                    root.after(150, refresh)
            except Exception:
                pass

    def _refresh_body():
        pump_events()
        if busy.get("quitting"):
            return
        tick_popup()
        if updater.enabled and updater.ready and not updater.applied:
            # 새 버전을 받아 둔 상태: 아무도 말하지 않고 답장 중도 아닐 때, 창이 숨겨져 있으면 바로 / 열려 있으면 30분 뒤에 교체
            idle = not (radio.talking or radio.requesting or radio.incoming_from or radio.playing or popup["replying"])
            hidden = root.state() in ("withdrawn", "iconic")
            if idle and (hidden or time.monotonic() - updater.ready_since > 1800):
                updater.apply()
        with radio.lock:
            me = cfg.name
            others = [m for m in radio.members if m != me]
            if radio.target != "all":
                kept = [t for t in radio.target_list if t in others]
                if len(kept) != len(radio.target_list):
                    cfg.set(target=",".join(kept) if kept else "all")
            rooms = radio.rooms()
            cur = radio.current_room()
            unread = {r[0]: radio.unread_count(r) for r in rooms}
            sig = (tuple(r[0] for r in rooms), tuple(radio.online), radio.target, tuple(sorted(radio.member_where.items())), tuple(sorted(unread.items())))
            if sig != cache["buttons"]:
                cache["buttons"] = sig
                for c in grid.winfo_children():
                    c.destroy()
                for i, room in enumerate(rooms):
                    room_button(grid, room, room[0] == cur[0], unread[room[0]]).grid(row=i // 4, column=i % 4, sticky="nsew", padx=3, pady=3)

            if radio.connected:
                status.configure(text="●  %s · 연결됨 · 가족 %d명" % (me, len(radio.online)), text_color=GREEN)
            else:
                status.configure(text="●  " + radio.status, text_color=SUB)

            if radio.incoming_from:
                incoming.configure(text=("🔊  %s님 전체 방송 중" if radio.incoming_mode == "all" else "🔊  %s님이 나에게 말하는 중") % radio.incoming_from)
                if not incoming.winfo_ismapped():
                    incoming.pack(fill="x", pady=(14, 0), after=head)
            elif incoming.winfo_ismapped():
                incoming.pack_forget()

            room_color, room_icon, room_name = cur[4], cur[3], cur[1]
            if radio.talking:
                talk.configure(fg_color=RED); talk_title.configure(text="%s에게 말하는 중…" % room_name, text_color="white"); talk_sub.configure(text="손을 떼면 끝", text_color="white")
            elif radio.requesting:
                talk.configure(fg_color=AMBER_D); talk_title.configure(text="잠깐만요…", text_color=ON_AMBER); talk_sub.configure(text="", text_color=ON_AMBER)
            elif not radio.connected:
                talk.configure(fg_color=CARD2); talk_title.configure(text="연결 대기 중", text_color=SUB); talk_sub.configure(text=radio.status, text_color=SUB)
            else:
                talk.configure(fg_color=room_color); talk_title.configure(text="%s %s에게 말하기" % (room_icon, room_name), text_color="#101418"); talk_sub.configure(text="누르고 있는 동안 말해요", text_color="#101418")
            notice.configure(text=radio.notice)
            msg_head.configure(text="%s %s" % (room_icon, "모두의 대화" if cur[0] == "all" else room_name + "와의 대화"), text_color=room_color)
            if chat.get("entry_room") != cur[0]:   # 입력칸 테두리·보내기 버튼·안내 글을 고른 방 색으로 — 누구에게 보내는지 입력줄만 봐도 보이게
                chat["entry_room"] = cur[0]
                entry.configure(border_color=room_color, placeholder_text="%s에게 메시지  (Enter로 보내기)" % ("모두" if cur[0] == "all" else room_name))
                send_btn.configure(fg_color=room_color, hover_color=blend(room_color, "#000000", 0.85), text_color="#101418")

            window_visible = root.state() in ("normal", "zoomed")   # 최대화된 창도 보이는 것
            shown = list(reversed(radio.room_clips(cur)[:40]))   # 오래된 것 → 최신 (최신이 아래)
            csig = (cur[0],) + tuple((c["id"], c["ts"], radio.playing is c, radio.receipt_text(c) if c.get("mine") else "",
                                      window_visible and c.get("kind") == "text" and not c.get("mine") and (c.get("mid") or 0) not in radio.seen_sent)
                                     for c in shown)
            if csig != cache["clips"]:
                prev_last = cache["clips"][-1] if (cache["clips"] and len(cache["clips"]) > 1) else None
                newest_changed = bool(shown) and prev_last != csig[-1]
                was_bottom = chat["stick"] or at_bottom()
                cache["clips"] = csig
                for c in msg_list.winfo_children():
                    c.destroy()
                if not shown:
                    ctk.CTkLabel(msg_list, text="아직 대화가 없어요. 아래에 적어 보내거나, 큰 버튼을 누르고 말해 보세요.", font=font(12), text_color=SUB, wraplength=360).pack(pady=28)
                prev_day, prev_from, prev_ts = None, None, 0
                block, block_key = None, None   # 같은 상대와 주고받은 연속 글은 그 사람 색 테두리 상자로 묶음 (모두에게 보낸 글은 상자 없음)
                for c in shown:
                    conv = radio.conv_of(c)
                    key = conv[0] if conv else None
                    if key != block_key:
                        block_key = key
                        if conv:
                            block = ctk.CTkFrame(msg_list, fg_color=blend(conv[2], BG, 0.10), border_width=2, border_color=conv[2], corner_radius=14)
                            block.pack(fill="x", pady=(8, 4), padx=2)
                            ctk.CTkLabel(block, text=conv[1], font=font(11, True), text_color=conv[2], anchor="w").pack(anchor="w", padx=10, pady=(6, 0))
                        else:
                            block = None
                        prev_from = None
                    holder = block if block is not None else msg_list
                    day = day_label(c["ts"])
                    if day != prev_day:
                        ctk.CTkLabel(holder, text=day, font=font(10), text_color=SUB, fg_color=BG, corner_radius=10, height=20).pack(pady=(10, 4))
                        prev_day, prev_from = day, None
                    mine = bool(c.get("mine"))
                    sender = "나" if mine else c.get("from")
                    same = (sender == prev_from) and (c["ts"] - prev_ts < 180)
                    prev_from, prev_ts = sender, c["ts"]
                    who_color = member_style(c.get("from"))[1]
                    line = ctk.CTkFrame(holder, fg_color="transparent")
                    line.pack(fill="x", pady=(1 if same else 6, 1) if block is None else (1 if same else 4, 3), padx=0 if block is None else 6)
                    col = ctk.CTkFrame(line, fg_color="transparent")
                    col.pack(side="right" if mine else "left", padx=(60, 4) if mine else (4, 60))
                    if not mine and not same:
                        ctk.CTkLabel(col, text="%s %s" % (member_style(c["from"])[0], c["from"]), font=font(12, True), text_color=who_color, anchor="w").pack(anchor="w", padx=4)
                    row = ctk.CTkFrame(col, fg_color=("#2A3F66" if mine else CARD2), corner_radius=16, border_width=0)
                    row.pack(anchor="e" if mine else "w")
                    if c.get("kind") == "text":
                        ctk.CTkLabel(row, text=c.get("text", ""), font=font(17), text_color=TXT, anchor="w", wraplength=300, justify="left").pack(padx=12, pady=(8, 8))
                    else:
                        playing = radio.playing is c
                        secs = max(1, int(c.get("seconds") or 0))
                        if c.get("kind") == "clip":
                            ctk.CTkButton(row, text=("■  재생 중 · %d초" % secs) if playing else ("▶  음성 %d초" % secs), font=font(15, True),
                                          fg_color="transparent", hover_color=OUTLINE, text_color=TXT, height=40, corner_radius=12,
                                          command=lambda c=c, p=playing: radio.stop_clip() if p else radio.play_clip(c)).pack(padx=6, pady=4)
                        else:
                            ctk.CTkLabel(row, text="🎙  음성 %d초 보냄" % secs, font=font(14), text_color=TXT).pack(padx=12, pady=(8, 8))
                    for wdg in [row] + row.winfo_children():
                        try:
                            wdg.bind("<Button-3>", lambda e, c=c: bubble_menu(e, c))
                        except Exception:
                            pass
                    meta = time.strftime("%H:%M", time.localtime(c["ts"]))
                    if mine:
                        rt = radio.receipt_text(c)
                        if rt:
                            meta = rt + "  ·  " + meta
                    elif c.get("kind") == "text" and window_visible:
                        radio.mark_seen(c)
                    ctk.CTkLabel(col, text=meta, font=font(10), text_color=(AMBER if (mine and radio.receipt_text(c)) else SUB)).pack(anchor="e" if mine else "w", padx=6)
                if was_bottom or not shown:
                    root.after(30, scroll_bottom)
                elif newest_changed:
                    new_btn.place(relx=0.5, rely=1.0, anchor="s", y=-6)

    def on_close():
        if tray["icon"]:
            hide_window()
            if not cache["tray_tip"]:
                cache["tray_tip"] = True
                toast(APP_TITLE, "오른쪽 아래 시계 옆 아이콘에서 계속 듣고 있어요. 완전히 끄려면 아이콘 → 완전히 종료 (가족 암호)")
        else:
            quit_app()
    root.protocol("WM_DELETE_WINDOW", on_close)

    if running_from_install():   # 다운로드 폴더에서 그냥 돌고 있을 때는 그 경로를 자동 시작에 등록하지 않음
        try:
            set_autostart(cfg.autostart)
        except Exception:
            pass
        if getattr(sys, "frozen", False):   # 고정된 작업 표시줄·시작 메뉴 바로가기가 옛 파일을 가리키면 설치본으로 고침
            threading.Thread(target=repair_shortcuts, daemon=True).start()
    start_tray()
    bind_hotkey()
    radio.start()
    updater.start()
    if "--updated" in sys.argv:   # 방금 자동 업데이트로 교체되어 켜짐
        updater.cleanup()
        toast(APP_TITLE, "새 버전 %s 으로 업데이트됐어요" % __version__)
    refresh()
    if start_minimized and cfg.configured and tray["icon"]:
        root.withdraw()
    elif not cfg.configured:
        root.after(300, open_settings)
    if selftest:
        _selftest_ctx.update(root=root, ctk=ctk, cfg=cfg, radio=radio, events=events, popup=popup, sched=sched, entry=entry,
                             grid=grid, msg_list=msg_list, incoming=incoming, tray=tray, messagebox=messagebox,
                             open_settings=open_settings, open_schedule=open_schedule, handle_event=handle_event, on_close=on_close,
                             quit_app=quit_app, updater=updater)
        root.after(800, _selftest_start, _selftest_ctx)
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
            _, _mutex_handle, already = _create_mutex()
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
                elif data.startswith(b"quit"):
                    show_requests.put("quit")
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
        # 한글 이름이 어느 언어 Windows에서도 깨지지 않도록 스크립트 파일(UTF-8 BOM)로 만들어 실행
        ps = ("$ErrorActionPreference='Stop'\n"
              "$desk=[Environment]::GetFolderPath('DesktopDirectory')\n"
              "if(-not (Test-Path $desk)){ New-Item -ItemType Directory -Force $desk | Out-Null }\n"
              # WScript.Shell은 영문판 Windows에서 한글 파일 이름을 못 쓰므로 영문 이름으로 만든 뒤 바꿈
              "$tmp=Join-Path $desk 'WoorizipRadio.lnk'\n"
              "$s=(New-Object -ComObject WScript.Shell).CreateShortcut($tmp)\n"
              "$s.TargetPath='%s'\n$s.WorkingDirectory='%s'\n$s.IconLocation='%s,0'\n$s.Description='우리집'\n$s.Save()\n"
              "Move-Item -Force -LiteralPath $tmp -Destination (Join-Path $desk '우리집.lnk')\n"
              % (target.replace("'", "''"), os.path.dirname(target).replace("'", "''"), target.replace("'", "''")))
        script = os.path.join(data_dir(), "shortcut.ps1")
        with open(script, "w", encoding="utf-8-sig") as f:
            f.write(ps)
        r = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script],
                           creationflags=0x08000000, timeout=30, capture_output=True)
        if r.returncode != 0:
            log("바로가기 만들기 실패: %s" % (r.stderr or r.stdout).decode("utf-8", "replace").strip()[:300])
            return False, "바로가기를 만들지 못했어요"
        return True, "바탕화면에 '우리집' 바로가기를 만들었어요"
    except Exception as e:
        log("바로가기 만들기 실패: %s" % e)
        return False, "바로가기를 만들지 못했어요"


MUTEX_NAME = "Local\\WoorizipRadio_single_instance"


def _create_mutex():
    """(핸들, 이미 있었는지). GetLastError는 ctypes의 use_last_error로 안전하게 읽음"""
    import ctypes
    from ctypes import wintypes
    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    k32.CreateMutexW.restype = wintypes.HANDLE
    k32.CreateMutexW.argtypes = [ctypes.c_void_p, wintypes.BOOL, ctypes.c_wchar_p]
    k32.CloseHandle.argtypes = [wintypes.HANDLE]
    h = k32.CreateMutexW(None, False, MUTEX_NAME)
    return k32, h, ctypes.get_last_error() == 183   # ERROR_ALREADY_EXISTS


def repair_shortcuts(exe=None):
    """
    작업 표시줄·시작 메뉴·바탕화면에 있는 우리집 바로가기가 옛 파일(지운 다운로드 파일 등)을 가리키면 설치본으로 고침.
    → '작업 표시줄에 고정'한 아이콘이 빈 문서 모양으로 나오던 문제. 시작 메뉴 항목(우리집)도 없으면 만듦.
    돌려주는 값: (성공 여부, 고친 개수 또는 메시지)
    """
    if os.name != "nt":
        return False, "Windows에서만 돼요"
    exe = exe or (sys.executable if getattr(sys, "frozen", False) else os.path.abspath(__file__))
    ps = (
        "$ErrorActionPreference='Continue'\n"
        "$exe='%s'\n"
        "$sh=New-Object -ComObject WScript.Shell\n"
        "$dirs=@((Join-Path $env:APPDATA 'Microsoft\\Internet Explorer\\Quick Launch\\User Pinned\\TaskBar'),"
        "(Join-Path $env:APPDATA 'Microsoft\\Internet Explorer\\Quick Launch\\User Pinned\\StartMenu'),"
        "(Join-Path $env:APPDATA 'Microsoft\\Windows\\Start Menu\\Programs'),[Environment]::GetFolderPath('DesktopDirectory'))\n"
        "$fixed=0\n"
        "foreach($d in $dirs){ if(-not (Test-Path -LiteralPath $d)){ continue }\n"
        "  Get-ChildItem -LiteralPath $d -Filter *.lnk -ErrorAction SilentlyContinue | ForEach-Object {\n"
        "    try { $s=$sh.CreateShortcut($_.FullName); $t=[string]$s.TargetPath\n"
        "      if($t -and ([IO.Path]::GetFileNameWithoutExtension($t) -like 'WoorizipRadio*')){\n"
        "        if(($t -ne $exe) -or (-not ([string]$s.IconLocation).StartsWith($exe))){\n"
        "          $s.TargetPath=$exe; $s.WorkingDirectory=(Split-Path $exe); $s.IconLocation=\"$exe,0\"; $s.Save(); $fixed++ } } } catch {} } }\n"
        "$sm=Join-Path $env:APPDATA 'Microsoft\\Windows\\Start Menu\\Programs'\n"
        "$final=Join-Path $sm '우리집.lnk'\n"
        "if(-not (Test-Path -LiteralPath $sm)){ New-Item -ItemType Directory -Force $sm | Out-Null }\n"
        "if(-not (Test-Path -LiteralPath $final)){ $tmp=Join-Path $sm 'WoorizipRadio.lnk'\n"
        "  $s=$sh.CreateShortcut($tmp); $s.TargetPath=$exe; $s.WorkingDirectory=(Split-Path $exe); $s.IconLocation=\"$exe,0\"; $s.Description='우리집'; $s.Save()\n"
        "  Move-Item -Force -LiteralPath $tmp -Destination $final }\n"
        "if($fixed -gt 0){ try { Start-Process ie4uinit.exe -ArgumentList '-show' -WindowStyle Hidden } catch {} }\n"
        "\"fixed=$fixed\"\n"
    ) % exe.replace("'", "''")
    try:
        script = os.path.join(data_dir(), "repair_shortcuts.ps1")
        with open(script, "w", encoding="utf-8-sig") as f:
            f.write(ps)
        r = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script],
                           creationflags=0x08000000, timeout=60, capture_output=True)
        out = (r.stdout or b"").decode("utf-8", "replace").strip()
        if r.returncode != 0:
            log("바로가기 점검 실패: %s" % (r.stderr or b"").decode("utf-8", "replace").strip()[:300])
            return False, "바로가기를 점검하지 못했어요"
        log("바로가기 점검: %s" % out[-100:])
        return True, out
    except Exception as e:
        log("바로가기 점검 실패: %s" % e)
        return False, str(e)


def _instance_running():
    """다른 인스턴스가 떠 있는지 (Windows 뮤텍스)"""
    try:
        k32, h, exists = _create_mutex()
        if h:
            k32.CloseHandle(h)
        return exists
    except Exception:
        return False


def app_root():
    return os.path.join(os.environ.get("LOCALAPPDATA") or data_dir(), "WoorizipRadio")


def installed_exe_path():
    """설치 파일(setup)이 프로그램 폴더를 풀어 넣는 자리 (폴더 형태라 켜는 속도가 빠름)"""
    return os.path.join(app_root(), "app", "WoorizipRadio.exe")


def running_from_install():
    """자동 시작에 등록해도 되는 위치(설치본 또는 개발용 스크립트)에서 실행 중인지"""
    if not getattr(sys, "frozen", False):
        return True
    return os.path.normcase(os.path.abspath(sys.executable)) == os.path.normcase(installed_exe_path())


# ---------------------------------------------------------------- 자가 점검 (GitHub 자동 빌드의 Windows 컴퓨터에서 실제로 띄워 봄)
# python radio_pc.py --selftest : 같은 컴퓨터의 서버(127.0.0.1)에 붙어 설정창·문자·확인 표시·팝업·예약창까지 눌러 보고 결과를 출력

_selftest_ctx = {}


class _Helper:
    """자가 점검용 가상 가족: 같은 서버에 다른 이름으로 붙어 문자를 받고 보냄"""

    def __init__(self, cfg, name):
        class C:
            pass
        c = C()
        c.host, c.out_host, c.name, c.key, c.configured = cfg.host, "", name, cfg.key, True
        self.name, self.msgs, self.connected = name, [], False
        self.client = WSClient(c, self._on_text, lambda b: None, self._on_state)
        self.client.start()

    def _on_state(self, connected, where, detail):
        self.connected = connected

    def _on_text(self, msg):
        self.msgs.append(msg)
        if msg.get("type") == "text":
            self.client.send_json({"type": "clip_ack", "id": msg.get("id", 0)})

    def find(self, type_, **match):
        return [m for m in self.msgs if m.get("type") == type_ and all(m.get(k) == v for k, v in match.items())]

    def send(self, obj):
        return self.client.send_json(obj)


def _selftest_start(ctx):
    import traceback
    root, ctk, radio, cfg = ctx["root"], ctx["ctk"], ctx["radio"], ctx["cfg"]
    results, dialogs = [], []
    mb = ctx["messagebox"]
    for fn in ("showerror", "showwarning", "showinfo"):
        setattr(mb, fn, lambda *a, _f=fn, **k: dialogs.append((_f,) + tuple(a)) or True)   # 대화상자는 가로채서 기록만
    helper = _Helper(cfg, "엄마")

    def txt(x):
        try:
            return x.cget("text")
        except Exception:
            return None

    def widgets(w, cls=None, text=None):
        out, stack = [], [w]
        while stack:
            x = stack.pop()
            try:
                stack.extend(x.winfo_children())
            except Exception:
                pass
            if (cls is None or isinstance(x, cls)) and (text is None or txt(x) == text):
                out.append(x)
        return out

    def button(text, under=None):
        hits = [b for b in widgets(under or root, ctk.CTkButton) if (txt(b) or "") == text or (text.endswith("…") and (txt(b) or "").startswith(text[:-1]))]
        if not hits:
            raise AssertionError("버튼 '%s' 없음" % text)
        return hits[0]

    def tops():   # 열려 있는 창들 (오른쪽 아래 팝업은 제외)
        return [w for w in root.winfo_children() if isinstance(w, ctk.CTkToplevel) and w.winfo_exists() and w is not ctx["popup"]["win"]]

    def labels_with(s, under=None):
        return [l for l in widgets(under or root, ctk.CTkLabel) if s in (txt(l) or "")]

    steps = []

    def step(name, do=None, until=None, timeout=12, soft=False):
        steps.append((name, do, until, timeout, soft))

    # 1. 첫 실행: 누구 것인지 고르기
    step("첫 실행 설정창", until=lambda: any(txt(b) == "시작" for b in widgets(root, ctk.CTkButton)))

    def pick_me():
        [s for t in tops() for s in widgets(t, ctk.CTkSegmentedButton)][0].set("아빠")
        button("시작").invoke()
    step("'아빠' 고르고 시작", pick_me, until=lambda: cfg.name == "아빠" and not tops())
    step("서버 연결", until=lambda: radio.connected, timeout=20)
    step("가상 가족(엄마) 접속", until=lambda: helper.connected and "엄마" in radio.online, timeout=20)
    step("가족 버튼 표시", until=lambda: any("엄마" in (txt(b) or "") for b in widgets(ctx["grid"], ctk.CTkButton)))
    step("엄마 방 고르기 (버튼이 엄마 색으로)", lambda: radio.select_room("엄마"),
         until=lambda: radio.target_list == ["엄마"] and any("엄마" in (txt(b) or "") and b.cget("fg_color") == member_style("엄마")[1]
                                                            for b in widgets(ctx["grid"], ctk.CTkButton)))
    step("우클릭으로 첫째 추가 → 두 명 묶음", lambda: radio.toggle_target("첫째"), until=lambda: set(radio.target_list) == {"엄마", "첫째"} and radio.current_room()[0] == "custom")
    step("아이들 방", lambda: radio.select_room("g:아이들"), until=lambda: set(radio.target_list) == {"첫째", "둘째", "셋째"})
    step("다시 모두로", lambda: radio.select_room("all"), until=lambda: radio.target == "all")

    # 2. 문자 보내기 / 받음·봄 표시
    def type_and_send():
        e = ctx["entry"]
        e.delete(0, "end")
        e.insert(0, "네")
        button("보내기", e.master).invoke()
    step("문자 보내기", type_and_send, until=lambda: any(c.get("mine") and c.get("text") == "네" for c in radio.clips) and ctx["entry"].get() == "")
    step("엄마가 문자 받음", until=lambda: helper.find("text", text="네"))
    step("받음 표시 ✓", until=lambda: any(radio.receipt_text(c).startswith("엄마✓ ") for c in radio.clips if c.get("mine")))
    step("봄 표시 ✓✓", lambda: helper.send({"type": "seen", "mid": helper.find("text", text="네")[0]["mid"]}),
         until=lambda: labels_with("엄마✓✓", ctx["msg_list"]))

    # 3. 문자 받기 → 오른쪽 아래 팝업 → 클릭 → 빠른 답장
    step("엄마가 문자 보냄 → 팝업", lambda: helper.send({"type": "text", "to": "아빠", "text": "지금 갈게요"}),
         until=lambda: ctx["popup"]["win"] is not None and labels_with("지금 갈게요", ctx["popup"]["win"]))
    step("팝업 클릭 → 답장칸", lambda: ctx["popup"]["win"].event_generate("<Button-1>"), until=lambda: widgets(ctx["popup"]["win"], ctk.CTkEntry))

    def new_text_while_replying():
        ctx["reply_popup"] = ctx["popup"]["win"]
        helper.send({"type": "text", "to": "아빠", "text": "하나 더"})
    step("답장 쓰는 중 새 문자 → 팝업 유지", new_text_while_replying,
         until=lambda: any(c.get("text") == "하나 더" for c in radio.clips) and ctx["popup"]["win"] is ctx["reply_popup"] and widgets(ctx["popup"]["win"], ctk.CTkEntry))
    step("빠른 답장 '알겠어요' → 팝업 안에 '보냈어요'", lambda: button("알겠어요", ctx["popup"]["win"]).invoke(),
         until=lambda: helper.find("text", text="알겠어요", mode="direct") and ctx["popup"]["win"] is ctx["reply_popup"] and labels_with("✓ 보냈어요", ctx["popup"]["win"]))
    step("'보냈어요' 팝업은 저절로 닫힘", until=lambda: ctx["popup"]["win"] is None, timeout=8)
    step("엄마 쪽에 '봄' 확인 도착", until=lambda: any((m.get("status") or {}).get("아빠") == "seen" for m in helper.find("receipt")))
    step("1:1 글은 '엄마와의 대화' 상자로 묶임", until=lambda: labels_with("엄마와의 대화", ctx["msg_list"]))
    step("답장칸 열어 두고 본 창 클릭 → 닫힘", lambda: (helper.send({"type": "text", "to": "아빠", "text": "밥은?"}), None)[1],
         until=lambda: ctx["popup"]["win"] is not None and labels_with("밥은?", ctx["popup"]["win"]))

    def open_reply_then_focus_main():
        ctx["popup"]["win"].event_generate("<Button-1>")
        root.after(400, lambda: root.event_generate("<FocusIn>"))
    step("  → 답장칸 열고 본 창에 포커스", open_reply_then_focus_main, until=lambda: ctx["popup"]["win"] is None, timeout=10)

    # 4. 실시간 말하기 수신 배너 (+ 음성 감지: 말소리 없이 누른 건 아무 신호도 안 옴)
    silence = bytes(640)                                                       # 20ms 무음
    voice = struct.pack("<320h", *[3000 if (i // 18) % 2 else -3000 for i in range(320)])   # 20ms 또렷한 소리

    def mom_touch_only():
        helper.send({"type": "talk_start", "to": "all"})
        for _ in range(40):
            helper.client.send_bytes(silence)   # 0.8초 무음 (버튼만 스친 경우)
        helper.send({"type": "talk_end"})
    step("엄마가 말 없이 눌렀다 뗌 → 나에겐 아무 신호 없음", mom_touch_only,
         until=lambda: helper.find("talk_silent") and radio.incoming_from is None and not ctx["incoming"].winfo_ismapped() and not helper.find("talk_sent"))

    def mom_talk():
        helper.send({"type": "talk_start", "to": "all"})
        for _ in range(20):
            helper.client.send_bytes(voice)     # 0.4초 말소리 → 감지되어 배너가 떠야 함
    step("엄마 말하기(말소리) → 배너", mom_talk,
         until=lambda: radio.incoming_from == "엄마" and ctx["incoming"].winfo_ismapped() and helper.find("talk_live"))

    def mom_audio_end():
        for _ in range(20):
            helper.client.send_bytes(voice)
        helper.send({"type": "talk_end"})
    step("엄마 말 끝 → 배너 사라짐", mom_audio_end, until=lambda: radio.incoming_from is None and not ctx["incoming"].winfo_ismapped())
    step("엄마 쪽 talk_sent(들음 확인)", until=lambda: helper.find("talk_sent"))
    step("내가 말하기 시도", lambda: radio.start_talk(), until=lambda: not radio.requesting)
    step("말하기 끝", lambda: radio.stop_talk(), until=lambda: not radio.talking)

    # 5. 음성 클립 받기 (서버를 거치지 않고 직접 넣어 봄)
    def inject_clip():
        radio._on_text({"type": "clip", "id": 9001, "mid": 9001, "from": "엄마", "mode": "direct", "ts": int(time.time()), "seconds": 1.0})
        radio._on_bin(bytes([CLIP]) + struct.pack("!I", 9001) + bytes(32000))
    step("음성 클립 받기 → 목록·팝업", inject_clip,
         until=lambda: any(c.get("kind") == "clip" and c.get("id") == 9001 for c in radio.clips)
         and any("음성" in (txt(b) or "") for b in widgets(ctx["msg_list"], ctk.CTkButton)))

    # 6. 설정창: 관리 항목(암호) · 바로가기 · 저장
    step("설정창 열기", ctx["open_settings"], until=lambda: any(txt(b) == "저장" for b in widgets(root, ctk.CTkButton)))
    step("사용자·주소 변경 → 암호창", lambda: button("사용자·주소 변경…").invoke(), until=lambda: labels_with("가족 암호를 입력하세요"))

    def enter_pw():
        d = [t for t in tops() if labels_with("가족 암호를 입력하세요", t)][0]
        widgets(d, ctk.CTkEntry)[0].insert(0, cfg.key)
        button("확인", d).invoke()
    step("암호 입력 → 관리 항목", enter_pw, until=lambda: any(txt(b) == "변경 저장하고 다시 연결" for b in widgets(root, ctk.CTkButton)))

    def save_admin():
        ctx["old_sock"] = radio.client.sock
        button("변경 저장하고 다시 연결").invoke()
    step("관리 저장(다시 연결)", save_admin, until=lambda: not tops())
    step("새 연결로 다시 붙음", until=lambda: radio.connected and radio.client.sock is not None and radio.client.sock is not ctx["old_sock"], timeout=20)

    def history_ok():   # 다시 붙으면 서버가 보낸 기록이 합쳐지되 중복은 없어야 함
        mids = [c.get("mid") for c in radio.clips if c.get("mid")]
        return len(mids) == len(set(mids)) and any(c.get("text") == "지금 갈게요" for c in radio.clips) and helper.find("history")
    step("서버 대화 기록 합침(중복 없음)", until=history_ok)

    def fake_update():   # 서버의 updates 폴더에 가짜 새 버전을 놓고, 클라이언트가 받아서 sha256까지 확인하는지
        d = os.path.join(os.environ.get("DATA_DIR") or tempfile_dir(), "updates")
        os.makedirs(d, exist_ok=True)
        blob = os.urandom(300000)
        with open(os.path.join(d, "WoorizipRadio-PC-v99999.exe"), "wb") as f:
            f.write(blob)
        with open(os.path.join(d, "latest.json"), "w", encoding="utf-8") as f:
            json.dump({"version": "v99999", "number": 99999,
                       "pc": {"file": "WoorizipRadio-PC-v99999.exe", "size": len(blob), "sha256": hashlib.sha256(blob).hexdigest()}}, f)
        ctx["upd"] = ctx["updater"].check()
    step("자동 업데이트: 새 버전 확인·받기(sha256)", fake_update,
         until=lambda: ctx.get("upd") and ctx["updater"].ready and ctx["updater"].ready[1] == 99999 and os.path.getsize(ctx["updater"].ready[0]) == 300000,
         soft=not os.environ.get("DATA_DIR"))
    step("설정창 다시 열기", ctx["open_settings"], until=lambda: any(txt(b) == "저장" for b in widgets(root, ctk.CTkButton)))
    step("바탕화면 바로가기 버튼", lambda: button("바탕화면에 바로가기 만들기").invoke(),
         until=lambda: dialogs and dialogs[-1][1] == "바로가기" and dialogs[-1][0] == "showinfo", timeout=30, soft=True)
    step("설정 저장", lambda: button("저장").invoke(), until=lambda: not tops())

    # 7. 예약 메시지
    def open_sched():
        ctx["entry"].delete(0, "end")
        ctx["entry"].insert(0, "밥 먹자")
        ctx["open_schedule"]()
    step("예약창 열기", open_sched, until=lambda: any(txt(b) == "예약 추가" for b in widgets(root, ctk.CTkButton)))

    def add_sched():
        w = ctx["sched"]["w"]
        [s for s in widgets(w, ctk.CTkSegmentedButton) if "내일" in s.cget("values")][0].set("내일")
        button("예약 추가", w).invoke()
    step("예약 추가 → 목록", add_sched, until=lambda: radio.schedules and labels_with("밥 먹자", ctx["sched"]["list"]))
    step("예약 삭제", lambda: button("삭제", ctx["sched"]["list"]).invoke(),
         until=lambda: not radio.schedules and labels_with("예약이 없어요", ctx["sched"]["list"]))
    step("예약창 닫기", lambda: ctx["sched"]["w"].destroy(), until=lambda: not tops())

    # 8. 트레이로 숨기기 / 다시 열기 / 자동 실행 등록
    step("창 닫기 → 트레이로 숨김", lambda: ctx["on_close"]() if ctx["tray"]["icon"] else None,
         until=lambda: root.state() == "withdrawn" if ctx["tray"]["icon"] else True, soft=True)
    step("트레이 → 창 열기", lambda: ctx["events"].put(("show", None)), until=lambda: root.state() == "normal")
    step("완전히 종료 → 암호창", ctx["quit_app"], until=lambda: labels_with("완전히 종료하려면"))
    step("암호창 닫으면 계속 실행", lambda: [t for t in tops() if labels_with("완전히 종료하려면", t)][0].destroy(),
         until=lambda: not tops() and root.winfo_exists())
    step("자동 실행 등록·해제", lambda: (set_autostart(True), set_autostart(False)))
    step("바로가기 점검 스크립트(시작 메뉴 항목)", lambda: ctx.update(repair=repair_shortcuts()),
         until=lambda: ctx["repair"][0] and os.path.exists(os.path.join(os.environ.get("APPDATA", ""), "Microsoft", "Windows", "Start Menu", "Programs", "우리집.lnk")),
         timeout=70, soft=True)

    def finish():
        bad = [r for r in results if not r[1] and not r[3]]
        lines = ["[자가 점검] customtkinter %s · python %s · %s" % (getattr(ctk, "__version__", "?"), sys.version.split()[0], "모두 통과" if not bad else "%d개 실패" % len(bad))]
        for name, ok, memo, soft in results:
            lines.append("  %s %s%s%s" % ("OK  " if ok else "FAIL", name, " (참고용)" if soft and not ok else "",
                                          ("\n        " + memo.strip().replace("\n", "\n        ")) if memo else ""))
        lines.append("  대화상자: %s" % [d[1:3] for d in dialogs])
        lines.append("  엄마 쪽 수신: %s" % sorted({m.get("type") for m in helper.msgs}))
        ctx["result"] = (not bad, "\n".join(lines))
        try:
            ctx["handle_event"]("quit", None)
        except Exception:
            pass

    def run(i=0, started=None):
        if i >= len(steps):
            finish()
            return
        name, do, until, timeout, soft = steps[i]
        try:
            if started is None:
                started = time.monotonic()
                if do:
                    do()
            ok = True if until is None else bool(until())
        except Exception:
            results.append((name, False, traceback.format_exc(limit=4), soft))
            root.after(200, run, i + 1)
            return
        if ok:
            results.append((name, True, "", soft))
            root.after(200, run, i + 1)
        elif time.monotonic() - started > timeout:
            results.append((name, False, "시간 초과 %ds" % timeout, soft))
            root.after(200, run, i + 1)
        else:
            root.after(100, run, i, started)
    run()


def tempfile_dir():
    import tempfile
    return tempfile.gettempdir()


def _selftest_main():
    import tempfile
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    # 진짜 설정을 건드리지 않도록 임시 폴더를 씀. 서버는 같은 컴퓨터(127.0.0.1)에서 미리 띄워 둘 것
    os.environ["APPDATA"] = os.path.join(tempfile.gettempdir(), "WoorizipSelftest-%d" % os.getpid())
    os.environ["LOCALAPPDATA"] = os.environ["APPDATA"]
    with open(os.path.join(data_dir(), "config.json"), "w", encoding="utf-8") as f:
        json.dump({"host": os.environ.get("WOORIZIP_TEST_HOST", "127.0.0.1"), "out_host": "", "name": "",
                   "key": os.environ.get("FAMILY_KEY", "105403")}, f)
    log("자가 점검 시작")
    run_ui(False, selftest=True)
    ok, report = _selftest_ctx.get("result", (False, "[자가 점검] 결과 없음 (창이 먼저 닫혔거나 점검이 시작되지 못함)"))
    print(report)
    try:
        with open(os.path.join(data_dir(), "log.txt"), encoding="utf-8") as f:
            print("--- 기록 파일 ---\n" + f.read()[-6000:])
    except Exception:
        pass
    return 0 if ok else 1


def _report_crash():
    import traceback
    tb = traceback.format_exc()
    log("프로그램 멈춤:\n" + tb)
    try:
        import tkinter
        from tkinter import messagebox as _mb
        r = tkinter.Tk()
        r.withdraw()
        _mb.showerror(APP_TITLE, "프로그램에 문제가 생겨 멈췄어요.\n\n%s\n\n기록 파일: %s" % (tb.strip().splitlines()[-1][:300], os.path.join(data_dir(), "log.txt")))
        r.destroy()
    except Exception:
        pass


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(_selftest_main())
    _guard = single_instance()
    if _guard is None:
        sys.exit(0)   # 이미 실행 중 → 그쪽 창이 열림
    log("시작 %s %s" % (APP_TITLE, sys.argv[1:]))
    try:
        run_ui("--minimized" in sys.argv)
    except Exception:
        _report_crash()
        sys.exit(1)
