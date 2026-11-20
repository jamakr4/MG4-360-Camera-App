package com.drivehub.kamera.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;
import android.widget.Switch;

import androidx.core.view.ViewCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.espresso.ViewInteraction;
import androidx.test.platform.app.InstrumentationRegistry;

import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.birdseye.BirdseyeCarColor;
import com.drivehub.kamera.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.pressImeActionButton;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ParkingSettingsInstrumentedTest {
    @Test public void reverseForegroundDefaultsOnAndPersistsWhenParkingReopens() {
        SharedPreferences prefs=UiPrefs.getPrefs(InstrumentationRegistry.getInstrumentation().getTargetContext());
        String key=UiPrefs.KEY_PARKING_REVERSE_FOREGROUND;boolean had=prefs.contains(key),saved=prefs.getBoolean(key,true);
        prefs.edit().remove(key).commit();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            openParking(scenario);dialogView(R.id.switchParkingReverseForeground).perform(scrollTo()).check(matches(isChecked())).perform(click());
            assertFalse(UiPrefs.isParkingReverseForegroundEnabled(prefs));
            dialogView(R.id.btnClose).perform(click());
            openParking(scenario);dialogView(R.id.switchParkingReverseForeground).perform(scrollTo()).check(matches(not(isChecked())));
        } finally {SharedPreferences.Editor edit=prefs.edit();if(had)edit.putBoolean(key,saved);else edit.remove(key);edit.commit();}
    }
    @Test public void guidelineDefaultsAndSelectionsPersistAcrossReopening() {
        SharedPreferences prefs=UiPrefs.getPrefs(InstrumentationRegistry.getInstrumentation().getTargetContext());
        Map<String,?> saved=prefs.getAll();
        String[] keys={UiPrefs.KEY_PARKING_GUIDE_SPEED_LIMIT,UiPrefs.KEY_PARKING_GUIDE_SPEED_KMH,UiPrefs.KEY_PARKING_GUIDE_STYLE};
        SharedPreferences.Editor clear=prefs.edit();for(String key:keys)clear.remove(key);clear.commit();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            openParking(scenario);
            dialogView(R.id.switchGuidelineSpeedLimit).perform(scrollTo()).check(matches(isChecked()));
            dialogView(R.id.etGuidelineSpeed).check(matches(withText("25"))).check(matches(isEnabled()));
            dialogView(R.id.segGuidelineSimple).perform(scrollTo()).check(matches(isSelected()));
            dialogView(R.id.etGuidelineSpeed).perform(replaceText("18"),pressImeActionButton(),closeSoftKeyboard());
            dialogView(R.id.segGuidelineLines).perform(scrollTo(),click()).check(matches(isSelected()));
            assertEquals(18,UiPrefs.getParkingGuideSpeedKmh(prefs));assertTrue(UiPrefs.isParkingGuideSpeedLimitEnabled(prefs));
            assertEquals(com.drivehub.kamera.steering.WheelGuides.Style.LINES,UiPrefs.getParkingGuideStyle(prefs));
            dialogView(R.id.btnClose).perform(click());scenario.recreate();openParking(scenario);
            dialogView(R.id.etGuidelineSpeed).perform(scrollTo()).check(matches(withText("18"))).check(matches(isEnabled()));
            dialogView(R.id.segGuidelineLines).perform(scrollTo()).check(matches(isSelected()));
            dialogView(R.id.switchGuidelineSpeedLimit).perform(scrollTo(),click());
            dialogView(R.id.etGuidelineSpeed).check(matches(not(isEnabled())));
            dialogView(R.id.segGuidelineSimple).perform(scrollTo(),click()).check(matches(isSelected()));
            dialogView(R.id.btnClose).perform(click());
        }finally {
            SharedPreferences.Editor edit=prefs.edit();
            for(String key:keys){Object value=saved.get(key);if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
                else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof String)edit.putString(key,(String)value);else edit.remove(key);}
            edit.commit();
        }
    }
    @Test public void sharedSteeringModelAndGearAreExplainedInDevStatus() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            openDev();
            onView(withText(R.string.settings_dev_steering_hint)).inRoot(isDialog()).perform(scrollTo())
                    .check((view,error)->{
                        if(error!=null)throw error;
                        String hint=((android.widget.TextView)view).getText().toString();
                        assertTrue(hint.contains("OEM"));assertFalse(hint.contains("14.77"));
                    });
            dialogView(R.id.btnClose).perform(click());scenario.recreate();openDev();
            dialogView(R.id.tabDevStatus).perform(scrollTo(),click());
            dialogView(R.id.tvDevStatusSteering).perform(scrollTo()).check((view,error)->{
                if(error!=null)throw error;
                String text=((android.widget.TextView)view).getText().toString();
                assertTrue(text,text.contains("0x21601563"));assertTrue(text,text.contains("OEM radius model"));
                assertTrue(text,text.contains("left/right="));assertTrue(text,text.contains("gear="));assertFalse(text,text.contains("scale="));
            });
            dialogView(R.id.btnClose).perform(click());
        }
    }
    @Test public void paintSwatchesSelectOneColorAndPersistWhenParkingReopens() {
        SharedPreferences prefs = UiPrefs.getPrefs(InstrumentationRegistry.getInstrumentation().getTargetContext());
        String saved = prefs.getString(UiPrefs.KEY_PARKING_BIRDSEYE_CAR_COLOR, null);
        assertTrue(prefs.edit().putString(UiPrefs.KEY_PARKING_BIRDSEYE_CAR_COLOR, "unknown_future_color").commit());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            assertEquals(BirdseyeCarColor.PICCADILLY_BLUE, UiPrefs.getBirdseyeCarColor(prefs));
            openParking(scenario);
            paintSwatch(BirdseyeCarColor.PICCADILLY_BLUE).check(matches(isSelected()));
            for (BirdseyeCarColor color : BirdseyeCarColor.values()) {
                paintSwatch(color).perform(click());
                assertEquals(color, UiPrefs.getBirdseyeCarColor(prefs));
                assertEquals(color.id, prefs.getString(UiPrefs.KEY_PARKING_BIRDSEYE_CAR_COLOR, null));
                for (BirdseyeCarColor candidate : BirdseyeCarColor.values()) {
                    paintSwatch(candidate).check((view, error) -> {
                        if (error != null) throw error;
                        assertEquals(candidate == color, view.isSelected());
                        android.view.accessibility.AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
                        assertTrue(node.isCheckable()); assertEquals(candidate == color, node.isChecked());
                        assertEquals(candidate.label(), view.getContentDescription().toString());
                    });
                }
            }
            dialogView(R.id.btnClose).perform(click());
            scenario.recreate();
            openParking(scenario);
            paintSwatch(BirdseyeCarColor.HUNTER_GREEN).check(matches(isSelected()));
            dialogView(R.id.btnClose).perform(click());
        } finally {
            SharedPreferences.Editor editor = prefs.edit();
            if (saved == null) editor.remove(UiPrefs.KEY_PARKING_BIRDSEYE_CAR_COLOR);
            else editor.putString(UiPrefs.KEY_PARKING_BIRDSEYE_CAR_COLOR, saved);
            assertTrue(editor.commit());
        }
    }

    private static ViewInteraction paintSwatch(BirdseyeCarColor color) {
        return onView(withTagValue(org.hamcrest.Matchers.is((Object) color.id))).inRoot(isDialog());
    }

    @Test public void devAnglesAcceptDecimalsPersistAndResetEachAxleIndependently() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        Map<String, ?> saved = prefs.getAll();
        assertTrue(prefs.edit().remove(UiPrefs.KEY_PARKING_FRONT_WHEEL_MODE).remove(UiPrefs.KEY_PARKING_REAR_WHEEL_MODE).remove(UiPrefs.KEY_DEV_PARKING_FRONT_WHEEL_ANGLE)
                .remove(UiPrefs.KEY_DEV_PARKING_REAR_WHEEL_ANGLE)
                .putBoolean(UiPrefs.KEY_PARKING_FRONT_WHEEL_ALIGNMENT, false)
                .putBoolean(UiPrefs.KEY_PARKING_REAR_WHEEL_ALIGNMENT, true).commit());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            openDev();
            assertAngleField(R.id.etDevParkingFrontWheelAngle, 16.5f);
            assertAngleField(R.id.etDevParkingRearWheelAngle, -45f);
            dialogView(R.id.etDevParkingFrontWheelAngle).perform(replaceText("25,5"), pressImeActionButton(), closeSoftKeyboard());
            dialogView(R.id.etDevParkingRearWheelAngle).perform(replaceText("-32.75"), pressImeActionButton(), closeSoftKeyboard());
            assertEquals(25.5f, UiPrefs.getDevParkingFrontWheelAngle(prefs), 0);
            assertEquals(-32.75f, UiPrefs.getDevParkingRearWheelAngle(prefs), 0);
            assertFalse(UiPrefs.isParkingFrontWheelAlignmentEnabled(prefs));
            assertTrue(UiPrefs.isParkingRearWheelAlignmentEnabled(prefs));
            dialogView(R.id.btnClose).perform(click());
            scenario.recreate();
            openDev();
            assertAngleField(R.id.etDevParkingFrontWheelAngle, 25.5f);
            assertAngleField(R.id.etDevParkingRearWheelAngle, -32.75f);
            dialogView(R.id.btnDevParkingFrontWheelAngleReset).perform(click());
            assertAngleField(R.id.etDevParkingFrontWheelAngle, 16.5f);
            assertEquals(16.5f, UiPrefs.getDevParkingFrontWheelAngle(prefs), 0);
            assertEquals(-32.75f, UiPrefs.getDevParkingRearWheelAngle(prefs), 0);
            dialogView(R.id.etDevParkingFrontWheelAngle).perform(replaceText("-"), closeSoftKeyboard());
            assertEquals(16.5f, UiPrefs.getDevParkingFrontWheelAngle(prefs), 0);
            dialogView(R.id.etDevParkingFrontWheelAngle).perform(replaceText("999"), pressImeActionButton(), closeSoftKeyboard());
            assertAngleField(R.id.etDevParkingFrontWheelAngle, 180f);
            assertEquals(180f, UiPrefs.getDevParkingFrontWheelAngle(prefs), 0);
            dialogView(R.id.btnDevParkingRearWheelAngleReset).perform(click());
            assertAngleField(R.id.etDevParkingRearWheelAngle, -45f);
            assertEquals(-45f, UiPrefs.getDevParkingRearWheelAngle(prefs), 0);
            assertEquals(180f, UiPrefs.getDevParkingFrontWheelAngle(prefs), 0);
            assertFalse(UiPrefs.isParkingFrontWheelAlignmentEnabled(prefs));
            assertTrue(UiPrefs.isParkingRearWheelAlignmentEnabled(prefs));
            dialogView(R.id.btnClose).perform(click());
        } finally {
            SharedPreferences.Editor editor = prefs.edit();
            for (String key : new String[]{UiPrefs.KEY_PARKING_FRONT_WHEEL_MODE,UiPrefs.KEY_PARKING_REAR_WHEEL_MODE,UiPrefs.KEY_DEV_PARKING_FRONT_WHEEL_ANGLE,
                    UiPrefs.KEY_DEV_PARKING_REAR_WHEEL_ANGLE, UiPrefs.KEY_PARKING_FRONT_WHEEL_ALIGNMENT,
                    UiPrefs.KEY_PARKING_REAR_WHEEL_ALIGNMENT}) {
                Object value = saved.get(key);
                if (value instanceof Float) editor.putFloat(key, (Float) value);
                else if (value instanceof String) editor.putString(key,(String)value);
                else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                else editor.remove(key);
            }
            assertTrue(editor.commit());
        }
    }

    @Test public void axleOptionsMigrateIndependentlyPersistAndKeepHighlightToggle() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs=UiPrefs.getPrefs(context);Map<String,?> saved=prefs.getAll();
        String[] keys={UiPrefs.KEY_PARKING_FRONT_WHEEL_MODE,UiPrefs.KEY_PARKING_REAR_WHEEL_MODE,
                UiPrefs.KEY_PARKING_FRONT_WHEEL_ALIGNMENT,UiPrefs.KEY_PARKING_REAR_WHEEL_ALIGNMENT,
                UiPrefs.KEY_PARKING_HIGHLIGHT_MISSING_AREAS,UiPrefs.KEY_SAFETY_WARNING,UiPrefs.KEY_ACCENT_COLOR};
        prefs.edit().remove(UiPrefs.KEY_PARKING_FRONT_WHEEL_MODE).remove(UiPrefs.KEY_PARKING_REAR_WHEEL_MODE)
                .putBoolean(UiPrefs.KEY_PARKING_FRONT_WHEEL_ALIGNMENT,false).putBoolean(UiPrefs.KEY_PARKING_REAR_WHEEL_ALIGNMENT,true)
                .putBoolean(UiPrefs.KEY_PARKING_HIGHLIGHT_MISSING_AREAS,true).putBoolean(UiPrefs.KEY_SAFETY_WARNING,false)
                .putString(UiPrefs.KEY_ACCENT_COLOR,"#F08020").commit();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            assertEquals(com.drivehub.kamera.side.WheelAlignmentMode.NONE,UiPrefs.getParkingWheelMode(prefs,true));
            assertEquals(com.drivehub.kamera.side.WheelAlignmentMode.BASIC,UiPrefs.getParkingWheelMode(prefs,false));
            openParking(scenario);
            dialogView(R.id.parkingWheelAlignmentOptions).check(matches(withEffectiveVisibility(Visibility.GONE)));
            dialogView(R.id.switchSafetyWarning).perform(click());assertTrue(UiPrefs.isSafetyWarningEnabled(prefs));
            dialogView(R.id.rowParkingWheelAlignment).perform(scrollTo(),click());
            dialogView(R.id.segParkingFrontNone).check(matches(isSelected()));
            dialogView(R.id.segParkingRearBasic).check(matches(isSelected()));
            dialogView(R.id.segParkingFrontAdvanced).perform(scrollTo(),click());
            assertEquals(com.drivehub.kamera.side.WheelAlignmentMode.ADVANCED,UiPrefs.getParkingWheelMode(prefs,true));
            assertEquals(com.drivehub.kamera.side.WheelAlignmentMode.BASIC,UiPrefs.getParkingWheelMode(prefs,false));
            dialogView(R.id.segParkingRearNone).perform(scrollTo(),click());
            assertFalse(UiPrefs.isParkingRearWheelAlignmentEnabled(prefs));
            dialogView(R.id.segParkingFrontBasic).perform(scrollTo(),click());
            assertEquals(com.drivehub.kamera.side.WheelAlignmentMode.BASIC,UiPrefs.getParkingWheelMode(prefs,true));
            dialogView(R.id.segParkingRearAdvanced).perform(scrollTo(),click());
            dialogView(R.id.segParkingFrontNone).perform(scrollTo(),click());
            assertFalse(UiPrefs.isParkingFrontWheelAlignmentEnabled(prefs));
            assertEquals(com.drivehub.kamera.side.WheelAlignmentMode.ADVANCED,UiPrefs.getParkingWheelMode(prefs,false));
            dialogView(R.id.switchParkingHighlightMissingAreas).perform(scrollTo(),click());assertFalse(UiPrefs.isParkingHighlightMissingAreasEnabled(prefs));
            dialogView(R.id.switchParkingHighlightMissingAreas).check((view,error)->{
                if(error!=null)throw error;
                assertEquals(Color.rgb(240,128,32),((Switch)view).getTrackTintList().getColorForState(new int[]{android.R.attr.state_checked},0));
            });
            dialogView(R.id.ivParkingWheelAlignmentChevron).check((view,error)->{if(error!=null)throw error;assertEquals(180f,view.getRotation(),0);});
            dialogView(R.id.btnClose).perform(click());scenario.recreate();openParking(scenario);
            dialogView(R.id.parkingWheelAlignmentOptions).check(matches(withEffectiveVisibility(Visibility.GONE)));
            dialogView(R.id.rowParkingWheelAlignment).perform(scrollTo(),click());
            dialogView(R.id.segParkingFrontNone).check(matches(isSelected()));
            dialogView(R.id.segParkingRearAdvanced).check(matches(isSelected()));
            dialogView(R.id.switchParkingHighlightMissingAreas).check(matches(not(isChecked()))).perform(scrollTo(),click());
            assertTrue(UiPrefs.isParkingHighlightMissingAreasEnabled(prefs));
            dialogView(R.id.btnClose).perform(click());
        }finally{
            SharedPreferences.Editor edit=prefs.edit();for(String key:keys){Object value=saved.get(key);
                if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof String)edit.putString(key,(String)value);else edit.remove(key);}
            edit.commit();
        }
    }

    @Test public void sideCorrectionDefaultsOnAndPersistsIndependentlyOfWheelModes(){
        SharedPreferences prefs=UiPrefs.getPrefs(InstrumentationRegistry.getInstrumentation().getTargetContext());
        Object saved=prefs.getAll().get(UiPrefs.KEY_PARKING_SIDE_CORRECTION);
        com.drivehub.kamera.side.WheelAlignmentMode front=UiPrefs.getParkingWheelMode(prefs,true),rear=UiPrefs.getParkingWheelMode(prefs,false);
        prefs.edit().remove(UiPrefs.KEY_PARKING_SIDE_CORRECTION).commit();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            assertTrue(UiPrefs.isParkingSideCorrectionEnabled(prefs));openParking(scenario);
            dialogView(R.id.segParkingSideStraight).perform(androidx.test.espresso.action.ViewActions.scrollTo()).check(matches(isSelected()));
            dialogView(R.id.segParkingSideNone).perform(click()).check(matches(isSelected()));
            dialogView(R.id.segParkingSideStraight).check(matches(not(isSelected())));
            assertFalse(UiPrefs.isParkingSideCorrectionEnabled(prefs));
            assertEquals(front,UiPrefs.getParkingWheelMode(prefs,true));assertEquals(rear,UiPrefs.getParkingWheelMode(prefs,false));
            dialogView(R.id.btnClose).perform(click());scenario.recreate();openParking(scenario);
            dialogView(R.id.segParkingSideNone).perform(androidx.test.espresso.action.ViewActions.scrollTo()).check(matches(isSelected()));
            dialogView(R.id.segParkingSideStraight).perform(click()).check(matches(isSelected()));
            dialogView(R.id.segParkingSideNone).check(matches(not(isSelected())));
            assertTrue(UiPrefs.isParkingSideCorrectionEnabled(prefs));dialogView(R.id.btnClose).perform(click());
        }finally{SharedPreferences.Editor edit=prefs.edit();if(saved instanceof Boolean)edit.putBoolean(UiPrefs.KEY_PARKING_SIDE_CORRECTION,(Boolean)saved);else edit.remove(UiPrefs.KEY_PARKING_SIDE_CORRECTION);edit.commit();}
    }

    @Test public void existingTabsAndDeveloperNavigationStillSelectTheirOwnSections() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.btnSettings)).perform(click());
            int[][] tabs = {{R.id.tabSettings, R.id.sectionSettings}, {R.id.tabParking, R.id.sectionParking},
                    {R.id.tabSignalCamera, R.id.sectionSignalCamera}, {R.id.tabDigitalRearview, R.id.sectionDigitalRearview},
                    {R.id.tabDashcam, R.id.sectionDashcam}, {R.id.tabUpdate, R.id.sectionUpdate},
                    {R.id.tabCredits, R.id.sectionCredits}};
            for (int[] tab : tabs) {
                dialogView(tab[0]).perform(click());
                dialogView(tab[1]).check(matches(isDisplayed()));
                if (tab[0] == R.id.tabSettings)
                    dialogView(R.id.switchSafetyWarning).check(matches(not(isDisplayed())));
            }
            dialogView(R.id.tvDialogVersion).perform(click(), click(), click(), click(), click());
            dialogView(R.id.tabDev).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click());
            dialogView(R.id.sectionDev).check(matches(isDisplayed()));
            dialogView(R.id.tabDevStatus).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click());
            dialogView(R.id.sectionDevStatus).check(matches(isDisplayed()));
            dialogView(R.id.tabParking).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click());
            dialogView(R.id.sectionParking).check(matches(isDisplayed()));
            dialogView(R.id.btnClose).perform(click());
        }
    }

    private static void openParking(ActivityScenario<MainActivity> scenario) {
        onView(withId(R.id.btnSettings)).perform(click());
        dialogView(R.id.tabParking).perform(click());
        dialogView(R.id.sectionParking).check(matches(isDisplayed()));
    }

    private static ViewInteraction dialogView(int id) {
        return onView(withId(id)).inRoot(isDialog());
    }

    private static void openDev() {
        onView(withId(R.id.btnSettings)).perform(click());
        dialogView(R.id.tvDialogVersion).perform(click(), click(), click(), click(), click());
        dialogView(R.id.tabDev).perform(androidx.test.espresso.action.ViewActions.scrollTo(), click());
        dialogView(R.id.sectionDev).check(matches(isDisplayed()));
    }

    private static void assertAngleField(int id, float expected) {
        dialogView(id).check((view, error) -> {
            if (error != null) throw error;
            String text = ((android.widget.EditText) view).getText().toString().replace(',', '.');
            assertEquals(expected, Float.parseFloat(text), 0);
        });
    }
}
