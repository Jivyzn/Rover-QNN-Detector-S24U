$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$ProgressPreference = 'SilentlyContinue'

# repo layout is fixed: tools lives one level below the project root
$ToolsRoot = $PSScriptRoot
$Root = Split-Path -Parent $ToolsRoot
$TemplateApp = Join-Path $Root 'template\app'
$Bootstrap = Join-Path $Root ('.bootstrap_v21_' + (Get-Date -Format 'yyyyMMdd_HHmmss'))
$Android = Join-Path $Bootstrap 'android'
$Log = Join-Path $Root 'NATIVE_V21_BUILD_LOG.txt'
$PhoneLog = Join-Path $Root 'NATIVE_V21_PHONE_LOG.txt'
$ModelReport = Join-Path $Root 'MODEL_INSPECTION.txt'
$RepoModel = Join-Path $Root 'models\best_qnn.onnx'
$DownloadsModel = Join-Path $env:USERPROFILE 'Downloads\best_qnn(1).onnx'
$ModelSource = $(if (Test-Path -LiteralPath $RepoModel -PathType Leaf) { $RepoModel } else { $DownloadsModel })
$ExpectedHash = '9DB8530FCB77A057E0E8FABD93AA3BDB8D8D70774FD6944B6E65ABF2B3E3A58A'
$Package = 'com.jivyzn.roverqnn'
$FinalApk = Join-Path $Root 'Rover_QNN_Detector_NATIVE_V21.apk'

try { Stop-Transcript | Out-Null } catch {}
Start-Transcript -Path $Log -Force | Out-Null

function Fail([string]$Message) {
    Write-Host ''
    Write-Host ('ERROR: {0}' -f $Message) -ForegroundColor Red
    Write-Host ('Build log: {0}' -f $Log) -ForegroundColor Yellow
    try { Stop-Transcript | Out-Null } catch {}
    exit 1
}

function Run-Checked {
    param(
        [Parameter(Mandatory = $true)][string]$Program,
        [Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments
    )
    Write-Host ''
    Write-Host ('> {0} {1}' -f $Program, ($Arguments -join ' ')) -ForegroundColor DarkGray
    & $Program @Arguments
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        Fail ('Command failed with exit code {0}: {1} {2}' -f $code, $Program, ($Arguments -join ' '))
    }
}

function Assert-PackageLayout {
    $required = @(
        (Join-Path $ToolsRoot 'inspect_model.py'),
        (Join-Path $TemplateApp 'build.gradle'),
        (Join-Path $TemplateApp 'proguard-rules.pro'),
        (Join-Path $TemplateApp 'src\main\AndroidManifest.xml'),
        (Join-Path $TemplateApp 'src\main\res\values\styles.xml'),
        (Join-Path $TemplateApp 'src\main\java\com\jivyzn\roverqnn\MainActivity.java'),
        (Join-Path $TemplateApp 'src\main\java\com\jivyzn\roverqnn\QnnModelRunner.java'),
        (Join-Path $TemplateApp 'src\main\java\com\jivyzn\roverqnn\YuvConverter.java'),
        (Join-Path $TemplateApp 'src\main\java\com\jivyzn\roverqnn\OverlayView.java'),
        (Join-Path $TemplateApp 'src\main\java\com\jivyzn\roverqnn\Detection.java')
    )
    foreach ($path in $required) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            Fail ('Package is incomplete. Missing: {0}' -f $path)
        }
    }
    Write-Host ('Package root verified: {0}' -f $Root) -ForegroundColor Green
    Write-Host ('Native template verified: {0}' -f $TemplateApp) -ForegroundColor Green
}

function Find-Flutter {
    $candidates = @(
        (Join-Path $env:USERPROFILE 'Downloads\Rover_QNN_Detector_FINAL\Rover_QNN_Detector_FINAL\.toolchain\flutter\bin\flutter.bat'),
        (Join-Path $env:USERPROFILE 'Downloads\Rover_QNN_Detector_DEPLOY_V5\Rover_QNN_Detector_DEPLOY_V5\.toolchain\flutter\bin\flutter.bat'),
        'C:\src\flutter\bin\flutter.bat'
    )
    $fromPath = Get-Command flutter.bat -ErrorAction SilentlyContinue
    if (-not $fromPath) { $fromPath = Get-Command flutter -ErrorAction SilentlyContinue }
    if ($fromPath) { $candidates += $fromPath.Source }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            try {
                & $candidate '--version' | Out-Null
                if ($LASTEXITCODE -eq 0) { return $candidate }
            } catch {}
        }
    }
    return $null
}

