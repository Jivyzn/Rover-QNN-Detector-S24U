# Browser model

`best.onnx` is the raw portable ONNX export used by the GitHub Pages demo.

The final updater copies it automatically from:

```text
C:\Users\jivyz\Downloads\best.onnx
```

The updater also writes `MODEL_INFO.txt` with the exact file size and SHA-256 that was committed.

This is separate from the Snapdragon-specific QNN model at `../../models/best_qnn.onnx`.
