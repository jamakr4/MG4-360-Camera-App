package com.drivehub.kamera.birdseye;

import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.R;
import com.drivehub.kamera.settings.UiPrefs;
import com.drivehub.kamera.steering.GearSnapshot;
import com.drivehub.kamera.steering.SteeringSnapshot;
import com.drivehub.kamera.steering.WheelGuides;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** MainActivity's actual view binding; no synthetic camera source is installed in production. */
@RunWith(AndroidJUnit4.class)
public class LiveGuidanceInstrumentedTest {
    @Test public void speedFilterAndStyleApplyToBothViewsWithoutChangingTheManualChoice() {
        SharedPreferences prefs=UiPrefs.getPrefs(InstrumentationRegistry.getInstrumentation().getTargetContext());
        java.util.Map<String,?> saved=prefs.getAll();
        String[] keys={UiPrefs.KEY_PARKING_SURROUND_GUIDES,UiPrefs.KEY_PARKING_GUIDE_SPEED_LIMIT,
                UiPrefs.KEY_PARKING_GUIDE_SPEED_KMH,UiPrefs.KEY_PARKING_GUIDE_STYLE,UiPrefs.KEY_PARKING_SURROUND_3D};
        prefs.edit().putBoolean(keys[0],true).putBoolean(keys[1],true).putInt(keys[2],25)
                .putString(keys[3],"simple").putBoolean(keys[4],false).commit();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->{
                BirdseyeView view=a.findViewById(R.id.birdseyeView);
                try {
                    java.lang.reflect.Field rendererField=BirdseyeView.class.getDeclaredField("renderer");rendererField.setAccessible(true);
                    BirdseyeRenderer renderer=(BirdseyeRenderer)rendererField.get(view);
                    java.lang.reflect.Field visible=BirdseyeRenderer.class.getDeclaredField("wheelGuidePreview");visible.setAccessible(true);
                    java.lang.reflect.Field style=BirdseyeRenderer.class.getDeclaredField("guideStyle");style.setAccessible(true);
                    long now=SystemClock.elapsedRealtime();
                    view.setSteeringSnapshot(SteeringSnapshot.value(-199.3f,0,now,1));
                    for(int gear:new int[]{2,4})for(boolean threeD:new boolean[]{false,true}) {
                        prefs.edit().putBoolean(keys[4],threeD).apply();
                        view.updateSteeringFrame(GearSnapshot.value(gear,now),com.drivehub.kamera.steering.VehicleMotionSnapshot.value(25,gear,now),now);
                        assertTrue(visible.getBoolean(renderer));
                        view.updateSteeringFrame(GearSnapshot.value(gear,now),com.drivehub.kamera.steering.VehicleMotionSnapshot.value(25.1f,gear,now),now);
                        assertFalse(visible.getBoolean(renderer));
                        assertTrue(prefs.getBoolean(keys[0],false));assertTrue(a.findViewById(R.id.surroundGuidesToggle).isSelected());
                        view.updateSteeringFrame(GearSnapshot.value(gear,now),com.drivehub.kamera.steering.VehicleMotionSnapshot.value(20,gear,now),now);
                        assertTrue(visible.getBoolean(renderer));
                    }
                    prefs.edit().putString(keys[3],"lines").apply();assertEquals(WheelGuides.Style.LINES,style.get(renderer));
                    a.findViewById(R.id.surroundGuidesToggle).performClick();
                    view.updateSteeringFrame(GearSnapshot.value(4,now),com.drivehub.kamera.steering.VehicleMotionSnapshot.value(5,4,now),now);
                    assertFalse(visible.getBoolean(renderer));assertFalse(prefs.getBoolean(keys[0],true));
                    a.findViewById(R.id.surroundGuidesToggle).performClick();
                    view.updateSteeringFrame(GearSnapshot.value(4,now),com.drivehub.kamera.steering.VehicleMotionSnapshot.EMPTY,now);
                    assertFalse(visible.getBoolean(renderer));assertTrue(prefs.getBoolean(keys[0],false));
                }catch(ReflectiveOperationException e){throw new AssertionError(e);}
            });
            scenario.recreate();scenario.onActivity(a->{
                assertTrue(a.findViewById(R.id.surroundGuidesToggle).isSelected());
                assertEquals(WheelGuides.Style.LINES,UiPrefs.getParkingGuideStyle(prefs));
                assertEquals(25,UiPrefs.getParkingGuideSpeedKmh(prefs));assertTrue(UiPrefs.isParkingGuideSpeedLimitEnabled(prefs));
            });
        }finally {
            SharedPreferences.Editor edit=prefs.edit();
            for(String key:keys){Object value=saved.get(key);if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
                else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof String)edit.putString(key,(String)value);else edit.remove(key);}
            edit.commit();
        }
    }
    @Test public void normalWideButtonSitsBesideModeSwitchAndPersistsAcrossThreeDAndRecreation() {
        SharedPreferences prefs=UiPrefs.getPrefs(InstrumentationRegistry.getInstrumentation().getTargetContext());
        boolean had3=prefs.contains(UiPrefs.KEY_PARKING_SURROUND_3D),saved3=prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,false);
        boolean hadW=prefs.contains(UiPrefs.KEY_PARKING_SURROUND_WIDE),savedW=prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_WIDE,false);
        prefs.edit().putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,false).putBoolean(UiPrefs.KEY_PARKING_SURROUND_WIDE,false).commit();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->{
                android.view.View wide=a.findViewById(R.id.surroundWideToggle),mode=a.findViewById(R.id.surroundModeToggle);
                assertEquals(android.view.View.VISIBLE,wide.getVisibility());assertTrue(wide.getRight()<=mode.getLeft());
                wide.performClick();assertTrue(prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_WIDE,false));
                mode.performClick();assertEquals(android.view.View.GONE,wide.getVisibility());
                mode.performClick();assertEquals(android.view.View.VISIBLE,wide.getVisibility());assertTrue(wide.isSelected());
            });
            scenario.recreate();scenario.onActivity(a->{
                android.widget.Button wide=a.findViewById(R.id.surroundWideToggle);
                assertTrue(wide.isSelected());assertEquals(a.getString(R.string.surround_label_normal),wide.getText().toString());
                wide.performClick();assertFalse(prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_WIDE,true));
            });
        }finally {
            SharedPreferences.Editor edit=prefs.edit();if(had3)edit.putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,saved3);else edit.remove(UiPrefs.KEY_PARKING_SURROUND_3D);
            if(hadW)edit.putBoolean(UiPrefs.KEY_PARKING_SURROUND_WIDE,savedW);else edit.remove(UiPrefs.KEY_PARKING_SURROUND_WIDE);edit.commit();
        }
    }
    @Test public void mainViewUsesLiveModelInTwoDAndThreeDAndClearsGuidanceAcrossPause() {
        SharedPreferences prefs=UiPrefs.getPrefs(InstrumentationRegistry.getInstrumentation().getTargetContext());
        boolean had=prefs.contains(UiPrefs.KEY_PARKING_SURROUND_3D),saved=prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,false);
        prefs.edit().putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,true).commit();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->{
                BirdseyeView view=activity.findViewById(R.id.birdseyeView);BirdseyeRenderer renderer;
                try{java.lang.reflect.Field field=BirdseyeView.class.getDeclaredField("renderer");field.setAccessible(true);renderer=(BirdseyeRenderer)field.get(view);}
                catch(ReflectiveOperationException e){throw new AssertionError(e);}
                assertTrue(ModelLoadSafety.status(activity),renderer.threeDRequested);
                long now=SystemClock.elapsedRealtime();view.setSteeringSnapshot(SteeringSnapshot.value(-516.95f,0,now,1));
                view.updateSteeringFrame(GearSnapshot.value(2,now),now);
                assertTrue(renderer.steering.angles().guidanceAvailable);
                assertEquals(28.8493,renderer.steering.angles().left,.001);assertEquals(38.0802,renderer.steering.angles().right,.001);
                assertEquals(WheelGuides.Direction.REVERSE,renderer.steering.angles().guidanceDirection);
                view.updateSteeringFrame(GearSnapshot.value(4,now),now);
                assertEquals(WheelGuides.Direction.FORWARD,renderer.steering.angles().guidanceDirection);
                for(int gear:new int[]{1,3,0}){view.updateSteeringFrame(GearSnapshot.value(gear,now),now);assertFalse(renderer.steering.angles().guidanceAvailable);}
                view.findViewById(R.id.surroundModeToggle).performClick();assertFalse(renderer.threeDRequested);
                view.updateSteeringFrame(GearSnapshot.value(4,now),now);assertTrue(renderer.steering.angles().guidanceAvailable);
                assertEquals(WheelGuides.Direction.FORWARD,renderer.steering.angles().guidanceDirection);
                view.findViewById(R.id.surroundModeToggle).performClick();assertTrue(renderer.threeDRequested);
                view.updateSteeringFrame(GearSnapshot.value(4,now),now);assertTrue(renderer.steering.angles().guidanceAvailable);
                view.setSteeringSnapshot(SteeringSnapshot.EMPTY);view.updateSteeringFrame(GearSnapshot.value(4,now),now);
                assertFalse(renderer.steering.angles().guidanceAvailable);assertEquals(38.0802,renderer.steering.angles().right,.001);
                view.pauseDisplay();assertFalse(renderer.steering.angles().guidanceAvailable);assertEquals(0,renderer.steering.degrees(),0);
                view.resumeDisplay();view.updateSteeringFrame(GearSnapshot.value(4,now),now);
                assertFalse(renderer.steering.angles().guidanceAvailable);
            });
        }finally {
            SharedPreferences.Editor edit=prefs.edit();if(had)edit.putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,saved);else edit.remove(UiPrefs.KEY_PARKING_SURROUND_3D);edit.commit();
        }
    }
}
