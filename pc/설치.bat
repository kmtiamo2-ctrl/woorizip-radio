@echo off
chcp 65001 >nul
cd /d "%~dp0"
title 우리집 무전기 설치
echo.
echo [1/4] Python 확인 중...
where py >nul 2>nul
if errorlevel 1 (
  echo.
  echo  Python이 설치되어 있지 않아요.
  echo  열리는 페이지에서 Python을 내려받아 설치한 뒤, 이 파일을 다시 실행하세요.
  echo  설치 첫 화면에서 "Add python.exe to PATH"에 꼭 체크하세요.
  start https://www.python.org/downloads/windows/
  pause
  exit /b 1
)
echo [2/4] 필요한 구성요소 설치 중... (처음엔 1~2분 걸려요)
py -m pip install --upgrade pip >nul 2>nul
py -m pip install -r requirements.txt pyinstaller
if errorlevel 1 goto fail
echo [3/4] 실행 파일 만드는 중...
py -m PyInstaller --noconfirm --clean --onefile --windowed --name WoorizipRadio radio_pc.py
if errorlevel 1 goto fail
set "TARGET=%LOCALAPPDATA%\WoorizipRadio"
if not exist "%TARGET%" mkdir "%TARGET%"
taskkill /im WoorizipRadio.exe /f >nul 2>nul
copy /y "dist\WoorizipRadio.exe" "%TARGET%\WoorizipRadio.exe" >nul
if errorlevel 1 goto fail
echo [4/4] 바탕화면 바로가기 만드는 중...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0shortcut.ps1" -Target "%TARGET%\WoorizipRadio.exe"
echo.
echo  설치 완료! 바탕화면의 '우리집 무전기'로 실행할 수 있어요.
echo  처음 실행하면 설정 창이 뜨고, 이후에는 Windows가 켜질 때 자동으로 실행돼요.
echo.
start "" "%TARGET%\WoorizipRadio.exe"
pause
exit /b 0

:fail
echo.
echo  설치 중 문제가 생겼어요. 이 창의 내용을 캡처해서 보내 주세요.
pause
exit /b 1
