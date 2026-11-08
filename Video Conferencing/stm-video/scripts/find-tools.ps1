# Dot-sourced by start-call-control.ps1. Finds JDK 21 and Maven even when they are not on PATH:
# uses IntelliJ's downloaded JDKs (%USERPROFILE%\.jdks) and IntelliJ's bundled Maven if needed. Sets $env:JAVA_HOME and $Mvn.
function Get-JavaMajor([string]$javaExe) {
  $v = (& $javaExe -version 2>&1 | Out-String)
  if ($v -match 'version "(\d+)') { return [int]$Matches[1] } else { return 0 }
}
$javaOk = $false
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe") -and (Get-JavaMajor "$env:JAVA_HOME\bin\java.exe") -ge 21) { $javaOk = $true }
if (-not $javaOk) {
  $cands = @()
  $onPath = Get-Command java -ErrorAction SilentlyContinue
  if ($onPath) { $cands += (Split-Path (Split-Path $onPath.Source)) }
  foreach ($root in @("$env:USERPROFILE\.jdks", "$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Microsoft", "$env:ProgramFiles\Amazon Corretto")) {
    if (Test-Path $root) { $cands += (Get-ChildItem $root -Directory | Sort-Object Name -Descending | ForEach-Object FullName) }
  }
  foreach ($c in $cands) {
    if ((Test-Path "$c\bin\java.exe") -and (Get-JavaMajor "$c\bin\java.exe") -ge 21) { $env:JAVA_HOME = $c; $javaOk = $true; break }
  }
}
if (-not $javaOk) { throw "JDK 21+ not found. In IntelliJ: File > Project Structure > SDKs > + > Download JDK > version 21, then run this again." }
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Write-Host "Java : $env:JAVA_HOME"

$Mvn = (Get-Command mvn -ErrorAction SilentlyContinue).Source
if (-not $Mvn) {
  $roots = @("$env:ProgramFiles\JetBrains", "${env:ProgramFiles(x86)}\JetBrains", "$env:LOCALAPPDATA\Programs", "$env:LOCALAPPDATA\JetBrains\Toolbox\apps")
  foreach ($r in $roots) {
    if (-not (Test-Path $r)) { continue }
    $hit = Get-ChildItem $r -Recurse -Filter mvn.cmd -ErrorAction SilentlyContinue -Depth 8 |
           Where-Object { $_.FullName -match 'plugins\\maven\\lib\\maven3\\bin' } | Select-Object -First 1
    if ($hit) { $Mvn = $hit.FullName; break }
  }
}
if (-not $Mvn) { throw "Maven not found. Install it (https://maven.apache.org/download.cgi, add its bin folder to PATH) or tell Claude where IntelliJ is installed." }
Write-Host "Maven: $Mvn"
