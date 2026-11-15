package com.drivehub.kamera.birdseye;

import android.app.Activity;
import android.graphics.Color;
import android.content.SharedPreferences;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.CheckBox;
import android.os.SystemClock;
import com.drivehub.kamera.steering.SteeringReader;
import com.drivehub.kamera.steering.SteeringSnapshot;
import com.drivehub.kamera.steering.SteeringPose;
import android.view.GestureDetector;
import android.view.ScaleGestureDetector;
import android.view.MotionEvent;
import com.drivehub.kamera.parking.PdcGeometry;
import com.drivehub.kamera.parking.PdcSnapshot;
import com.drivehub.kamera.settings.UiPrefs;
import com.drivehub.kamera.settings.BirdseyeCarColorPicker;
import com.drivehub.kamera.R;
import android.os.Bundle;
import android.os.Handler;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.view.Gravity;

/** Offline debug: labelled synthetic data or explicitly selected private recorded stills. */
public final class BirdseyeDebugActivity extends Activity {
    private final Handler handler = new Handler();
    BirdseyeSurface gl;
    BirdseyeRenderer renderer;
    BirdseyeSynthetic frames;
    private DebugFrameSource frameSource;
    private PrivateCameraCapture recording;
    private TextView sourceLabel;
    private int sourceGeneration;
    private final java.util.concurrent.ExecutorService sourceIo=java.util.concurrent.Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private Button twoD,threeD,zoomIn,zoomOut;
    private SeekBar steeringSlider;
    private Button steeringReset;
    private TextView steeringValue;
    private CheckBox liveSteering;
    private TextView steeringStatus;
    private SteeringReader steeringReader;
    private SteeringSnapshot steeringSnapshot=SteeringSnapshot.EMPTY;
    private final SteeringPose steeringPose=new SteeringPose();
    private boolean resumed;
    private boolean destroyed;
    private boolean pdcEnabled;
    private final PdcGeometry pdc=SyntheticRearPdc.geometry();
    volatile String preparationError;
    private void applyMode(){boolean safe=ModelLoadSafety.blocked(this);boolean mode=prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,false)&&!safe;renderer.setThreeD(mode);if(twoD!=null){twoD.setSelected(!mode);threeD.setSelected(mode);threeD.setEnabled(!safe);zoomIn.setEnabled(mode);zoomOut.setEnabled(mode);steeringSlider.setEnabled(mode&&!liveSteering.isChecked());steeringReset.setEnabled(mode&&!liveSteering.isChecked());}}
    private void updateLiveSteering() {
        boolean live=liveSteering.isChecked();
        steeringPose.reset(); steeringSnapshot=SteeringSnapshot.EMPTY;
        if(live&&resumed)steeringReader.start();else steeringReader.stop();
        renderer.steering.setDegrees(live?0:steeringSlider.getProgress()-WheelSteering.MAX_DEGREES);
        steeringStatus.setVisibility(live?android.view.View.VISIBLE:android.view.View.GONE);
        updateSteeringLabel(); applyMode(); gl.requestRender();
    }
    private void updateSteeringLabel() {
        int degrees=Math.round(renderer.steering.degrees());
        String direction=getString(degrees<0?R.string.birdseye_debug_left:degrees>0?R.string.birdseye_debug_right:R.string.birdseye_debug_straight);
        String label=getString(liveSteering!=null&&liveSteering.isChecked()?R.string.birdseye_debug_steering_live_angle:R.string.birdseye_debug_steering,degrees,direction);
        steeringValue.setText(label);steeringSlider.setContentDescription(label);
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener preferences = (p, key) -> {
        if(UiPrefs.KEY_PARKING_SURROUND_3D.equals(key))applyMode();
        if (UiPrefs.KEY_PARKING_BIRDSEYE_CAR_COLOR.equals(key)) renderer.setCarColor(UiPrefs.getBirdseyeCarColor(p));
    };
    private final Runnable draw = new Runnable() {
        @Override public void run() {
            if(pdcEnabled)pdc.set(SyntheticRearPdc.sample(android.os.SystemClock.elapsedRealtime()));
            if(liveSteering.isChecked()) {
                long now=SystemClock.elapsedRealtime();
                renderer.steering.setDegrees(steeringPose.update(steeringSnapshot,now,
                        UiPrefs.getSteeringScale(prefs),UiPrefs.isSteeringInverted(prefs)));
                updateSteeringLabel();
                steeringStatus.setText(getString(R.string.birdseye_debug_steering_status,steeringSnapshot.rawDegrees,
                        steeringSnapshot.fresh(now)?"available":steeringSnapshot.available?"stale":steeringSnapshot.state));
            }
            handler.postDelayed(this,50); // State updates only; EGL follows display vsync.
        }
    };
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = UiPrefs.getPrefs(this);
        frames = new BirdseyeSynthetic();
        frameSource=new DebugFrameSource(frames);
        renderer = new BirdseyeRenderer(frameSource,getAssets());
        if(state!=null)renderer.steering.setDegrees(state.getFloat("syntheticWheelSteeringDegrees",0));
        renderer.setPdcGeometry(pdc);
        try {
            ProjectionAssets projection=ProjectionAssets.get(frames.calibration);
            renderer.setMesh(projection.mesh);renderer.setScene(projection.scene);
            if(!ModelLoadSafety.blocked(this))RivelaAssets.prepare(projection.scene,this,(model,error)->{
                if(destroyed)return;
                if(error!=null){preparationError=error.toString();renderer.failThreeD(preparationError);}
                else renderer.setModel(model);
            });
        }catch(Exception error){preparationError=error.toString();renderer.failThreeD(preparationError);}
        FrameLayout root = new FrameLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                if (gl != null) {
                    float scale = Math.min(MeasureSpec.getSize(widthSpec)/450f, MeasureSpec.getSize(heightSpec)/640f);
                    FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) gl.getLayoutParams();
                    p.width = Math.round(450*scale); p.height = Math.round(640*scale);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        root.setBackgroundColor(Color.BLACK);
        gl = new BirdseyeSurface(this,renderer,null);gl.setId(R.id.surroundDebugSurface);
        GestureDetector gestures=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onDoubleTap(MotionEvent e){renderer.orbit.reset();return true;}
            @Override public boolean onScroll(MotionEvent a,MotionEvent b,float x,float y){renderer.orbit.rotate(-x,-y);return true;}
        });
        ScaleGestureDetector scale=new ScaleGestureDetector(this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){renderer.orbit.zoom(d.getScaleFactor());return true;}
        });
        gl.setOnTouchListener((v,event)->{if(!renderer.threeDRequested)return false;scale.onTouchEvent(event);if(!scale.isInProgress()&&event.getPointerCount()==1)gestures.onTouchEvent(event);return true;});
        // Use the full right-panel reference aspect, with no crop of the ground.
        root.addView(gl, new FrameLayout.LayoutParams(450,640,Gravity.CENTER));
        TextView label = new TextView(this);sourceLabel=label;label.setText(com.drivehub.kamera.R.string.birdseye_synthetic_label);
        label.setTextColor(Color.WHITE); label.setGravity(Gravity.CENTER); label.setTextSize(20);
        root.addView(label,new FrameLayout.LayoutParams(-1,64,Gravity.TOP));
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        controls.setPadding(padding,padding,padding,padding);
        controls.setBackgroundColor(0xEE10151C);
        LinearLayout sources=new LinearLayout(this);
        Button recorded=new Button(this),synthetic=new Button(this),curbside=new Button(this);
        recorded.setText(R.string.birdseye_debug_private_recording);recorded.setId(R.id.privateDebugRecording);
        synthetic.setText(R.string.birdseye_debug_private_synthetic);synthetic.setId(R.id.privateDebugSynthetic);
        curbside.setText(R.string.birdseye_debug_private_curbside);curbside.setId(R.id.privateDebugCurbside);
        for(Button button:new Button[]{recorded,synthetic,curbside})sources.addView(button,new LinearLayout.LayoutParams(0,-2,1));
        controls.addView(sources);
        recorded.setOnClickListener(v->chooseRecording());
        synthetic.setOnClickListener(v->useSynthetic());
        curbside.setOnClickListener(v->{
            android.content.Intent intent=new android.content.Intent(this,CurbsideDebugActivity.class);
            if(recording!=null)intent.putExtra(PrivateCameraCapture.EXTRA_SET,recording.id);
            startActivity(intent);
        });
        TextView colorLabel = new TextView(this);
        colorLabel.setText(R.string.settings_parking_car_color);
        colorLabel.setTextColor(Color.WHITE); colorLabel.setTextSize(18);
        controls.addView(colorLabel);
        controls.addView(new BirdseyeCarColorPicker(this));
        LinearLayout modes=new LinearLayout(this);twoD=new Button(this);threeD=new Button(this);
        twoD.setText("2D");threeD.setText("3D");twoD.setId(R.id.surroundMode2d);threeD.setId(R.id.surroundMode3d);
        modes.addView(twoD);modes.addView(threeD);controls.addView(modes);
        zoomOut=new Button(this);zoomIn=new Button(this);
        zoomOut.setId(R.id.surroundDebugZoomOut);zoomIn.setId(R.id.surroundDebugZoomIn);
        zoomOut.setText(R.string.birdseye_debug_zoom_minus);zoomIn.setText(R.string.birdseye_debug_zoom_plus);
        zoomOut.setContentDescription(getString(R.string.birdseye_debug_zoom_out));zoomIn.setContentDescription(getString(R.string.birdseye_debug_zoom_in));
        modes.addView(zoomOut);modes.addView(zoomIn);
        zoomOut.setOnClickListener(v->{renderer.orbit.zoom(1/1.25f);gl.requestRender();});
        zoomIn.setOnClickListener(v->{renderer.orbit.zoom(1.25f);gl.requestRender();});
        twoD.setOnClickListener(v->prefs.edit().putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,false).apply());
        threeD.setOnClickListener(v->prefs.edit().putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,true).apply());
        Button pdcButton=new Button(this);pdcButton.setText(R.string.birdseye_debug_pdc);pdcButton.setId(R.id.surroundDebugPdc);
        pdcButton.setOnClickListener(v->{pdcEnabled=!pdcEnabled;if(!pdcEnabled)pdc.set(PdcSnapshot.EMPTY);});controls.addView(pdcButton);
        steeringValue=new TextView(this);steeringValue.setId(R.id.surroundDebugSteeringValue);
        steeringValue.setTextColor(Color.WHITE);steeringValue.setTextSize(16);controls.addView(steeringValue);
        LinearLayout steeringRow=new LinearLayout(this);
        steeringSlider=new SeekBar(this);steeringSlider.setId(R.id.surroundDebugSteering);
        steeringSlider.setMax(WheelSteering.MAX_DEGREES*2);steeringSlider.setProgress(WheelSteering.MAX_DEGREES+Math.round(renderer.steering.degrees()));
        steeringRow.addView(steeringSlider,new LinearLayout.LayoutParams(0,Math.round(56*getResources().getDisplayMetrics().density),1));
        steeringReset=new Button(this);steeringReset.setId(R.id.surroundDebugSteeringReset);steeringReset.setText(R.string.birdseye_debug_steering_zero);
        steeringReset.setContentDescription(getString(R.string.birdseye_debug_steering_reset));
        steeringRow.addView(steeringReset);controls.addView(steeringRow);
        steeringSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){renderer.steering.setDegrees(progress-WheelSteering.MAX_DEGREES);updateSteeringLabel();gl.requestRender();}
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
        steeringReset.setOnClickListener(v->steeringSlider.setProgress(WheelSteering.MAX_DEGREES));
        liveSteering=new CheckBox(this);liveSteering.setId(R.id.surroundDebugLiveSteering);
        liveSteering.setText(R.string.birdseye_debug_live_steering);liveSteering.setTextColor(Color.WHITE);
        liveSteering.setChecked(state!=null&&state.getBoolean("liveVehicleSteering",false));controls.addView(liveSteering);
        steeringStatus=new TextView(this);steeringStatus.setId(R.id.surroundDebugSteeringStatus);
        steeringStatus.setTextColor(Color.WHITE);steeringStatus.setTextSize(12);steeringStatus.setMaxLines(3);
        steeringStatus.setVisibility(liveSteering.isChecked()?android.view.View.VISIBLE:android.view.View.GONE);controls.addView(steeringStatus);
        steeringReader=new SteeringReader(this,snapshot->steeringSnapshot=snapshot);
        liveSteering.setOnCheckedChangeListener((view,checked)->updateLiveSteering());
        updateSteeringLabel();
        applyMode();
        FrameLayout.LayoutParams controlParams = new FrameLayout.LayoutParams(
                Math.round(480 * getResources().getDisplayMetrics().density), -2, Gravity.END | Gravity.CENTER_VERTICAL);
        root.addView(controls, controlParams);
        setContentView(root);
        String requested=state!=null&&state.containsKey(PrivateCameraCapture.EXTRA_SET)
                ?state.getString(PrivateCameraCapture.EXTRA_SET):getIntent().getStringExtra(PrivateCameraCapture.EXTRA_SET);
        if(requested!=null)loadRecording(requested);
    }
    private void chooseRecording(){
        String[] ids=PrivateCameraCapture.available(this);
        if(ids.length==0){new android.app.AlertDialog.Builder(this).setMessage(R.string.birdseye_debug_private_import_hint).setPositiveButton(android.R.string.ok,null).show();return;}
        new android.app.AlertDialog.Builder(this).setTitle(R.string.birdseye_debug_private_recording).setItems(ids,(dialog,index)->loadRecording(ids[index])).show();
    }
    private void loadRecording(String id){
        int token=++sourceGeneration;sourceLabel.setText(R.string.birdseye_debug_private_loading);
        sourceIo.execute(()->{
            PrivateCameraCapture capture=null;
            try{
                capture=PrivateCameraCapture.load(this,id);ProjectionAssets projection=ProjectionAssets.get(capture.calibration);
                PrivateCameraCapture loaded=capture;
                handler.post(()->{
                    if(destroyed||token!=sourceGeneration){loaded.close();return;}
                    PrivateCameraCapture previous=recording;recording=loaded;if(previous!=null)previous.close();
                    switchSource(loaded,projection);
                    sourceLabel.setText(R.string.birdseye_debug_private_still_label);
                    prepareRecordedModel(projection,token);gl.requestRender();
                });
            }catch(Exception|OutOfMemoryError error){
                if(capture!=null)capture.close();
                handler.post(()->{if(!destroyed&&token==sourceGeneration){
                    sourceLabel.setText(recording==null?R.string.birdseye_synthetic_label:R.string.birdseye_debug_private_still_label);
                    new android.app.AlertDialog.Builder(this).setTitle(R.string.birdseye_debug_private_failed).setMessage(error.getMessage()).setPositiveButton(android.R.string.ok,null).show();
                }});
            }
        });
    }
    private void prepareRecordedModel(ProjectionAssets projection,int token){
        if(!ModelLoadSafety.blocked(this))RivelaAssets.prepare(projection.scene,this,(model,error)->{
            if(destroyed||token!=sourceGeneration)return;
            if(error!=null){preparationError=error.toString();renderer.failThreeD(preparationError);}else renderer.setModel(model);
        });
    }
    private void switchSource(BirdseyeRenderer.Frames source,ProjectionAssets projection){
        // Offline debug switch: disconnect old EGL before reinitializing textures/calibration together.
        // The view owns no native cameras or recording sessions.
        gl.onPause();frameSource.source=source;renderer.setMesh(projection.mesh);renderer.setScene(projection.scene);
        if(resumed)gl.onResume();
    }
    private void useSynthetic(){
        int token=++sourceGeneration;
        sourceIo.execute(()->{try{
            ProjectionAssets projection=ProjectionAssets.get(frames.calibration);
            handler.post(()->{
                if(destroyed||token!=sourceGeneration)return;
                PrivateCameraCapture previous=recording;recording=null;if(previous!=null)previous.close();
                switchSource(frames,projection);
                sourceLabel.setText(R.string.birdseye_synthetic_label);prepareRecordedModel(projection,token);gl.requestRender();
            });
        }catch(java.io.IOException error){handler.post(()->{if(!destroyed)sourceLabel.setText(R.string.birdseye_debug_private_failed);});}});
    }
    @Override protected void onSaveInstanceState(Bundle out){out.putString(PrivateCameraCapture.EXTRA_SET,recording==null?null:recording.id);out.putFloat("syntheticWheelSteeringDegrees",steeringSlider.getProgress()-WheelSteering.MAX_DEGREES);out.putBoolean("liveVehicleSteering",liveSteering.isChecked());super.onSaveInstanceState(out);}
    @Override protected void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(preferences);
        renderer.setCarColor(UiPrefs.getBirdseyeCarColor(prefs));applyMode();
        resumed=true; updateLiveSteering(); gl.onResume(); handler.post(draw);
    }
    @Override protected void onPause() { resumed=false;steeringReader.stop();steeringPose.reset();renderer.steering.setDegrees(0);handler.removeCallbacks(draw); prefs.unregisterOnSharedPreferenceChangeListener(preferences); gl.onPause(); super.onPause(); }
    @Override protected void onDestroy() { destroyed=true;sourceGeneration++;sourceIo.shutdownNow();if(recording!=null)recording.close();steeringReader.close();super.onDestroy(); }
}
