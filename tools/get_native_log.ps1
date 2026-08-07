$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Out = Join-Path $Root 'NATIVE_V21_MANUAL_DIAGNOSTIC.txt'
$Package = 'com.jivyzn.roverqnn'

function Find-Adb {
    $command = Get-Command adb.exe -ErrorAction SilentlyContinue
    if (-not $command) { $command = Get-Command adb -ErrorAction SilentlyContinue }
    if ($command) { return $command.Source }
    $paths = @()
    if ($env:LOCALAPPDATA) { $paths += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe') }
    if ($env:ANDROID_HOME) { $paths += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    if ($env:ANDROID_SDK_ROOT) { $paths += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe') }
    return ($paths | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1)
}

$adb = Find-Adb
if (-not $adb) { throw 'adb was not found.' }
$device = @(& $adb devices | Select-Object -Skip 1 | ForEach-Object { if ($_ -match '^(\S+)\s+device$') { $matches[1] } }) | Select-Object -First 1
if (-not $device) { throw 'No authorized phone was found.' }

& $adb -s $device logcat -c
& $adb -s $device shell am force-stop $Package
& $adb -s $device shell pm grant $Package android.permission.CAMERA
& $adb -s $device shell am start -n ($Package + '/.MainActivity')
Write-Host 'Tap START QNN on the phone once.' -ForegroundColor Yellow
Start-Sleep -Seconds 90
& $adb -s $device logcat -d -v threadtime | Set-Content -LiteralPath $Out -Encoding UTF8
'' | Add-Content -LiteralPath $Out
'===== LAST ANR =====' | Add-Content -LiteralPath $Out
& $adb -s $device shell dumpsys activity lastanr | Add-Content -LiteralPath $Out
Write-Host ('Created: {0}' -f $Out) -ForegroundColor Green
