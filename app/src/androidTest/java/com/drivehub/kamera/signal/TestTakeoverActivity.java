package com.drivehub.kamera.signal;

/** Foreign test-APK task: no connection to real projection services or vehicle hardware. */
public class TestTakeoverActivity extends android.app.Activity {
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable expire=this::finishAndRemoveTask;
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        android.widget.TextView label=new android.widget.TextView(this);
        label.setText(getClass().getSimpleName());label.setTextSize(24);setContentView(label);
        handler.postDelayed(expire,10000);
    }
    @Override public void onDestroy(){handler.removeCallbacks(expire);super.onDestroy();}
}
