package com.drivehub.kamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

final class ColorHueStripView extends View {

    interface OnHueChangedListener {
        void onHueChanged(float hue);
    }

    // Six primary/secondary hue stops with red repeated at the end so the gradient closes
    // the wheel back to red instead of fading out at magenta.
    private static final int[] HUE_COLORS = new int[]{
            0xFFFF0000,
            0xFFFFFF00,
            0xFF00FF00,
            0xFF00FFFF,
            0xFF0000FF,
            0xFFFF00FF,
            0xFFFF0000
    };

    private final Paint gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF contentRect = new RectF();
    private float hue;
    private OnHueChangedListener listener;

    public ColorHueStripView(Context context) {
        super(context);
        init();
    }

    public ColorHueStripView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ColorHueStripView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        markerPaint.setStyle(Paint.Style.FILL);
        markerPaint.setColor(0xFFFFFFFF);
        markerOutlinePaint.setStyle(Paint.Style.STROKE);
        markerOutlinePaint.setStrokeWidth(dp(2f));
        markerOutlinePaint.setColor(0xCC000000);
    }

    void setHue(float hue) {
        this.hue = clamp(hue, 0f, 360f);
        invalidate();
    }

    void setOnHueChangedListener(@Nullable OnHueChangedListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float inset = dp(2f);
        contentRect.set(inset, inset, w - inset, h - inset);
        // The strip is a single horizontal sweep across the full hue wheel.
        gradientPaint.setShader(new LinearGradient(
                contentRect.left,
                contentRect.top,
                contentRect.right,
                contentRect.top,
                HUE_COLORS,
                null,
                Shader.TileMode.CLAMP
        ));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float radius = dp(10f);
        canvas.drawRoundRect(contentRect, radius, radius, gradientPaint);

        // Draw a simple vertical marker so the selected hue stays readable on any background.
        float x = contentRect.left + (contentRect.width() * (hue / 360f));
        float cy = contentRect.centerY();
        float halfHeight = contentRect.height() / 2f;
        canvas.drawLine(x, cy - halfHeight, x, cy + halfHeight, markerOutlinePaint);
        canvas.drawLine(x, cy - halfHeight, x, cy + halfHeight, markerPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                // Map the horizontal touch position directly back into a 0..360 hue value.
                float x = clamp(event.getX(), contentRect.left, contentRect.right);
                float ratio = contentRect.width() <= 0f ? 0f : (x - contentRect.left) / contentRect.width();
                hue = clamp(ratio * 360f, 0f, 360f);
                invalidate();
                if (listener != null) {
                    listener.onHueChanged(hue);
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
