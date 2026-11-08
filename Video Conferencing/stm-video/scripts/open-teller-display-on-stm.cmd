@echo off
rem Run ON THE STM. Shows the teller's video full screen on the STM's UPPER monitor during a call.
rem   open-teller-display-on-stm.cmd LAPTOP_IP TERMINAL_ID X Y [PORT]
rem PORT = call-control's port on the laptop (default 8080; 8090 when the STM middleware uses 8080)
rem X Y = top-left corner of the upper monitor (run: powershell -ExecutionPolicy Bypass -File list-screens.ps1)
rem Example: open-teller-display-on-stm.cmd 192.168.1.49 STM-01 0 -1080
if "%~4"=="" ( echo Usage: %~nx0 LAPTOP_IP TERMINAL_ID X Y [PORT]   ^(run list-screens.ps1 to find X Y^) & exit /b 1 )
set PORT=%~5
if "%PORT%"=="" set PORT=8080
set URL=http://%~1:%PORT%
set B="%ProgramFiles%\Google\Chrome\Application\chrome.exe"
if not exist %B% set B="%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe"
if not exist %B% set B="%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"
if not exist %B% set B="%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"
if not exist %B% ( echo Chrome or Edge not found & exit /b 1 )
rem Its own profile folder = its own browser instance, so it can sit full screen on the other monitor.
rem Close it with Alt+F4.
start "" %B% --user-data-dir="%TEMP%\stm-teller-display" --window-position=%~3,%~4 --kiosk --edge-kiosk-type=fullscreen --no-first-run --autoplay-policy=no-user-gesture-required "%URL%/kiosk/teller-display.html?terminal=%~2"
