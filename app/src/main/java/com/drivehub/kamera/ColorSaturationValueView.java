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

final class ColorSaturationValueView extends View {

    interface OnColorPositionChangedListener {
        void onColorPositionChanged(float saturation, float value);
    }

    private final Paint fieldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint saturationOverlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valueOverlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF contentRect = new RectF();
    private float hue;
    private float saturation = 1f;
    private float value = 1f;
    private OnColorPositionChangedListener listener;

    public ColorSaturationValueView(Context context) {
        super(context);
        init();
    }

    public ColorSaturationValueView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ColorSaturationValueView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        markerPaint.setStyle(Paint.Style.FILL);
        markerPaint.setColor(0x00FFFFFF); // Transparent
        markerOutlinePaint.setStyle(Paint.Style.STROKE);
        markerOutlinePaint.setStrokeWidth(dp(2f));
        markerOutlinePaint.setColor(0xFFFFFFFF);
    }

    void setColor(float hue, float saturation, float value) {
        this.hue = hue;
        this.saturation = clamp(saturation, 0f, 1f);
        this.value = clamp(value, 0f, 1f);
        updateShader();
        invalidate();
    }

    void setOnColorPositionChangedListener(@Nullable OnColorPositionChangedListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float inset = dp(2f);
        contentRect.set(inset, inset, w - inset, h - inset);
        updateShader();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float radius = dp(12f);
        // Build the picker field in layers: pure hue, then wash it with white and black
        // gradients.
        canvas.drawRoundRect(contentRect, radius, radius, fieldPaint);
        canvas.drawRoundRect(contentRect, radius, radius, saturationOverlayPaint);
        canvas.drawRoundRect(contentRect, radius, radius, valueOverlayPaint);

        float cx = contentRect.left + (saturation * contentRect.width());
        float cy = contentRect.top + ((1f - value) * contentRect.height());
        float markerRadius = dp(9f);
        canvas.drawCircle(cx, cy, markerRadius, markerPaint);
        canvas.drawCircle(cx, cy, markerRadius, markerOutlinePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null)
            return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                updateFromTouch(event.getX(), event.getY());
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void updateFromTouch(float x, float y) {
        if (contentRect.width() <= 0f || contentRect.height() <= 0f)
            return;
        // Horizontal movement changes saturation, vertical movement changes
        // brightness/value.
        saturation = clamp((x - contentRect.left) / contentRect.width(), 0f, 1f);
        value = clamp(1f - ((y - contentRect.top) / contentRect.height()), 0f, 1f);
        invalidate();
        if (listener != null) {
            listener.onColorPositionChanged(saturation, value);
        }
    }

    private void updateShader() {
        if (contentRect.width() <= 0f || contentRect.height() <= 0f)
            return;
        int hueColor = android.graphics.Color.HSVToColor(new float[] { hue, 1f, 1f });
        fieldPaint.setShader(null);
        fieldPaint.setColor(hueColor);

        // Fade from white into the current hue to cover saturation on the X axis.
        Shader saturationShader = new LinearGradient(
                contentRect.left,
                contentRect.top,
                contentRect.right,
                contentRect.top,
                0xFFFFFFFF,
                0x00FFFFFF,
                Shader.TileMode.CLAMP);
        saturationOverlayPaint.setShader(saturationShader);

        // Fade from transparent to black to cover value/brightness on the Y axis.
        Shader valueShader = new LinearGradient(
                contentRect.left,
                contentRect.top,
                contentRect.left,
                contentRect.bottom,
                0x00FFFFFF,
                0xFF000000,
                Shader.TileMode.CLAMP);
        valueOverlayPaint.setShader(valueShader);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
