@echo off
rem Run ON THE STM. Opens the kiosk test page from the Smart Teller server (HTTPS). Usage:
rem   open-kiosk-test.cmd SERVER_IP [TERMINAL_ID]
rem   open-kiosk-test.cmd 10.20.30.40 STM-01
rem   open-kiosk-test.cmd 10.20.30.40:8443 STM-01     (when the server uses another port)
rem Run install-server-certificate.ps1 once first, or the browser blocks the cameras.
if "%~1"=="" ( echo Usage: %~nx0 SERVER_IP [TERMINAL_ID] & exit /b 1 )
set URL=https://%~1
set TERM=%~2
if "%TERM%"=="" set TERM=STM-01
set B="%ProgramFiles%\Google\Chrome\Application\chrome.exe"
if not exist %B% set B="%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe"
if not exist %B% set B="%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"
if not exist %B% set B="%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"
if not exist %B% ( echo Chrome or Edge not found & exit /b 1 )
rem --auto-select-desktop-capture-source shares the whole screen with the teller without a picker dialog.
start "" %B% --user-data-dir="%TEMP%\stm-kiosk-test" --autoplay-policy=no-user-gesture-required --auto-select-desktop-capture-source="Entire screen" --no-first-run "%URL%/kiosk/kiosk-test.html?terminal=%TERM%"
