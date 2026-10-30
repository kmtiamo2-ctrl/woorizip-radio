@echo off
chcp 65001 >nul
title 우리집 무전기 제거
taskkill /im WoorizipRadio.exe /f >nul 2>nul
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v WoorizipRadio /f >nul 2>nul
rmdir /s /q "%LOCALAPPDATA%\WoorizipRadio" >nul 2>nul
rmdir /s /q "%APPDATA%\WoorizipRadio" >nul 2>nul
powershell -NoProfile -Command "Remove-Item -LiteralPath ([Environment]::GetFolderPath('Desktop') + '\' + [char]0xC6B0 + [char]0xB9AC + [char]0xC9D1 + ' ' + [char]0xBB34 + [char]0xC804 + [char]0xAE30 + '.lnk') -ErrorAction SilentlyContinue"
echo 우리집 무전기를 제거했어요 (자동 실행, 프로그램, 설정, 바로가기).
pause
