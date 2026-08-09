# RoverCam model files

This directory contains detector exports used by the RoverCam / Cars4Mars computer-vision project.

## RoverCam26m - retrained model

**RoverCam26m** is a retrained YOLO26m detector with higher reported evaluation metrics than the earlier public checkpoint.

### Reported retrained-run metrics

- Precision: **93.3%**
- Recall: **84.5%**
- mAP@0.50: **89.8%**
- mAP@0.50:0.95: **79.7%**

Previous repository held-out figures: 87.7% precision, 80.7% recall, 88.7% mAP50 and 69.5% mAP50-95.

Reported difference: +5.6 percentage points precision, +3.8 recall, +1.1 mAP50 and +10.2 mAP50-95. Compare these directly only when both checkpoints are evaluated on the same split.

### Files

- `rovercam26m.pt` - retrained PyTorch checkpoint
- `rovercam26m_qnn.onnx` - Qualcomm QNN / ONNX export for Snapdragon deployment work

QNN file size: **22.50 MB**

QNN SHA-256: `CA900E82F2616FB5E1F52F006219E4E211E9A57E43D80EE9881EFBB6057FB1E6`

PyTorch file size: **42.04 MB**

PyTorch SHA-256: `62F32D05922382FDE546C46DE54D22DA11D08633FE5BC3BA6E1F02A4BEE2DE98`

### Ultralytics

Public model page:
https://platform.ultralytics.com/jivesh-ramnath/rovercam/rovercam26m

Training view:
https://platform.ultralytics.com/jivesh-ramnath/rovercam/rovercam26m?tab=train

## Original QNN deployment model

The existing `best_qnn.onnx` is retained as the original verified Android deployment model.

Original expected SHA-256: `9DB8530FCB77A057E0E8FABD93AA3BDB8D8D70774FD6944B6E65ABF2B3E3A58A`

The retrained files are added as additional model versions. This upload does **not** silently replace the model currently referenced by the Android packaging/build path.
