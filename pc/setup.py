"""
우리집 — Windows 설치 파일 (작은 실행기)
안에 들어 있는 프로그램 폴더(app)를 사용자 폴더(LOCALAPPDATA\\WoorizipRadio\\app)에 풀어 넣고 실행함.
- 이미 켜져 있는 우리집이 있으면 '종료해 달라'고 부탁하고 꺼질 때까지 기다린 뒤 교체 (자동 업데이트도 이 파일을 씀)
- 풀어 넣은 프로그램은 폴더 형태라 켜는 것도, 이미 켜져 있을 때 창을 다시 여는 것도 빠름
- 인자(--minimized 등)는 그대로 프로그램에 넘김
"""
import ctypes
import os
import shutil
import socket
import subprocess
import sys
import time

MUTEX_NAME = "Local\\WoorizipRadio_single_instance"
GUARD_PORT = 47651
ROOT = os.path.join(os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"), "WoorizipRadio")
APP = os.path.join(ROOT, "app")
EXE = os.path.join(APP, "WoorizipRadio.exe")


def msg(text, title="우리집 설치", flags=0x40):
    try:
        ctypes.windll.user32.MessageBoxW(None, text, title, flags)
    except Exception:
        pass


def instance_running():
    try:
        from ctypes import wintypes
        k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        k32.CreateMutexW.restype = wintypes.HANDLE
        k32.CreateMutexW.argtypes = [ctypes.c_void_p, wintypes.BOOL, ctypes.c_wchar_p]
        k32.CloseHandle.argtypes = [wintypes.HANDLE]
        h = k32.CreateMutexW(None, False, MUTEX_NAME)
        exists = ctypes.get_last_error() == 183
        if h:
            k32.CloseHandle(h)
        return exists
    except Exception:
        return False


def ask_quit():
    try:
        c = socket.create_connection(("127.0.0.1", GUARD_PORT), timeout=1)
        c.sendall(b"quit")
        c.close()
    except Exception:
        pass


def retry(fn, tries=40, wait=0.25):
    last = None
    for _ in range(tries):
        try:
            return fn()
        except Exception as e:   # 방금 꺼진 프로그램이 파일을 아직 잡고 있을 수 있음
            last = e
            time.sleep(wait)
    raise last


def main():
    src = os.path.join(getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__))), "app")
    if not os.path.isdir(src):
        msg("설치 파일이 손상됐어요. 다시 내려받아 주세요.", flags=0x10)
        return 1
    if instance_running():
        ask_quit()
        for _ in range(60):
            if not instance_running():
                break
            time.sleep(0.25)
        if instance_running():
            msg("실행 중인 '우리집'을 먼저 종료해 주세요.\n(오른쪽 아래 시계 옆 별 아이콘 → 완전히 종료)\n\n그 다음 이 파일을 다시 실행하면 새 버전으로 바뀌어요.", flags=0x30)
            return 1
    os.makedirs(ROOT, exist_ok=True)
    new, old = APP + ".new", APP + ".old"
    try:
        shutil.rmtree(new, ignore_errors=True)
        shutil.copytree(src, new)
        if os.path.isdir(old):
            shutil.rmtree(old, ignore_errors=True)
        if os.path.isdir(APP):
            retry(lambda: os.rename(APP, old))
        retry(lambda: os.rename(new, APP))
        shutil.rmtree(old, ignore_errors=True)
        legacy = os.path.join(ROOT, "WoorizipRadio.exe")   # 예전 한 파일 설치본
        if os.path.isfile(legacy):
            try:
                os.remove(legacy)
            except Exception:
                pass
    except Exception as e:
        msg("설치 중 문제가 생겼어요:\n%s\n\n프로그램을 완전히 종료한 뒤 다시 실행해 주세요." % e, flags=0x10)
        return 1
    try:
        subprocess.Popen([EXE] + sys.argv[1:], close_fds=True, cwd=APP)
    except Exception as e:
        msg("설치는 됐지만 실행하지 못했어요:\n%s" % e, flags=0x10)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
