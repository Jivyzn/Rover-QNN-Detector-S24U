package com.jivyzn.roverqnn;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.system.Os;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends AppCompatActivity {
    private static final String TAG = "RoverQNN";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor(
            r -> priorityThread(r, "rover-camera", Process.THREAD_PRIORITY_DISPLAY));
    private final ExecutorService qnnExecutor = Executors.newSingleThreadExecutor(
            r -> priorityThread(r, "rover-qnn", Process.THREAD_PRIORITY_DISPLAY));
    private final AtomicBoolean inferenceBusy = new AtomicBoolean(false);

    private PreviewView previewView;
    private OverlayView overlayView;
    private TextView statusView;
    private Button startButton;
    private volatile QnnModelRunner modelRunner;
    private volatile boolean modelLoading = false;
    private long lastResultTimeNs = 0;
    private long lastStatusTimeNs = 0;
    private double smoothedFps = 0.0;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    status("Camera ready. Tap START QNN.");
                    startCamera();
                } else {
                    status("Camera permission is required.");
                    startButton.setEnabled(false);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // QNN 2.4.0 discovery on Android is broken: it checks /dev/fastrpc-cdsp*,
        // but normal apps cannot enumerate /dev. This flag only makes the QNN EP show up.
        // QnnModelRunner still forces backend_type=htp, so inference is going to the NPU.
        // keep this before OrtEnvironment is created.
        try {
            Os.setenv("ORT_QNN_ENABLE_CPU_BACKEND", "1", true);
            String adspLibraryPath = getApplicationContext().getApplicationInfo().nativeLibraryDir;
            Os.setenv("ADSP_LIBRARY_PATH", adspLibraryPath, true);
            Log.i(TAG, "QNN 2.4.0 Android discovery bypass enabled pre-ORT: ORT_QNN_ENABLE_CPU_BACKEND="
                    + Os.getenv("ORT_QNN_ENABLE_CPU_BACKEND"));
            Log.i(TAG, "ADSP_LIBRARY_PATH(pre-ORT)=" + Os.getenv("ADSP_LIBRARY_PATH"));
        } catch (Throwable t) {
            Log.e(TAG, "Failed to configure QNN environment before ORT initialization", t);
        }
        super.onCreate(savedInstanceState);
        buildUi();
        startButton.setOnClickListener(v -> initializeQnn());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            status("Camera ready. Tap START QNN.");
            startCamera();
        } else {
            status("Requesting camera permission...");
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlayView = new OverlayView(this);
        root.addView(overlayView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(15);
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        statusView.setPadding(dp(14), dp(10), dp(14), dp(10));
        statusView.setBackgroundColor(0xD0000000);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        statusParams.setMargins(dp(8), dp(36), dp(8), 0);
        root.addView(statusView, statusParams);

        startButton = new Button(this);
        startButton.setText("START QNN");
        startButton.setTextSize(17);
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(dp(210), dp(64), Gravity.CENTER);
        root.addView(startButton, buttonParams);

        setContentView(root);
    }

    private void initializeQnn() {
        if (modelRunner != null || modelLoading) return;
        modelLoading = true;
        startButton.setEnabled(false);
        startButton.setText("LOADING...");
        status("Loading Snapdragon QNN on a worker thread...");
        Log.i(TAG, "User requested QNN initialization");

        mainHandler.postDelayed(() -> {
            if (modelLoading) status("QNN is still loading, but the app UI is responsive.");
        }, 12_000);

        qnnExecutor.execute(() -> {
            try {
                QnnModelRunner runner = new QnnModelRunner(getApplicationContext());
                modelRunner = runner;
                modelLoading = false;
                runOnUiThread(() -> {
                    startButton.setVisibility(android.view.View.GONE);
                    status("QNN READY (" + runner.getRuntimeMode() + ") | waiting for camera frame");
                });
            } catch (Throwable error) {
                modelLoading = false;
                Log.e(TAG, "QNN initialization failed", error);
                runOnUiThread(() -> {
                    startButton.setEnabled(true);
                    startButton.setText("RETRY QNN");
                    status("QNN ERROR: " + rootMessage(error));
                });
            }
        });
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                provider.unbindAll();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            } catch (Throwable error) {
                Log.e(TAG, "Camera startup failed", error);
                status("CAMERA ERROR: " + rootMessage(error));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(@NonNull ImageProxy image) {
        QnnModelRunner runner = modelRunner;
        // KEEP_ONLY_LATEST + inferenceBusy is enough. no extra frame skipping here -
        // as soon as HTP is free I want the newest camera frame.
        if (runner == null || !inferenceBusy.compareAndSet(false, true)) {
            image.close();
            return;
        }

        Bitmap bitmap;
        try {
            bitmap = YuvConverter.toUprightBitmap(image);
        } catch (Throwable error) {
            image.close();
            inferenceBusy.set(false);
            Log.e(TAG, "Frame conversion failed", error);
            return;
        }
        image.close();

        qnnExecutor.execute(() -> {
            try {
                QnnModelRunner.InferenceResult result = runner.run(bitmap);
                bitmap.recycle();
                long now = System.nanoTime();
                double instantFps = lastResultTimeNs == 0 ? 0.0 : 1_000_000_000.0 / (now - lastResultTimeNs);
                lastResultTimeNs = now;
                if (instantFps > 0.0) {
                    smoothedFps = smoothedFps == 0.0 ? instantFps : (0.82 * smoothedFps + 0.18 * instantFps);
                }
                final double uiFps = smoothedFps;
                final boolean updateStatus = lastStatusTimeNs == 0 || now - lastStatusTimeNs >= 250_000_000L;
                if (updateStatus) lastStatusTimeNs = now;
                runOnUiThread(() -> {
                    overlayView.setDetections(result.detections, result.frameWidth, result.frameHeight);
                    if (updateStatus) {
                        status(String.format(Locale.US,
                                "QNN TURBO | %d objects | prep %d | infer %d | post %d | total %d ms | %.1f FPS",
                                result.detections.size(), result.preprocessMs, result.inferenceMs,
                                result.postprocessMs, result.totalMs, uiFps));
                    }
                });
            } catch (Throwable error) {
                bitmap.recycle();
                Log.e(TAG, "QNN inference failed", error);
                runOnUiThread(() -> status("INFERENCE ERROR: " + rootMessage(error)));
            } finally {
                inferenceBusy.set(false);
            }
        });
    }


    private static Thread priorityThread(Runnable runnable, String name, int priority) {
        return new Thread(() -> {
            try { Process.setThreadPriority(priority); } catch (Throwable ignored) { }
            runnable.run();
        }, name);
    }

    private void status(String text) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            statusView.setText(text);
        } else {
            mainHandler.post(() -> statusView.setText(text));
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        QnnModelRunner runner = modelRunner;
        modelRunner = null;
        if (runner != null) {
            qnnExecutor.execute(() -> {
                try { runner.close(); } catch (Throwable ignored) { }
            });
        }
        cameraExecutor.shutdownNow();
        qnnExecutor.shutdown();
        super.onDestroy();
    }
}
