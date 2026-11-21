package com.drivehub.kamera.birdseye;

import android.app.Activity;
import android.graphics.Color;
import android.content.SharedPreferences;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.os.SystemClock;
import com.drivehub.kamera.steering.SteeringReader;
import com.drivehub.kamera.steering.SteeringSnapshot;
import com.drivehub.kamera.steering.SteeringModel;
import com.drivehub.kamera.steering.SteeringModelPose;
import com.drivehub.kamera.steering.OemSteeringCurve;
import com.drivehub.kamera.steering.WheelGuides;
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
import android.widget.ScrollView;
import android.view.Gravity;

/** Offline debug: labelled synthetic data or explicitly selected private recorded stills. */
public final class BirdseyeDebugActivity extends Activity {
    private final Handler handler = new Handler();
    BirdseyeSurface gl;
    BirdseyeRenderer renderer;
    BirdseyeSynthetic frames;
    private DebugFrameSource frameSource;
    private RivelaScene previewScene;
    private PrivateCameraCapture recording;
    private TextView sourceLabel;
    private int sourceGeneration;
    private final java.util.concurrent.ExecutorService sourceIo=java.util.concurrent.Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private Button twoD,threeD,wideToggle,zoomIn,zoomOut;
    private SeekBar steeringSlider,speedSlider;
    private TextView speedValue;
    private Button steeringReset;
    private TextView steeringValue;
    private CheckBox liveSteering, unavailableSteering, reverseGuides, blueCorridor,guideSpeedLimit,blackBox;
    private RadioGroup guideDirection;
    private TextView guideHorizon;
    private final java.util.List<Button> steeringPresets=new java.util.ArrayList<>();
    private ScrollView controlScroll;
    private TextView steeringStatus;
    private SteeringReader steeringReader;
    private SteeringSnapshot steeringSnapshot=SteeringSnapshot.EMPTY;
    private final SteeringModel steeringModel=new SteeringModel();
    private final SteeringModelPose steeringPose=new SteeringModelPose(steeringModel);
    SteeringModelPose.Frame steeringFrame;
    // 0.05 degree sensor steps, including both Float sensor endpoints.
    static final int STEERING_CENTER=10339;
    private boolean resumed;
    private boolean destroyed;
    private boolean pdcEnabled;
    private final PdcGeometry pdc=SyntheticRearPdc.geometry();
    volatile String preparationError;
    private void applyMode(){boolean mode=prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,false);renderer.setThreeD(mode);boolean wide=prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_WIDE,false);renderer.setWide(wide);
        if(wideToggle!=null){wideToggle.setVisibility(mode?android.view.View.GONE:android.view.View.VISIBLE);wideToggle.setSelected(wide);wideToggle.setText(wide?R.string.surround_label_normal:R.string.surround_label_wide);}
        if(speedSlider!=null)speedSlider.setEnabled(!liveSteering.isChecked());
        if(twoD!=null){twoD.setSelected(!mode);threeD.setSelected(mode);zoomIn.setEnabled(mode);zoomOut.setEnabled(mode);boolean manual=!liveSteering.isChecked();steeringSlider.setEnabled(manual);steeringReset.setEnabled(manual);for(Button preset:steeringPresets)preset.setEnabled(manual);}}
    private void updateLiveSteering() {
        boolean live=liveSteering.isChecked();
        steeringPose.reset(); steeringSnapshot=SteeringSnapshot.EMPTY;
        if(live&&resumed)steeringReader.start();else steeringReader.stop();
        updateSteeringFrame(); applyMode(); gl.requestRender();
    }
    float manualSteeringDegrees() { return (steeringSlider.getProgress()-STEERING_CENTER)/20f; }
    private void updateGuideDirection() {
        boolean park=guideDirection.getCheckedRadioButtonId()==R.id.surroundDebugGuidePark;
        boolean forward=guideDirection.getCheckedRadioButtonId()==R.id.surroundDebugGuideForward;
        if(!park)renderer.setGuideDirection(forward?WheelGuides.Direction.FORWARD:WheelGuides.Direction.REVERSE);
        guideHorizon.setText(park?R.string.birdseye_debug_park_horizon:blueCorridor.isChecked()
                ?(forward?R.string.birdseye_debug_blue_forward_horizon:R.string.birdseye_debug_blue_reverse_horizon)
                :(forward?R.string.birdseye_debug_forward_horizon:R.string.birdseye_debug_reverse_horizon));
        gl.requestRender();
    }
    private void updateSteeringFrame() {
        long now=SystemClock.elapsedRealtime();
        SteeringSnapshot sample=liveSteering.isChecked()?steeringSnapshot:
                SteeringSnapshot.value(manualSteeringDegrees(),0,now,0);
        if(unavailableSteering.isChecked())sample=SteeringSnapshot.unavailable("debug interruption",now);
        steeringFrame=steeringPose.update(sample,now);
        SteeringModel.Solution displayed=steeringFrame.displayed;
        boolean park=guideDirection.getCheckedRadioButtonId()==R.id.surroundDebugGuidePark;
        boolean forward=guideDirection.getCheckedRadioButtonId()==R.id.surroundDebugGuideForward;
        com.drivehub.kamera.steering.VehicleMotionSnapshot motion=liveSteering.isChecked()
                ?com.drivehub.kamera.signal.SignalService.vehicleMotion()
                :com.drivehub.kamera.steering.VehicleMotionSnapshot.value(speedSlider==null?0:speedSlider.getProgress()/10f,park?1:forward?4:2,now);
        renderer.setWheelGuidePreview(!park&&com.drivehub.kamera.steering.GuidelinePolicy.visible(reverseGuides.isChecked(),
                guideSpeedLimit.isChecked(),UiPrefs.getParkingGuideSpeedKmh(prefs),motion,now));
        // P is a visibility target, not invalid steering: retain usable geometry for the exit animation.
        renderer.steering.setSolution(displayed,steeringFrame.available,null);
        if(speedValue!=null){
            String text=motion.fresh(now)?getString(R.string.birdseye_debug_speed,motion.speedKmh):getString(R.string.birdseye_debug_speed_unavailable);
            if(!text.contentEquals(speedValue.getText()))speedValue.setText(text);
        }
        String direction=getString(sample.rawDegrees>0?R.string.birdseye_debug_left:sample.rawDegrees<0?R.string.birdseye_debug_right:R.string.birdseye_debug_straight);
        String label=sample.available?getString(R.string.birdseye_debug_sensor_angle,sample.rawDegrees,direction):getString(R.string.birdseye_debug_sensor_missing);
        if(!label.contentEquals(steeringValue.getText()))steeringValue.setText(label);
        steeringSlider.setContentDescription(label);
        String radius=displayed.curvaturePerMm==0?getString(R.string.birdseye_debug_straight):
                String.format(java.util.Locale.getDefault(),"%.3f m",1/Math.abs(displayed.curvaturePerMm)/1000);
        String status=getString(R.string.birdseye_debug_model_values,
                OemSteeringCurve.magnitudeDegrees(Math.abs(sample.rawDegrees)),displayed.curvaturePerMm*1000,
                radius,displayed.leftDegrees,displayed.rightDegrees);
        if(!steeringFrame.available)status=getString(R.string.birdseye_debug_steering_unavailable)+"\n"+status;
        if(liveSteering.isChecked())status+="\n"+sample.state;
        if(!status.contentEquals(steeringStatus.getText()))steeringStatus.setText(status);
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener preferences = (p, key) -> {
        if(UiPrefs.KEY_PARKING_SURROUND_3D.equals(key)||UiPrefs.KEY_PARKING_SURROUND_WIDE.equals(key))applyMode();
        if (UiPrefs.KEY_PARKING_BIRDSEYE_CAR_COLOR.equals(key)) renderer.setCarColor(UiPrefs.getBirdseyeCarColor(p));
        if (UiPrefs.KEY_PARKING_BIRDSEYE_BLACK_BOX.equals(key)) applyBlackBox();
        if(UiPrefs.KEY_PARKING_GUIDE_SPEED_KMH.equals(key))updateSpeedLimit();
    };
    private void applyBlackBox() {
        boolean enabled=UiPrefs.isBirdseyeBlackBoxEnabled(prefs);
        renderer.setBlackBoxEnabled(enabled);
        if(blackBox!=null&&blackBox.isChecked()!=enabled)blackBox.setChecked(enabled);
        gl.requestRender();
    }
    private void updateSpeedLimit() {
        int limit=UiPrefs.getParkingGuideSpeedKmh(prefs);
        guideSpeedLimit.setText(getString(R.string.birdseye_debug_guide_speed_limit,limit));
        if(speedSlider!=null)speedSlider.setMax(Math.max(600,(limit+10)*10));
    }
    private final Runnable draw = new Runnable() {
        @Override public void run() {
            if(pdcEnabled)pdc.set(SyntheticRearPdc.sample(android.os.SystemClock.elapsedRealtime()));
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
        if(state!=null&&state.getBoolean("steeringHasValue",false))steeringPose.restore(state.getDouble("steeringCurvature",0));
        renderer.setPdcGeometry(pdc);
        try {
            ProjectionAssets projection=ProjectionAssets.get(frames.calibration);
            // The shared scene already keeps the complete guide preview on flat ground.
            previewScene=projection.scene;
            renderer.setMesh(projection.mesh);renderer.setWideMesh(projection.wideMesh,projection.wideError);renderer.setScene(previewScene);
            RivelaAssets.prepare(previewScene,this,(model,error)->{
                if(destroyed)return;
                if(error!=null){preparationError=error.toString();renderer.failThreeD(ThreeDFailure.at(ThreeDFailure.Kind.MODEL,error));}
                else renderer.setModel(model);
            });
        }catch(Exception error){preparationError=error.toString();renderer.failThreeD(ThreeDFailure.at(ThreeDFailure.Kind.PROJECTION,error));}
        FrameLayout root = new FrameLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                if (gl != null) {
                    int panelWidth=Math.min(Math.round(480*getResources().getDisplayMetrics().density),Math.round(MeasureSpec.getSize(widthSpec)*.48f));
                    if(controlScroll!=null){FrameLayout.LayoutParams c=(FrameLayout.LayoutParams)controlScroll.getLayoutParams();c.width=panelWidth;}
                    float scale = Math.min((MeasureSpec.getSize(widthSpec)-panelWidth)/450f,(MeasureSpec.getSize(heightSpec)-64)/640f);
                    FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) gl.getLayoutParams();
                    p.width = Math.round(450*scale); p.height = Math.round(640*scale);
                    p.leftMargin=Math.max(0,(MeasureSpec.getSize(widthSpec)-panelWidth-p.width)/2);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        root.setBackgroundColor(Color.BLACK);
        gl = new BirdseyeSurface(this,renderer,null);gl.setId(R.id.surroundDebugSurface);
        gl.setBeforeFrame(this::updateSteeringFrame);
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
        root.addView(gl, new FrameLayout.LayoutParams(450,640,Gravity.START|Gravity.CENTER_VERTICAL));
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
        modes.addView(twoD);modes.addView(threeD);
        wideToggle=new Button(this);wideToggle.setId(R.id.surroundWideToggle);wideToggle.setAllCaps(false);
        wideToggle.setOnClickListener(v->prefs.edit().putBoolean(UiPrefs.KEY_PARKING_SURROUND_WIDE,!prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_WIDE,false)).apply());
        modes.addView(wideToggle);controls.addView(modes);
        blackBox=new CheckBox(this);blackBox.setId(R.id.surroundDebugBlackBox);
        blackBox.setText(R.string.birdseye_black_box_title);blackBox.setTextColor(Color.WHITE);
        blackBox.setChecked(UiPrefs.isBirdseyeBlackBoxEnabled(prefs));
        blackBox.setOnCheckedChangeListener((button,enabled)->UiPrefs.setBirdseyeBlackBoxEnabled(prefs,enabled));
        controls.addView(blackBox);
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
        reverseGuides=new CheckBox(this);reverseGuides.setId(R.id.surroundDebugReverseGuides);
        reverseGuides.setText(R.string.birdseye_debug_reverse_guides);reverseGuides.setTextColor(Color.WHITE);
        reverseGuides.setChecked(state==null||state.getBoolean("reverseGuidePreview",true));controls.addView(reverseGuides);
        renderer.setWheelGuidePreview(reverseGuides.isChecked());
        reverseGuides.setOnCheckedChangeListener((v,checked)->{updateSteeringFrame();gl.requestRender();});
        guideSpeedLimit=new CheckBox(this);guideSpeedLimit.setId(R.id.surroundDebugGuideSpeedLimit);
        guideSpeedLimit.setTextColor(Color.WHITE);controls.addView(guideSpeedLimit);
        guideSpeedLimit.setChecked(state==null?UiPrefs.isParkingGuideSpeedLimitEnabled(prefs):state.getBoolean("guideSpeedLimitTest",false));
        updateSpeedLimit();
        guideSpeedLimit.setOnCheckedChangeListener((v,checked)->{updateSteeringFrame();gl.requestRender();});
        blueCorridor=new CheckBox(this);blueCorridor.setId(R.id.surroundDebugBlueCorridor);
        blueCorridor.setText(R.string.birdseye_debug_blue_corridor);blueCorridor.setTextColor(Color.WHITE);
        blueCorridor.setChecked(state==null?UiPrefs.getParkingGuideStyle(prefs)==WheelGuides.Style.SIMPLE:state.getBoolean("blueGuideCorridor",true));controls.addView(blueCorridor);
        renderer.setBlueGuideCorridor(blueCorridor.isChecked());
        guideDirection=new RadioGroup(this);guideDirection.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton park=new RadioButton(this),reverse=new RadioButton(this),forward=new RadioButton(this);
        park.setId(R.id.surroundDebugGuidePark);reverse.setId(R.id.surroundDebugGuideReverse);forward.setId(R.id.surroundDebugGuideForward);
        park.setText(R.string.birdseye_debug_guide_park);
        reverse.setText(R.string.birdseye_debug_guide_reverse);forward.setText(R.string.birdseye_debug_guide_forward);
        for(RadioButton button:new RadioButton[]{park,reverse,forward}){button.setTextColor(Color.WHITE);guideDirection.addView(button,new RadioGroup.LayoutParams(0,-2,1));}
        controls.addView(guideDirection);
        guideHorizon=new TextView(this);guideHorizon.setTextColor(0xFFCCD6DF);guideHorizon.setTextSize(13);controls.addView(guideHorizon);
        guideDirection.check(state!=null&&state.getBoolean("guidePark",false)?R.id.surroundDebugGuidePark:
                state!=null&&state.getBoolean("guideForward",false)?R.id.surroundDebugGuideForward:R.id.surroundDebugGuideReverse);
        guideDirection.setOnCheckedChangeListener((group,id)->updateGuideDirection());updateGuideDirection();
        blueCorridor.setOnCheckedChangeListener((v,checked)->{renderer.setBlueGuideCorridor(checked);updateGuideDirection();});
        steeringValue=new TextView(this);steeringValue.setId(R.id.surroundDebugSteeringValue);
        steeringValue.setTextColor(Color.WHITE);steeringValue.setTextSize(16);controls.addView(steeringValue);
        LinearLayout steeringRow=new LinearLayout(this);
        steeringSlider=new SeekBar(this);steeringSlider.setId(R.id.surroundDebugSteering);
        steeringSlider.setMax(STEERING_CENTER*2);steeringSlider.setProgress(STEERING_CENTER+Math.round((state==null?0:state.getFloat("syntheticSensorDegrees",0))*20));
        steeringRow.addView(steeringSlider,new LinearLayout.LayoutParams(0,Math.round(56*getResources().getDisplayMetrics().density),1));
        steeringReset=new Button(this);steeringReset.setId(R.id.surroundDebugSteeringReset);steeringReset.setText(R.string.birdseye_debug_steering_zero);
        steeringReset.setContentDescription(getString(R.string.birdseye_debug_steering_reset));
        steeringRow.addView(steeringReset);controls.addView(steeringRow);
        steeringSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){updateSteeringFrame();gl.requestRender();}
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
        steeringReset.setOnClickListener(v->steeringSlider.setProgress(STEERING_CENTER));
        LinearLayout presets=new LinearLayout(this);
        float[] values={516.95f,199.3f,-199.3f,-516.95f};
        int[] ids={R.id.surroundDebugSteeringFullLeft,R.id.surroundDebugSteeringMidLeft,R.id.surroundDebugSteeringMidRight,R.id.surroundDebugSteeringFullRight};
        for(int i=0;i<values.length;i++){
            final float value=values[i];Button preset=new Button(this);preset.setId(ids[i]);preset.setText(String.format(java.util.Locale.getDefault(),"%+.2f°",value));
            preset.setTextSize(12);preset.setPadding(0,0,0,0);preset.setMinWidth(0);
            preset.setOnClickListener(v->steeringSlider.setProgress(STEERING_CENTER+Math.round(value*20)));
            presets.addView(preset,new LinearLayout.LayoutParams(0,-2,1));steeringPresets.add(preset);
        }
        controls.addView(presets);
        speedValue=new TextView(this);speedValue.setTextColor(Color.WHITE);speedValue.setTextSize(14);controls.addView(speedValue);
        speedSlider=new SeekBar(this);speedSlider.setId(R.id.surroundDebugGuideSpeed);speedSlider.setMax(Math.max(600,(UiPrefs.getParkingGuideSpeedKmh(prefs)+10)*10));
        speedSlider.setProgress(state==null?0:state.getInt("guideSpeed",0));controls.addView(speedSlider);
        speedSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){updateSteeringFrame();gl.requestRender();}
            public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}
        });
        liveSteering=new CheckBox(this);liveSteering.setId(R.id.surroundDebugLiveSteering);
        liveSteering.setText(R.string.birdseye_debug_live_steering);liveSteering.setTextColor(Color.WHITE);
        liveSteering.setChecked(state!=null&&state.getBoolean("liveVehicleSteering",false));controls.addView(liveSteering);
        steeringStatus=new TextView(this);steeringStatus.setId(R.id.surroundDebugSteeringStatus);
        steeringStatus.setTextColor(Color.WHITE);steeringStatus.setTextSize(14);
        unavailableSteering=new CheckBox(this);unavailableSteering.setId(R.id.surroundDebugSteeringUnavailable);
        unavailableSteering.setText(R.string.birdseye_debug_interrupt_steering);unavailableSteering.setTextColor(Color.WHITE);
        unavailableSteering.setChecked(state!=null&&state.getBoolean("interruptSteering",false));controls.addView(unavailableSteering);
        unavailableSteering.setOnCheckedChangeListener((v,checked)->{updateSteeringFrame();gl.requestRender();});
        controls.addView(steeringStatus);
        TextView modelNote=new TextView(this);modelNote.setText(R.string.birdseye_debug_model_note);modelNote.setTextColor(0xFFFFD180);modelNote.setTextSize(12);controls.addView(modelNote);
        steeringReader=new SteeringReader(this,snapshot->steeringSnapshot=snapshot);
        liveSteering.setOnCheckedChangeListener((view,checked)->updateLiveSteering());
        updateSteeringFrame();
        applyMode();
        FrameLayout.LayoutParams controlParams = new FrameLayout.LayoutParams(
                Math.round(480 * getResources().getDisplayMetrics().density), -2, Gravity.END | Gravity.TOP);
        controlParams.height=-1;controlParams.topMargin=64;
        controlScroll=new ScrollView(this);controlScroll.addView(controls);root.addView(controlScroll, controlParams);
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
        RivelaAssets.prepare(previewScene,this,(model,error)->{
            if(destroyed||token!=sourceGeneration)return;
            if(error!=null){preparationError=error.toString();renderer.failThreeD(ThreeDFailure.at(ThreeDFailure.Kind.MODEL,error));}else renderer.setModel(model);
        });
    }
    private void switchSource(BirdseyeRenderer.Frames source,ProjectionAssets projection){
        // Offline debug switch: disconnect old EGL before reinitializing textures/calibration together.
        // The view owns no native cameras or recording sessions.
        gl.onPause();frameSource.source=source;renderer.setMesh(projection.mesh);
        renderer.setWideMesh(projection.wideMesh,projection.wideError);
        previewScene=projection.scene;renderer.setScene(previewScene);
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
    @Override protected void onSaveInstanceState(Bundle out){
        out.putString(PrivateCameraCapture.EXTRA_SET,recording==null?null:recording.id);
        out.putFloat("syntheticSensorDegrees",manualSteeringDegrees());
        out.putBoolean("liveVehicleSteering",liveSteering.isChecked());
        out.putBoolean("interruptSteering",unavailableSteering.isChecked());
        out.putInt("guideSpeed",speedSlider.getProgress());
        out.putBoolean("reverseGuidePreview",reverseGuides.isChecked());
        out.putBoolean("guideSpeedLimitTest",guideSpeedLimit.isChecked());
        out.putBoolean("blueGuideCorridor",blueCorridor.isChecked());
        out.putBoolean("guideForward",guideDirection.getCheckedRadioButtonId()==R.id.surroundDebugGuideForward);
        out.putBoolean("guidePark",guideDirection.getCheckedRadioButtonId()==R.id.surroundDebugGuidePark);
        if(steeringFrame!=null){
            out.putBoolean("steeringHasValue",steeringFrame.hasValue);
            out.putDouble("steeringCurvature",steeringFrame.displayed.curvaturePerMm);
        }
        super.onSaveInstanceState(out);
    }
    @Override protected void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(preferences);
        renderer.setCarColor(UiPrefs.getBirdseyeCarColor(prefs));
        applyBlackBox();applyMode();
        resumed=true;if(liveSteering.isChecked())steeringReader.start();gl.onResume();handler.post(draw);
    }
    @Override protected void onPause() { resumed=false;steeringReader.stop();steeringPose.pause();handler.removeCallbacks(draw); prefs.unregisterOnSharedPreferenceChangeListener(preferences); gl.onPause(); super.onPause(); }
    @Override protected void onDestroy() { destroyed=true;sourceGeneration++;sourceIo.shutdownNow();if(recording!=null)recording.close();steeringReader.close();super.onDestroy(); }
}