function Find-Adb {
    $command = Get-Command adb.exe -ErrorAction SilentlyContinue
    if (-not $command) { $command = Get-Command adb -ErrorAction SilentlyContinue }
    if ($command) { return $command.Source }
    $candidates = @()
    if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe') }
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe') }
    return ($candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1)
}

function Set-JavaHomeIfNeeded {
    try {
        & java '-version' 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) { return }
    } catch {}
    $jbr = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path -LiteralPath (Join-Path $jbr 'bin\java.exe')) {
        $env:JAVA_HOME = $jbr
        $env:Path = (Join-Path $jbr 'bin') + ';' + $env:Path
    }
}

function Inspect-ModelBestEffort {
    Remove-Item -LiteralPath $ModelReport -Force -ErrorAction SilentlyContinue
    try {
        # no WSL needed here. after the SHA check I only scan for the two strings I care about
        # so I can catch the wrong ONNX before wasting a full Android build
        $bytes = [System.IO.File]::ReadAllBytes($ModelSource)
        $ascii = [System.Text.Encoding]::ASCII.GetString($bytes)
        $hasEpContext = $ascii.Contains('EPContext')
        $hasQnnProvider = $ascii.Contains('QNNExecutionProvider')
        $sdkCandidates = @([regex]::Matches($ascii, '2\.(?:3[0-9]|4[0-9])\.[0-9]+(?:\.[0-9]+)?') | ForEach-Object { $_.Value } | Select-Object -Unique)
        $report = @(
            ('Path: {0}' -f $ModelSource),
            ('Bytes: {0}' -f $bytes.Length),
            ('SHA-256: {0}' -f (Get-FileHash -LiteralPath $ModelSource -Algorithm SHA256).Hash),
            ('Contains EPContext: {0}' -f $hasEpContext),
            ('Contains QNNExecutionProvider: {0}' -f $hasQnnProvider),
            ('Embedded SDK-like version strings: {0}' -f ($(if ($sdkCandidates.Count -gt 0) { $sdkCandidates -join ', ' } else { '<none found by ASCII scan>' }))),
            'Target expected by this package: precompiled Ultralytics QNN context for HTP v75 / Snapdragon 8 Gen 3.',
            'Runtime family expected by V21: Qualcomm QNN Plugin EP 2.x (not Provider Bridge QNN 1.x).',
            'V21 TURBO: reusable input/output tensors, CameraX native bitmap conversion, no frameCounter throttle.'
        )
        $report | Set-Content -LiteralPath $ModelReport -Encoding ASCII
        $report | ForEach-Object { Write-Host $_ -ForegroundColor DarkGray }
        if (-not $hasEpContext) { Fail 'The verified model does not contain EPContext; V21 intentionally deploys the Ultralytics precompiled QNN context model.' }
        if (-not $hasQnnProvider) { Fail 'The verified model does not identify QNNExecutionProvider; refusing to package the wrong ONNX.' }
    } catch {
        if ($_.Exception.Message -like 'The verified model*') { throw }
        ('Binary model inspection unavailable: {0}' -f $_.Exception.Message) | Set-Content -LiteralPath $ModelReport -Encoding ASCII
        Write-Host 'Binary model inspection unavailable; SHA-256 verification still passed.' -ForegroundColor Yellow
    }
}

function Copy-Directory([string]$Source, [string]$Destination) {
    if (-not (Test-Path -LiteralPath $Source -PathType Container)) {
        Fail ('Source folder does not exist: {0}' -f $Source)
    }
    if (Test-Path -LiteralPath $Destination) { Remove-Item -LiteralPath $Destination -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    Get-ChildItem -LiteralPath $Source -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $Destination -Recurse -Force
    }
}

