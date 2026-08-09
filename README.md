# RoverCam — YOLO26m Rover Vision + Snapdragon QNN

This is my rover computer-vision project built around a custom **8-class YOLO26m detector**.

I trained the CNN **locally from scratch on my own custom dataset** for the Cars4Mars rover project. The final training run took **about 35 hours** on my laptop using an **NVIDIA RTX 3050 Laptop GPU with 4 GB VRAM** through WSL2 Ubuntu, CUDA and mixed precision.

The repo has two deployment paths:

1. **Raw ONNX** — portable version that anyone can test from the GitHub Pages browser demo.
2. **Qualcomm QNN** — the Android build I use on my Samsung Galaxy S24 Ultra, running the detector through the Snapdragon 8 Gen 3 Hexagon HTP/NPU.

## Live browser demo

**https://jivyzn.github.io/Rover-QNN-Detector-S24U/**

Upload an image and run the raw ONNX model directly in your browser. The image stays local to the browser; the demo does not send it to a server.

Ultralytics model page:

**https://platform.ultralytics.com/jivesh-ramnath/rovercam**

## What RoverCam detects

The model has 8 classes:

0. **hammer**
1. **tennis ball**
2. **traffic cone**
3. **black balloon**
4. **blue balloon**
5. **pink balloon**
6. **white balloon**
7. **yellow balloon**

The detector was built for the vision/autonomy side of the **2026 Cars4Mars African Rover Challenge**. The detections are used for object localisation and for the autonomous target sequence used by the rover.

## Final model

| Parameter | Final setup |
| --- | --- |
| Architecture | **YOLO26m** |
| Task | Object detection |
| Training | **Locally, from scratch** (`pretrained=False`) |
| Custom dataset | **15,450 training images** |
| Classes | **8** |
| Input resolution | **736 × 736 px** |
| Fused parameters | **20,355,620** |
| Compute | **67.9 GFLOPs** |
| Training time | **~35 hours** |
| Local GPU | **NVIDIA RTX 3050 Laptop GPU, 4 GB VRAM** |
| CPU | AMD Ryzen 5 6600H |
| RAM | 32 GB DDR5 |
| Training environment | WSL2 Ubuntu 22.04 + CUDA + AMP |
| Framework | Ultralytics / PyTorch |
| Browser `best.onnx` input | `4 × 3 × 736 × 736` (static batch 4) |
| Raw ONNX output | `batch × 12 × 11109` |

## Final held-out test metrics

These are the results I use for the public project because they come from the **final held-out test**, not an earlier validation/smoke run.

| Metric | Result |
| --- | ---: |
| **Precision** | **87.7%** |
| **Recall** | **80.7%** |
| **mAP@0.50** | **88.7%** |
| **mAP@0.50:0.95** | **69.5%** |
| Test images | **306** |
| Labelled instances | **3,123** |

### Per-class test performance

| Class | Precision | Recall | mAP50 | mAP50–95 |
| --- | ---: | ---: | ---: | ---: |
| Hammer | 91.6% | 83.7% | **90.6%** | 59.5% |
| Tennis ball | 84.5% | **87.5%** | **85.1%** | 65.8% |
| Traffic cone | **100.0%** | **97.9%** | **99.5%** | **86.9%** |
| Black balloon | 82.8% | 73.5% | **84.2%** | 60.8% |
| Blue balloon | **94.1%** | 78.4% | **92.4%** | 75.6% |
| Pink balloon | 80.1% | 72.1% | **82.7%** | 66.2% |
| White balloon | 81.4% | 72.1% | **83.8%** | 66.2% |
| Yellow balloon | 87.0% | 80.2% | **91.1%** | 74.6% |

Traffic cone was the strongest class at **99.5% mAP50**. The balloon classes were harder because lighting, shadows, colour and overlap have a bigger effect on them.

## Training setup

The final scratch-training configuration was built around:

- YOLO26m
- `pretrained=False`
- 736 px input
- batch size 4
- maximum 175 epochs
- early-stopping patience 25
- cosine learning-rate schedule
- AMP enabled
- dataset cached in RAM
- 6 workers
- seed 42
- initial LR 0.01
- final LR factor 0.01
- momentum 0.937
- weight decay 0.0005
- 3 warm-up epochs

I used augmentation because the rover sees the same objects under very different distances, backgrounds, camera angles and outdoor lighting. The training pipeline used Mosaic, MixUp, CutMix, translation, scale, small rotation/shear/perspective changes, horizontal flipping, HSV variation and random erasing.

More detail is in [`docs/MODEL_TRAINING.md`](docs/MODEL_TRAINING.md).

