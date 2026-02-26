param(
  [string]$OutFile = "sealantermap-bridge-sealantern.zip"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$manifest = Join-Path $root "manifest.json"
$mainLua = Join-Path $root "main.lua"
$readme = Join-Path $root "README.md"
$outPath = Join-Path $root $OutFile

if (!(Test-Path $manifest)) { throw "manifest.json not found: $manifest" }
if (!(Test-Path $mainLua)) { throw "main.lua not found: $mainLua" }

if (Test-Path $outPath) {
  Remove-Item -Force $outPath
}

$files = @($manifest, $mainLua)
if (Test-Path $readme) {
  $files += $readme
}

Compress-Archive -Path $files -DestinationPath $outPath -Force

Write-Host "Packed:" $outPath

