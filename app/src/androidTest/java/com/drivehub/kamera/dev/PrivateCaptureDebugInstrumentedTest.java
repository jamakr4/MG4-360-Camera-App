package com.drivehub.kamera.dev;

import android.content.*;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.R;
import com.drivehub.kamera.birdseye.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.*;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;

/** Only generated fixtures. Never reads the developer's private vehicle recordings. */
@RunWith(AndroidJUnit4.class)
public class PrivateCaptureDebugInstrumentedTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private File fixture()throws Exception{
        CameraSnapshotCapture.Frame[] frames=new CameraSnapshotCapture.Frame[4];
        for(int c=0;c<4;c++){
            byte[] raw=new byte[CameraSnapshotCapture.BYTES];
            for(int y=0;y<480;y++)for(int x=0;x<720;x+=2){int i=(y*720+x)*2;raw[i]=(byte)128;raw[i+1]=(byte)(y<240?16:235);raw[i+2]=(byte)128;raw[i+3]=raw[i+1];}
            frames[c]=new CameraSnapshotCapture.Frame(raw,100000+c*10000,c,5,1440);
        }
        return CameraSnapshotExporter.write(context.getFilesDir(),new CameraSnapshotCapture.Set(frames,90000),BirdseyeSynthetic.calibrationBytes(),"generated test fixture");
    }
    @Test public void privateReplayValidatesHashesExtractsTrueTopFieldAndRejectsPathTraversal()throws Exception{
        File fixture=fixture();
        try{
            try(PrivateCameraCapture capture=PrivateCameraCapture.load(context,fixture.getName())){
                assertEquals(720,capture.image(0).getWidth());assertEquals(480,capture.image(0).getHeight());
                assertEquals(0xFF000000,capture.image(0).getPixel(0,0));assertEquals(0xFFFFFFFF,capture.image(0).getPixel(0,1));
                ByteBuffer output=ByteBuffer.allocateDirect(720*240*2);long time=capture.copy(0,output,0);assertEquals(capture.clockUs(),time);
                for(int y=0;y<240;y++)assertEquals(16,output.get(y*1440+1)&255);
                assertEquals(0,capture.copy(0,output,time));assertTrue(capture.allowed());
            }
            try{PrivateCameraCapture.load(context,"../outside");fail();}catch(IOException expected){}
            try(RandomAccessFile raw=new RandomAccessFile(new File(fixture,"left.uyvy"),"rw")){raw.write(99);}
            try{PrivateCameraCapture.load(context,fixture.getName());fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("checksum"));}
        }finally{remove(fixture);}
    }
    private static Object field(Object target,String name){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(Exception e){throw new AssertionError(e);}}
    private static <A extends android.app.Activity> void await(ActivityScenario<A> scenario,java.util.function.Predicate<A> condition){
        AtomicBoolean ready=new AtomicBoolean();long end=SystemClock.elapsedRealtime()+12000;
        do{scenario.onActivity(a->ready.set(condition.test(a)));if(ready.get())return;SystemClock.sleep(25);}while(SystemClock.elapsedRealtime()<end);
        fail("Private debug did not settle");
    }
    @Test public void recordedSourceSwitchAndBothSideGroundPatchesWorkWithoutBundledImages()throws Exception{
        File fixture=fixture();
        try{
            Intent intent=new Intent(context,BirdseyeDebugActivity.class).putExtra(PrivateCameraCapture.EXTRA_SET,fixture.getName());
            try(ActivityScenario<BirdseyeDebugActivity> scenario=ActivityScenario.launch(intent)){
                await(scenario,a->field(a,"recording")!=null&&((BirdseyeRenderer)field(a,"renderer")).freshMask==15);
                onView(withId(R.id.privateDebugSynthetic)).perform(click());
                await(scenario,a->field(a,"recording")==null&&((BirdseyeRenderer)field(a,"renderer")).freshMask==15);
                onView(withId(R.id.privateDebugRecording)).perform(click());onView(withText(fixture.getName())).perform(click());
                await(scenario,a->field(a,"recording")!=null&&((BirdseyeRenderer)field(a,"renderer")).freshMask==15);
                scenario.recreate();await(scenario,a->field(a,"recording")!=null&&((BirdseyeRenderer)field(a,"renderer")).freshMask==15);
            }
            Intent curb=new Intent(context,CurbsideDebugActivity.class).putExtra(PrivateCameraCapture.EXTRA_SET,fixture.getName());
            try(ActivityScenario<CurbsideDebugActivity> scenario=ActivityScenario.launch(curb)){
                await(scenario,a->((TextView)a.findViewById(R.id.curbsideDebugStatus)).getText().toString().contains("%"));
                scenario.onActivity(a->{assertEquals(0,((Spinner)a.findViewById(R.id.curbsideDebugRegion)).getSelectedItemPosition());assertEquals(2,((Spinner)a.findViewById(R.id.curbsideDebugProjection)).getSelectedItemPosition());assertNull(field(a.findViewById(R.id.curbsideDebugInput),"listener"));((Spinner)a.findViewById(R.id.curbsideDebugSide)).setSelection(1);((Spinner)a.findViewById(R.id.curbsideDebugRegion)).setSelection(1);});
                await(scenario,a->((Spinner)a.findViewById(R.id.curbsideDebugSide)).getSelectedItemPosition()==1&&((TextView)a.findViewById(R.id.curbsideDebugStatus)).getText().toString().contains("%"));
                scenario.onActivity(a->{assertEquals(1,((Spinner)a.findViewById(R.id.curbsideDebugRegion)).getSelectedItemPosition());assertEquals(2,((Spinner)a.findViewById(R.id.curbsideDebugProjection)).getSelectedItemPosition());((Spinner)a.findViewById(R.id.curbsideDebugProjection)).setSelection(1);});
                await(scenario,a->((TextView)a.findViewById(R.id.curbsideDebugStatus)).getText().toString().contains("%"));
                onView(withId(R.id.curbsideDebugInput)).perform(new androidx.test.espresso.ViewAction(){
                    @Override public org.hamcrest.Matcher<android.view.View> getConstraints(){return isDisplayed();}
                    @Override public String getDescription(){return "Drag a ground point through letterboxed screen coordinates";}
                    @Override public void perform(androidx.test.espresso.UiController ui,android.view.View view){
                        double[] before=((double[])field(view,"points")).clone();
                        android.graphics.RectF rect=(android.graphics.RectF)field(view,"imageRect");
                        float x=rect.left+(float)before[0]/720*rect.width(),y=rect.top+(float)before[1]/480*rect.height();
                        float target=x+5*rect.width()/720;long time=SystemClock.uptimeMillis();
                        int[] actions={android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_MOVE,android.view.MotionEvent.ACTION_UP};
                        for(int i=0;i<actions.length;i++){
                            android.view.MotionEvent event=android.view.MotionEvent.obtain(time,time+i*20,actions[i],i==0?x:target,y,0);
                            try{assertTrue(view.dispatchTouchEvent(event));}finally{event.recycle();}
                        }
                        double[] after=(double[])field(view,"points");assertEquals(before[0]+5,after[0],.002);assertEquals(before[1],after[1],.002);
                        ui.loopMainThreadUntilIdle();
                    }
                });
                onView(withId(R.id.curbsideDebugReset)).perform(click());
                await(scenario,a->((TextView)a.findViewById(R.id.curbsideDebugStatus)).getText().toString().contains("%"));
                scenario.recreate();await(scenario,a->((TextView)a.findViewById(R.id.curbsideDebugStatus)).getText().toString().contains("%"));
            }
        }finally{remove(fixture);}
    }
    @Test public void wholeSidesCanBeSelectedOnBothCamerasAndSurviveRecreation()throws Exception{
        File fixture=fixture();
        try{
            Intent intent=new Intent(context,CurbsideDebugActivity.class).putExtra(PrivateCameraCapture.EXTRA_SET,fixture.getName()).putExtra("curbside_region",0);
            try(ActivityScenario<CurbsideDebugActivity> scenario=ActivityScenario.launch(intent)){
                await(scenario,a->field(a,"shownOutput") instanceof Bitmap&&((Bitmap)field(a,"shownOutput")).getHeight()==456);
                scenario.onActivity(a->{assertEquals(2,((Spinner)a.findViewById(R.id.curbsideDebugRegion)).getSelectedItemPosition());assertEquals(2,((Spinner)a.findViewById(R.id.curbsideDebugProjection)).getSelectedItemPosition());assertNull(field(a.findViewById(R.id.curbsideDebugInput),"listener"));((Spinner)a.findViewById(R.id.curbsideDebugSide)).setSelection(1);});
                await(scenario,a->((Spinner)a.findViewById(R.id.curbsideDebugSide)).getSelectedItemPosition()==1&&((TextView)a.findViewById(R.id.curbsideDebugStatus)).getText().toString().contains("%"));
                scenario.recreate();
                await(scenario,a->field(a,"shownOutput") instanceof Bitmap&&((Bitmap)field(a,"shownOutput")).getHeight()==456);
                scenario.onActivity(a->{assertEquals(1,((Spinner)a.findViewById(R.id.curbsideDebugSide)).getSelectedItemPosition());assertEquals(2,((Spinner)a.findViewById(R.id.curbsideDebugRegion)).getSelectedItemPosition());assertEquals(254,((Bitmap)field(a,"shownOutput")).getWidth());assertEquals(2,((Spinner)a.findViewById(R.id.curbsideDebugProjection)).getSelectedItemPosition());});
            }
        }finally{remove(fixture);}
    }
    private void remove(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);file.delete();}
}
