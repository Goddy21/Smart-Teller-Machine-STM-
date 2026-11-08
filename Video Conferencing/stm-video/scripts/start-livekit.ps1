# Runs LiveKit natively on Windows so WebRTC media goes straight between the STM and this laptop.
# Downloads livekit-server.exe 1.13.11 the first time. Keep this window open.
# -CallControlPort: where LiveKit sends its webhooks (call-control). Use 8090 when the STM middleware has 8080.
param([string]$LanIp = (& "$PSScriptRoot\lan-ip.ps1"), [int]$CallControlPort = 8080)
$ErrorActionPreference = "Continue"   # native tools log to stderr; do not treat that as failure
$version = "1.13.11"
$dir = Join-Path $PSScriptRoot "..\media-plane\bin"
$exe = Join-Path $dir "livekit-server.exe"
if (-not (Test-Path $exe)) {
  New-Item -ItemType Directory -Force $dir | Out-Null
  $zip = Join-Path $dir "livekit.zip"
  Write-Host "Downloading LiveKit $version ..."
  Invoke-WebRequest "https://github.com/livekit/livekit/releases/download/v$version/livekit_${version}_windows_amd64.zip" -OutFile $zip
  Expand-Archive $zip -DestinationPath $dir -Force; Remove-Item $zip
}
$src = Join-Path $PSScriptRoot "..\media-plane\livekit.lan.yaml"
$cfg = Join-Path $PSScriptRoot "..\media-plane\livekit.lan.generated.yaml"
(Get-Content $src -Raw).Replace("NODE_IP", $LanIp).Replace("localhost:8080/webhooks", "localhost:$CallControlPort/webhooks") | Set-Content $cfg -Encoding ascii
Write-Host "LiveKit on ws://${LanIp}:7880 (node_ip $LanIp), webhooks to http://localhost:$CallControlPort/webhooks/livekit. Ctrl+C to stop."
& $exe --config $cfg
