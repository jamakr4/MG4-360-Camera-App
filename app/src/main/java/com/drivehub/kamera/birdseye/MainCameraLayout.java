package com.drivehub.kamera.birdseye;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.constraintlayout.widget.ConstraintLayout;
import com.drivehub.kamera.R;

/** 540px wheelcam + 930px single camera + 450px birdseye, uniformly fitted. */
public final class MainCameraLayout extends ViewGroup {
    public MainCameraLayout(Context context, AttributeSet attrs) { super(context, attrs); }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        MainCameraGeometry g = geometry(width, height);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i); int[] p = g.panels[panel(child)];
            if (child.getId() == R.id.mainCameraPanel) sizeActionButtons(child, p[2]-p[0], p[3]-p[1]);
            child.measure(MeasureSpec.makeMeasureSpec(p[2]-p[0], MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(p[3]-p[1], MeasureSpec.EXACTLY));
        }
    }
    private void sizeActionButtons(View center, int width, int height) {
        Button dashcam = center.findViewById(R.id.btnTriggerEventSave);
        Button test = center.findViewById(R.id.btnRecordTestClip);
        if (dashcam == null || test == null) return;
        // Match the side panel's 640px canvas, 64px buttons and 26px text, independent of density.
        float scale = Math.min(width / 930f, height / 640f);
        for (Button button : new Button[]{dashcam, test}) {
            button.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26 * scale);
            int padding = Math.round(22 * scale);
            button.setPadding(padding, 0, padding, 0);
            ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) button.getLayoutParams();
            int textWidth = (int) Math.ceil(button.getPaint().measureText(button.getText().toString())) + padding * 2;
            lp.width = Math.min(Math.round(441 * scale), Math.max(Math.round(300 * scale), textWidth));
            lp.height = Math.round(64 * scale);
            lp.setMarginStart(Math.round((button == dashcam ? 16 : 8) * scale));
            lp.setMarginEnd(Math.round((button == dashcam ? 8 : 16) * scale));
            if (button == dashcam) {
                lp.bottomMargin = Math.round(16 * scale);
                lp.goneEndMargin = Math.round(16 * scale);
            }
        }
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        MainCameraGeometry g = geometry(r-l, b-t);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i); int[] p = g.panels[panel(child)];
            child.layout(getPaddingLeft()+p[0], getPaddingTop()+p[1], getPaddingLeft()+p[2], getPaddingTop()+p[3]);
        }
    }
    private MainCameraGeometry geometry(int width, int height) {
        return new MainCameraGeometry(width-getPaddingLeft()-getPaddingRight(), height-getPaddingTop()-getPaddingBottom());
    }
    private int panel(View child) {
        return child.getId() == R.id.sideCameraPanel ? 0 : child.getId() == R.id.birdseyeView ? 2 : 1;
    }
}
