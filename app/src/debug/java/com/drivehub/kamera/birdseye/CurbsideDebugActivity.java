package com.drivehub.kamera.birdseye;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import com.drivehub.kamera.R;
import com.drivehub.kamera.settings.UiPrefs;
import com.drivehub.kamera.side.SideCameraGeometry;
import java.util.concurrent.*;

/** Local comparison experiment, with private stills only. Does not attach cameras or change live preferences. */
public final class CurbsideDebugActivity extends Activity {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private PrivateCameraCapture capture;
    private GroundPointView input;
    private ImageView baseline,output;
    private TextView status,inputTitle;
    private int camera,region=SideGroundPatch.FRONT,generation;
    private boolean destroyed;
    private int projectionMode;
    private Bitmap shownBaseline,shownOutput;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(12,8,12,8);root.setBackgroundColor(0xFF10151C);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text(R.string.birdseye_debug_curbside_title);title.setTextSize(19);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button birdseye=new Button(this);birdseye.setId(R.id.curbsideDebugBirdseye);birdseye.setText(R.string.birdseye_debug_back_to_surround);birdseye.setTextSize(12);
        birdseye.setContentDescription(getString(R.string.birdseye_debug_back_to_surround_description));birdseye.setOnClickListener(v->returnToBirdseye());header.addView(birdseye);root.addView(header);
        TextView hint=text(R.string.birdseye_debug_curbside_hint);hint.setTextSize(12);root.addView(hint);
        LinearLayout controls=new LinearLayout(this);
        Button source=new Button(this);source.setText(R.string.birdseye_debug_private_recording);source.setOnClickListener(v->choose());controls.addView(source);
        Spinner side=new Spinner(this);side.setId(R.id.curbsideDebugSide);side.setPopupBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF202832));side.setAdapter(choices(new String[]{getString(R.string.birdseye_debug_left),getString(R.string.birdseye_debug_right)}));controls.addView(side);
        Spinner area=new Spinner(this);area.setId(R.id.curbsideDebugRegion);area.setPopupBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF202832));area.setAdapter(choices(new String[]{getString(R.string.birdseye_debug_curbside_front),getString(R.string.birdseye_debug_curbside_rear),getString(R.string.birdseye_debug_curbside_side)}));controls.addView(area);
        Spinner projection=new Spinner(this);projection.setId(R.id.curbsideDebugProjection);projection.setPopupBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF202832));projection.setAdapter(choices(new String[]{getString(R.string.birdseye_debug_curbside_lens),getString(R.string.birdseye_debug_curbside_ground),getString(R.string.birdseye_debug_curbside_parking)}));controls.addView(projection);
        Button reset=new Button(this);reset.setId(R.id.curbsideDebugReset);reset.setText(R.string.birdseye_debug_curbside_reset);reset.setOnClickListener(v->reset());controls.addView(reset);
        root.addView(controls);
        status=text(R.string.birdseye_debug_private_import_hint);status.setId(R.id.curbsideDebugStatus);status.setTextSize(12);status.setMaxLines(3);root.addView(status);
        LinearLayout panes=new LinearLayout(this);root.addView(panes,new LinearLayout.LayoutParams(-1,0,1));
        input=new GroundPointView(this);input.setId(R.id.curbsideDebugInput);baseline=new ImageView(this);output=new ImageView(this);output.setId(R.id.curbsideDebugOutput);
        baseline.setScaleType(ImageView.ScaleType.FIT_CENTER);output.setScaleType(ImageView.ScaleType.FIT_CENTER);
        inputTitle=pane(panes,R.string.birdseye_debug_curbside_points,input,1.4f);pane(panes,R.string.birdseye_debug_curbside_old,baseline,1);pane(panes,R.string.birdseye_debug_curbside_new,output,1);
        AdapterView.OnItemSelectedListener selected=new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                int selectedRegion=new int[]{SideGroundPatch.FRONT,SideGroundPatch.REAR,SideGroundPatch.SIDE}[area.getSelectedItemPosition()];
                camera=side.getSelectedItemPosition();region=selectedRegion;projectionMode=projection.getSelectedItemPosition();reset();
            }
            @Override public void onNothingSelected(AdapterView<?> parent){}
        };side.setOnItemSelectedListener(selected);area.setOnItemSelectedListener(selected);projection.setOnItemSelectedListener(selected);
        if(state!=null){camera=Math.max(0,Math.min(1,state.getInt("camera")));region=Math.max(0,Math.min(2,state.getInt("region",1)));projectionMode=Math.max(0,Math.min(2,state.getInt("projectionMode",state.getBoolean("ground")?1:0)));}
        else {region=Math.max(0,Math.min(2,getIntent().getIntExtra("curbside_region",SideGroundPatch.FRONT)));projectionMode=2;}
        side.setSelection(camera);area.setSelection(region==SideGroundPatch.SIDE?2:region-1);projection.setSelection(projectionMode);
        setContentView(root);
        String id=state!=null?state.getString(PrivateCameraCapture.EXTRA_SET):getIntent().getStringExtra(PrivateCameraCapture.EXTRA_SET);
        if(id!=null)load(id);else {String[] available=PrivateCameraCapture.available(this);if(available.length>0)load(available[0]);}
    }
    private void returnToBirdseye(){
        Intent intent=new Intent(this,BirdseyeDebugActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if(capture!=null)intent.putExtra(PrivateCameraCapture.EXTRA_SET,capture.id);
        startActivity(intent);
        finish();
    }
    private ArrayAdapter<String> choices(String[] items){
        return new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,items){
            private View readable(View view){((TextView)view).setTextColor(Color.WHITE);return view;}
            @Override public View getView(int position,View convert,ViewGroup parent){return readable(super.getView(position,convert,parent));}
            @Override public View getDropDownView(int position,View convert,ViewGroup parent){return readable(super.getDropDownView(position,convert,parent));}
        };
    }
    private TextView text(int resource){TextView view=new TextView(this);view.setText(resource);view.setTextColor(Color.WHITE);view.setPadding(4,3,4,3);return view;}
    private TextView pane(LinearLayout parent,int title,View image,float weight){LinearLayout pane=new LinearLayout(this);pane.setOrientation(LinearLayout.VERTICAL);TextView heading=text(title);pane.addView(heading);pane.addView(image,new LinearLayout.LayoutParams(-1,0,1));parent.addView(pane,new LinearLayout.LayoutParams(0,-1,weight));return heading;}
    private void choose(){String[] ids=PrivateCameraCapture.available(this);if(ids.length==0){status.setText(R.string.birdseye_debug_private_import_hint);return;}new AlertDialog.Builder(this).setItems(ids,(d,i)->load(ids[i])).show();}
    private void load(String id){
        int token=++generation;status.setText(R.string.birdseye_debug_private_loading);
        io.execute(()->{try{
            PrivateCameraCapture loaded=PrivateCameraCapture.load(this,id);
            ui.post(()->{if(destroyed||token!=generation){loaded.close();return;}PrivateCameraCapture previous=capture;capture=loaded;reset();if(previous!=null)previous.close();});
        }catch(Exception|OutOfMemoryError error){ui.post(()->{if(!destroyed&&token==generation)status.setText(getString(R.string.birdseye_debug_private_failed_detail,error.getMessage()));});}});
    }
    private void reset(){
        if(capture==null)return;
        try{
            if(projectionMode==2){
                inputTitle.setText(R.string.birdseye_debug_curbside_automatic_boundary);
                if(region==SideGroundPatch.SIDE)
                    input.setAutomaticGround(capture.image(camera),capture.calibration,camera,SideGroundPatch.defaults(capture.calibration,camera,SideGroundPatch.SIDE));
                else input.setAutomaticWheel(capture.image(camera),capture.calibration,camera,
                        new WheelSillPatch.Map(capture.calibration,camera,region==SideGroundPatch.FRONT,65,57));
                render(null);return;
            }
            inputTitle.setText(R.string.birdseye_debug_curbside_points);
            boolean groundProjection=projectionMode==1;
            double[] points=groundProjection?SideGroundPatch.imageCorners(capture.calibration,camera,SideGroundPatch.defaults(capture.calibration,camera,region))
                    :region==SideGroundPatch.SIDE?SideLensPatch.sideDefaults(capture.calibration,camera):SideLensPatch.defaults(capture.calibration,camera,region==SideGroundPatch.FRONT);
            input.set(capture.image(camera),capture.calibration,camera,points,groundProjection,this::render);render(points);
        }catch(IllegalArgumentException error){generation++;clearOutput();status.setText(error.getMessage());}
    }
    private void render(double[] points){
        int token=++generation,cam=camera,area=region,height=region==SideGroundPatch.SIDE?456:224;PrivateCameraCapture source=capture;
        int[] pixels;try{pixels=source.pixels(cam);}catch(OutOfMemoryError error){clearOutput();status.setText(R.string.birdseye_debug_private_failed);return;}
        boolean parking=projectionMode==2,ground=projectionMode==1;status.setText(R.string.birdseye_debug_private_loading);
        android.content.SharedPreferences prefs=UiPrefs.getPrefs(this);
        float angle=area==SideGroundPatch.FRONT&&UiPrefs.isParkingFrontWheelAlignmentEnabled(prefs)?UiPrefs.getDevParkingFrontWheelAngle(prefs)
                :area==SideGroundPatch.REAR&&UiPrefs.isParkingRearWheelAlignmentEnabled(prefs)?UiPrefs.getDevParkingRearWheelAngle(prefs):0;
        if(cam==1)angle=-angle;float rotation=angle;
        io.execute(()->{
            if(destroyed||token!=generation)return;
            Bitmap projected=null,old=null;
            try{
                int[] image;int covered;
                if(parking&&area!=SideGroundPatch.SIDE){WheelSillPatch.Map map=new WheelSillPatch.Map(source.calibration,cam,area==SideGroundPatch.FRONT,254,height);image=map.render(pixels);covered=map.covered;}
                else if(parking){SideGroundPatch.Map map=new SideGroundPatch.Map(source.calibration,cam,SideGroundPatch.defaults(source.calibration,cam,SideGroundPatch.SIDE),254,height);image=map.render(pixels);covered=map.covered;}
                else if(ground){SideGroundPatch.Map map=new SideGroundPatch.Map(source.calibration,cam,SideGroundPatch.fromImageCorners(source.calibration,cam,points),254,height);image=map.render(pixels);covered=map.covered;}
                else {SideLensPatch.Map map=new SideLensPatch.Map(source.calibration,cam,points,254,height,area==SideGroundPatch.SIDE);image=map.render(pixels);covered=map.covered;}
                projected=Bitmap.createBitmap(image,254,height,Bitmap.Config.ARGB_8888);
                old=baseline(pixels,cam,area,rotation);Bitmap ready=projected,comparison=old;
                ui.post(()->{
                    if(destroyed||token!=generation){ready.recycle();comparison.recycle();return;}
                    clearOutput();shownOutput=ready;shownBaseline=comparison;output.setImageBitmap(ready);baseline.setImageBitmap(comparison);
                    status.setText(getString(R.string.birdseye_debug_curbside_coverage,covered*100.0/(254*height)));
                });
            }catch(Exception|OutOfMemoryError error){
                if(projected!=null)projected.recycle();if(old!=null)old.recycle();
                ui.post(()->{if(!destroyed&&token==generation){clearOutput();status.setText(error.getMessage());}});
            }
        });
    }
    private Bitmap baseline(int[] pixels,int camera,int region,float angle){
        Bitmap original=Bitmap.createBitmap(pixels,720,480,Bitmap.Config.ARGB_8888);Matrix rotation=new Matrix();rotation.setRotate(camera==0?-90:90);
        Bitmap upright=Bitmap.createBitmap(original,0,0,720,480,rotation,false);
        Bitmap result=Bitmap.createBitmap(254,region==SideGroundPatch.SIDE?456:224,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(result);canvas.drawColor(SideGroundPatch.MISSING);
        SideCameraGeometry.Crop crop=region==SideGroundPatch.SIDE?SideCameraGeometry.side(camera==0):SideCameraGeometry.wheel(camera==0,region==SideGroundPatch.FRONT);
        Matrix transform=new Matrix();transform.setRectToRect(new RectF(crop.left*upright.getWidth(),crop.top*upright.getHeight(),crop.right*upright.getWidth(),crop.bottom*upright.getHeight()),new RectF(0,0,result.getWidth(),result.getHeight()),Matrix.ScaleToFit.FILL);
        canvas.rotate(angle,result.getWidth()/2f,result.getHeight()/2f);canvas.drawBitmap(upright,transform,new Paint(Paint.FILTER_BITMAP_FLAG));
        upright.recycle();original.recycle();return result;
    }
    private void clearOutput(){output.setImageDrawable(null);baseline.setImageDrawable(null);if(shownOutput!=null){shownOutput.recycle();shownOutput=null;}if(shownBaseline!=null){shownBaseline.recycle();shownBaseline=null;}}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("camera",camera);out.putInt("region",region);out.putInt("projectionMode",projectionMode);out.putString(PrivateCameraCapture.EXTRA_SET,capture==null?null:capture.id);super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){destroyed=true;generation++;io.shutdownNow();clearOutput();if(capture!=null)capture.close();super.onDestroy();}
}