## Browser ONNX demo

The current raw `best.onnx` is a **static batch-4 export**. The site auto-detects the input shape, tiles one uploaded image to the required batch, and decodes batch item 0.

The GitHub Pages demo loads:

```text
docs/models/best.onnx
```

through **ONNX Runtime Web**.

It supports:

- image upload / drag-and-drop
- 736 × 736 letterbox preprocessing
- confidence threshold control
- NMS IoU control
- class-aware NMS
- bounding boxes + class confidence
- inference-time readout
- detection count
- model output-shape display
- raw YOLO `[batch, 12, N]` output
- end-to-end `[batch, N, 6]` output if I swap exports later

The browser demo is the portable raw ONNX build. It does **not** pretend to run Qualcomm QNN in the browser.

## Snapdragon Android deployment

The Android side is the deployment build I made for my S24 Ultra:

- Samsung Galaxy S24 Ultra
- Snapdragon 8 Gen 3 for Galaxy
- Hexagon HTP v75
- Android arm64-v8a
- CameraX camera pipeline
- ONNX Runtime Android
- Qualcomm QNN Plugin EP
- QNN HTP backend

The QNN model in the repo is:

```text
models/best_qnn.onnx
SHA-256: 9DB8530FCB77A057E0E8FABD93AA3BDB8D8D70774FD6944B6E65ABF2B3E3A58A
```

My QNN export returns:

```text
[1, 12, 11109]
```

For 8 classes that is:

```text
4 box values + 8 class scores = 12 channels
```

The Android app decodes `xywh`, chooses the highest class score, converts to `xyxy`, reverses the letterbox transform and applies class-aware NMS.

## QNN Android issue I had to solve

The released Qualcomm QNN Plugin EP 2.4.0 had an Android ARM64 device-discovery regression. The provider registered, but Android app permissions prevented the discovery code from enumerating `/dev/fastrpc-cdsp*`.

The bridge used by this build exposes a selectable QNN EP device, while the session itself is still explicitly forced to:

```text
backend_type=htp
```

so the detector executes through the Qualcomm HTP/NPU path.

Qualcomm merged the upstream Android discovery fix on **7 August 2026**. The full debugging notes are in [`docs/QNN_ANDROID_NOTES.md`](docs/QNN_ANDROID_NOTES.md).

## V21 TURBO

Once the QNN version was working, I left the stable NPU path alone and optimised the camera/inference pipeline around it:

- removed the every-third-frame throttle
- newest-frame processing with CameraX `KEEP_ONLY_LATEST`
- reusable 736 × 736 preprocessing bitmap
- reusable pixel and direct float buffers
- reusable ONNX input tensor
- pinned reusable output tensor when supported
- fallback if pinned output is rejected
- direct `FloatBuffer` output reads
- CameraX `ImageProxy.toBitmap()` conversion
- class-aware NMS
- reduced UI-update overhead
- FastRPC warm-latency setting
- removed the unused FP32 Android fallback model

The app reports:

```text
prep | infer | post | total ms | FPS
```

## Repo layout

```text
.
├── docs/
│   ├── index.html                   GitHub Pages demo
│   ├── styles.css
│   ├── app.js
│   ├── MODEL_TRAINING.md
│   ├── QNN_ANDROID_NOTES.md
│   └── models/
│       ├── best.onnx               raw portable model
│       └── MODEL_INFO.txt           generated by updater
├── models/
│   └── best_qnn.onnx               Snapdragon QNN model
├── template/app/                    native Android source
├── tools/
├── .github/workflows/pages.yml
├── RUN_NATIVE_V21.cmd
└── GET_NATIVE_LOG.cmd
```

## Android build

Enable Developer Options + USB debugging on the S24 Ultra, then:

```bat
RUN_NATIVE_V21.cmd
```

For QNN/FastRPC logs:

```bat
GET_NATIVE_LOG.cmd
```

## Retrained model - RoverCam26m

A retrained YOLO26m checkpoint with higher reported evaluation metrics has been added under [`models/`](models/README.md) while the original verified `best_qnn.onnx` is retained for the existing Android deployment path.

Retrained metrics: **93.3% precision**, **84.5% recall**, **89.8% mAP50**, and **79.7% mAP50-95**.

Ultralytics model: **https://platform.ultralytics.com/jivesh-ramnath/rovercam/rovercam26m**

See [`models/README.md`](models/README.md) for file hashes, model-version details and the training link.
## Author

**Jivesh Ramnath**

Built as part of my Cars4Mars rover / computer-vision work.
