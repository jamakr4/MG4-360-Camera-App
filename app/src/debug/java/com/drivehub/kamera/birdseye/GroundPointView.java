package com.drivehub.kamera.birdseye;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;
import java.util.function.Consumer;

/** Touch coordinates stay in original 720x480 pixels, independent of screen scale/letterboxing. */
final class GroundPointView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final RectF imageRect=new RectF();
    private final Path outline=new Path();
    private boolean groundMode;
    private static final String[] NUMBERS={"1","2","3","4"};
    private Bitmap image;
    private BirdseyeCalibration calibration;
    private int camera,drag=-1,next;
    private double[] points;
    private double[] beforeDrag;
    private double[] automaticGround;
    private WheelSillPatch.Map automaticWheel;
    private Consumer<double[]> listener;
    GroundPointView(Context context){super(context);setContentDescription(context.getString(com.drivehub.kamera.R.string.birdseye_debug_curbside_points));}
    void set(Bitmap image,BirdseyeCalibration calibration,int camera,double[] points,boolean groundMode,Consumer<double[]> listener){
        setContentDescription(getContext().getString(com.drivehub.kamera.R.string.birdseye_debug_curbside_points));
        this.image=image;this.calibration=calibration;this.camera=camera;this.points=points.clone();this.listener=listener;this.groundMode=groundMode;automaticGround=null;automaticWheel=null;next=0;updateOutline();invalidate();
    }
    void setAutomaticGround(Bitmap image,BirdseyeCalibration calibration,int camera,double[] ground){
        set(image,calibration,camera,SideGroundPatch.imageCorners(calibration,camera,ground),true,null);
        automaticGround=ground.clone();setContentDescription(getContext().getString(com.drivehub.kamera.R.string.birdseye_debug_curbside_automatic_boundary));updateOutline();invalidate();
    }
    void setAutomaticWheel(Bitmap image,BirdseyeCalibration calibration,int camera,WheelSillPatch.Map map){
        set(image,calibration,camera,new double[8],false,null);
        automaticWheel=map;
        setContentDescription(getContext().getString(com.drivehub.kamera.R.string.birdseye_debug_curbside_automatic_boundary));
        updateOutline();invalidate();
    }
    double[] points(){return points==null?null:points.clone();}
    private void fit(){float margin=24*getResources().getDisplayMetrics().density;float scale=Math.max(0,Math.min((getWidth()-2*margin)/720f,(getHeight()-2*margin)/480f));float w=720*scale,h=480*scale;imageRect.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);}
    private float sx(double x){return imageRect.left+(float)x/720*imageRect.width();}
    private float sy(double y){return imageRect.top+(float)y/480*imageRect.height();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);if(image==null||image.isRecycled())return;fit();paint.setColor(Color.WHITE);paint.setStyle(Paint.Style.FILL);canvas.drawBitmap(image,null,imageRect,paint);
        if(points==null)return;
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(Color.CYAN);
        canvas.drawPath(outline,paint);
        paint.setStyle(Paint.Style.FILL);paint.setTextSize(18*getResources().getDisplayMetrics().scaledDensity);
        for(int i=0;i<4;i++)if(listener!=null&&Double.isFinite(points[2*i])&&Double.isFinite(points[2*i+1])){
            float x=sx(points[2*i]),y=sy(points[2*i+1]);paint.setColor(i==drag?Color.YELLOW:Color.CYAN);canvas.drawCircle(x,y,9,paint);
            paint.setColor(Color.BLACK);canvas.drawText(NUMBERS[i],x+12,y+6,paint);paint.setColor(Color.WHITE);canvas.drawText(NUMBERS[i],x+11,y+5,paint);
        }
    }
    private void updateOutline(){
        if(points==null)return;fit();
        outline.reset();
        if(automaticWheel!=null){
            boolean segment=false,complete=true;
            for(int edge=0;edge<4;edge++)for(int step=0;step<=32;step++){
                double f=step/32.0;
                int x=(int)Math.round((edge==0?f:edge==1?1:edge==2?1-f:0)*(automaticWheel.width-1));
                int y=(int)Math.round((edge==0?0:edge==1?f:edge==2?1:1-f)*(automaticWheel.height-1));
                double[] uv=automaticWheel.sourcePixel(x,y);
                if(Double.isFinite(uv[0])){if(segment)outline.lineTo(sx(uv[0]),sy(uv[1]));else outline.moveTo(sx(uv[0]),sy(uv[1]));segment=true;}
                else{segment=false;complete=false;}
            }
            if(complete)outline.close();return;
        }
        if(!groundMode){for(int i=0;i<4;i++){if(i==0)outline.moveTo(sx(points[0]),sy(points[1]));else outline.lineTo(sx(points[2*i]),sy(points[2*i+1]));}outline.close();return;}
        try{
            double[] ground=automaticGround!=null?automaticGround:SideGroundPatch.fromImageCorners(calibration,camera,points);SideGroundPatch.Warp warp=new SideGroundPatch.Warp(ground);Path path=outline;
            boolean segment=false,complete=true;
            for(int edge=0;edge<4;edge++)for(int step=0;step<=24;step++){
                double f=step/24.0,s=edge==0?f:edge==1?1:edge==2?1-f:0,t=edge==0?0:edge==1?f:edge==2?1:1-f;
                double[] g=warp.ground(s,t);BirdseyeCalibration.Projection p=calibration.project(camera,g[0],g[1]);
                if(p.weight>0){
                    if(!segment)path.moveTo(sx(p.u),sy(p.v));else path.lineTo(sx(p.u),sy(p.v));segment=true;
                }else{segment=false;complete=false;}
            }
            if(complete)path.close();
        }catch(IllegalArgumentException ignored){} // Keep handles editable when a selection is invalid.
    }
    @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){super.onSizeChanged(w,h,oldW,oldH);updateOutline();}
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event){
        if(points==null||listener==null)return false;fit();
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
            if(!imageRect.contains(event.getX(),event.getY()))return false;
            beforeDrag=points.clone();
            drag=next;double distance=40*getResources().getDisplayMetrics().density;
            for(int i=0;i<4;i++){double d=Math.hypot(event.getX()-sx(points[2*i]),event.getY()-sy(points[2*i+1]));if(d<distance){distance=d;drag=i;}}
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if(drag<0)return false;
        if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){points=beforeDrag;drag=-1;updateOutline();invalidate();return true;}
        points[2*drag]=Math.max(0,Math.min(719,(event.getX()-imageRect.left)/imageRect.width()*720));
        points[2*drag+1]=Math.max(0,Math.min(479,(event.getY()-imageRect.top)/imageRect.height()*480));updateOutline();invalidate();
        if(event.getActionMasked()==MotionEvent.ACTION_UP){next=(drag+1)%4;drag=-1;if(listener!=null)listener.accept(points.clone());performClick();}
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
