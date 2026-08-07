# Release notes

## V21 TURBO

This is the first version I am treating as the clean GitHub release.

### Working

- native Android camera preview
- custom 8-class YOLO26 model
- Qualcomm QNN Plugin EP
- Snapdragon 8 Gen 3 HTP/NPU execution path
- raw QNN `[1,12,11109]` decoder
- class-aware NMS
- live boxes, labels, confidence, object count and FPS

### Performance cleanup from V20

- removed every-third-frame skip
- reused preprocessing buffers
- reused ORT input tensor
- pinned output path with safe fallback
- direct `FloatBuffer` output decoding
- CameraX native bitmap conversion
- lower UI update overhead
- removed unused FP32 model from APK
- unique build bootstrap folders so old generated files do not break rebuilds

### Known temporary workaround

QNN Plugin EP 2.4.0 Android device discovery is bridged with `ORT_QNN_ENABLE_CPU_BACKEND=1`, while the session explicitly forces `backend_type=htp`. Qualcomm has already fixed the discovery bug upstream; the bridge can go once the fixed Android artifact is published.
