@echo off
rem Run ON THE STM. Shows the teller's camera full screen on the upper monitor. Usage:
rem   open-teller-display.cmd SERVER_IP TERMINAL_ID X Y      (run list-screens.ps1 to find X Y)
rem   open-teller-display.cmd 10.20.30.40 STM-01 1280 0
rem   open-teller-display.cmd 10.20.30.40:8443 STM-01 1280 0   (when the server uses another port)
rem TERMINAL_ID: the same id as the kiosk page (STM-01 with the kiosk test page; the machine's device id, e.g.
rem STM-NBO-MOI-03, when the kiosk app calls a teller through the middleware). Close it with Alt+F4.
if "%~4"=="" ( echo Usage: %~nx0 SERVER_IP TERMINAL_ID X Y   ^(run list-screens.ps1 to find X Y^) & exit /b 1 )
set URL=https://%~1
set B="%ProgramFiles%\Google\Chrome\Application\chrome.exe"
if not exist %B% set B="%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe"
if not exist %B% set B="%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"
if not exist %B% set B="%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"
if not exist %B% ( echo Chrome or Edge not found & exit /b 1 )
start "" %B% --user-data-dir="%TEMP%\stm-teller-display" --window-position=%~3,%~4 --kiosk --edge-kiosk-type=fullscreen --no-first-run --autoplay-policy=no-user-gesture-required "%URL%/kiosk/teller-display.html?terminal=%~2"
