package com.jivyzn.roverqnn;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;
import android.os.Build;
import android.system.Os;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtEpDevice;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class QnnModelRunner implements AutoCloseable {
    private static final String TAG = "RoverQNN";
    private static final String ASSET_NAME = "rover_detector_qnn.onnx";
    private static final String QNN_EP_NAME = "QNNExecutionProvider";
    private static final String QNN_PLUGIN_LIBRARY = "libonnxruntime_providers_qnn.so";
    private static final String QNN_HTP_LIBRARY = "libQnnHtp.so";
    private static final float CONFIDENCE = 0.25f;
    private static final float NMS_IOU = 0.70f;
    private static final int MAX_DETECTIONS = 300;
    private static final Object PLUGIN_LOCK = new Object();
    private static boolean pluginRegistered = false;
    private static String registeredPluginPath = null;

    private static final String[] LABELS = {
            "hammer",
            "tennis ball",
            "traffic cone",
            "black balloon",
            "blue balloon",
            "pink balloon",
            "white balloon",
            "yellow balloon"
    };

    static final class InferenceResult {
        final List<Detection> detections;
        final int frameWidth;
        final int frameHeight;
        final long preprocessMs;
        final long inferenceMs;
        final long postprocessMs;
        final long totalMs;

        InferenceResult(List<Detection> detections, int frameWidth, int frameHeight,
                        long preprocessMs, long inferenceMs, long postprocessMs, long totalMs) {
            this.detections = detections;
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
            this.preprocessMs = preprocessMs;
            this.inferenceMs = inferenceMs;
            this.postprocessMs = postprocessMs;
            this.totalMs = totalMs;
        }
    }

    private final OrtEnvironment environment;
    private final OrtSession.SessionOptions sessionOptions;
    private final OrtSession session;
    private final String inputName;
    private final long[] inputShape;
    private final boolean nchw;
    private final int inputWidth;
    private final int inputHeight;
    private final String runtimeMode;
    private final String outputName;
    private final long[] outputShape;

    // these are big buffers, so allocate them once. rebuilding them every frame was just
    // feeding the garbage collector instead of feeding the NPU.
    private final Bitmap modelBitmap;
    private final Canvas modelCanvas;
    private final Paint modelPaint;
    private final int[] pixelBuffer;
    private final RectF modelRect = new RectF();
    private final FloatBuffer inputBuffer;
    private final OnnxTensor inputTensor;
    private final Map<String, OnnxTensor> inputFeed;
    private final FloatBuffer pinnedOutputBuffer;
    private final OnnxTensor pinnedOutputTensor;
    private final Map<String, OnnxValue> pinnedOutputs;
    private final Letterbox transform = new Letterbox();
    private boolean pinnedOutputsEnabled = true;

    QnnModelRunner(Context context) throws Exception {
        Log.i(TAG, "QNN Plugin EP initialization started on " + Thread.currentThread().getName());
        Log.i(TAG, "SoC manufacturer=" + Build.SOC_MANUFACTURER + " model=" + Build.SOC_MODEL
                + " ABI=" + java.util.Arrays.toString(Build.SUPPORTED_ABIS));
        Log.i(TAG, "Runtime: QNN Plugin EP 2.4.0 + ORT Android 1.24.3 + QAIRT/QNN runtime 2.48.0");

        File model = copyAsset(context, ASSET_NAME);
        Log.i(TAG, "QNN context model: " + model.getAbsolutePath() + " (" + model.length() + " bytes)");

        File nativeLibraryDir = new File(context.getApplicationInfo().nativeLibraryDir);
        String nativeLibPath = nativeLibraryDir.getAbsolutePath();
        // yes, the CPU_BACKEND flag looks wrong. it is only a discovery workaround for the
        // broken 2.4.0 Android plugin. the session below still forces backend_type=htp.
        Os.setenv("ORT_QNN_ENABLE_CPU_BACKEND", "1", true);
        Os.setenv("ADSP_LIBRARY_PATH", nativeLibPath, true);
        Log.i(TAG, "ORT_QNN_ENABLE_CPU_BACKEND=" + Os.getenv("ORT_QNN_ENABLE_CPU_BACKEND"));
        Log.i(TAG, "ADSP_LIBRARY_PATH=" + Os.getenv("ADSP_LIBRARY_PATH"));
        logNativeLibraries(nativeLibraryDir);

        File pluginLibrary = findNativeLibrary(nativeLibraryDir, QNN_PLUGIN_LIBRARY,
                "onnxruntime", "provider", "qnn");
        File htpLibrary = findNativeLibrary(nativeLibraryDir, QNN_HTP_LIBRARY, "qnn", "htp");
        Log.i(TAG, "QNN plugin present: " + pluginLibrary.getAbsolutePath());
        Log.i(TAG, "QNN HTP backend present: " + htpLibrary.getAbsolutePath());

        // keep ORT verbose for now. if QNN breaks again I want the FastRPC reason in the log.
        environment = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE);
        registerPluginOnce(environment);

        List<OrtEpDevice> allDevices = environment.getEpDevices();
        List<OrtEpDevice> qnnDevices = new ArrayList<>();
        for (OrtEpDevice device : allDevices) {
            Log.i(TAG, "ORT EP device: " + device);
            if (QNN_EP_NAME.equals(device.getEpName())) {
                qnnDevices.add(device);
            }
        }
        if (qnnDevices.isEmpty()) {
            throw new IllegalStateException(
                    "QNN 2.4.0 discovery bypass failed: Plugin EP still exposed no selectable QNN device. "
                            + "ORT_QNN_ENABLE_CPU_BACKEND=" + Os.getenv("ORT_QNN_ENABLE_CPU_BACKEND")
                            + ", SoC manufacturer=" + Build.SOC_MANUFACTURER
                            + ", Devices=" + allDevices);
        }
        Log.i(TAG, "QNN DISCOVERY BYPASS ACTIVE: selectable QNN device count=" + qnnDevices.size());
        for (OrtEpDevice device : qnnDevices) {
            Log.i(TAG, "Selected QNN EP device candidate: " + device);
        }

        sessionOptions = new OrtSession.SessionOptions();
        // QNN stays first. CPU fallback is left available for tiny host-side graph work;
        // the compiled detector context itself is still going through QNN/HTP.
        Log.i(TAG, "CPU fallback enabled for host-side graph nodes; QNN remains primary");

        Map<String, String> providerOptions = new HashMap<>();
        // this is the line that matters for execution: force HTP. the selectable device can
        // look CPU-shaped because of the 2.4.0 discovery workaround, but the QNN backend is HTP.
        // backend_type and backend_path are alternatives, so only send one.
        providerOptions.put("backend_type", "htp");
        providerOptions.put("htp_performance_mode", "burst");
        // keep FastRPC warm between camera frames. 100 us worked well for the low-latency path.
        providerOptions.put("rpc_control_latency", "100");
        Log.i(TAG, "FORCING QNN HTP/NPU backend with provider options: " + providerOptions);
        sessionOptions.addExecutionProvider(qnnDevices, providerOptions);

        Log.i(TAG, "Creating QNN Plugin EP session for Ultralytics precompiled EPContext model");
        session = environment.createSession(model.getAbsolutePath(), sessionOptions);
        runtimeMode = "precompiled QNN context / TURBO";
        Log.i(TAG, "QNN session created successfully; mode=" + runtimeMode);

        Map<String, NodeInfo> outputs = session.getOutputInfo();
        if (outputs.size() != 1) {
            throw new IllegalStateException("Expected one output, found " + outputs.keySet());
        }
        outputName = outputs.keySet().iterator().next();
        TensorInfo outInfo = (TensorInfo) outputs.get(outputName).getInfo();
        outputShape = outInfo.getShape();
        if (outInfo.type != OnnxJavaType.FLOAT) {
            throw new IllegalStateException("Expected FLOAT output, got " + outInfo.type);
        }
        int outputElements = checkedElementCount(outputShape, "output");
        Log.i(TAG, "Model output=" + outputName + " shape="
                + java.util.Arrays.toString(outputShape) + " type=" + outInfo.type);

        Map<String, NodeInfo> inputs = session.getInputInfo();
        if (inputs.size() != 1) {
            throw new IllegalStateException("Expected one input, found " + inputs.keySet());
        }
        inputName = inputs.keySet().iterator().next();
        TensorInfo info = (TensorInfo) inputs.get(inputName).getInfo();
        inputShape = info.getShape();
        if (info.type != OnnxJavaType.FLOAT) {
            throw new IllegalStateException("Expected FLOAT input, got " + info.type);
        }
        if (inputShape.length != 4) {
            throw new IllegalStateException("Expected 4D input, got " + java.util.Arrays.toString(inputShape));
        }

        if (inputShape[1] == 3) {
            nchw = true;
            inputHeight = checkedDimension(inputShape[2], "height");
            inputWidth = checkedDimension(inputShape[3], "width");
        } else if (inputShape[3] == 3) {
            nchw = false;
            inputHeight = checkedDimension(inputShape[1], "height");
            inputWidth = checkedDimension(inputShape[2], "width");
        } else {
            throw new IllegalStateException("Cannot infer NCHW/NHWC from " + java.util.Arrays.toString(inputShape));
        }

        int inputElements = checkedElementCount(inputShape, "input");
        modelBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888);
        modelCanvas = new Canvas(modelBitmap);
        modelPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        pixelBuffer = new int[inputWidth * inputHeight];

        ByteBuffer inputBytes = ByteBuffer.allocateDirect(inputElements * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        inputBuffer = inputBytes.asFloatBuffer();
        inputTensor = OnnxTensor.createTensor(environment, inputBuffer, inputShape);
        Map<String, OnnxTensor> mutableInputFeed = new HashMap<>(1);
        mutableInputFeed.put(inputName, inputTensor);
        inputFeed = Collections.unmodifiableMap(mutableInputFeed);

        ByteBuffer outputBytes = ByteBuffer.allocateDirect(outputElements * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        pinnedOutputBuffer = outputBytes.asFloatBuffer();
        pinnedOutputTensor = OnnxTensor.createTensor(environment, pinnedOutputBuffer, outputShape);
        Map<String, OnnxValue> mutablePinnedOutputs = new HashMap<>(1);
        mutablePinnedOutputs.put(outputName, pinnedOutputTensor);
        pinnedOutputs = Collections.unmodifiableMap(mutablePinnedOutputs);

        Log.i(TAG, "QNN TURBO buffers allocated once: input=" + inputElements
                + " floats, output=" + outputElements + " floats, pinned output enabled");
        Log.i(TAG, "QNN session ready; input=" + inputName + " shape="
                + java.util.Arrays.toString(inputShape) + " layout=" + (nchw ? "NCHW" : "NHWC"));
    }

    InferenceResult run(Bitmap source) throws Exception {
        long totalStarted = System.nanoTime();

        long prepStarted = totalStarted;
        prepareInput(source);
        long prepFinished = System.nanoTime();

        long inferStarted = prepFinished;
        OrtSession.Result result = null;
        FloatBuffer outputBuffer;
        try {
            if (pinnedOutputsEnabled) {
                try {
                    pinnedOutputBuffer.rewind();
                    result = session.run(inputFeed, pinnedOutputs);
                    outputBuffer = pinnedOutputBuffer;
                } catch (OrtException pinnedError) {
                    // pinned output saves an allocation. if this QNN build hates it, disable it once
                    // and carry on with the normal output path instead of crashing.
                    pinnedOutputsEnabled = false;
                    Log.w(TAG, "Pinned output unsupported; falling back to regular ORT output allocation", pinnedError);
                    result = session.run(inputFeed);
                    outputBuffer = outputFloatBuffer(result);
                }
            } else {
                result = session.run(inputFeed);
                outputBuffer = outputFloatBuffer(result);
            }
            long inferFinished = System.nanoTime();

            long postStarted = inferFinished;
            List<Detection> detections = decode(outputBuffer, outputShape,
                    source.getWidth(), source.getHeight(), transform);
            long postFinished = System.nanoTime();

            long prepMs = (prepFinished - prepStarted) / 1_000_000L;
            long inferMs = (inferFinished - inferStarted) / 1_000_000L;
            long postMs = (postFinished - postStarted) / 1_000_000L;
            long totalMs = (postFinished - totalStarted) / 1_000_000L;
            return new InferenceResult(detections, source.getWidth(), source.getHeight(),
                    prepMs, inferMs, postMs, totalMs);
        } finally {
            if (result != null) result.close();
        }
    }

    private FloatBuffer outputFloatBuffer(OrtSession.Result result) throws OrtException {
        if (result.size() == 0) throw new IllegalStateException("Model returned no outputs");
        OnnxValue output = result.get(0);
        if (!(output instanceof OnnxTensor)) {
            throw new IllegalStateException("Expected tensor output, got " + output.getClass().getName());
        }
        FloatBuffer buffer = ((OnnxTensor) output).getFloatBuffer();
        if (buffer == null) throw new IllegalStateException("Model output cannot be read as FLOAT");
        return buffer;
    }

    private List<Detection> decode(FloatBuffer flat, long[] shape, int sourceW, int sourceH, Letterbox lb) {
        // handle both YOLO26 output styles. my QNN export is the raw one-to-many head:
        // [1, 4+classes, N] = xywh + class scores, so it needs NMS. the [N,6] path is kept
        // here too in case I swap in an end-to-end export later.
        final int rawChannels = 4 + LABELS.length;

        if (shape.length == 3 && shape[0] == 1) {
            int a = safeInt(shape[1]);
            int b = safeInt(shape[2]);
            if (b == 6 || a == 6) {
                return decodeEndToEnd(flat, a, b, a == 6, sourceW, sourceH, lb);
            }
            if (a == rawChannels || b == rawChannels) {
                return decodeTraditional(flat, a, b, a == rawChannels, sourceW, sourceH, lb);
            }
            throw new IllegalStateException(
                    "Unsupported YOLO detect output " + java.util.Arrays.toString(shape)
                            + ". Expected end-to-end [1,N,6] or traditional [1,"
                            + rawChannels + ",N].");
        }

        if (shape.length == 2) {
            int a = safeInt(shape[0]);
            int b = safeInt(shape[1]);
            if (b == 6 || a == 6) {
                return decodeEndToEnd(flat, a, b, a == 6, sourceW, sourceH, lb);
            }
            if (a == rawChannels || b == rawChannels) {
                return decodeTraditional(flat, a, b, a == rawChannels, sourceW, sourceH, lb);
            }
            throw new IllegalStateException(
                    "Unsupported YOLO detect output " + java.util.Arrays.toString(shape)
                            + ". Expected [N,6] or [" + rawChannels + ",N].");
        }

        throw new IllegalStateException("Unsupported output shape " + java.util.Arrays.toString(shape));
    }

    private List<Detection> decodeEndToEnd(FloatBuffer flat, int a, int b, boolean channelsFirst,
                                            int sourceW, int sourceH, Letterbox lb) {
        final int rows = channelsFirst ? b : a;
        final int cols = 6;
        if ((long) rows * cols > flat.limit()) {
            throw new IllegalStateException("Output buffer is shorter than end-to-end tensor");
        }

        List<Detection> output = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            float x1 = channelsFirst ? flat.get(row) : flat.get(row * cols);
            float y1 = channelsFirst ? flat.get(rows + row) : flat.get(row * cols + 1);
            float x2 = channelsFirst ? flat.get(2 * rows + row) : flat.get(row * cols + 2);
            float y2 = channelsFirst ? flat.get(3 * rows + row) : flat.get(row * cols + 3);
            float confidence = channelsFirst ? flat.get(4 * rows + row) : flat.get(row * cols + 4);
            int classId = Math.round(channelsFirst ? flat.get(5 * rows + row) : flat.get(row * cols + 5));
            if (!Float.isFinite(confidence) || confidence < CONFIDENCE
                    || classId < 0 || classId >= LABELS.length) continue;

            float coordinateMax = Math.max(Math.max(Math.abs(x1), Math.abs(y1)),
                    Math.max(Math.abs(x2), Math.abs(y2)));
            if (coordinateMax <= 2.0f) {
                x1 *= inputWidth;
                x2 *= inputWidth;
                y1 *= inputHeight;
                y2 *= inputHeight;
            }

            Detection detection = mapBoxToSource(x1, y1, x2, y2, confidence, classId,
                    sourceW, sourceH, lb);
            if (detection != null) output.add(detection);
        }
        return output;
    }

    private List<Detection> decodeTraditional(FloatBuffer flat, int a, int b, boolean channelsFirst,
                                               int sourceW, int sourceH, Letterbox lb) {
        final int channels = 4 + LABELS.length;
        final int predictions = channelsFirst ? b : a;
        if ((long) predictions * channels > flat.limit()) {
            throw new IllegalStateException("Output buffer is shorter than traditional YOLO tensor");
        }

        List<Detection> candidates = new ArrayList<>();
        for (int i = 0; i < predictions; i++) {
            float cx = rawValue(flat, i, 0, predictions, channels, channelsFirst);
            float cy = rawValue(flat, i, 1, predictions, channels, channelsFirst);
            float w = rawValue(flat, i, 2, predictions, channels, channelsFirst);
            float h = rawValue(flat, i, 3, predictions, channels, channelsFirst);
            if (!Float.isFinite(cx) || !Float.isFinite(cy) || !Float.isFinite(w) || !Float.isFinite(h)
                    || w <= 0.0f || h <= 0.0f) continue;

            int classId = -1;
            float confidence = -Float.MAX_VALUE;
            for (int cls = 0; cls < LABELS.length; cls++) {
                float score = rawValue(flat, i, 4 + cls, predictions, channels, channelsFirst);
                if (Float.isFinite(score) && score > confidence) {
                    confidence = score;
                    classId = cls;
                }
            }
            if (classId < 0 || confidence < CONFIDENCE) continue;

            float coordinateMax = Math.max(Math.max(Math.abs(cx), Math.abs(cy)),
                    Math.max(Math.abs(w), Math.abs(h)));
            if (coordinateMax <= 2.0f) {
                cx *= inputWidth;
                w *= inputWidth;
                cy *= inputHeight;
                h *= inputHeight;
            }

            float x1 = cx - w * 0.5f;
            float y1 = cy - h * 0.5f;
            float x2 = cx + w * 0.5f;
            float y2 = cy + h * 0.5f;
            Detection detection = mapBoxToSource(x1, y1, x2, y2, confidence, classId,
                    sourceW, sourceH, lb);
            if (detection != null) candidates.add(detection);
        }

        return classAwareNms(candidates);
    }

    private static float rawValue(FloatBuffer flat, int prediction, int channel,
                                  int predictions, int channels, boolean channelsFirst) {
        return channelsFirst
                ? flat.get(channel * predictions + prediction)
                : flat.get(prediction * channels + channel);
    }

    private Detection mapBoxToSource(float x1, float y1, float x2, float y2,
                                     float confidence, int classId,
                                     int sourceW, int sourceH, Letterbox lb) {
        x1 = (x1 - lb.padX) / lb.scale;
        x2 = (x2 - lb.padX) / lb.scale;
        y1 = (y1 - lb.padY) / lb.scale;
        y2 = (y2 - lb.padY) / lb.scale;
        x1 = clamp(x1, 0, sourceW - 1);
        x2 = clamp(x2, 0, sourceW - 1);
        y1 = clamp(y1, 0, sourceH - 1);
        y2 = clamp(y2, 0, sourceH - 1);
        if (x2 <= x1 || y2 <= y1) return null;
        return new Detection(x1, y1, x2, y2, confidence, classId, LABELS[classId]);
    }

    private static List<Detection> classAwareNms(List<Detection> candidates) {
        if (candidates.isEmpty()) return candidates;
        candidates.sort((left, right) -> Float.compare(right.confidence, left.confidence));

        boolean[] suppressed = new boolean[candidates.size()];
        List<Detection> kept = new ArrayList<>(Math.min(MAX_DETECTIONS, candidates.size()));
        for (int i = 0; i < candidates.size() && kept.size() < MAX_DETECTIONS; i++) {
            if (suppressed[i]) continue;
            Detection chosen = candidates.get(i);
            kept.add(chosen);

            for (int j = i + 1; j < candidates.size(); j++) {
                if (suppressed[j]) continue;
                Detection other = candidates.get(j);
                if (chosen.classId != other.classId) continue;
                if (iou(chosen, other) > NMS_IOU) suppressed[j] = true;
            }
        }
        return kept;
    }

    private static float iou(Detection a, Detection b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        float iw = Math.max(0.0f, right - left);
        float ih = Math.max(0.0f, bottom - top);
        float intersection = iw * ih;
        if (intersection <= 0.0f) return 0.0f;

        float areaA = Math.max(0.0f, a.right - a.left) * Math.max(0.0f, a.bottom - a.top);
        float areaB = Math.max(0.0f, b.right - b.left) * Math.max(0.0f, b.bottom - b.top);
        float union = areaA + areaB - intersection;
        return union > 0.0f ? intersection / union : 0.0f;
    }

    private static void registerPluginOnce(OrtEnvironment environment) throws OrtException {
        synchronized (PLUGIN_LOCK) {
            // this is the plugin filename Qualcomm ships. Android resolves it from nativeLibraryDir.
            String path = QNN_PLUGIN_LIBRARY;
            if (!pluginRegistered) {
                Log.i(TAG, "Registering QNN Plugin EP as " + QNN_EP_NAME + " from " + path);
                environment.registerExecutionProviderLibrary(QNN_EP_NAME, path);
                pluginRegistered = true;
                registeredPluginPath = path;
                Log.i(TAG, "registerExecutionProviderLibrary succeeded");
            } else if (!path.equals(registeredPluginPath)) {
                throw new IllegalStateException("QNN plugin was already registered from a different path: "
                        + registeredPluginPath);
            }
        }
    }

    private static File findNativeLibrary(File nativeDir, String exactName, String... requiredTokens) {
        File exact = new File(nativeDir, exactName);
        if (exact.isFile()) return exact;

        File[] files = nativeDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.isFile()) continue;
                String lower = file.getName().toLowerCase(java.util.Locale.ROOT);
                if (!lower.endsWith(".so")) continue;
                boolean matches = true;
                for (String token : requiredTokens) {
                    if (!lower.contains(token.toLowerCase(java.util.Locale.ROOT))) {
                        matches = false;
                        break;
                    }
                }
                if (matches) return file;
            }
        }
        throw new IllegalStateException("Required native library " + exactName
                + " was not packaged in " + nativeDir.getAbsolutePath());
    }

    private static void logNativeLibraries(File nativeDir) {
        StringBuilder builder = new StringBuilder("Packaged native libraries in ")
                .append(nativeDir.getAbsolutePath()).append(':');
        File[] files = nativeDir.listFiles();
        if (files == null || files.length == 0) {
            builder.append(" <none>");
        } else {
            java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File file : files) {
                builder.append("\n  ").append(file.getName()).append(" (").append(file.length()).append(" bytes)");
            }
        }
        Log.i(TAG, builder.toString());
    }

    private void prepareInput(Bitmap source) {
        float scale = Math.min(inputWidth / (float) source.getWidth(), inputHeight / (float) source.getHeight());
        float scaledW = source.getWidth() * scale;
        float scaledH = source.getHeight() * scale;
        float padX = (inputWidth - scaledW) * 0.5f;
        float padY = (inputHeight - scaledH) * 0.5f;
        transform.scale = scale;
        transform.padX = padX;
        transform.padY = padY;

        modelBitmap.eraseColor(Color.rgb(114, 114, 114));
        modelRect.set(padX, padY, padX + scaledW, padY + scaledH);
        modelCanvas.drawBitmap(source, null, modelRect, modelPaint);
        modelBitmap.getPixels(pixelBuffer, 0, inputWidth, 0, 0, inputWidth, inputHeight);

        final float inv255 = 1.0f / 255.0f;
        if (nchw) {
            int plane = inputWidth * inputHeight;
            for (int i = 0; i < pixelBuffer.length; i++) {
                int p = pixelBuffer[i];
                inputBuffer.put(i, ((p >>> 16) & 0xFF) * inv255);
                inputBuffer.put(plane + i, ((p >>> 8) & 0xFF) * inv255);
                inputBuffer.put(plane * 2 + i, (p & 0xFF) * inv255);
            }
        } else {
            for (int i = 0; i < pixelBuffer.length; i++) {
                int p = pixelBuffer[i];
                int j = i * 3;
                inputBuffer.put(j, ((p >>> 16) & 0xFF) * inv255);
                inputBuffer.put(j + 1, ((p >>> 8) & 0xFF) * inv255);
                inputBuffer.put(j + 2, (p & 0xFF) * inv255);
            }
        }
    }

    private File copyAsset(Context context, String assetName) throws Exception {
        File dir = new File(context.getFilesDir(), "models");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create model directory");
        File target = new File(dir, assetName);
        // install -r keeps app data, so reuse the verified 22 MB context instead of copying it every launch.
        if (target.isFile() && target.length() == 23_197_770L) {
            Log.i(TAG, "Reusing cached QNN context model: " + target.getAbsolutePath());
            return target;
        }
        try (InputStream input = context.getAssets().open(assetName);
             FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.getFD().sync();
        }
        return target;
    }

    private static int checkedDimension(long value, String name) {
        if (value <= 0 || value > 4096) throw new IllegalStateException("Invalid input " + name + ": " + value);
        return (int) value;
    }

    private static int checkedElementCount(long[] shape, String name) {
        long count = 1;
        for (long dim : shape) {
            if (dim <= 0) throw new IllegalStateException("Dynamic/invalid " + name + " shape " + java.util.Arrays.toString(shape));
            count = Math.multiplyExact(count, dim);
            if (count > Integer.MAX_VALUE) throw new IllegalStateException(name + " tensor is too large");
        }
        return (int) count;
    }

    private static int safeInt(long value) {
        if (value <= 0 || value > Integer.MAX_VALUE) throw new IllegalStateException("Invalid tensor dimension " + value);
        return (int) value;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    String getRuntimeMode() {
        return runtimeMode;
    }

    @Override
    public void close() throws OrtException {
        try { pinnedOutputTensor.close(); } catch (Throwable ignored) { }
        try { inputTensor.close(); } catch (Throwable ignored) { }
        if (!modelBitmap.isRecycled()) modelBitmap.recycle();
        session.close();
        sessionOptions.close();
    }

    private static final class Letterbox {
        float scale;
        float padX;
        float padY;
    }
}
