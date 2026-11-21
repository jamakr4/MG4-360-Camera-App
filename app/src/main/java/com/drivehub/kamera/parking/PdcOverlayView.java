package com.drivehub.kamera.parking;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;
import com.drivehub.kamera.birdseye.RivelaScene;
import com.drivehub.kamera.rivela.GroundProjector;
import com.drivehub.kamera.rivela.PdcBars;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
/** Ground-projected rear bands over the existing full-surface, horizontally mirrored rear feed. */
public final class PdcOverlayView extends View {
    private static volatile String projectionState="inactive";
    private static volatile int inputBars,projectedPolygons;
    private static volatile long projectionAtMs;
    public static String projectionDiagnostic() {
        return "Main rear PDC projection: "+projectionState+"; bars="+inputBars+", polygons="+projectedPolygons
                +"; updated "+(projectionAtMs==0?"never":Math.max(0,SystemClock.elapsedRealtime()-projectionAtMs)+"ms ago");
    }
    private static void recordProjection(String state,int bars,int polygons) {
        inputBars=bars;projectedPolygons=polygons;projectionState=state;projectionAtMs=SystemClock.elapsedRealtime();
    }
    private Supplier<RivelaScene> calibratedScene=()->null;
    private PdcGeometry pdc;
    private TextView status;
    private BooleanSupplier rearShown=()->false;
    private LongSupplier mainPreviewFrameAgeMs=()->-1;
    private boolean active;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private String lastStatus="";
    private final Runnable tick=new Runnable(){public void run(){if(!active)return;updateStatus();invalidate();main.postDelayed(this,50);}};
    public PdcOverlayView(Context context,AttributeSet attrs) {super(context,attrs);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    /** rearShown includes reverse gear; the age must belong to the displayed main output. */
    public void bind(Supplier<RivelaScene> calibratedScene,PdcGeometry pdc,TextView status,
            BooleanSupplier rearShown,LongSupplier mainPreviewFrameAgeMs) {
        this.calibratedScene=calibratedScene;this.pdc=pdc;this.status=status;
        this.rearShown=rearShown;this.mainPreviewFrameAgeMs=mainPreviewFrameAgeMs;
    }
    public void resumeDisplay() {active=true;main.removeCallbacks(tick);main.post(tick);}
    public void pauseDisplay() {active=false;main.removeCallbacks(tick);if(status!=null)status.setVisibility(GONE);recordProjection("paused",0,0);invalidate();}
    void updateStatus() {
        if(status==null||pdc==null)return;
        PdcSnapshot snapshot=pdc.snapshot();
        boolean show=active&&rearShown.getAsBoolean()&&snapshot.fresh(SystemClock.elapsedRealtime())&&snapshot.hasSectors();
        status.setVisibility(show?VISIBLE:GONE);
        if(!show)return;
        android.text.SpannableStringBuilder text=new android.text.SpannableStringBuilder();
        StringBuilder key=new StringBuilder();
        for(int i=0;i<4;i++) {
            int level=snapshot.level(i),start=text.length(),band=PdcBars.band(level);
            text.append(level<0?"–":level==0?"○":"●");
            text.setSpan(new android.text.style.ForegroundColorSpan(band<0?0xFFA0A0A0:PdcBars.BAND_COLOR[band]),start,text.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if(i<3)text.append("   ");key.append(level).append('/');
        }
        if(snapshot.closestCm>0&&snapshot.hasObstacle())text.append("   ").append(Integer.toString(snapshot.closestCm)).append(" cm");
        key.append(snapshot.closestCm);
        if(!key.toString().equals(lastStatus)) {
            lastStatus=key.toString();status.setText(text);
            StringBuilder spoken=new StringBuilder("PDC ");
            for(int i=0;i<4;i++){if(i>0)spoken.append(", ");spoken.append(snapshot.level(i)<0?"–":Integer.toString(snapshot.level(i)));}
            if(snapshot.closestCm>0&&snapshot.hasObstacle())spoken.append("; ").append(snapshot.closestCm).append(" cm");
            status.setContentDescription(spoken);
        }
    }
    public static float[] imageToView(float u,float v,int width,int height) {
        return new float[]{(719-u)*width/720f,v*height/480f};
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if(!active||pdc==null||!rearShown.getAsBoolean()){recordProjection("inactive or not showing rear in R",0,0);return;}
        long age=mainPreviewFrameAgeMs.getAsLong();
        if(age<0||age>500){recordProjection("no fresh main camera frame",0,0);return;}
        RivelaScene scene=calibratedScene.get();if(scene==null){recordProjection("no calibration",0,0);return;}
        List<Integer> colors=new ArrayList<>();
        List<PdcBars.Bar> bars=pdc.bars(scene.car,SystemClock.elapsedRealtime());
        List<float[]> polys=GroundProjector.bars(scene.camera(3),bars,colors);
        recordProjection("projected",bars.size(),polys.size());
        for(int i=0;i<polys.size();i++) {
            float[] points=polys.get(i);path.reset();
            for(int k=0;k<points.length;k+=2) {
                float[] p=imageToView(points[k],points[k+1],getWidth(),getHeight());
                if(k==0)path.moveTo(p[0],p[1]);else path.lineTo(p[0],p[1]);
            }
            path.close();paint.setColor(colors.get(i));canvas.drawPath(path,paint);
        }
    }
}
