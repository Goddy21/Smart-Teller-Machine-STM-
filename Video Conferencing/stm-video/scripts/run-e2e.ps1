# Two-browser automated call test (fake cameras) against call-control. Needs Node 18+.
param([string]$BaseUrl = "http://localhost:8080", [switch]$Headed)
$ErrorActionPreference = "Continue"   # native tools log to stderr; do not treat that as failure
Set-Location (Join-Path $PSScriptRoot "..\e2e")
if (-not (Test-Path node_modules)) { npm install; npx playwright install chromium }
if ($Headed) { node run.mjs $BaseUrl --headed } else { node run.mjs $BaseUrl }
