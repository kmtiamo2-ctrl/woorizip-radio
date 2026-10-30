"""
우리집 무전기 - Windows PC 버전
- NAS 중계 서버에 가족 한 명처럼 접속해서 말하고 듣기
- 다른 프로그램을 쓰는 중에도 단축키(기본 F9)를 누르고 있는 동안 말하기
- Windows 시작 시 자동 실행 (최소화 상태로 시작)

필요한 패키지: sounddevice (마이크·스피커), keyboard (전역 단축키)
"""
import base64
import json
import math
import os
import socket
import struct
import sys
import threading
import time
from array import array
from collections import deque

APP_NAME = "WoorizipRadio"
RATE = 16000
FRAME_BYTES = 640  # 20ms, 16비트 모노
DEFAULT_MEMBERS = ["엄마", "아빠", "첫째", "둘째", "셋째"]

try:
    import sounddevice as sd
except Exception:  # 개발·테스트 환경용
    sd = None
try:
    import keyboard
except Exception:
    keyboard = None


# ---------------------------------------------------------------- 설정

def config_path():
    base = os.environ.get("APPDATA") or os.path.expanduser("~")
    d = os.path.join(base, APP_NAME)
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, "config.json")


class Config:
    DEFAULTS = {"host": "", "port": 8765, "name": "", "key": "", "hotkey": "f9",
                "target": "all", "dnd": False, "autostart": True}

    def __init__(self):
        self.data = dict(self.DEFAULTS)
        try:
            with open(config_path(), encoding="utf-8") as f:
                self.data.update(json.load(f))
        except Exception:
            pass

    def __getattr__(self, k):
        if k in Config.DEFAULTS:
            return self.data.get(k, Config.DEFAULTS[k])
        raise AttributeError(k)

    def set(self, **kw):
        self.data.update(kw)
        with open(config_path(), "w", encoding="utf-8") as f:
            json.dump(self.data, f, ensure_ascii=False, indent=2)

    @property
    def configured(self):
        return bool(self.host and self.name and self.key)


# ---------------------------------------------------------------- Windows 자동 실행

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"


