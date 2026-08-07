package com.jivyzn.roverqnn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.Collections;
import java.util.List;

public final class OverlayView extends View {
    private static final int[] CLASS_COLORS = {
            Color.rgb(255, 82, 82),
            Color.rgb(0, 230, 118),
            Color.rgb(255, 145, 0),
            Color.rgb(160, 160, 160),
            Color.rgb(41, 121, 255),
            Color.rgb(255, 64, 129),
            Color.rgb(245, 245, 245),
            Color.rgb(255, 214, 0)
    };

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF drawRect = new RectF();
    private List<Detection> detections = Collections.emptyList();
    private int frameWidth = 1;
    private int frameHeight = 1;

    public OverlayView(Context context) {
        this(context, null);
    }

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(dp(3));
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(15));
        textPaint.setFakeBoldText(true);
        backgroundPaint.setStyle(Paint.Style.FILL);
    }

    public void setDetections(List<Detection> newDetections, int width, int height) {
        // detections are already frozen when they reach the UI, so do not copy the list every frame.
        detections = newDetections;
        frameWidth = Math.max(1, width);
        frameHeight = Math.max(1, height);
        postInvalidateOnAnimation();
    }

    public void clear() {
        detections = Collections.emptyList();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (detections.isEmpty()) return;

        float scale = Math.min(getWidth() / (float) frameWidth, getHeight() / (float) frameHeight);
        float drawW = frameWidth * scale;
        float drawH = frameHeight * scale;
        float offsetX = (getWidth() - drawW) * 0.5f;
        float offsetY = (getHeight() - drawH) * 0.5f;

        for (Detection d : detections) {
            int color = CLASS_COLORS[Math.floorMod(d.classId, CLASS_COLORS.length)];
            boxPaint.setColor(color);
            backgroundPaint.setColor((color & 0x00FFFFFF) | 0xD0000000);

            drawRect.set(
                    offsetX + d.left * scale,
                    offsetY + d.top * scale,
                    offsetX + d.right * scale,
                    offsetY + d.bottom * scale
            );
            canvas.drawRect(drawRect, boxPaint);

            String text = d.label + " " + Math.round(d.confidence * 100f) + "%";
            float pad = dp(5);
            float textW = textPaint.measureText(text);
            float textH = textPaint.getTextSize() + pad * 2;
            float textTop = Math.max(offsetY, drawRect.top - textH);
            canvas.drawRect(drawRect.left, textTop, drawRect.left + textW + pad * 2,
                    textTop + textH, backgroundPaint);
            canvas.drawText(text, drawRect.left + pad, textTop + textH - pad, textPaint);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
