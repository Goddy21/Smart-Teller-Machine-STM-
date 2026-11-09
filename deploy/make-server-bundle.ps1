# Run ON YOUR LAPTOP (Windows PowerShell), from anywhere:
#   powershell -ExecutionPolicy Bypass -File "C:\Goddie\STM Middleware\deploy\make-server-bundle.ps1"
# Packs what the server needs into "C:\Goddie\STM Middleware\stm-server.tgz" (no node_modules, builds or secrets).
# Copy it to the server (WinSCP, or: scp stm-server.tgz user@SERVER_IP:/tmp/) and follow deploy/README.md.
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$out = Join-Path $root "stm-server.tgz"
Push-Location $root
try {
  tar.exe -czf $out `
    --exclude=node_modules --exclude=target --exclude=dist --exclude=.git --exclude=src_old --exclude=*.exe `
    --exclude=deploy/.env --exclude=deploy/certs --exclude=deploy/livekit.yaml --exclude=*.tsbuildinfo `
    --exclude="Video Conferencing/stm-video/alternatives" --exclude="Video Conferencing/stm-video/media-plane" `
    --exclude="Video Conferencing/stm-video/e2e" --exclude="Video Conferencing/stm-video/integration-tests" `
    deploy stm-middleware stm-portal "Video Conferencing/stm-video"
  if ($LASTEXITCODE -ne 0) { throw "tar failed ($LASTEXITCODE)" }
  Write-Host ("Created {0} ({1:N1} MB)" -f $out, ((Get-Item $out).Length / 1MB)) -ForegroundColor Green
} finally { Pop-Location }
