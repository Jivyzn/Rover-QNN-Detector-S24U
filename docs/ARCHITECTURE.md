# Architecture

```text
CameraX rear camera
        |
        v
ImageProxy.toBitmap()
        |
        v
736 x 736 letterbox + RGB float input
        |
        v
reused direct FloatBuffer / OnnxTensor
        |
        v
ONNX Runtime
        |
        v
Qualcomm QNN Plugin EP
        |
        v
backend_type=htp
        |
        v
FastRPC / Android HAL
        |
        v
Hexagon HTP v75 (NPU)
        |
        v
raw YOLO26 output [1,12,11109]
        |
        v
xywh + 8 class scores
        |
        v
confidence filter + class-aware NMS
        |
        v
OverlayView
```

## Threading

- CameraX analysis uses `STRATEGY_KEEP_ONLY_LATEST`.
- `inferenceBusy` stops inference jobs from stacking up.
- As soon as HTP is free, the newest available frame is used.
- QNN inference runs away from the UI thread.
- Box updates happen at inference rate; status text is throttled so UI work does not become the bottleneck.

## Why buffers are reused

The earlier working build was wasting time allocating multi-megabyte buffers every frame. V21 keeps the large preprocessing and tensor buffers alive and just rewinds/refills them. That cuts GC pressure and keeps the NPU fed more consistently.
