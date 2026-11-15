package com.drivehub.kamera.birdseye;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.R;
import com.drivehub.kamera.settings.UiPrefs;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicBoolean;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class RivelaUiInstrumentedTest {
    @Test public void parkingSwatchChangesBothViewsAndPersistsAcrossRecreation() throws Exception {
        SharedPreferences prefs=UiPrefs.getPrefs(InstrumentationRegistry.getInstrumentation().getTargetContext());
        java.util.Map<String,?> saved=prefs.getAll();
        prefs.edit().putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,true)
                .putString(UiPrefs.KEY_PARKING_BIRDSEYE_CAR_COLOR,BirdseyeCarColor.DOVER_WHITE.id).commit();
        java.util.List<android.graphics.Bitmap> captures=new java.util.ArrayList<>();
        try(ActivityScenario<BirdseyeDebugActivity> scenario=ActivityScenario.launch(BirdseyeDebugActivity.class)) {
            await3d(scenario);awaitDraws(scenario);android.graphics.Bitmap white=capture(scenario);captures.add(white);
            onView(withTagValue(org.hamcrest.Matchers.is((Object)BirdseyeCarColor.DIAMOND_RED.id))).perform(click());
            awaitDraws(scenario);android.graphics.Bitmap red=capture(scenario);captures.add(red);assertDifferent(white,red);
            assertEquals(BirdseyeCarColor.DIAMOND_RED,UiPrefs.getBirdseyeCarColor(prefs));
            onView(withId(R.id.surroundMode2d)).perform(click());awaitDraws(scenario);
            android.graphics.Bitmap red2d=capture(scenario);captures.add(red2d);
            onView(withTagValue(org.hamcrest.Matchers.is((Object)BirdseyeCarColor.PICCADILLY_BLUE.id))).perform(click());
            awaitDraws(scenario);android.graphics.Bitmap blue2d=capture(scenario);captures.add(blue2d);assertDifferent(red2d,blue2d);
            onView(withId(R.id.surroundMode3d)).perform(click());await3d(scenario);awaitDraws(scenario);
            android.graphics.Bitmap blue=capture(scenario);captures.add(blue);assertDifferent(red,blue);
            scenario.recreate();await3d(scenario);awaitDraws(scenario);
            onView(withTagValue(org.hamcrest.Matchers.is((Object)BirdseyeCarColor.PICCADILLY_BLUE.id))).check(matches(isSelected()));
            android.graphics.Bitmap recreated=capture(scenario);captures.add(recreated);
            assertEquals(0,pixelDifferences(blue,recreated));
        } finally {
            for(android.graphics.Bitmap b:captures)b.recycle();
            SharedPreferences.Editor edit=prefs.edit();
            for(String key:new String[]{UiPrefs.KEY_PARKING_SURROUND_3D,UiPrefs.KEY_PARKING_BIRDSEYE_CAR_COLOR}) {
                Object value=saved.get(key);
                if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
                else if(value instanceof String)edit.putString(key,(String)value);else edit.remove(key);
            }
            edit.commit();
        }
    }
    private static void awaitDraws(ActivityScenario<BirdseyeDebugActivity> scenario) {
        java.util.concurrent.atomic.AtomicLong target=new java.util.concurrent.atomic.AtomicLong();
        scenario.onActivity(a->target.set(a.renderer.renderedFrames+2));
        AtomicBoolean ready=new AtomicBoolean();long deadline=SystemClock.elapsedRealtime()+5000;
        do {
            scenario.onActivity(a->{assertNull(a.renderer.error);assertNull(a.renderer.threeDError);ready.set(a.renderer.renderedFrames>=target.get());});
            if(ready.get())return;SystemClock.sleep(25);
        }while(SystemClock.elapsedRealtime()<deadline);
        fail("Color update did not reach a new rendered frame");
    }
    private static android.graphics.Bitmap capture(ActivityScenario<BirdseyeDebugActivity> scenario) throws Exception {
        java.util.concurrent.atomic.AtomicReference<android.graphics.Bitmap> bitmap=new java.util.concurrent.atomic.AtomicReference<>();
        scenario.onActivity(a->bitmap.set(a.gl.getBitmap()));
        assertNotNull("TextureView did not expose the rendered vehicle",bitmap.get());
        return bitmap.get();
    }
    private static void assertDifferent(android.graphics.Bitmap a,android.graphics.Bitmap b) {assertTrue("Selected paint did not change the rendered vehicle",pixelDifferences(a,b)>200);}
    private static int pixelDifferences(android.graphics.Bitmap a,android.graphics.Bitmap b) {
        assertEquals(a.getWidth(),b.getWidth());assertEquals(a.getHeight(),b.getHeight());
        int[] x=new int[a.getWidth()*a.getHeight()],y=new int[x.length];
        a.getPixels(x,0,a.getWidth(),0,0,a.getWidth(),a.getHeight());b.getPixels(y,0,b.getWidth(),0,0,b.getWidth(),b.getHeight());
        int count=0;for(int i=0;i<x.length;i++)if(x[i]!=y[i])count++;return count;
    }
    @Test public void syntheticModesGesturesAndPersistedSelectionWorkWithPdc() {
        SharedPreferences prefs=UiPrefs.getPrefs(InstrumentationRegistry.getInstrumentation().getTargetContext());
        boolean had=prefs.contains(UiPrefs.KEY_PARKING_SURROUND_3D),saved=prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,false);
        prefs.edit().remove(UiPrefs.KEY_PARKING_SURROUND_3D).commit();
        try(ActivityScenario<BirdseyeDebugActivity> scenario=ActivityScenario.launch(BirdseyeDebugActivity.class)) {
            onView(withId(R.id.surroundMode2d)).check(matches(isSelected()));
            onView(withId(R.id.surroundMode3d)).perform(click());await3d(scenario);
            scenario.onActivity(a->{
                android.widget.SeekBar slider=a.findViewById(R.id.surroundDebugSteering);
                assertTrue(slider.isEnabled());slider.setProgress(60);assertEquals(25,a.renderer.steering.degrees(),0);
                assertTrue(((android.widget.TextView)a.findViewById(R.id.surroundDebugSteeringValue)).getText().toString().contains("25°"));
                slider.setProgress(5);assertEquals(-30,a.renderer.steering.degrees(),0);
            });
            onView(withId(R.id.surroundDebugSurface)).perform(swipeLeft());
            scenario.onActivity(a->assertNotEquals(35,a.renderer.orbit.values()[0],.1));
            onView(withId(R.id.surroundDebugSurface)).perform(new androidx.test.espresso.ViewAction(){
                public org.hamcrest.Matcher<android.view.View> getConstraints(){return isDisplayed();}
                public String getDescription(){return "spread two fingers to zoom";}
                public void perform(androidx.test.espresso.UiController controller,android.view.View view){pinchOut(view,controller);}
            });
            scenario.onActivity(a->assertTrue("Pinch must zoom the rendered view: "+a.renderer.orbit.values()[2],a.renderer.orbit.values()[2]<11000));
            onView(withId(R.id.surroundDebugSurface)).perform(doubleClick());
            scenario.onActivity(a->assertArrayEquals(new float[]{35,55,11000},a.renderer.orbit.values(),.1f));
            onView(withId(R.id.surroundDebugPdc)).perform(click());
            scenario.recreate();onView(withId(R.id.surroundMode3d)).check(matches(isSelected()));await3d(scenario);
            scenario.onActivity(a->assertEquals(-30,a.renderer.steering.degrees(),0));
            onView(withId(R.id.surroundDebugLiveSteering)).perform(click());
            onView(withId(R.id.surroundDebugSteering)).check(matches(org.hamcrest.Matchers.not(isEnabled())));
            onView(withId(R.id.surroundDebugSteeringReset)).check(matches(org.hamcrest.Matchers.not(isEnabled())));
            // The Automotive emulator has no SAIC vendor property. Do not retain the manual angle.
            SystemClock.sleep(600);
            scenario.onActivity(a->{assertEquals(0,a.renderer.steering.degrees(),0);
                assertFalse(((android.widget.TextView)a.findViewById(R.id.surroundDebugSteeringStatus)).getText().toString().isEmpty());});
            scenario.recreate();await3d(scenario);
            onView(withId(R.id.surroundDebugLiveSteering)).check(matches(isChecked())).perform(click());
            scenario.onActivity(a->assertEquals(-30,a.renderer.steering.degrees(),0));
            onView(withId(R.id.surroundDebugSteeringReset)).perform(click());
            scenario.onActivity(a->assertEquals(0,a.renderer.steering.degrees(),0));
            onView(withId(R.id.surroundMode2d)).perform(click());
            onView(withId(R.id.surroundMode2d)).check(matches(isSelected()));
            onView(withId(R.id.surroundDebugSteering)).check(matches(org.hamcrest.Matchers.not(isEnabled())));
            assertFalse(prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,true));
        }finally{SharedPreferences.Editor edit=prefs.edit();if(had)edit.putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,saved);else edit.remove(UiPrefs.KEY_PARKING_SURROUND_3D);edit.commit();}
    }
    /** Deliver a two-finger sequence through the surface's actual gesture listener. */
    private static void pinchOut(android.view.View view,androidx.test.espresso.UiController controller) {
        android.view.MotionEvent.PointerProperties[] properties=new android.view.MotionEvent.PointerProperties[2];
        android.view.MotionEvent.PointerCoords[] points=new android.view.MotionEvent.PointerCoords[2];
        for(int i=0;i<2;i++) {
            properties[i]=new android.view.MotionEvent.PointerProperties();properties[i].id=i;
            properties[i].toolType=android.view.MotionEvent.TOOL_TYPE_FINGER;
            points[i]=new android.view.MotionEvent.PointerCoords();points[i].pressure=1;points[i].size=1;points[i].y=view.getHeight()/2f;
        }
        long down=SystemClock.uptimeMillis();float center=view.getWidth()/2f,span=view.getWidth()*.08f;
        points[0].x=center-span;points[1].x=center+span;
        touch(view,down,android.view.MotionEvent.ACTION_DOWN,1,properties,points);
        controller.loopMainThreadForAtLeast(24);
        touch(view,down,android.view.MotionEvent.ACTION_POINTER_DOWN|(1<<android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,properties,points);
        controller.loopMainThreadForAtLeast(24);
        for(int i=1;i<=8;i++) {
            float distance=span*(1+i*.5f);points[0].x=center-distance;points[1].x=center+distance;
            touch(view,down,android.view.MotionEvent.ACTION_MOVE,2,properties,points);
            controller.loopMainThreadForAtLeast(24);
        }
        touch(view,down,android.view.MotionEvent.ACTION_POINTER_UP|(1<<android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,properties,points);
        touch(view,down,android.view.MotionEvent.ACTION_UP,1,properties,points);
    }
    private static void touch(android.view.View view,long down,int action,int count,
            android.view.MotionEvent.PointerProperties[] properties,android.view.MotionEvent.PointerCoords[] points) {
        android.view.MotionEvent event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,count,properties,points,
                0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
        try{view.dispatchTouchEvent(event);}finally{event.recycle();}
    }
    private static void await3d(ActivityScenario<BirdseyeDebugActivity> scenario) {
        long deadline=SystemClock.elapsedRealtime()+20000;AtomicBoolean active=new AtomicBoolean();
        do {
            scenario.onActivity(a->{assertNull(a.preparationError);assertNull(a.renderer.error);assertNull(a.renderer.threeDError);active.set(a.renderer.threeDActive);});
            if(active.get())return;SystemClock.sleep(50);
        }while(SystemClock.elapsedRealtime()<deadline);
        assertTrue("3D renderer did not become active",active.get());
    }
}
