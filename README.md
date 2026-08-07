# Rover QNN Detector — Snapdragon NPU

A native Android YOLO26 detector I built for my rover, running my custom 8-class model on the **Snapdragon 8 Gen 3 Hexagon HTP/NPU** in a Samsung Galaxy S24 Ultra.

The goal was simple: get the model off CPU/GPU, make the camera pipeline properly real-time, and keep the app lightweight enough to actually use on the rover.

This repo is the cleaned-up **V21 TURBO** build. The QNN/NPU path is the same one that finally worked; the optimisations are around it so I am not breaking the stable part just to chase FPS.

## What it detects

The model has 8 classes:

- hammer
- tennis ball
- traffic cone
- black balloon
- blue balloon
- pink balloon
- white balloon
- yellow balloon

## Hardware / target

- Samsung Galaxy S24 Ultra
- Snapdragon 8 Gen 3 for Galaxy
- Hexagon HTP v75
- Android arm64-v8a
- rear camera through CameraX

## Runtime stack

- Ultralytics YOLO26 QNN context
- ONNX Runtime Android 1.24.3
- Qualcomm QNN Plugin EP 2.4.0
- Qualcomm QNN runtime 2.48.0
- `backend_type=htp`
- `htp_performance_mode=burst`

The model output is the raw YOLO26 QNN head:

```text
[1, 12, 11109]
```

For 8 classes that is:

```text
4 box values + 8 class scores = 12 channels
```

The app decodes `xywh`, picks the strongest class score, converts to `xyxy`, reverses letterboxing, then runs class-aware NMS.

## Why the QNN setup looks weird

QNN Plugin EP 2.4.0 has an Android ARM64 device-discovery regression. The provider registers, but a normal Android app cannot enumerate `/dev/fastrpc-cdsp*` because of SELinux, so ORT sees no selectable QNN device.

The temporary bridge used here is:

```text
ORT_QNN_ENABLE_CPU_BACKEND=1
```

That is **only used to expose a selectable QNN EP device**. Inference is still explicitly forced to:

```text
backend_type=htp
```

so execution goes through the Qualcomm HTP/NPU path, not the QNN CPU backend.

Qualcomm fixed the Android discovery bug upstream on 7 August 2026. Once that fix is available in the Maven Android artifact, this discovery bridge can be removed. More detail is in [`docs/QNN_ANDROID_NOTES.md`](docs/QNN_ANDROID_NOTES.md).

## V21 TURBO changes

V20 was working, so I left the actual QNN session path alone and optimised the stuff around it:

- removed the old every-third-frame throttle
- `KEEP_ONLY_LATEST + inferenceBusy` always works on the newest available frame
- reusable 736×736 preprocessing bitmap
- reusable pixel buffer
- reusable direct float input buffer
- reusable `OnnxTensor` input
- reusable pinned output tensor when ORT accepts it
- automatic fallback if pinned output is rejected
- `FloatBuffer` output reads instead of nested Java arrays
- CameraX `ImageProxy.toBitmap()` instead of a slow Java YUV loop
- class-aware NMS for the raw `[1,12,11109]` QNN output
- reduced status UI updates while boxes still update at inference rate
- `rpc_control_latency=100`
- `htp_performance_mode=burst`
- no unused 78 MB FP32 fallback model in the APK

The app shows timing live:

```text
prep | infer | post | total ms | FPS
```

so I can see whether the next bottleneck is preprocessing, HTP inference or post-processing instead of guessing.

## Project layout

```text
.
├── template/app/                  native Android app source
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/jivyzn/roverqnn/
│       │   ├── MainActivity.java
│       │   ├── QnnModelRunner.java
│       │   ├── OverlayView.java
│       │   ├── Detection.java
│       │   └── YuvConverter.java
│       └── res/values/styles.xml
├── models/                        local QNN model goes here
├── tools/
│   ├── build_native.ps1
│   ├── get_native_log.ps1
│   ├── inspect_model.py
│   ├── prep_for_github.ps1
│   └── push_to_github.ps1
├── RUN_NATIVE_V21.cmd
├── GET_NATIVE_LOG.cmd
├── PREP_FOR_GITHUB.cmd
└── PUSH_TO_GITHUB.cmd
```

## Model

My working QNN context is:

```text
best_qnn(1).onnx
SHA-256: 9DB8530FCB77A057E0E8FABD93AA3BDB8D8D70774FD6944B6E65ABF2B3E3A58A
```

`PREP_FOR_GITHUB.cmd` looks for it in:

```text
%USERPROFILE%\Downloads\best_qnn(1).onnx
```

and copies it to:

```text
models\best_qnn.onnx
```

The build script prefers the repo copy, so after that the project is self-contained.

## Build + install

### First time

1. Install Android Studio / Android SDK.
2. Have Flutter available. Flutter is only used to create the known-good Android Gradle bootstrap; the app itself is native Java.
3. Enable Developer Options + USB debugging on the phone.
4. Put the QNN model in `models/best_qnn.onnx`, or keep the original file in Downloads.
5. Run:

```bat
RUN_NATIVE_V21.cmd
```

The script builds the APK, installs it with ADB and launches the app.

### Logs

```bat
GET_NATIVE_LOG.cmd
```

This pulls the useful Android/QNN log so I can check HTP/FastRPC behaviour without digging through all of logcat.

## Upload this repo to GitHub

I included a terminal uploader because I wanted the repo setup to be one command too.

First:

```bat
PREP_FOR_GITHUB.cmd
```

Then:

```bat
PUSH_TO_GITHUB.cmd
```

The upload script checks `git` and `gh`, initialises the repo, commits everything, creates the GitHub repo if needed and pushes `main`.

Default repo name:

```text
Rover-QNN-Detector-S24U
```

You can change it when the script asks.

## Notes

This is a project build, not a general-purpose Android inference SDK. A few decisions are intentionally specific to my device/model, especially HTP v75, the model hash and the current QNN 2.4.0 Android discovery workaround.

If Qualcomm ships the fixed Android QNN Maven package, the first cleanup I would do is remove the `ORT_QNN_ENABLE_CPU_BACKEND` discovery bridge and use the real NPU `OrtEpDevice` directly.

## Author

**Jivesh Ramnath**

Built as part of my rover / computer-vision work.
