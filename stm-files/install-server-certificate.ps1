# Run ON THE STM (and on staff PCs) in an ADMINISTRATOR PowerShell, from this folder:
#   .\install-server-certificate.ps1
# Trusts the Smart Teller server's private certificate authority, so Edge and Chrome open https://<server> without
# a warning and allow the cameras, microphone and screen share. Close every browser window afterwards.
$ErrorActionPreference = "Stop"
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) { Write-Host "Run this in an Administrator PowerShell." -ForegroundColor Red; exit 1 }
$crt = Join-Path $PSScriptRoot "stm-server-ca.crt"
if (-not (Test-Path $crt)) { Write-Host "stm-server-ca.crt is missing: copy it from the server (deploy/stm/) first." -ForegroundColor Red; exit 1 }
$c = Import-Certificate -FilePath $crt -CertStoreLocation Cert:\LocalMachine\Root
Write-Host "Trusted: $($c.Subject) (expires $($c.NotAfter.ToString('dd MMM yyyy')))." -ForegroundColor Green

# The old laptop setup allowed plain http with a browser policy; HTTPS does not need it. Remove it if present.
foreach ($root in "HKLM:\SOFTWARE\Policies\Microsoft\Edge", "HKLM:\SOFTWARE\Policies\Google\Chrome") {
  foreach ($list in "OverrideSecurityRestrictionsOnInsecureOrigin", "VideoCaptureAllowedUrls", "AudioCaptureAllowedUrls") {
    $key = Join-Path $root $list
    if (Test-Path $key) { Remove-Item $key -Recurse -Force; Write-Host "Removed the old policy $list." }
  }
}
Write-Host "Close all Edge and Chrome windows, then run open-kiosk-test.cmd / open-teller-display.cmd."
