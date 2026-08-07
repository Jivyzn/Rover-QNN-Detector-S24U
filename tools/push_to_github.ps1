$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

Write-Host ''
Write-Host 'ROVER QNN - PUSH TO GITHUB' -ForegroundColor Cyan
Write-Host '==========================' -ForegroundColor Cyan

function Need($Name) {
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $cmd) { throw "$Name is not installed or not in PATH." }
    return $cmd.Source
}

$Git = Need 'git'
$Gh = Need 'gh'

& $Gh auth status
if ($LASTEXITCODE -ne 0) {
    Write-Host ''
    Write-Host 'GitHub CLI is not logged in. Running gh auth login...' -ForegroundColor Yellow
    & $Gh auth login
    if ($LASTEXITCODE -ne 0) { throw 'GitHub login failed.' }
}

$defaultName = 'Rover-QNN-Detector-S24U'
$repoName = Read-Host "Repo name [$defaultName]"
if ([string]::IsNullOrWhiteSpace($repoName)) { $repoName = $defaultName }

$visibility = Read-Host 'Visibility: public or private [public]'
if ([string]::IsNullOrWhiteSpace($visibility)) { $visibility = 'public' }
$visibility = $visibility.ToLowerInvariant()
if ($visibility -notin @('public','private')) { throw 'Visibility must be public or private.' }

if (-not (Test-Path -LiteralPath (Join-Path $Root '.git'))) {
    & $Git init
    & $Git branch -M main
}

& $Git add -A
$status = (& $Git status --porcelain)
if ($status) {
    & $Git commit -m 'release: native V21 turbo QNN detector'
} else {
    Write-Host 'Nothing new to commit.' -ForegroundColor DarkGray
}

$owner = (& $Gh api user --jq '.login').Trim()
if ([string]::IsNullOrWhiteSpace($owner)) { throw 'Could not resolve GitHub username.' }
$fullRepo = "$owner/$repoName"

& $Gh repo view $fullRepo --json name 2>$null | Out-Null
$exists = ($LASTEXITCODE -eq 0)

if (-not $exists) {
    $args = @(
        'repo','create',$fullRepo,
        "--$visibility",
        '--source','.',
        '--remote','origin',
        '--description','Native Android YOLO26 detector running a custom 8-class QNN model on Snapdragon 8 Gen 3 Hexagon HTP/NPU.'
    )
    & $Gh @args
    if ($LASTEXITCODE -ne 0) { throw 'GitHub repo creation failed.' }
} else {
    Write-Host "Repo already exists: $fullRepo" -ForegroundColor Yellow
    $remote = (& $Git remote 2>$null)
    if ($remote -notcontains 'origin') {
        & $Git remote add origin "https://github.com/$fullRepo.git"
    }
}

& $Git push -u origin main
if ($LASTEXITCODE -ne 0) { throw 'git push failed.' }

# best-effort metadata; a push should not fail just because topics do.
try {
    & $Gh repo edit $fullRepo --description 'Native Android YOLO26 detector running a custom 8-class QNN model on Snapdragon 8 Gen 3 Hexagon HTP/NPU.' --add-topic android --add-topic computer-vision --add-topic yolo --add-topic ultralytics --add-topic onnxruntime --add-topic qualcomm --add-topic snapdragon --add-topic npu --add-topic qnn --add-topic robotics | Out-Null
} catch {}

Write-Host ''
Write-Host 'PUSH COMPLETE' -ForegroundColor Green
Write-Host "https://github.com/$fullRepo" -ForegroundColor Cyan
Write-Host ''
Write-Host 'Good first release tag:' -ForegroundColor DarkGray
Write-Host '  git tag -a v21.0 -m "V21 TURBO - Snapdragon NPU detector"' -ForegroundColor DarkGray
Write-Host '  git push origin v21.0' -ForegroundColor DarkGray
