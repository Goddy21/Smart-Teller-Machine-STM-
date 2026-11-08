# Builds and runs call-control (Spring Boot, dev profile) on :8080, or -Port 8090 when the STM middleware already uses 8080.
# Needs JDK 21 and Maven on PATH, and start-infra.ps1.
# -Recording: tell call-control that Egress writes to MinIO as http://minio:9000 (inside Docker).
param([string]$LanIp = (& "$PSScriptRoot\lan-ip.ps1"), [switch]$Recording, [int]$PgPort = 5434, [int]$Port = 8080)
$ErrorActionPreference = "Continue"   # native tools log to stderr; do not treat that as failure
$env:DB_URL = "jdbc:postgresql://localhost:$PgPort/stm"   # the stm-video Postgres container (start-infra.ps1)
$env:LIVEKIT_PUBLIC_URL = "ws://${LanIp}:7880"      # what the kiosk and teller browsers connect to
if ($Recording) { $env:STORAGE_EGRESS_ENDPOINT = "http://minio:9000"; $env:RECORDING_ENABLED = "true" } else { Remove-Item Env:STORAGE_EGRESS_ENDPOINT -ErrorAction SilentlyContinue; $env:RECORDING_ENABLED = "false" }
$env:SERVER_PORT = "$Port"
Write-Host "Teller console : http://localhost:$Port/teller/index.html"
Write-Host "Kiosk test page: http://${LanIp}:$Port/kiosk/kiosk-test.html   (open on the STM)"
Write-Host "Teller display : http://${LanIp}:$Port/kiosk/teller-display.html?terminal=STM-01   (STM upper screen)"
. "$PSScriptRoot\find-tools.ps1"
Set-Location (Join-Path $PSScriptRoot "..\call-control")
& $Mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
