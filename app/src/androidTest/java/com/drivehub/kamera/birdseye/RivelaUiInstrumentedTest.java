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
    @Test public void debugSpeedChangesDoNotAnimateVehicleAndPersistAcrossRecreation() throws Exception {
        SharedPreferences prefs=UiPrefs.getPrefs(InstrumentationRegistry.getInstrumentation().getTargetContext());
        boolean had=prefs.contains(UiPrefs.KEY_PARKING_SURROUND_3D),saved=prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,false);
        prefs.edit().putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,true).commit();
        java.util.List<android.graphics.Bitmap> pictures=new java.util.ArrayList<>();
        try(ActivityScenario<BirdseyeDebugActivity> scenario=ActivityScenario.launch(BirdseyeDebugActivity.class)) {
            await3d(scenario);
            scenario.onActivity(a->((android.widget.CheckBox)a.findViewById(R.id.surroundDebugGuideSpeedLimit)).setChecked(false));
            awaitDraws(scenario);android.graphics.Bitmap before=capture(scenario);pictures.add(before);
            scenario.onActivity(a->((android.widget.SeekBar)a.findViewById(R.id.surroundDebugGuideSpeed)).setProgress(100));
            awaitDraws(scenario);SystemClock.sleep(100);
            android.graphics.Bitmap after=capture(scenario);pictures.add(after);assertEquals(0,pixelDifferences(before,after));
            scenario.recreate();await3d(scenario);awaitDraws(scenario);
            scenario.onActivity(a->assertEquals(100,((android.widget.SeekBar)a.findViewById(R.id.surroundDebugGuideSpeed)).getProgress()));
        }finally {
            for(android.graphics.Bitmap b:pictures)b.recycle();
            SharedPreferences.Editor edit=prefs.edit();if(had)edit.putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,saved);else edit.remove(UiPrefs.KEY_PARKING_SURROUND_3D);edit.commit();
        }
    }
    @Test public void debugSpeedThresholdAnimatesBothGearsAndViewsAndKeepsTheManualChoice() {
        SharedPreferences prefs=UiPrefs.getPrefs(InstrumentationRegistry.getInstrumentation().getTargetContext());
        java.util.Map<String,?> saved=prefs.getAll();
        prefs.edit().putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,false).putInt(UiPrefs.KEY_PARKING_GUIDE_SPEED_KMH,25).commit();
        try(ActivityScenario<BirdseyeDebugActivity> scenario=ActivityScenario.launch(BirdseyeDebugActivity.class)) {
            scenario.onActivity(a->{((android.widget.CheckBox)a.findViewById(R.id.surroundDebugGuideSpeedLimit)).setChecked(true);
                ((android.widget.SeekBar)a.findViewById(R.id.surroundDebugGuideSpeed)).setProgress(250);});
            for(boolean threeD:new boolean[]{false,true})for(boolean forward:new boolean[]{false,true}) {
                scenario.onActivity(a->{a.findViewById(threeD?R.id.surroundMode3d:R.id.surroundMode2d).performClick();
                    a.findViewById(forward?R.id.surroundDebugGuideForward:R.id.surroundDebugGuideReverse).performClick();});
                if(threeD)await3d(scenario);awaitGuideLevel(scenario,1);
                scenario.onActivity(a->((android.widget.SeekBar)a.findViewById(R.id.surroundDebugGuideSpeed)).setProgress(251));
                awaitGuideLevel(scenario,0);
                scenario.onActivity(a->{assertTrue(((android.widget.CheckBox)a.findViewById(R.id.surroundDebugReverseGuides)).isChecked());
                    assertTrue(a.findViewById(R.id.surroundDebugGuideSpeed).isEnabled());
                    ((android.widget.SeekBar)a.findViewById(R.id.surroundDebugGuideSpeed)).setProgress(250);});
                awaitGuideLevel(scenario,1);
            }
            scenario.recreate();await3d(scenario);awaitGuideLevel(scenario,1);
            scenario.onActivity(a->{assertTrue(((android.widget.CheckBox)a.findViewById(R.id.surroundDebugGuideSpeedLimit)).isChecked());
                assertEquals(250,((android.widget.SeekBar)a.findViewById(R.id.surroundDebugGuideSpeed)).getProgress());});
        }finally {
            SharedPreferences.Editor edit=prefs.edit();
            for(String key:new String[]{UiPrefs.KEY_PARKING_SURROUND_3D,UiPrefs.KEY_PARKING_GUIDE_SPEED_KMH}) {
                Object value=saved.get(key);if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
                else if(value instanceof Integer)edit.putInt(key,(Integer)value);else edit.remove(key);
            }
            edit.commit();
        }
    }
    @Test public void debugParkAnimatesBothDirectionsAndStylesInTwoDAndThreeD() {
        SharedPreferences prefs=UiPrefs.getPrefs(InstrumentationRegistry.getInstrumentation().getTargetContext());
        boolean had=prefs.contains(UiPrefs.KEY_PARKING_SURROUND_3D),saved=prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,false);
        prefs.edit().putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,true).commit();
        try(ActivityScenario<BirdseyeDebugActivity> scenario=ActivityScenario.launch(BirdseyeDebugActivity.class)) {
            await3d(scenario);
            for(boolean threeD:new boolean[]{false,true})for(boolean simple:new boolean[]{false,true})
                for(int direction:new int[]{R.id.surroundDebugGuideForward,R.id.surroundDebugGuideReverse}) {
                    scenario.onActivity(a->{a.findViewById(threeD?R.id.surroundMode3d:R.id.surroundMode2d).performClick();
                        ((android.widget.CheckBox)a.findViewById(R.id.surroundDebugBlueCorridor)).setChecked(simple);
                        ((android.widget.SeekBar)a.findViewById(R.id.surroundDebugGuideSpeed)).setProgress(100);
                        a.findViewById(direction).performClick();});
                    awaitDraws(scenario);awaitGuideLevel(scenario,1);
                    scenario.onActivity(a->a.findViewById(R.id.surroundDebugGuidePark).performClick());
                    awaitGuideRetraction(scenario);
                    scenario.onActivity(a->{assertTrue(a.steeringFrame.available);
                        assertTrue(((android.widget.RadioButton)a.findViewById(R.id.surroundDebugGuidePark)).isChecked());});
                    SystemClock.sleep(60);
                    // Returning during retraction must resume the shared animation rather than reset it.
                    scenario.onActivity(a->a.findViewById(direction).performClick());
                    awaitGuideLevel(scenario,1);
                    scenario.onActivity(a->a.findViewById(R.id.surroundDebugGuidePark).performClick());
                    awaitGuideRetraction(scenario);awaitGuideLevel(scenario,0);
                }
            scenario.recreate();await3d(scenario);awaitDraws(scenario);awaitGuideLevel(scenario,0);
            scenario.onActivity(a->{assertTrue(((android.widget.RadioButton)a.findViewById(R.id.surroundDebugGuidePark)).isChecked());
                a.findViewById(R.id.surroundDebugGuideReverse).performClick();});
            awaitGuideLevel(scenario,1);
        }finally {
            SharedPreferences.Editor edit=prefs.edit();if(had)edit.putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,saved);else edit.remove(UiPrefs.KEY_PARKING_SURROUND_3D);edit.commit();
        }
    }
    private static void awaitGuideRetraction(ActivityScenario<BirdseyeDebugActivity> scenario) {
        long deadline=SystemClock.elapsedRealtime()+2000;AtomicBoolean ready=new AtomicBoolean();
        do {
            scenario.onActivity(a->ready.set(a.renderer.guideProgress>.001&&a.renderer.guideProgress<.999));
            if(ready.get())return;SystemClock.sleep(10);
        }while(SystemClock.elapsedRealtime()<deadline);
        fail("P removed the path immediately instead of animating it out");
    }
    private static void awaitGuideLevel(ActivityScenario<BirdseyeDebugActivity> scenario,float target) {
        long deadline=SystemClock.elapsedRealtime()+5000;AtomicBoolean ready=new AtomicBoolean();
        do {
            scenario.onActivity(a->ready.set(a.renderer.renderedFrames>0&&Math.abs(a.renderer.guideProgress-target)<.00001&&a.renderer.outgoingGuideProgress<.00001));
            if(ready.get())return;SystemClock.sleep(25);
        }while(SystemClock.elapsedRealtime()<deadline);
        fail("Guideline animation did not reach "+target);
    }
    private static android.graphics.Bitmap capture(ActivityScenario<BirdseyeDebugActivity> scenario) throws Exception {
        awaitGuideLevel(scenario,1); // Compare settled styles/paint rather than different reveal frames.
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
                assertTrue(slider.isEnabled());slider.setProgress(BirdseyeDebugActivity.STEERING_CENTER+3986);
                assertEquals(199.3f,a.manualSteeringDegrees(),0);
                assertTrue(((android.widget.TextView)a.findViewById(R.id.surroundDebugSteeringValue)).getText().toString().contains("199"));
                slider.setProgress(BirdseyeDebugActivity.STEERING_CENTER-3986);
            });
            awaitSteering(scenario,-199.3f);
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
            onView(withId(R.id.surroundDebugPdc)).perform(scrollTo(),click());
            scenario.recreate();onView(withId(R.id.surroundMode3d)).check(matches(isSelected()));await3d(scenario);
            awaitSteering(scenario,-199.3f);
            onView(withId(R.id.surroundDebugLiveSteering)).perform(scrollTo(),click());
            onView(withId(R.id.surroundDebugSteering)).check(matches(org.hamcrest.Matchers.not(isEnabled())));
            onView(withId(R.id.surroundDebugSteeringReset)).check(matches(org.hamcrest.Matchers.not(isEnabled())));
            // The Automotive emulator has no SAIC vendor property. Do not retain the manual angle.
            SystemClock.sleep(600);
            scenario.onActivity(a->{assertEquals(0,a.renderer.steering.degrees(),0);
                assertFalse(((android.widget.TextView)a.findViewById(R.id.surroundDebugSteeringStatus)).getText().toString().isEmpty());});
            scenario.recreate();await3d(scenario);
            onView(withId(R.id.surroundDebugLiveSteering)).check(matches(isChecked())).perform(scrollTo(),click());
            awaitSteering(scenario,-199.3f);
            onView(withId(R.id.surroundDebugSteeringReset)).perform(scrollTo(),click());
            awaitSteering(scenario,0);
            onView(withId(R.id.surroundMode2d)).perform(scrollTo(),click());
            onView(withId(R.id.surroundMode2d)).check(matches(isSelected()));
            onView(withId(R.id.surroundDebugSteering)).check(matches(isEnabled()));
            onView(withId(R.id.surroundDebugSteeringFullLeft)).perform(scrollTo(),click());awaitSteering(scenario,516.95f);
            assertFalse(prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,true));
        }finally{SharedPreferences.Editor edit=prefs.edit();if(had)edit.putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,saved);else edit.remove(UiPrefs.KEY_PARKING_SURROUND_3D);edit.commit();}
    }
    @Test public void sensorPresetsHoldOnOutageSurviveRecreationAndRecoverToOppositeLock() {
        SharedPreferences prefs=UiPrefs.getPrefs(InstrumentationRegistry.getInstrumentation().getTargetContext());
        boolean had=prefs.contains(UiPrefs.KEY_PARKING_SURROUND_3D),saved=prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,false);
        prefs.edit().putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,true).commit();
        try(ActivityScenario<BirdseyeDebugActivity> scenario=ActivityScenario.launch(BirdseyeDebugActivity.class)) {
            await3d(scenario);
            onView(withId(R.id.surroundDebugReverseGuides)).check(matches(isChecked())).perform(scrollTo(),click());
            scenario.recreate();await3d(scenario);
            onView(withId(R.id.surroundDebugReverseGuides)).check(matches(org.hamcrest.Matchers.not(isChecked()))).perform(scrollTo(),click());
            onView(withId(R.id.surroundDebugGuideReverse)).check(matches(isChecked()));
            onView(withId(R.id.surroundDebugGuideForward)).perform(scrollTo(),click());
            scenario.recreate();await3d(scenario);
            onView(withId(R.id.surroundDebugGuideForward)).check(matches(isChecked()));
            onView(withId(R.id.surroundDebugGuideReverse)).perform(scrollTo(),click());
            onView(withId(R.id.surroundDebugSteeringFullLeft)).perform(scrollTo(),click());
            awaitSteering(scenario,516.95f);
            scenario.onActivity(a->{
                assertEquals(-38.0802,a.renderer.steering.angles().left,.01);
                assertEquals(-28.8493,a.renderer.steering.angles().right,.01);
            });
            onView(withId(R.id.surroundDebugSteeringUnavailable)).perform(scrollTo(),click());
            awaitDraws(scenario);
            java.util.concurrent.atomic.AtomicReference<WheelSteering.Angles> held=new java.util.concurrent.atomic.AtomicReference<>();
            scenario.onActivity(a->{assertFalse(a.steeringFrame.available);assertFalse(a.renderer.steering.angles().guidanceAvailable);held.set(a.renderer.steering.angles());});
            onView(withId(R.id.surroundDebugSteeringFullRight)).perform(scrollTo(),click());
            awaitDraws(scenario);
            scenario.onActivity(a->assertEquals(held.get().left,a.renderer.steering.angles().left,0));
            scenario.recreate();await3d(scenario);awaitDraws(scenario);
            scenario.onActivity(a->{
                assertFalse(a.steeringFrame.available);assertTrue(a.steeringFrame.hasValue);
                assertEquals(held.get().left,a.renderer.steering.angles().left,0);
                assertEquals(-516.95f,a.manualSteeringDegrees(),0);
                assertTrue(((android.widget.TextView)a.findViewById(R.id.surroundDebugSteeringStatus)).getText().toString()
                        .contains(a.getString(R.string.birdseye_debug_steering_unavailable)));
            });
            onView(withId(R.id.surroundDebugSteeringUnavailable)).perform(scrollTo(),click());
            awaitSteering(scenario,-516.95f);
            onView(withId(R.id.surroundDebugSteeringMidLeft)).perform(scrollTo(),click());awaitSteering(scenario,199.3f);
            onView(withId(R.id.surroundDebugSteeringMidRight)).perform(scrollTo(),click());awaitSteering(scenario,-199.3f);
            onView(withId(R.id.surroundDebugSteeringReset)).perform(scrollTo(),click());awaitSteering(scenario,0);
        }finally{SharedPreferences.Editor edit=prefs.edit();if(had)edit.putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,saved);else edit.remove(UiPrefs.KEY_PARKING_SURROUND_3D);edit.commit();}
    }
    private static void awaitSteering(ActivityScenario<BirdseyeDebugActivity> scenario,float raw) {
        com.drivehub.kamera.steering.SteeringModel.Solution expected=new com.drivehub.kamera.steering.SteeringModel().solve(raw);
        AtomicBoolean ready=new AtomicBoolean();long deadline=SystemClock.elapsedRealtime()+5000;
        do {
            scenario.onActivity(a->{WheelSteering.Angles actual=a.renderer.steering.angles();
                assertNull(a.renderer.error);ready.set(a.steeringFrame.available&&Math.abs(actual.left-expected.leftDegrees)<.001
                        &&Math.abs(actual.right-expected.rightDegrees)<.001);});
            if(ready.get())return;SystemClock.sleep(25);
        }while(SystemClock.elapsedRealtime()<deadline);
        fail("Sensor pose did not settle: "+raw);
    }
    @Test public void blueCorridorCheckboxChangesOnlyStyleAndPersistsAcrossRecreation() throws Exception {
        SharedPreferences prefs=UiPrefs.getPrefs(InstrumentationRegistry.getInstrumentation().getTargetContext());
        boolean had=prefs.contains(UiPrefs.KEY_PARKING_SURROUND_3D),saved=prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,false);
        String savedStyle=prefs.getString(UiPrefs.KEY_PARKING_GUIDE_STYLE,null);
        prefs.edit().putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,true).remove(UiPrefs.KEY_PARKING_GUIDE_STYLE).commit();
        java.util.List<android.graphics.Bitmap> pictures=new java.util.ArrayList<>();
        try(ActivityScenario<BirdseyeDebugActivity> scenario=ActivityScenario.launch(BirdseyeDebugActivity.class)) {
            await3d(scenario);awaitDraws(scenario);
            onView(withId(R.id.surroundDebugBlueCorridor)).check(matches(isChecked())).perform(scrollTo(),click());
            awaitDraws(scenario);android.graphics.Bitmap separate=capture(scenario);pictures.add(separate);
            scenario.onActivity(a->a.findViewById(R.id.surroundDebugReverseGuides).performClick());
            awaitGuideLevel(scenario,0);
            java.util.concurrent.atomic.AtomicReference<android.graphics.Bitmap> bare=new java.util.concurrent.atomic.AtomicReference<>();
            scenario.onActivity(a->bare.set(a.gl.getBitmap()));assertNotNull(bare.get());pictures.add(bare.get());
            assertLightBlueGuidelines(separate,bare.get());
            scenario.onActivity(a->a.findViewById(R.id.surroundDebugReverseGuides).performClick());
            awaitGuideLevel(scenario,1);
            onView(withId(R.id.surroundDebugBlueCorridor)).check(matches(org.hamcrest.Matchers.not(isChecked()))).perform(scrollTo(),click());
            awaitDraws(scenario);android.graphics.Bitmap blue=capture(scenario);pictures.add(blue);assertDifferent(separate,blue);
            scenario.recreate();await3d(scenario);awaitDraws(scenario);
            onView(withId(R.id.surroundDebugBlueCorridor)).check(matches(isChecked()));
            android.graphics.Bitmap restored=capture(scenario);pictures.add(restored);assertEquals(0,pixelDifferences(blue,restored));
            onView(withId(R.id.surroundDebugBlueCorridor)).perform(scrollTo(),click());awaitDraws(scenario);
            android.graphics.Bitmap comparison=capture(scenario);pictures.add(comparison);assertEquals(0,pixelDifferences(separate,comparison));
        }finally {
            for(android.graphics.Bitmap b:pictures)b.recycle();
            SharedPreferences.Editor edit=prefs.edit();if(had)edit.putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,saved);else edit.remove(UiPrefs.KEY_PARKING_SURROUND_3D);
            if(savedStyle==null)edit.remove(UiPrefs.KEY_PARKING_GUIDE_STYLE);else edit.putString(UiPrefs.KEY_PARKING_GUIDE_STYLE,savedStyle);edit.commit();
        }
    }
    private static void assertLightBlueGuidelines(android.graphics.Bitmap shown,android.graphics.Bitmap background) {
        int size=shown.getWidth()*shown.getHeight(),blue=0;
        int[] a=new int[size],b=new int[size],diagnostic={0x00FFFF,0xFFFF00,0x00FF00,0xFF66FF};shown.getPixels(a,0,shown.getWidth(),0,0,shown.getWidth(),shown.getHeight());
        background.getPixels(b,0,background.getWidth(),0,0,background.getWidth(),background.getHeight());
        for(int i=0;i<size;i++)if(a[i]!=b[i]) {
            int rgb=a[i]&0xFFFFFF;
            if(Math.abs(((rgb>>16)&255)-115)<=3&&Math.abs(((rgb>>8)&255)-217)<=3&&(rgb&255)>=252)blue++;
            for(int old:diagnostic)assertNotEquals("Debug must use the same blue lines as Main",old,rgb);
        }
        assertTrue("No light-blue individual guidelines in Debug",blue>20);
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
