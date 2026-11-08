# Starts Redis and PostgreSQL in Docker Desktop (enough for video calls). -Recording also starts MinIO + LiveKit Egress.
param([switch]$Recording, [int]$PgPort = 5434)   # keep -PgPort the same in start-call-control.ps1
$env:PG_PORT = "$PgPort"
$ErrorActionPreference = "Continue"   # docker writes progress to stderr; PowerShell 5.1 would treat that as a failure
$null = docker info 2>&1
if ($LASTEXITCODE -ne 0) {
  Write-Host "Docker Desktop is not ready. Start it (or wait), until its window says 'Engine running', then run this again. Stuck? See TESTING.md > Troubleshooting." -ForegroundColor Red
  exit 1
}
$compose = Join-Path $PSScriptRoot "..\media-plane\docker-compose.windows.yml"
if ($Recording) { docker compose -f $compose --profile recording up -d } else { docker compose -f $compose up -d }
docker compose -f $compose ps
if ($Recording) { Write-Host "`nRedis :6379, Postgres :$PgPort, MinIO :9000 (console http://localhost:9001, minioadmin/minioadmin)" } else { Write-Host "`nRedis :6379, Postgres :$PgPort (no recording: approvals of 1000+ will be blocked, by design)" }