def autostart_command():
    if getattr(sys, "frozen", False):  # exe로 빌드된 경우
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
    """서버에 연결하고, 끊기면 자동으로 다시 연결"""

    def __init__(self, cfg, on_text, on_bin, on_state):
        self.cfg = cfg
        self.on_text, self.on_bin, self.on_state = on_text, on_bin, on_state
        self.sock = None
        self.send_lock = threading.Lock()
        self.running = False
        self.kick = threading.Event()
        self.last_error = None

    def start(self):
        self.running = True
        threading.Thread(target=self._loop, daemon=True).start()

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
                self.on_state(False, "설정에서 NAS 주소·이름·암호를 입력하세요")
                self.kick.wait(5)
                continue
            self.on_state(False, "연결 중…")
            err = "NAS 서버에 연결할 수 없어요"
            self.last_error = None
            try:
                s = socket.create_connection((cfg.host, int(cfg.port)), timeout=5)
                s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                self._handshake(s, cfg.host, cfg.port)
                s.settimeout(40)  # 서버가 15초마다 ping을 보내므로 40초 조용하면 끊긴 것
                self.sock = s
                self.send_json({"type": "hello", "name": cfg.name, "key": cfg.key,
                                "device": "pc-" + socket.gethostname()})
                backoff = 2
                err = self._read_loop(s)
            except Exception:
                pass
            self._close()
            self.on_state(False, self.last_error or err)
            if self.kick.wait(backoff):
                backoff = 2
            else:
                backoff = min(backoff * 2, 15)

    def _handshake(self, s, host, port):
        key = base64.b64encode(os.urandom(16)).decode()
        req = ("GET / HTTP/1.1\r\nHost: %s:%s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
               "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n" % (host, port, key))
        s.sendall(req.encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = s.recv(1024)
            if not chunk:
                raise ConnectionError()
            buf += chunk
        if b" 101 " not in buf.split(b"\r\n", 1)[0]:
            raise ConnectionError()

    def _recv_exact(self, s, n):
        buf = b""
        while len(buf) < n:
            chunk = s.recv(n - len(buf))
            if not chunk:
                raise ConnectionError()
            buf += chunk
        return buf

    def _read_loop(self, s):
        """연결이 끊길 때까지 메시지를 읽음. 끊긴 이유를 반환"""
        reason = "연결 끊김"
        msg_op, parts = None, []
        while self.running and self.sock is s:
            b1, b2 = self._recv_exact(s, 2)
            fin, op, n = b1 & 0x80, b1 & 0x0F, b2 & 0x7F
            if n == 126:
                n = struct.unpack("!H", self._recv_exact(s, 2))[0]
            elif n == 127:
                n = struct.unpack("!Q", self._recv_exact(s, 8))[0]
            data = self._recv_exact(s, n) if n else b""
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
                    if msg.get("type") == "welcome":
                        self.on_state(True, "연결됨")
                    elif msg.get("type") == "error":
                        reason = self.last_error = {"auth": "가족 암호가 서버와 달라요",
                                  "unknown_member": "이름이 서버의 가족 목록에 없어요"}.get(msg.get("reason"), "서버 오류")
                    self.on_text(msg)
                else:
                    self.on_bin(payload)
                msg_op = None
        return reason

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


BEEP_IN = tone(880, 150)                              # 방송 들어옴
BEEP_OK = tone(1200, 120)                             # 말하기 시작
BEEP_NO = tone(300, 150) + bytes(1600) + tone(300, 150)  # 거절


class Audio:
    """스피커는 항상 열어 두고, 마이크는 말할 때만 엶"""

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
        if self.waiting and len(self.q) < 3:   # 60ms 모아서 재생 시작 (끊김 방지)
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
        while len(self.q) > 25:  # 0.5초 넘게 밀리면 오래된 소리 버림
            self.q.popleft()

    def effect(self, pcm):
        """신호음: 바로 재생"""
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
        self.status = "연결 준비 중"
        self.members = list(DEFAULT_MEMBERS)
        self.online = []
        self.talking = False
        self.requesting = False
        self.incoming_from = None
        self.incoming_mode = None
        self.notice = ""
        self.on_incoming = None   # 화면에서 창 깜빡임 등에 사용
        self.client = WSClient(cfg, self._on_text, self._on_bin, self._on_state)
        self.audio = audio_factory(self.client.send_bytes)
        if self.audio.error:
            self.notice = self.audio.error

    def start(self):
        self.client.start()

    @property
    def target(self):
        return self.cfg.target

    def set_target(self, t):
        with self.lock:
            if not self.talking:
                self.cfg.set(target=t)

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
            self.requesting = True
            if not self.client.send_json({"type": "talk_start", "to": self.target}):
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

    def _on_text(self, msg):
        with self.lock:
            t = msg.get("type")
            if t == "welcome":
                self.members = msg.get("members") or self.members
                self.notice = ""
            elif t == "presence":
                self.online = msg.get("online") or []
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
                    to = msg.get("to")
                    self.notice = "전체 방송 중" if to == "all" else "%s님에게 말하는 중" % to
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
                if self.cfg.dnd:
                    self.notice = "방해금지 중 — %s님의 말을 받지 않았어요" % self.incoming_from
                else:
                    self.audio.effect(BEEP_IN)
                    if self.on_incoming:
                        self.on_incoming()
            elif t == "incoming_end":
                self.incoming_from = self.incoming_mode = None

    def _on_bin(self, data):
        if self.incoming_from and not self.cfg.dnd:
            self.audio.play(data)

    def _on_state(self, connected, detail):
        with self.lock:
            self.connected, self.status = connected, detail
            if not connected:
                if self.talking:
                    self.talking = False
                    self.audio.stop_mic()
                self.requesting = False
                self.incoming_from = self.incoming_mode = None
                self.online = []


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
    root.title("우리집 무전기")
    root.configure(bg=BG)
    root.geometry("420x640")
    root.minsize(380, 560)

    def flash():
        if os.name == "nt":
            try:
                import ctypes
                ctypes.windll.user32.FlashWindow(int(root.wm_frame(), 16), True)
            except Exception:
                pass
    radio.on_incoming = lambda: root.after(0, flash)

    tk.Label(root, text="우리집 무전기", font=(F, 20, "bold"), fg=TXT, bg=BG).pack(anchor="w", padx=20, pady=(18, 0))
    status = tk.Label(root, text="", font=(F, 10), fg=SUB, bg=BG)
    status.pack(anchor="w", padx=20)
    incoming = tk.Label(root, text="", font=(F, 13, "bold"), fg="black", bg=ACCENT, pady=8)

    tk.Label(root, text="누구에게 말할까요?", font=(F, 10), fg=SUB, bg=BG).pack(anchor="w", padx=20, pady=(14, 4))
    grid = tk.Frame(root, bg=BG)
    grid.pack(fill="x", padx=16)

    talk = tk.Label(root, text="", font=(F, 18, "bold"), fg="white", bg=BLUE, height=5, cursor="hand2")
    talk.pack(fill="x", padx=20, pady=(14, 6))
    talk.bind("<ButtonPress-1>", lambda e: radio.start_talk())
    talk.bind("<ButtonRelease-1>", lambda e: radio.stop_talk())
    notice = tk.Label(root, text="", font=(F, 11), fg=ACCENT, bg=BG, wraplength=380)
    notice.pack(pady=(0, 6))

    opts = tk.Frame(root, bg=CARD)
    opts.pack(fill="x", padx=20, pady=6)
    dnd_var = tk.BooleanVar(value=cfg.dnd)
    tk.Checkbutton(opts, text="방해금지 (가족 말 받지 않기)", variable=dnd_var, font=(F, 10),
                   fg=TXT, bg=CARD, selectcolor=CARD, activebackground=CARD, activeforeground=TXT,
                   command=lambda: cfg.set(dnd=dnd_var.get())).pack(anchor="w", padx=10, pady=6)
    hotkey_label = tk.Label(opts, text="", font=(F, 10), fg=SUB, bg=CARD)
    hotkey_label.pack(anchor="w", padx=10, pady=(0, 8))

    tk.Button(root, text="설정", font=(F, 10), command=lambda: open_settings()).pack(pady=6)

    # ---- 전역 단축키 (다른 프로그램을 쓰는 중에도 동작) ----
    hk = {"key": None, "down": False}

    def bind_hotkey():
        if keyboard is None:
            hotkey_label.config(text="단축키 사용 불가 (keyboard 패키지 없음)")
            return
        if hk["key"]:
            try:
                keyboard.unhook_all()
            except Exception:
                pass
        key = cfg.hotkey.strip().lower() or "f9"
        hk["key"] = key

        def down(_):
            if not hk["down"]:        # 누르고 있을 때 반복 입력 무시
                hk["down"] = True
                radio.start_talk()

        def up(_):
            hk["down"] = False
            radio.stop_talk()
        try:
            keyboard.on_press_key(key, down)
            keyboard.on_release_key(key, up)
            hotkey_label.config(text="[%s] 키를 누르고 있는 동안 말하기 (다른 창에서도 동작)" % key.upper())
        except Exception:
            hotkey_label.config(text="단축키 [%s]를 등록할 수 없어요. 설정에서 바꿔 주세요." % key)

    # ---- 설정 창 ----
    def open_settings():
        w = tk.Toplevel(root)
        w.title("설정")
        w.configure(bg=BG)
        w.resizable(False, False)
        fields = {}
        rows = [("host", "NAS 주소 (예: 192.168.0.10)"), ("port", "포트"),
                ("name", "이 PC를 주로 쓰는 가족 (엄마·아빠·첫째·둘째·셋째)"), ("key", "가족 암호"),
                ("hotkey", "말하기 단축키 (예: f9, f8, scroll lock)")]
        for k, label in rows:
            tk.Label(w, text=label, font=(F, 9), fg=SUB, bg=BG).pack(anchor="w", padx=16, pady=(10, 0))
            e = tk.Entry(w, font=(F, 11), width=34, show="*" if k == "key" else "")
            e.insert(0, str(getattr(cfg, k)))
            e.pack(padx=16)
            fields[k] = e
        auto_var = tk.BooleanVar(value=cfg.autostart)
        tk.Checkbutton(w, text="Windows 시작 시 자동 실행", variable=auto_var, font=(F, 10),
                       fg=TXT, bg=BG, selectcolor=BG, activebackground=BG, activeforeground=TXT
                       ).pack(anchor="w", padx=12, pady=(12, 0))

        def save():
            v = {k: e.get().strip() for k, e in fields.items()}
            try:
                v["port"] = int(v["port"] or 8765)
            except ValueError:
                messagebox.showerror("설정", "포트는 숫자로 입력하세요", parent=w)
                return
            if not (v["host"] and v["name"] and v["key"]):
                messagebox.showerror("설정", "주소·이름·암호를 모두 입력하세요", parent=w)
                return
            v["autostart"] = auto_var.get()
            cfg.set(**v)
            try:
                set_autostart(cfg.autostart)
            except Exception as e:
                messagebox.showwarning("설정", "자동 실행 등록 실패: %s" % e, parent=w)
            bind_hotkey()
            radio.client.reconnect()
            w.destroy()
        tk.Button(w, text="저장하고 연결", font=(F, 10), command=save).pack(pady=14)
        w.transient(root)
        w.grab_set()

    # ---- 화면 갱신 (0.15초마다) ----
    last_buttons = {"sig": None}

    def refresh():
        with radio.lock:
            me = cfg.name
            others = [m for m in radio.members if m != me]
            if radio.target != "all" and radio.target not in others:
                cfg.set(target="all")
            sig = (tuple(others), tuple(radio.online), radio.target)
            if sig != last_buttons["sig"]:
                last_buttons["sig"] = sig
                for c in grid.winfo_children():
                    c.destroy()
                items = [("전체", "all", True)] + [(m, m, m in radio.online) for m in others]
                for i, (label, value, on) in enumerate(items):
                    sel = radio.target == value
                    text = label if value == "all" else ("● " if on else "○ ") + label
                    b = tk.Label(grid, text=text, font=(F, 11, "bold"), pady=8,
                                 fg="black" if sel else (TXT if on else SUB), bg=ACCENT if sel else CARD,
                                 cursor="hand2")
                    b.grid(row=i // 3, column=i % 3, sticky="nsew", padx=4, pady=4)
                    b.bind("<Button-1>", lambda e, v=value: radio.set_target(v))
                for c in range(3):
                    grid.columnconfigure(c, weight=1)

            if radio.connected:
                status.config(text="● 연결됨 · %s · 집에 %d명 접속" % (me, len(radio.online)), fg=GREEN)
            else:
                status.config(text=radio.status, fg=SUB)

            if radio.incoming_from:
                incoming.config(text=("🔊 %s님 전체 방송 중" if radio.incoming_mode == "all"
                                      else "🔊 %s님이 나에게 말하는 중") % radio.incoming_from)
                if not incoming.winfo_ismapped():
                    incoming.pack(fill="x", padx=20, pady=(10, 0), after=status)
            elif incoming.winfo_ismapped():
                incoming.pack_forget()

            who = "전체" if radio.target == "all" else radio.target
            if radio.talking:
                talk.config(text="🎙 말하는 중…\n떼면 끝", bg=RED)
            elif radio.requesting:
                talk.config(text="잠깐만요…", bg="#B45309")
            elif not radio.connected:
                talk.config(text="연결 대기 중", bg="#334155")
            else:
                talk.config(text="누르고 말하기\n(%s)" % who, bg=BLUE)
            notice.config(text=radio.notice)
        root.after(150, refresh)

    def on_close():
        # 창을 닫으면 작업 표시줄로 내려가고 계속 수신 (완전 종료는 확인 후)
        if messagebox.askyesno("우리집 무전기", "무전기를 완전히 끌까요?\n'아니요'를 누르면 최소화해서 계속 들어요."):
            root.destroy()
        else:
            root.iconify()
    root.protocol("WM_DELETE_WINDOW", on_close)

    # 처음 실행이면 자동 실행 등록 + 설정 창
    try:
        set_autostart(cfg.autostart)
    except Exception:
        pass
    bind_hotkey()
    radio.start()
    refresh()
    if start_minimized and cfg.configured:
        root.iconify()
    elif not cfg.configured:
        root.after(300, open_settings)
    root.mainloop()


def single_instance():
    """이미 실행 중이면 False (같은 이름으로 두 번 접속하면 서로 끊기므로 하나만 실행)"""
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.bind(("127.0.0.1", 47651))
        s.listen(1)
        return s  # 프로그램이 끝날 때까지 잡아 둠
    except OSError:
        return None


if __name__ == "__main__":
    _guard = single_instance()
    if _guard is None:
        if "--minimized" not in sys.argv:
            import tkinter as tk
            from tkinter import messagebox
            r = tk.Tk(); r.withdraw()
            messagebox.showinfo("우리집 무전기", "이미 실행 중이에요. 작업 표시줄에서 찾아 주세요.")
        sys.exit(0)
    run_ui("--minimized" in sys.argv)
