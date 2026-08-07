# RoverCam model training

This is the training record I want attached to the public RoverCam project.

## Why I trained it

I developed RoverCam for the **Cars4Mars African Rover Challenge 2026** as the computer-vision system for the Martian Mechanics rover.

The model was built for two linked jobs:

1. detect and localise the competition objects in the camera feed;
2. feed those detections into the rover's autonomous navigation logic.

The eight classes are hammer, tennis ball, traffic cone, black balloon, blue balloon, pink balloon, white balloon and yellow balloon.

## Local training

I trained this model **locally from scratch on a custom dataset** rather than using a cloud training service.

The final local training cycle took **about 35 hours**.

### Hardware

| Component | Hardware |
| --- | --- |
| GPU | NVIDIA GeForce RTX 3050 Laptop GPU |
| VRAM | 4 GB / 4096 MiB |
| CPU | AMD Ryzen 5 6600H |
| System RAM | 32 GB DDR5 |
| Host OS | Windows |
| Training environment | WSL2 Ubuntu 22.04 |
| CUDA | Enabled |
| Mixed precision | AMP enabled |

## Final model

| Specification | Value |
| --- | --- |
| Model | YOLO26m |
| Task | Object detection |
| Classes | 8 |
| Input | 736 × 736 px |
| Starting weights | From scratch (`pretrained=False`) |
| Fused parameters | 20,355,620 |
| Compute | 67.9 GFLOPs |
| Framework | Ultralytics |
| Ultralytics | 8.4.87 |
| PyTorch | 2.12.1+cu130 |
| Python | 3.10.12 |
| ONNX input | `1 × 3 × 736 × 736` |
| Raw ONNX output | `1 × 12 × 11109` |

## Dataset

The final Cars4Mars training dataset contained **15,450 training images across 8 classes**.

The final independent test set contained:

- **306 images**
- **3,123 labelled object instances**

## Training configuration

| Parameter | Value |
| --- | --- |
| Architecture | YOLO26m |
| Pretrained | `False` |
| Image size | 736 px |
| Maximum epochs | 175 |
| Batch size | 4 |
| Early-stopping patience | 25 |
| Optimizer | Auto |
| LR schedule | Cosine |
| AMP | Enabled |
| Dataset cache | RAM |
| Workers | 6 |
| Seed | 42 |
| Initial LR | 0.01 |
| Final LR factor | 0.01 |
| Momentum | 0.937 |
| Weight decay | 0.0005 |
| Warm-up | 3 epochs |
| Save interval | 10 epochs |

## Augmentation

The rover needed to work with changing lighting, distance, scale, backgrounds and camera angles, so I trained with aggressive but controlled augmentation.

| Augmentation | Setting |
| --- | ---: |
| Mosaic | 1.0 / enabled |
| Mosaic off for final | 15 epochs |
| MixUp | 0.10 |
| CutMix | 0.10 |
| Rotation | ±5° |
| Translation | 0.20 |
| Scale | 0.80 |
| Shear | 2° |
| Perspective | 0.0005 |
| Horizontal flip | 50% |
| Vertical flip | 0% |
| HSV saturation | 0.25 |
| HSV brightness/value | 0.35 |
| Random erasing | 0.40 |

## Final held-out performance

| Metric | Result |
| --- | ---: |
| Precision | **87.7%** |
| Recall | **80.7%** |
| mAP@0.50 | **88.7%** |
| mAP@0.50:0.95 | **69.5%** |
| Test images | **306** |
| Labelled instances | **3,123** |

### Per-class results

| Class | Precision | Recall | mAP50 | mAP50–95 |
| --- | ---: | ---: | ---: | ---: |
| Hammer | 91.6% | 83.7% | 90.6% | 59.5% |
| Tennis ball | 84.5% | 87.5% | 85.1% | 65.8% |
| Traffic cone | 100.0% | 97.9% | 99.5% | 86.9% |
| Black balloon | 82.8% | 73.5% | 84.2% | 60.8% |
| Blue balloon | 94.1% | 78.4% | 92.4% | 75.6% |
| Pink balloon | 80.1% | 72.1% | 82.7% | 66.2% |
| White balloon | 81.4% | 72.1% | 83.8% | 66.2% |
| Yellow balloon | 87.0% | 80.2% | 91.1% | 74.6% |

Traffic cone was the strongest class at **99.5% mAP50**.

## Local validation speed

Final PyTorch testing on the RTX 3050 recorded approximately:

- 1.1 ms preprocessing
- 32.5 ms inference
- 0.3 ms post-processing per image

## Deployment

I exported the detector to multiple formats for deployment, including ONNX, TFLite/LiteRT and OpenVINO. The raw ONNX stays portable for the browser demo, while the QNN build is the Snapdragon-targeted version used by the Android app.
