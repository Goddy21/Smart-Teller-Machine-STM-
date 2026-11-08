# Run ON THE STM in an ADMINISTRATOR PowerShell (LAN test only).
# Lets Chrome and Edge use the cameras, microphone and screen capture on the plain-http call-control address,
# without a permission prompt. A browser policy applies to every window and cannot be lost the way a
# command-line flag can (a browser that is already running ignores new flags).
#   .\set-stm-browser-policy.ps1 -Origin http://192.168.1.49:8090
#   .\set-stm-browser-policy.ps1 -Remove
# Afterwards close ALL browser windows and check edge://policy (or chrome://policy).
param([string]$Origin = "http://192.168.1.49:8090", [switch]$Remove)
$ErrorActionPreference = "Stop"
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) { Write-Host "Run this in an Administrator PowerShell." -ForegroundColor Red; exit 1 }

$roots = "HKLM:\SOFTWARE\Policies\Microsoft\Edge", "HKLM:\SOFTWARE\Policies\Google\Chrome"
$lists = "OverrideSecurityRestrictionsOnInsecureOrigin", "VideoCaptureAllowedUrls", "AudioCaptureAllowedUrls"
foreach ($root in $roots) {
  foreach ($list in $lists) {
    $key = Join-Path $root $list
    if ($Remove) { if (Test-Path $key) { Remove-Item $key -Recurse -Force }; continue }
    New-Item -Path $key -Force | Out-Null
    New-ItemProperty -Path $key -Name "1" -Value $Origin -PropertyType String -Force | Out-Null
  }
}
if ($Remove) { Write-Host "Policies removed. Restart the browser." }
else {
  Write-Host "Done: $Origin may use cameras, microphone and screen capture in Chrome and Edge." -ForegroundColor Green
  Write-Host "Now close every browser window (taskkill /F /IM msedge.exe), reopen the kiosk test page, and check edge://policy."
}
