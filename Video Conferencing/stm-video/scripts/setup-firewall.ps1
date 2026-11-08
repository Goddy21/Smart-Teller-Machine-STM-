# Run ONCE, in PowerShell "Run as administrator". Lets the STM reach call-control and LiveKit on this laptop.
# Also make sure the Wi-Fi/Ethernet network is set to "Private" (Settings > Network > Properties).
# -CallControlPort 8090 when call-control runs on 8090 (start-call-control.ps1 -Port 8090).
param([int]$CallControlPort = 8080)
$ErrorActionPreference = "Stop"
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) {
  Write-Host "This needs administrator rights. Opening an elevated PowerShell for it (click Yes)..." -ForegroundColor Yellow
  Start-Process powershell -Verb RunAs -ArgumentList "-NoExit -ExecutionPolicy Bypass -File `"$PSCommandPath`" -CallControlPort $CallControlPort"
  exit
}
$rules = @(
  @{ Name = "STM call-control $CallControlPort"; Protocol = "TCP"; Port = "$CallControlPort" },
  @{ Name = "STM LiveKit signal 7880"; Protocol = "TCP"; Port = "7880" },
  @{ Name = "STM LiveKit ICE/TCP 7881"; Protocol = "TCP"; Port = "7881" },
  @{ Name = "STM LiveKit media UDP";   Protocol = "UDP"; Port = "55000-55200" }
)
foreach ($r in $rules) {
  Get-NetFirewallRule -DisplayName $r.Name -ErrorAction SilentlyContinue | Remove-NetFirewallRule
  New-NetFirewallRule -DisplayName $r.Name -Direction Inbound -Action Allow -Protocol $r.Protocol -LocalPort $r.Port -Profile Private,Domain | Out-Null
  Write-Host "allowed inbound $($r.Protocol) $($r.Port)  ($($r.Name))"
}
Write-Host "Done. Remove later with: Get-NetFirewallRule -DisplayName 'STM *' | Remove-NetFirewallRule"
