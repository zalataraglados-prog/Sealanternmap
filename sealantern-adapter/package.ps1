param(
  [string]$OutFile = "sealantermap-bridge-sealantern.zip"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$manifest = Join-Path $root "manifest.json"
$mainLua = Join-Path $root "main.lua"
$readme = Join-Path $root "README.md"
$payloadDir = Join-Path $root "payload"
$payloadJar = Join-Path $payloadDir "sealantermap-0.1.0.jar"
$outPath = Join-Path $root $OutFile

if (!(Test-Path $manifest)) { throw "manifest.json not found: $manifest" }
if (!(Test-Path $mainLua)) { throw "main.lua not found: $mainLua" }
if (!(Test-Path $payloadJar)) { throw "payload jar not found: $payloadJar" }

if (Test-Path $outPath) {
  Remove-Item -Force $outPath
}

$stageDir = Join-Path $root ".pack-stage"
if (Test-Path $stageDir) {
  Remove-Item -Recurse -Force $stageDir
}
New-Item -ItemType Directory -Path $stageDir | Out-Null

Copy-Item -Path $manifest -Destination (Join-Path $stageDir "manifest.json")
Copy-Item -Path $mainLua -Destination (Join-Path $stageDir "main.lua")
if (Test-Path $readme) {
  Copy-Item -Path $readme -Destination (Join-Path $stageDir "README.md")
}
Copy-Item -Path $payloadDir -Destination (Join-Path $stageDir "payload") -Recurse -Force

Compress-Archive -Path (Join-Path $stageDir "*") -DestinationPath $outPath -Force
Remove-Item -Recurse -Force $stageDir

Write-Host "Packed:" $outPath
