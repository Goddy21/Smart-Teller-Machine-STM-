# Prints this laptop's LAN IPv4 address (the adapter that has the default gateway). Used by the other scripts.
$cfg = Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' } | Select-Object -First 1
if (-not $cfg) { throw "No connected network adapter with a gateway found. Pass -LanIp 192.168.x.x explicitly." }
$cfg.IPv4Address.IPAddress | Select-Object -First 1
