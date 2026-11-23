package com.drivehub.kamera.birdseye;

import android.content.SharedPreferences;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.R;
import com.drivehub.kamera.settings.UiPrefs;
import com.drivehub.kamera.steering.GuidelineWidth;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GuidelineWidthInstrumentedTest {
    private static final String MODE=UiPrefs.KEY_PARKING_GUIDE_WIDTH_MODE;
    private static final String CUSTOM=UiPrefs.KEY_PARKING_GUIDE_CUSTOM_WIDTH_MM;
    private static SharedPreferences prefs(){return UiPrefs.getPrefs(InstrumentationRegistry.getInstrumentation().getTargetContext());}

    @Test public void parkingGeometryStyleAndWidthPersistIndependently() {
        SharedPreferences prefs=prefs();Map<String,?> saved=prefs.getAll();
        String geometry=UiPrefs.KEY_PARKING_GUIDE_GEOMETRY,style=UiPrefs.KEY_PARKING_GUIDE_STYLE;
        prefs.edit().putString(geometry,"unknown").putString(style,"simple").putString(MODE,"body").putInt(CUSTOM,2475).commit();
        assertEquals(com.drivehub.kamera.steering.WheelGuides.Geometry.FRONT_BUMPER,UiPrefs.getParkingGuideGeometry(prefs));
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            openParking();
            chooseGeometry(0);assertEquals("axles",prefs.getString(geometry,null));assertEquals(1836,UiPrefs.getParkingGuideWidthMm(prefs));
            onView(withId(R.id.segGuidelineLines)).perform(scrollTo(),click());
            assertEquals("lines",prefs.getString(style,null));assertEquals("axles",prefs.getString(geometry,null));
            chooseWidth(2);assertEquals(2475,UiPrefs.getParkingGuideWidthMm(prefs));
            chooseGeometry(1);assertEquals("front_bumper",prefs.getString(geometry,null));
            assertEquals("lines",prefs.getString(style,null));assertEquals(2475,UiPrefs.getParkingGuideWidthMm(prefs));
            onView(withId(R.id.segGuidelineOuterLines)).perform(scrollTo(),click());
            assertEquals("outer_lines",prefs.getString(style,null));chooseGeometry(0);
            onView(withId(R.id.btnClose)).perform(click());scenario.recreate();openParking();
            onView(withId(R.id.segGuidelineAxles)).perform(scrollTo()).check(matches(isSelected()));
            onView(withId(R.id.segGuidelineOuterLines)).perform(scrollTo()).check(matches(isSelected()));
            onView(withId(R.id.etGuidelineCustomWidth)).perform(scrollTo()).check(matches(withText("2475")));
            onView(withId(R.id.btnClose)).perform(click());
        }finally{restore(prefs,saved,MODE,CUSTOM,geometry,style);}
    }

    @Test public void parkingWidthPresetsCustomValidationAndReopeningKeepTheLastValidWidth() {
        SharedPreferences prefs=prefs();Map<String,?> saved=prefs.getAll();
        prefs.edit().putString(MODE,"future_mode").putInt(CUSTOM,3001).commit();
        assertEquals(2060,UiPrefs.getParkingGuideWidthMm(prefs));
        assertEquals(2060,UiPrefs.getParkingGuideCustomWidthMm(prefs));
        prefs.edit().remove(MODE).remove(CUSTOM).commit();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            openParking();
            onView(withId(R.id.segGuidelineWidthMirrors)).perform(scrollTo()).check(matches(isSelected()));
            onView(withId(R.id.textGuidelineWidthValue)).check(matches(withText(
                    InstrumentationRegistry.getInstrumentation().getTargetContext().getString(R.string.guidelines_width_mirrors,2060))));
            onView(withId(R.id.rowGuidelineCustomWidth)).check(matches(withEffectiveVisibility(Visibility.GONE)));
            chooseWidth(1);assertEquals(1836,UiPrefs.getParkingGuideWidthMm(prefs));
            chooseWidth(2);onView(withId(R.id.etGuidelineCustomWidth)).check(matches(withText("2060")));
            onView(withId(R.id.etGuidelineCustomWidth)).perform(scrollTo(),replaceText("2475"),pressImeActionButton(),closeSoftKeyboard());
            assertEquals(2475,UiPrefs.getParkingGuideWidthMm(prefs));
            chooseWidth(0);assertEquals(2060,UiPrefs.getParkingGuideWidthMm(prefs));
            chooseWidth(2);onView(withId(R.id.etGuidelineCustomWidth)).check(matches(withText("2475")));
            for(String invalid:new String[]{"3001","0","","999999999999999999999"}) {
                onView(withId(R.id.etGuidelineCustomWidth)).perform(scrollTo(),replaceText(invalid));
                assertEquals(2475,UiPrefs.getParkingGuideWidthMm(prefs));
                onView(withId(R.id.etGuidelineCustomWidth)).check((view,error)->{
                    if(error!=null)throw error;assertNotNull(((android.widget.EditText)view).getError());
                }).perform(pressImeActionButton(),closeSoftKeyboard()).check(matches(withText("2475")));
            }
            for(int width:new int[]{1,3000}) {
                onView(withId(R.id.etGuidelineCustomWidth)).perform(scrollTo(),replaceText(String.valueOf(width)),pressImeActionButton(),closeSoftKeyboard());
                assertEquals(width,UiPrefs.getParkingGuideWidthMm(prefs));
            }
            onView(withId(R.id.btnClose)).perform(click());scenario.recreate();openParking();
            onView(withId(R.id.etGuidelineCustomWidth)).perform(scrollTo()).check(matches(withText("3000")));
            assertEquals(GuidelineWidth.Mode.CUSTOM,UiPrefs.getParkingGuideWidthMode(prefs));
            chooseWidth(1);chooseWidth(2);
            onView(withId(R.id.etGuidelineCustomWidth)).check(matches(withText("3000")));
            onView(withId(R.id.btnClose)).perform(click());
        }finally{restore(prefs,saved,MODE,CUSTOM);}
    }

    @Test public void debugStartsWithSavedWidthAndKeepsEditsLocalAcrossResumeAndRecreation() {
        SharedPreferences prefs=prefs();Map<String,?> saved=prefs.getAll();
        prefs.edit().putString(MODE,"body").putInt(CUSTOM,2475).commit();
        try {
            try(ActivityScenario<BirdseyeDebugActivity> scenario=ActivityScenario.launch(BirdseyeDebugActivity.class)) {
                scenario.onActivity(a->assertEquals(1836,rendererWidth(a.renderer)));
                chooseWidth(2);
                onView(withId(R.id.etGuidelineCustomWidth)).perform(scrollTo()).check(matches(withText("2475")))
                        .perform(replaceText("2500"),pressImeActionButton(),closeSoftKeyboard());
                scenario.onActivity(a->assertEquals(2500,rendererWidth(a.renderer)));
                onView(withId(R.id.textGuidelineWidthValue)).check(matches(withEffectiveVisibility(Visibility.GONE)));
                scenario.moveToState(Lifecycle.State.STARTED);scenario.moveToState(Lifecycle.State.RESUMED);
                scenario.onActivity(a->assertEquals(2500,rendererWidth(a.renderer)));
                scenario.recreate();
                onView(withId(R.id.etGuidelineCustomWidth)).perform(scrollTo()).check(matches(withText("2500")));
                scenario.onActivity(a->assertEquals(2500,rendererWidth(a.renderer)));
                chooseWidth(0);scenario.onActivity(a->assertEquals(2060,rendererWidth(a.renderer)));
                chooseWidth(2);onView(withId(R.id.etGuidelineCustomWidth)).perform(scrollTo()).check(matches(withText("2500")));
                assertEquals("body",prefs.getString(MODE,null));assertEquals(2475,prefs.getInt(CUSTOM,0));
            }
            try(ActivityScenario<BirdseyeDebugActivity> fresh=ActivityScenario.launch(BirdseyeDebugActivity.class)) {
                fresh.onActivity(a->assertEquals(1836,rendererWidth(a.renderer)));
            }
        }finally{restore(prefs,saved,MODE,CUSTOM);}
    }

    @Test public void normalViewReceivesWidthChangesInEveryModeAndRestoresThemAfterRecreation() {
        SharedPreferences prefs=prefs();Map<String,?> saved=prefs.getAll();
        prefs.edit().remove(MODE).remove(CUSTOM).commit();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->{
                BirdseyeView view=a.findViewById(R.id.birdseyeView);
                BirdseyeRenderer renderer;
                try {
                    java.lang.reflect.Field field=BirdseyeView.class.getDeclaredField("renderer");field.setAccessible(true);
                    renderer=(BirdseyeRenderer)field.get(view);
                }catch(ReflectiveOperationException e){throw new AssertionError(e);}
                assertEquals(2060,rendererWidth(renderer));
                for(boolean threeD:new boolean[]{false,true})for(boolean wide:new boolean[]{false,true}) {
                    prefs.edit().putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,threeD).putBoolean(UiPrefs.KEY_PARKING_SURROUND_WIDE,wide).apply();
                    prefs.edit().putString(MODE,"body").apply();assertEquals(1836,rendererWidth(renderer));
                    prefs.edit().putString(MODE,"custom").putInt(CUSTOM,2800).apply();assertEquals(2800,rendererWidth(renderer));
                    prefs.edit().putInt(CUSTOM,2500).apply();assertEquals(2500,rendererWidth(renderer));
                    for(com.drivehub.kamera.steering.WheelGuides.Geometry geometry:com.drivehub.kamera.steering.WheelGuides.Geometry.values())
                        for(String style:new String[]{"simple","lines","outer_lines"}) {
                            prefs.edit().putString(UiPrefs.KEY_PARKING_GUIDE_GEOMETRY,geometry.id).putString(UiPrefs.KEY_PARKING_GUIDE_STYLE,style).apply();
                            assertEquals(geometry,rendererOption(renderer,"guideGeometry"));
                            assertEquals(UiPrefs.getParkingGuideStyle(prefs),rendererOption(renderer,"guideStyle"));
                            assertEquals(2500,rendererWidth(renderer));
                        }
                }
            });
            scenario.recreate();scenario.onActivity(a->{
                try {
                    java.lang.reflect.Field field=BirdseyeView.class.getDeclaredField("renderer");field.setAccessible(true);
                    assertEquals(2500,rendererWidth((BirdseyeRenderer)field.get(a.findViewById(R.id.birdseyeView))));
                }catch(ReflectiveOperationException e){throw new AssertionError(e);}
            });
        }finally{restore(prefs,saved,MODE,CUSTOM,UiPrefs.KEY_PARKING_SURROUND_3D,UiPrefs.KEY_PARKING_SURROUND_WIDE,
                UiPrefs.KEY_PARKING_GUIDE_GEOMETRY,UiPrefs.KEY_PARKING_GUIDE_STYLE);}
    }

    private static void openParking(){onView(withId(R.id.btnSettings)).perform(click());onView(withId(R.id.tabParking)).perform(click());}
    private static void chooseWidth(int position) {
        int[] choices={R.id.segGuidelineWidthMirrors,R.id.segGuidelineWidthBody,R.id.segGuidelineWidthCustom};
        onView(withId(choices[position])).perform(scrollTo(),click()).check(matches(isSelected()));
    }
    private static void chooseGeometry(int position) {
        onView(withId(position==0?R.id.segGuidelineAxles:R.id.segGuidelineFrontBumper))
                .perform(scrollTo(),click()).check(matches(isSelected()));
    }
    private static Object rendererOption(BirdseyeRenderer renderer,String name) {
        try {
            java.lang.reflect.Field field=BirdseyeRenderer.class.getDeclaredField(name);field.setAccessible(true);
            return field.get(renderer);
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static int rendererWidth(BirdseyeRenderer renderer) {
        try {
            java.lang.reflect.Field field=BirdseyeRenderer.class.getDeclaredField("guideWidthMm");field.setAccessible(true);
            return field.getInt(renderer);
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static void restore(SharedPreferences prefs,Map<String,?> saved,String...keys) {
        SharedPreferences.Editor edit=prefs.edit();
        for(String key:keys){Object value=saved.get(key);
            if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);
            else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else edit.remove(key);}
        edit.commit();
    }
}
