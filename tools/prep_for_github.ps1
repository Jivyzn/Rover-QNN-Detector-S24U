$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $PSScriptRoot
$Expected = '9DB8530FCB77A057E0E8FABD93AA3BDB8D8D70774FD6944B6E65ABF2B3E3A58A'
$Source = Join-Path $env:USERPROFILE 'Downloads\best_qnn(1).onnx'
$Models = Join-Path $Root 'models'
$Dest = Join-Path $Models 'best_qnn.onnx'

Write-Host ''
Write-Host 'ROVER QNN - GITHUB PREP' -ForegroundColor Cyan
Write-Host '=======================' -ForegroundColor Cyan

New-Item -ItemType Directory -Force -Path $Models | Out-Null

if (Test-Path -LiteralPath $Dest -PathType Leaf) {
    $hash = (Get-FileHash -LiteralPath $Dest -Algorithm SHA256).Hash
    if ($hash -eq $Expected) {
        Write-Host 'Model already in repo and hash is correct.' -ForegroundColor Green
    } else {
        throw "models\best_qnn.onnx exists but the SHA-256 is wrong: $hash"
    }
} elseif (Test-Path -LiteralPath $Source -PathType Leaf) {
    $hash = (Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash
    if ($hash -ne $Expected) {
        throw "Downloads model hash does not match the working V21 model. Got: $hash"
    }
    Copy-Item -LiteralPath $Source -Destination $Dest -Force
    Write-Host 'Copied the verified QNN model into models\best_qnn.onnx' -ForegroundColor Green
} else {
    Write-Host 'Model was not found in Downloads.' -ForegroundColor Yellow
    Write-Host 'Repo source is ready, but copy the working model here before building:' -ForegroundColor Yellow
    Write-Host ('  ' + $Dest) -ForegroundColor Yellow
}

$junk = @(
    'NATIVE_V21_BUILD_LOG.txt',
    'NATIVE_V21_PHONE_LOG.txt',
    'MODEL_INSPECTION.txt',
    'Rover_QNN_Detector_NATIVE_V21.apk'
)
foreach ($name in $junk) {
    $p = Join-Path $Root $name
    if (Test-Path -LiteralPath $p) { Remove-Item -LiteralPath $p -Force -ErrorAction SilentlyContinue }
}

Get-ChildItem -LiteralPath $Root -Directory -Filter '.bootstrap*' -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Host ('Leaving generated bootstrap out of Git via .gitignore: ' + $_.Name) -ForegroundColor DarkGray
}

Write-Host ''
Write-Host 'GitHub prep complete.' -ForegroundColor Green
Write-Host 'Next: run PUSH_TO_GITHUB.cmd' -ForegroundColor Cyan