function Get-AuthorizedDevice([string]$Adb) {
    & $Adb 'start-server' | Out-Null
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $lines = & $Adb 'devices'
        $devices = @($lines | Select-Object -Skip 1 | ForEach-Object {
            if ($_ -match '^([^\s]+)\s+device$') { $matches[1] }
        })
        if ($devices.Count -gt 0) { return $devices[0] }
        if ($lines -match '\sunauthorized$') {
            Write-Host 'Phone detected but USB debugging is unauthorized. Unlock it and tap Allow.' -ForegroundColor Yellow
        }
        Start-Sleep -Seconds 1
    }
    return $null
}

function Tap-StartQnn([string]$Adb, [string]$Device) {
    $remote = '/sdcard/rover_native_ui.xml'
    $local = Join-Path $Root 'rover_native_ui.xml'
    Remove-Item -LiteralPath $local -Force -ErrorAction SilentlyContinue
    & $Adb '-s' $Device 'shell' 'uiautomator' 'dump' $remote | Out-Null
    & $Adb '-s' $Device 'pull' $remote $local | Out-Null
    if (Test-Path -LiteralPath $local) {
        try {
            [xml]$xml = Get-Content -LiteralPath $local -Raw
            $node = $xml.SelectSingleNode("//*[@text='START QNN']")
            if ($node -and ([string]$node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')) {
                $x = [int](([int]$matches[1] + [int]$matches[3]) / 2)
                $y = [int](([int]$matches[2] + [int]$matches[4]) / 2)
                & $Adb '-s' $Device 'shell' 'input' 'tap' $x $y | Out-Null
                Write-Host ('Tapped START QNN at {0},{1}.' -f $x, $y) -ForegroundColor Cyan
                return
            }
        } catch {}
    }
    Write-Host 'Could not auto-tap START QNN. Tap it once on the phone now.' -ForegroundColor Yellow
}

function Capture-PhoneLog([string]$Adb, [string]$Device) {
    $lines = & $Adb '-s' $Device 'logcat' '-d' '-v' 'threadtime'
    $lines | Set-Content -LiteralPath $PhoneLog -Encoding UTF8
    '' | Add-Content -LiteralPath $PhoneLog
    '===== PACKAGE STATE =====' | Add-Content -LiteralPath $PhoneLog
    (& $Adb '-s' $Device 'shell' 'dumpsys' 'activity' 'processes' $Package 2>&1) | Add-Content -LiteralPath $PhoneLog
    '' | Add-Content -LiteralPath $PhoneLog
    '===== LAST ANR =====' | Add-Content -LiteralPath $PhoneLog
    (& $Adb '-s' $Device 'shell' 'dumpsys' 'activity' 'lastanr' 2>&1) | Add-Content -LiteralPath $PhoneLog
}

try {
    Write-Host 'ROVER QNN DETECTOR - NATIVE V21' -ForegroundColor Cyan
    Write-Host '================================' -ForegroundColor Cyan
    Write-Host 'Flutter UI/runtime: REMOVED' -ForegroundColor Green
    Write-Host 'Flutter is used only to generate its already-working Android Gradle wrapper.' -ForegroundColor DarkGray
    Write-Host 'QNN session and inference: dedicated background worker' -ForegroundColor Green
    Write-Host 'Architecture: Qualcomm QNN PLUGIN EP (same EP family as current Ultralytics QNN export)' -ForegroundColor Green
    Write-Host 'Android requirements: libcdsprpc + pre-ORT ADSP_LIBRARY_PATH' -ForegroundColor Green
    Write-Host '2.4.0 Android discovery regression bypass: ORT_QNN_ENABLE_CPU_BACKEND=1, then FORCE backend_type=htp' -ForegroundColor Yellow
    Write-Host 'Runtime: plugin 2.4.0 + ORT Android 1.24.3 + QNN runtime 2.48.0' -ForegroundColor Green
    Write-Host 'Model: precompiled v75 EPContext best_qnn(1).onnx (Snapdragon 8 Gen 3)' -ForegroundColor Green
    Write-Host 'Decoder: YOLO26 QNN raw [1,12,11109] xywh+8 class scores + class-aware NMS' -ForegroundColor Green
    Write-Host 'TURBO: every-latest-frame scheduling + reusable tensors/buffers + pinned output + native CameraX conversion' -ForegroundColor Green
    Write-Host 'APK slim: proven QNN context only; 78 MB FP32 fallback removed' -ForegroundColor Green

    Assert-PackageLayout

    $gradleText = Get-Content -LiteralPath (Join-Path $TemplateApp 'build.gradle') -Raw
    foreach ($requiredRuntime in @('onnxruntime-android:1.24.3', 'onnxruntime-android-qnn:2.4.0', 'qnn-runtime:2.48.0')) {
        if ($gradleText -notmatch [regex]::Escape($requiredRuntime)) { Fail ('Missing required plugin runtime: {0}' -f $requiredRuntime) }
    }
    if ($gradleText -match 'com.microsoft.onnxruntime:onnxruntime-android-qnn') { Fail 'V21 must not use the older built-in Provider Bridge QNN AAR.' }
    if ($gradleText -notmatch 'useLegacyPackaging true') { Fail 'QNN requires useLegacyPackaging=true so DSP libraries are extracted.' }

    $manifestText = Get-Content -LiteralPath (Join-Path $TemplateApp 'src\main\AndroidManifest.xml') -Raw
    if ($manifestText -notmatch 'libcdsprpc\.so') { Fail 'Manifest is missing Qualcomm-required libcdsprpc.so uses-native-library declaration.' }

    $mainText = Get-Content -LiteralPath (Join-Path $TemplateApp 'src\main\java\com\jivyzn\roverqnn\MainActivity.java') -Raw
    if ($mainText -notmatch 'ADSP_LIBRARY_PATH') { Fail 'MainActivity must set ADSP_LIBRARY_PATH before ORT initialization.' }
    if ($mainText -notmatch 'ORT_QNN_ENABLE_CPU_BACKEND') { Fail 'MainActivity must enable the QNN 2.4.0 Android discovery bypass before ORT initialization.' }
    $runnerText = Get-Content -LiteralPath (Join-Path $TemplateApp 'src\main\java\com\jivyzn\roverqnn\QnnModelRunner.java') -Raw
    foreach ($requiredToken in @('OrtEpDevice', 'registerExecutionProviderLibrary', 'getEpDevices', 'ORT_QNN_ENABLE_CPU_BACKEND', 'backend_type', 'htp')) {
        if ($runnerText -notmatch [regex]::Escape($requiredToken)) { Fail ('Runner is missing official Plugin EP step: {0}' -f $requiredToken) }
    }
    if ($runnerText -match 'options\.addQnn|sessionOptions\.addQnn') { Fail 'V21 must not use the Provider Bridge addQnn() path.' }
    foreach ($decoderToken in @('decodeTraditional', 'classAwareNms', '4 + LABELS.length', 'NMS_IOU', 'pinnedOutputs', 'prepareInput', 'rpc_control_latency')) {
        if ($runnerText -notmatch [regex]::Escape($decoderToken)) { Fail ('V21 raw YOLO decoder is missing token: {0}' -f $decoderToken) }
    }

    if (-not (Test-Path -LiteralPath $ModelSource -PathType Leaf)) { Fail ('Model not found: {0}' -f $ModelSource) }
    $model = Get-Item -LiteralPath $ModelSource
    $hash = (Get-FileHash -LiteralPath $ModelSource -Algorithm SHA256).Hash
    if ($hash -ne $ExpectedHash) { Fail ('Model SHA-256 mismatch. Expected {0}, got {1}' -f $ExpectedHash, $hash) }
    Write-Host ('Model: {0}' -f $ModelSource) -ForegroundColor Green
    Write-Host ('Size: {0:N2} MB' -f ($model.Length / 1MB)) -ForegroundColor Green
    Write-Host ('SHA-256 verified: {0}' -f $hash) -ForegroundColor Green
    Inspect-ModelBestEffort

    $Flutter = Find-Flutter
    if (-not $Flutter) { Fail 'No working Flutter installation was found for Gradle bootstrap.' }
    Write-Host ('Using existing Flutter for Gradle bootstrap: {0}' -f $Flutter) -ForegroundColor DarkGray
    Set-JavaHomeIfNeeded

    Write-Host ('Using fresh bootstrap directory: {0}' -f $Bootstrap) -ForegroundColor DarkGray
    Run-Checked $Flutter 'create' '--no-pub' '--platforms=android' '--android-language=java' '--org' 'com.jivyzn' '--project-name' 'rover_native_bootstrap' $Bootstrap

    if (-not (Test-Path -LiteralPath $Android -PathType Container)) { Fail 'Flutter did not create the Android bootstrap folder.' }
    if (-not (Test-Path -LiteralPath (Join-Path $Android 'gradlew.bat') -PathType Leaf)) { Fail 'Flutter did not create gradlew.bat.' }
    if (-not ((Test-Path -LiteralPath (Join-Path $Android 'settings.gradle.kts')) -or (Test-Path -LiteralPath (Join-Path $Android 'settings.gradle')))) {
        Fail 'Flutter did not create Android Gradle settings.'
    }

    # Keep the complete generated Android root intact. Replace only the app module.
    # This preserves the exact AGP/Gradle/JDK configuration that already works on this laptop.
    Copy-Directory $TemplateApp (Join-Path $Android 'app')
    Write-Host 'Native app module installed into the intact Flutter Android bootstrap.' -ForegroundColor Green

    $assetDir = Join-Path $Android 'app\src\main\assets'
    New-Item -ItemType Directory -Force -Path $assetDir | Out-Null
    $assetModel = Join-Path $assetDir 'rover_detector_qnn.onnx'
    Copy-Item -LiteralPath $ModelSource -Destination $assetModel -Force
    $assetHash = (Get-FileHash -LiteralPath $assetModel -Algorithm SHA256).Hash
    if ($assetHash -ne $ExpectedHash) { Fail 'Bundled model hash verification failed.' }
    Write-Host 'Precompiled QNN model embedded into native Android assets and verified.' -ForegroundColor Green

    $Gradle = Join-Path $Android 'gradlew.bat'
    Push-Location $Android
    try {
        Run-Checked $Gradle '--stop'
        Run-Checked $Gradle '--no-daemon' '--stacktrace' ':app:assembleRelease'
    } finally {
        Pop-Location
    }

    $builtCandidates = @(
        (Join-Path $Bootstrap 'build\app\outputs\apk\release\app-release.apk'),
        (Join-Path $Android 'app\build\outputs\apk\release\app-release.apk'),
        (Join-Path $Android 'app\build\outputs\apk\release\app-release-unsigned.apk')
    )
    $built = $builtCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if (-not $built) {
        $built = Get-ChildItem -LiteralPath $Bootstrap -Recurse -File -Filter '*.apk' -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match '[\\/]outputs[\\/]apk[\\/]release[\\/]' -and $_.Name -notmatch 'androidTest|unaligned' } |
            Sort-Object LastWriteTime -Descending | Select-Object -ExpandProperty FullName -First 1
    }
    if (-not $built) { Fail 'Gradle completed but no release APK was found.' }
    Write-Host ('Gradle APK located: {0}' -f $built) -ForegroundColor Green
    Copy-Item -LiteralPath $built -Destination $FinalApk -Force
    $apkInfo = Get-Item -LiteralPath $FinalApk
    $apkHash = (Get-FileHash -LiteralPath $FinalApk -Algorithm SHA256).Hash
    Write-Host ''
    Write-Host ('APK CREATED: {0}' -f $FinalApk) -ForegroundColor Green
    Write-Host ('APK size: {0:N2} MB' -f ($apkInfo.Length / 1MB)) -ForegroundColor Green
    Write-Host ('APK SHA-256: {0}' -f $apkHash) -ForegroundColor DarkGray

    $Adb = Find-Adb
    if (-not $Adb) { Fail ('APK built, but adb was not found. Install manually: {0}' -f $FinalApk) }
    $Device = Get-AuthorizedDevice $Adb
    if (-not $Device) { Fail ('APK built, but no authorized phone was found. APK: {0}' -f $FinalApk) }
    Write-Host ('Installing on: {0}' -f $Device) -ForegroundColor Cyan

    & $Adb '-s' $Device 'uninstall' 'com.jivyzn.rover_qnn_detector' | Out-Null
    Run-Checked $Adb '-s' $Device 'install' '-r' $FinalApk
    & $Adb '-s' $Device 'shell' 'pm' 'grant' $Package 'android.permission.CAMERA' | Out-Null
    & $Adb '-s' $Device 'logcat' '-c'
    Run-Checked $Adb '-s' $Device 'shell' 'am' 'start' '-n' ($Package + '/.MainActivity')

    Write-Host 'Unlock the phone. The native camera should appear immediately.' -ForegroundColor Yellow
    Start-Sleep -Seconds 5
    Tap-StartQnn $Adb $Device
    Write-Host 'Checking QNN initialization and first inference...' -ForegroundColor Cyan
    for ($i = 0; $i -lt 15; $i++) {
        Start-Sleep -Seconds 2
        $probe = (& $Adb '-s' $Device 'logcat' '-d' '-v' 'threadtime' 'RoverQNN:I' '*:S' 2>&1) -join "`n"
        if ($probe -match 'QNN session ready|QNN TURBO|INFERENCE ERROR|QNN initialization failed|FATAL EXCEPTION') { break }
    }
    Capture-PhoneLog $Adb $Device

    $phoneText = Get-Content -LiteralPath $PhoneLog -Raw
    $hasBypass = $phoneText -match 'QNN DISCOVERY BYPASS ACTIVE'
    $hasHtpEvidence = $phoneText -match 'QnnHtp|libQnnHtpV[0-9]+Skel|remote_handle64_open|open thru HAL|backend_type.?htp|FORCING QNN HTP/NPU'

    if ($phoneText -match 'Expected \[1,N,6\]|Unsupported YOLO detect output') {
        Write-Host 'OLD DECODER ERROR DETECTED. V21 should not emit this; verify the newly built APK was installed.' -ForegroundColor Red
        Write-Host ('Diagnostic: {0}' -f $PhoneLog) -ForegroundColor Yellow
    } elseif ($phoneText -match 'QNN session ready') {
        Write-Host 'QNN SESSION READY confirmed in the phone log.' -ForegroundColor Green
        if ($hasBypass) { Write-Host 'Android 2.4.0 discovery bypass was active.' -ForegroundColor Green }
        if ($hasHtpEvidence) {
            Write-Host 'HTP/NPU evidence found in logcat (QnnHtp/FastRPC/backend_type=htp).' -ForegroundColor Green
        } else {
            Write-Host 'Session is ready and backend_type=htp was forced, but logcat did not contain an additional HTP marker.' -ForegroundColor Yellow
        }
    } elseif ($phoneText -match 'QNN 2.4.0 discovery bypass failed') {
        Write-Host 'Even the upstream-validated Android discovery bypass exposed no QNN device.' -ForegroundColor Red
        Write-Host ('Diagnostic: {0}' -f $PhoneLog) -ForegroundColor Yellow
    } elseif ($phoneText -match "EPContext node generated by 'QNNExecutionProvider' is not compatible") {
        Write-Host 'The Plugin EP is selectable, but this EPContext was not claimed. Check verbose QNN backend lines.' -ForegroundColor Red
        Write-Host ('Diagnostic: {0}' -f $PhoneLog) -ForegroundColor Yellow
    } elseif ($phoneText -match 'QNN initialization failed') {
        Write-Host 'The native app remained responsive, but QNN returned an explicit initialization error.' -ForegroundColor Red
        Write-Host ('Diagnostic: {0}' -f $PhoneLog) -ForegroundColor Yellow
    } elseif ($phoneText -match 'FATAL EXCEPTION|Fatal signal|SIGABRT|SIGSEGV') {
        Write-Host 'A native crash was captured. The diagnostic contains the exact library failure.' -ForegroundColor Red
        Write-Host ('Diagnostic: {0}' -f $PhoneLog) -ForegroundColor Yellow
    } else {
        Write-Host 'QNN did not report ready during the deployment check. The UI should remain responsive because QNN is off the main thread.' -ForegroundColor Yellow
        Write-Host ('Diagnostic: {0}' -f $PhoneLog) -ForegroundColor Yellow
    }

    Write-Host ''
    Write-Host 'NATIVE V21 DEPLOYED.' -ForegroundColor Green
    Stop-Transcript | Out-Null
    exit 0
} catch {
    Fail $_.Exception.Message
}
