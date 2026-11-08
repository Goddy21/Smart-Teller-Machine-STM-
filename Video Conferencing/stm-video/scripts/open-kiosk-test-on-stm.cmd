@echo off
rem Run ON THE STM. Opens the kiosk test page from your laptop in a separate Chrome/Edge window that is allowed to use
rem the cameras over plain http (LAN test only). Usage:  open-kiosk-test-on-stm.cmd 192.168.1.50 [STM-01] [PORT]
rem PORT = call-control's port on the laptop (default 8080; 8090 when the STM middleware uses 8080)
if "%~1"=="" ( echo Usage: %~nx0 LAPTOP_IP [TERMINAL_ID] [PORT] & exit /b 1 )
set IP=%~1
set TERM=%~2
if "%TERM%"=="" set TERM=STM-01
set PORT=%~3
if "%PORT%"=="" set PORT=8080
set URL=http://%IP%:%PORT%
set B="%ProgramFiles%\Google\Chrome\Application\chrome.exe"
if not exist %B% set B="%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe"
if not exist %B% set B="%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"
if not exist %B% set B="%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"
if not exist %B% ( echo Chrome or Edge not found & exit /b 1 )
rem A separate profile folder is required for the insecure-origin flag to take effect.
rem --auto-select-desktop-capture-source shares the whole screen with the teller without a picker dialog.
start "" %B% --user-data-dir="%TEMP%\stm-kiosk-test" --unsafely-treat-insecure-origin-as-secure=%URL% --autoplay-policy=no-user-gesture-required --auto-select-desktop-capture-source="Entire screen" --no-first-run "%URL%/kiosk/kiosk-test.html?terminal=%TERM%"
