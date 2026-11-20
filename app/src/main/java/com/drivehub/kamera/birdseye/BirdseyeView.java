package com.drivehub.kamera.birdseye;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;
import android.view.GestureDetector;
import android.view.ScaleGestureDetector;
import android.view.MotionEvent;
import com.drivehub.kamera.parking.PdcGeometry;
import com.drivehub.kamera.parking.PdcSnapshot;
import com.drivehub.kamera.steering.SteeringSnapshot;
import com.drivehub.kamera.steering.SteeringGuidance;
import com.drivehub.kamera.steering.GearSnapshot;

import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.camera.CameraHandoffController;
import com.drivehub.kamera.R;
import com.drivehub.kamera.settings.UiPrefs;

import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Independent live output. Device attachment and calibration IO never run on the UI/GL threads. */
public final class BirdseyeView extends FrameLayout {
    private static final ScheduledThreadPoolExecutor IO = new ScheduledThreadPoolExecutor(1);
    static { IO.setRemoveOnCancelPolicy(true); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final BirdseyeSurface gl;
    private final TextView message, cameraWarning;
    private final Source source = new Source(BirdseyeOwnerIds.next());
    private final BirdseyeRenderer renderer = new BirdseyeRenderer(source, getContext().getAssets());
    private ScheduledFuture<?> task;
    private volatile int generation;
    private long handoffGeneration;
    private boolean resumed, surfaceReady, blocked, renderFailed;
    private int lastContext;
    private int lastMask = -1;
    private boolean calibrationRetrying;
    private final PdcGeometry pdc=new PdcGeometry();
    private SteeringSnapshot steeringSnapshot=SteeringSnapshot.EMPTY;
    private final SteeringGuidance steeringGuidance=new SteeringGuidance();
    private volatile RivelaScene scene;
    private Button modeToggle,wideToggle;
    private ImageButton guidesToggle;
    private boolean guidesEnabled=true,guideSpeedLimitEnabled;
    private int guideSpeedKmh=com.drivehub.kamera.steering.GuidelinePolicy.DEFAULT_SPEED_KMH;
    private final java.util.concurrent.atomic.AtomicBoolean preparing3d=new java.util.concurrent.atomic.AtomicBoolean();
    public PdcGeometry pdcGeometry() { return pdc; }
    public RivelaScene scene() { return scene; }
    public void setPdcSnapshot(PdcSnapshot snapshot) { pdc.set(snapshot); }
    public void setSteeringSnapshot(SteeringSnapshot snapshot) { steeringSnapshot=snapshot; }
    private void updateSteeringFrame() {
        updateSteeringFrame(com.drivehub.kamera.signal.SignalService.guidanceGear(),
                com.drivehub.kamera.signal.SignalService.vehicleMotion(),SystemClock.elapsedRealtime());
    }
    void updateSteeringFrame(GearSnapshot gear,long nowMs) {
        updateSteeringFrame(gear,com.drivehub.kamera.steering.VehicleMotionSnapshot.EMPTY,nowMs);
    }
    void updateSteeringFrame(GearSnapshot gear,com.drivehub.kamera.steering.VehicleMotionSnapshot motion,long nowMs) {
        SteeringGuidance.Frame frame=steeringGuidance.update(steeringSnapshot,gear,resumed&&!blocked&&!renderFailed,nowMs);
        renderer.steering.setSolution(frame.wheels,frame.guidanceAvailable,frame.direction);
        updateGuideVisibility(motion,nowMs);
    }
    public boolean rearFrameFresh() { return resumed&&!blocked&&source.ready&&(renderer.freshMask&8)!=0
            &&SystemClock.elapsedRealtime()-renderer.lastDrawElapsedMs<=500; }
    private void applyMode() {
        boolean mode=prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,false);
        renderer.setThreeD(mode);
        boolean wide=prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_WIDE,false);
        renderer.setWide(wide);
        if(wideToggle!=null){
            wideToggle.setVisibility(mode?GONE:VISIBLE);wideToggle.setSelected(wide);
            wideToggle.setText(wide?R.string.surround_label_normal:R.string.surround_label_wide);
            wideToggle.setContentDescription(getContext().getString(wide?R.string.surround_mode_normal:R.string.surround_mode_wide));
        }
        if (modeToggle != null) {
            modeToggle.setText(mode ? R.string.surround_label_2d : R.string.surround_label_3d);
            modeToggle.setContentDescription(getContext().getString(mode ? R.string.surround_mode_2d : R.string.surround_mode_3d));
            styleModeToggle();
        }
        if(guidesToggle!=null) {
            LayoutParams beside=(LayoutParams)(mode?modeToggle:wideToggle).getLayoutParams();
            LayoutParams params=(LayoutParams)guidesToggle.getLayoutParams();
            params.rightMargin=beside.rightMargin+beside.width+Math.round(8*getResources().getDisplayMetrics().density);
            guidesToggle.setLayoutParams(params);
        }
        applyGuides();
        if(mode)prepareThreeD();
    }
    private void applyGuides() {
        boolean enabled=prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_GUIDES,true);
        guidesEnabled=enabled;
        guideSpeedLimitEnabled=UiPrefs.isParkingGuideSpeedLimitEnabled(prefs);
        guideSpeedKmh=UiPrefs.getParkingGuideSpeedKmh(prefs);
        renderer.setGuideStyle(UiPrefs.getParkingGuideStyle(prefs));
        updateGuideVisibility(com.drivehub.kamera.signal.SignalService.vehicleMotion(),SystemClock.elapsedRealtime());
        if(guidesToggle!=null) {
            guidesToggle.setSelected(enabled);
            String action=getContext().getString(enabled?R.string.surround_guides_hide:R.string.surround_guides_show);
            guidesToggle.setContentDescription(action);guidesToggle.setTooltipText(action);
            styleGuidesToggle();
        }
        gl.requestRender();
    }
    private void updateGuideVisibility(com.drivehub.kamera.steering.VehicleMotionSnapshot motion,long nowMs) {
        renderer.setWheelGuidePreview(com.drivehub.kamera.steering.GuidelinePolicy.visible(
                guidesEnabled,guideSpeedLimitEnabled,guideSpeedKmh,motion,nowMs));
    }
    private void styleModeToggle() {
        styleToggle(modeToggle);if(wideToggle!=null)styleToggle(wideToggle);
        if(guidesToggle!=null)styleGuidesToggle();
    }
    private void styleToggle(Button button) {
        int accent = UiPrefs.getAccentColorInt(prefs);
        GradientDrawable background = new GradientDrawable();
        background.setColor(accent);
        background.setCornerRadius(8 * getResources().getDisplayMetrics().density);
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), background, null));
        button.setTextColor(UiPrefs.isLightColor(accent) ? 0xFF111111 : Color.WHITE);
    }
    private void styleGuidesToggle() {
        int accent=UiPrefs.getAccentColorInt(prefs);
        boolean on=guidesToggle.isSelected();
        GradientDrawable background=new GradientDrawable();
        background.setColor(on?accent:0xE610151C);
        background.setCornerRadius(8*getResources().getDisplayMetrics().density);
        if(!on)background.setStroke(Math.round(2*getResources().getDisplayMetrics().density),accent);
        guidesToggle.setBackgroundTintList(null);
        guidesToggle.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF),background,null));
        guidesToggle.setImageTintList(ColorStateList.valueOf(on?(UiPrefs.isLightColor(accent)?0xFF111111:Color.WHITE):accent));
    }
    private void prepareThreeD() {
        RivelaScene requested=scene;
        if(!resumed||!renderer.threeDRequested||requested==null||!preparing3d.compareAndSet(false,true))return;
        RivelaAssets.prepare(requested,getContext(),(model,error)->{
            preparing3d.set(false);
            if(!resumed||!renderer.threeDRequested)return;
            if(scene!=requested){prepareThreeD();return;}
            if(error!=null)renderer.failThreeD(ThreeDFailure.at(ThreeDFailure.Kind.MODEL,error));else renderer.setModel(model);
        });
    }
    private void showThreeDFailure(ThreeDFailure failure) {
        failure.report(getContext());
        Toast.makeText(getContext(),failure.userMessage(getContext()),Toast.LENGTH_LONG).show();
    }
    private final SharedPreferences.OnSharedPreferenceChangeListener preferences = (p, key) -> {
        if(UiPrefs.KEY_PARKING_SURROUND_3D.equals(key)||UiPrefs.KEY_PARKING_SURROUND_WIDE.equals(key))applyMode();
        if(UiPrefs.KEY_PARKING_SURROUND_GUIDES.equals(key)||UiPrefs.KEY_PARKING_GUIDE_SPEED_LIMIT.equals(key)
                ||UiPrefs.KEY_PARKING_GUIDE_SPEED_KMH.equals(key)||UiPrefs.KEY_PARKING_GUIDE_STYLE.equals(key))applyGuides();
        if (UiPrefs.KEY_ACCENT_COLOR.equals(key)) styleModeToggle();
        if (UiPrefs.KEY_PARKING_BIRDSEYE_CAR_COLOR.equals(key)) {
            renderer.setCarColor(UiPrefs.getBirdseyeCarColor(p));
        }
        if (UiPrefs.KEY_OEM_CAMERA_ACCESS_BLOCKED.equals(key) || UiPrefs.KEY_OEM_AVM_ACTIVE.equals(key)
                || UiPrefs.KEY_OEM_AVM_COEXIST.equals(key) || UiPrefs.KEY_EXTERNAL_CAMERA_PAUSED.equals(key)) updateOemState();
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if(!com.drivehub.kamera.signal.SignalService.isReverseActive())pdc.set(PdcSnapshot.EMPTY);
            if(renderer.threeDError!=null&&prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,false)) {
                ThreeDFailure failure=renderer.threeDFailure;
                prefs.edit().putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,false).apply();
                if(failure!=null)showThreeDFailure(failure);
            }
            if(renderer.wideError!=null&&prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_WIDE,false)) {
                prefs.edit().putBoolean(UiPrefs.KEY_PARKING_SURROUND_WIDE,false).apply();
                Toast.makeText(getContext(),R.string.surround_wide_failed,Toast.LENGTH_LONG).show();
            }
            if (lastContext != renderer.contextGeneration) {
                lastContext = renderer.contextGeneration;
                if (renderFailed && renderer.error == null) {
                    renderFailed = false;
                    if (surfaceReady && !blocked) startSource();
                }
            }
            if (renderer.error != null && !renderFailed) {
                renderFailed = true;
                Log.e("Birdseye", "Renderer failed: " + renderer.error);
                stopSource(); showMessage(R.string.birdseye_render_failed);
            }
            gl.setContinuousRendering(!blocked && source.ready && !renderFailed);
            long drawAgeMs = SystemClock.elapsedRealtime()-renderer.lastDrawElapsedMs;
            int fresh = drawAgeMs > 500 ? 0 : renderer.freshMask;
            CameraHandoffController.ready("birdseye", !blocked && source.ready && !renderFailed && fresh != 0,
                    handoffGeneration);
            // Retain the camera image through short gaps; predictions and handoff still require fresh data.
            int mask = drawAgeMs > BirdseyeFrameHealth.DISPLAY_HOLD_US/1000 ? 0 : renderer.displayMask;
            int displayState=mask|(calibrationRetrying?16:0);
            if (displayState != lastMask && !blocked && source.ready && !renderFailed) {
                lastMask = displayState;
                message.setVisibility(mask == 0 ? VISIBLE : GONE);
                message.setText(R.string.birdseye_waiting);
                cameraWarning.setVisibility(mask != 0 && (mask != 15 || calibrationRetrying) ? VISIBLE : GONE);
                if(mask==15&&calibrationRetrying)cameraWarning.setText(R.string.birdseye_calibration_rechecking);
                if (mask != 0 && mask != 15) {
                    int[] labels = {R.string.main_camera_label_left, R.string.main_camera_label_right,
                            R.string.main_camera_label_front, R.string.main_camera_label_rear};
                    StringBuilder missing = new StringBuilder();
                    for (int i = 0; i < 4; i++) if ((mask & (1 << i)) == 0) {
                        if (missing.length() > 0) missing.append(", ");
                        missing.append(getContext().getString(labels[i]));
                    }
                    cameraWarning.setText(getContext().getString(R.string.birdseye_partial, missing.toString()));
                }
            }
            if(!blocked&&source.ready&&!renderFailed&&renderer.threeDRequested&&!renderer.threeDActive) {
                message.setText(R.string.surround_3d_loading);message.setVisibility(VISIBLE);lastMask=-1;
            }
            main.postDelayed(this, 50);
        }
    };

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    public BirdseyeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(Color.BLACK);
        prefs = UiPrefs.getPrefs(context);
        renderer.setPdcGeometry(pdc);
        gl = new BirdseyeSurface(context, renderer, ready -> {
            surfaceReady = ready;
            if (!ready) stopSource();
            else if (resumed && !blocked && !renderFailed) startSource();
        });
        gl.setBeforeFrame(this::updateSteeringFrame);
        gl.setContinuousRendering(false);
        addView(gl, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        cameraWarning = text(context, 12);
        cameraWarning.setBackgroundColor(0xCC10151C);
        cameraWarning.setVisibility(GONE);
        addView(cameraWarning, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        message = text(context, 17);
        message.setBackgroundColor(0xFF10151C);
        message.setText(R.string.birdseye_loading);
        message.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(message, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        modeToggle = new Button(context);
        modeToggle.setId(R.id.surroundModeToggle);
        modeToggle.setAllCaps(false);
        modeToggle.setSingleLine(true);
        modeToggle.setTextSize(18);
        modeToggle.setTypeface(modeToggle.getTypeface(), Typeface.BOLD);
        modeToggle.setMinWidth(0);
        modeToggle.setMinHeight(0);
        modeToggle.setPadding(0, 0, 0, 0);
        int size=Math.round(48*getResources().getDisplayMetrics().density);
        LayoutParams toggleParams = new LayoutParams(size*3/2, size, Gravity.TOP|Gravity.END);
        toggleParams.topMargin = toggleParams.rightMargin = Math.round(8*getResources().getDisplayMetrics().density);
        addView(modeToggle, toggleParams);
        modeToggle.setOnClickListener(v -> {
            boolean enable=!prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,false);
            prefs.edit().putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,enable).apply();
        });
        wideToggle=new Button(context);wideToggle.setId(R.id.surroundWideToggle);
        wideToggle.setAllCaps(false);wideToggle.setSingleLine(true);wideToggle.setTextSize(18);
        wideToggle.setTypeface(wideToggle.getTypeface(),Typeface.BOLD);
        wideToggle.setMinWidth(0);wideToggle.setMinHeight(0);wideToggle.setPadding(0,0,0,0);
        LayoutParams wideParams=new LayoutParams(size*2,size,Gravity.TOP|Gravity.END);
        wideParams.topMargin=toggleParams.topMargin;
        wideParams.rightMargin=toggleParams.rightMargin+size*3/2+Math.round(8*getResources().getDisplayMetrics().density);
        addView(wideToggle,wideParams);
        wideToggle.setOnClickListener(v->prefs.edit().putBoolean(UiPrefs.KEY_PARKING_SURROUND_WIDE,
                !prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_WIDE,false)).apply());
        guidesToggle=new ImageButton(context);guidesToggle.setId(R.id.surroundGuidesToggle);
        guidesToggle.setImageResource(R.drawable.ic_horizontal_distribute);
        int iconPadding=Math.round(12*getResources().getDisplayMetrics().density);
        guidesToggle.setPadding(iconPadding,iconPadding,iconPadding,iconPadding);
        LayoutParams guidesParams=new LayoutParams(size,size,Gravity.TOP|Gravity.END);
        guidesParams.topMargin=toggleParams.topMargin;
        addView(guidesToggle,guidesParams);
        guidesToggle.setOnClickListener(v->prefs.edit().putBoolean(UiPrefs.KEY_PARKING_SURROUND_GUIDES,
                !prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_GUIDES,true)).apply());
        GestureDetector gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onDoubleTap(MotionEvent e){renderer.orbit.reset();return true;}
            @Override public boolean onScroll(MotionEvent a,MotionEvent b,float dx,float dy){renderer.orbit.rotate(-dx,-dy);return true;}
        });
        ScaleGestureDetector scale=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector detector){renderer.orbit.zoom(detector.getScaleFactor());return true;}
        });
        gl.setOnTouchListener((v,event)->{
            if(!renderer.threeDRequested)return false;
            scale.onTouchEvent(event);if(!scale.isInProgress()&&event.getPointerCount()==1)gestures.onTouchEvent(event);return true;
        });
        applyMode();
    }

    private TextView text(Context context, int size) {
        TextView v = new TextView(context);
        v.setTextColor(Color.WHITE); v.setTextSize(size); v.setGravity(Gravity.CENTER);
        int padding = Math.round(8*getResources().getDisplayMetrics().density);
        v.setPadding(padding, padding, padding, padding);
        return v;
    }

    public void resumeDisplay() {
        if (resumed) return;
        resumed = true; renderFailed = false; renderer.error = null;
        CameraHandoffController.demand("birdseye", true);
        prefs.registerOnSharedPreferenceChangeListener(preferences);
        renderer.setCarColor(UiPrefs.getBirdseyeCarColor(prefs));applyMode();
        gl.onResume(); updateOemState();
        main.removeCallbacks(tick); main.post(tick);
    }

    public void pauseDisplay() {
        if (!resumed) return;
        resumed = false;
        CameraHandoffController.demand("birdseye", false);
        steeringSnapshot=SteeringSnapshot.EMPTY;steeringGuidance.reset();
        updateSteeringFrame(GearSnapshot.EMPTY,SystemClock.elapsedRealtime());
        prefs.unregisterOnSharedPreferenceChangeListener(preferences);
        main.removeCallbacks(tick); stopSource();
        gl.onPause();
    }

    private void updateOemState() {
        blocked = UiPrefs.isOemCameraAccessBlocked(prefs) || CameraProbe.isOemCameraAccessBlocked();
        if (blocked) { stopSource(); showMessage(R.string.side_camera_oem_paused); }
        else if (resumed && surfaceReady && !renderFailed) startSource();
    }

    private void startSource() {
        if (task != null && !task.isCancelled()) return;
        handoffGeneration=CameraHandoffController.generation();
        final int token = ++generation;
        source.active = true; source.ready = false; lastMask = -1;
        final String[] calibrationHash = {null};
        final CalibrationReadState reads=new CalibrationReadState();
        calibrationRetrying=false;
        renderer.invalidateFrames(); showMessage(R.string.birdseye_loading);
        task = IO.scheduleWithFixedDelay(() -> {
            if (generation != token || !source.active) return;
            try {
                reads.success(BirdseyeCalibration.load(Paths.get(BirdseyeCalibration.VEHICLE_PATH)));
            }catch(java.io.IOException error) {
                reads.ioFailure(SystemClock.elapsedRealtime());
                Log.w("Birdseye","Calibration read IO failure; retrying="+reads.retrying()+": "+error.getMessage());
            }catch(RuntimeException error) {
                reads.invalid();Log.w("Birdseye","Invalid vehicle calibration: "+error.getMessage());
            }
            BirdseyeCalibration c=reads.current();
            boolean retrying=reads.retrying();
            main.post(()->{if(generation==token)calibrationRetrying=retrying;});
            if(c==null) {
                BirdseyeNative.detach(source.owner);calibrationHash[0]=null;
                main.post(()->{
                    if(generation!=token)return;
                    source.ready=false;renderer.invalidateFrames();showMessage(R.string.birdseye_calibration_missing);
                });
                return;
            }
            try {
                if (!c.sha256.equals(calibrationHash[0])) {
                    ProjectionAssets projection=ProjectionAssets.get(c);
                    main.post(() -> {
                        if (generation != token) return;
                        scene=projection.scene;renderer.setScene(projection.scene);
                        renderer.setMesh(projection.mesh);renderer.setWideMesh(projection.wideMesh,projection.wideError); source.ready = true; lastMask = -1;
                        if(renderer.threeDRequested)prepareThreeD();
                        showMessage(R.string.birdseye_waiting);
                        Log.i("Birdseye", "Vehicle calibration SHA-256="+c.sha256);
                    });
                    calibrationHash[0] = c.sha256;
                }
            }catch(Exception error) {
                calibrationHash[0]=null;
                main.post(()->{if(generation==token){source.ready=false;renderer.invalidateFrames();showMessage(R.string.birdseye_render_failed);}});
                Log.e("Birdseye","Projection preparation failed",error);return;
            }
            // A failed camera attachment must not erase a valid calibration or detach other cameras.
            for (int i = 0; i < 4 && generation == token && source.active; i++) {
                try {BirdseyeNative.attach(source.owner,i);}
                catch(RuntimeException error){Log.w("Birdseye","Camera "+i+" attach failed",error);}
            }
        }, 0, 2, TimeUnit.SECONDS);
    }

    private void stopSource() {
        gl.setContinuousRendering(false);
        generation++; source.active = false; source.ready = false;calibrationRetrying=false;
        renderer.invalidateFrames(); lastMask = -1;
        if (task != null) { task.cancel(false); task = null; }
        IO.execute(() -> BirdseyeNative.detach(source.owner));
    }

    private void showMessage(int id) { message.setText(id); message.setVisibility(VISIBLE); }

    private static final class Source implements BirdseyeRenderer.Frames {
        final int owner;
        volatile boolean active, ready;
        Source(int owner) { this.owner = owner; }
        @Override public long copy(int camera, ByteBuffer output, long afterUs) {
            return allowed() ? BirdseyeNative.copy(owner, camera, output, afterUs) : 0;
        }
        @Override public long clockUs() { return BirdseyeNative.clockUs(); }
        @Override public boolean allowed() { return active && ready && !CameraProbe.isOemCameraAccessBlocked(); }
    }
}
