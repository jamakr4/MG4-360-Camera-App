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
import com.drivehub.kamera.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
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
    @Test public void devAnglesAcceptDecimalsPersistAndResetEachAxleIndependently() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        Map<String, ?> saved = prefs.getAll();
        assertTrue(prefs.edit().remove(UiPrefs.KEY_DEV_PARKING_FRONT_WHEEL_ANGLE)
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
            for (String key : new String[]{UiPrefs.KEY_DEV_PARKING_FRONT_WHEEL_ANGLE,
                    UiPrefs.KEY_DEV_PARKING_REAR_WHEEL_ANGLE, UiPrefs.KEY_PARKING_FRONT_WHEEL_ALIGNMENT,
                    UiPrefs.KEY_PARKING_REAR_WHEEL_ALIGNMENT}) {
                Object value = saved.get(key);
                if (value instanceof Float) editor.putFloat(key, (Float) value);
                else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                else editor.remove(key);
            }
            assertTrue(editor.commit());
        }
    }

    @Test public void axleOptionsAreIndependentPersistedAndInitiallyCollapsed() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        Map<String, ?> saved = prefs.getAll();
        assertTrue(prefs.edit().remove(UiPrefs.KEY_PARKING_FRONT_WHEEL_ALIGNMENT)
                .remove(UiPrefs.KEY_PARKING_REAR_WHEEL_ALIGNMENT)
                .remove(UiPrefs.KEY_PARKING_HIGHLIGHT_MISSING_AREAS)
                .putBoolean(UiPrefs.KEY_SAFETY_WARNING, false)
                .putString(UiPrefs.KEY_ACCENT_COLOR, "#F08020").commit());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            assertFalse(UiPrefs.isParkingFrontWheelAlignmentEnabled(prefs));
            assertTrue(UiPrefs.isParkingRearWheelAlignmentEnabled(prefs));
            assertTrue(UiPrefs.isParkingHighlightMissingAreasEnabled(prefs));
            openParking(scenario);
            dialogView(R.id.parkingWheelAlignmentOptions).check(matches(withEffectiveVisibility(Visibility.GONE)));
            dialogView(R.id.switchSafetyWarning).check(matches(not(isChecked())));
            dialogView(R.id.switchSafetyWarning).perform(click());
            assertTrue(UiPrefs.isSafetyWarningEnabled(prefs));
            scenario.onActivity(activity -> assertEquals(View.VISIBLE,
                    activity.findViewById(R.id.warningBanner).getVisibility()));

            dialogView(R.id.rowParkingWheelAlignment).perform(click());
            dialogView(R.id.parkingWheelAlignmentOptions).check(matches(isDisplayed()));
            dialogView(R.id.switchParkingRearWheelAlignment).check(matches(isChecked())).perform(click());
            assertFalse(UiPrefs.isParkingRearWheelAlignmentEnabled(prefs));
            dialogView(R.id.switchParkingHighlightMissingAreas).check(matches(isChecked())).perform(click());
            assertFalse(UiPrefs.isParkingHighlightMissingAreasEnabled(prefs));
            assertFalse(UiPrefs.isParkingFrontWheelAlignmentEnabled(prefs));
            assertFalse(UiPrefs.isParkingRearWheelAlignmentEnabled(prefs));
            dialogView(R.id.ivParkingWheelAlignmentChevron).check((view, error) -> {
                if (error != null) throw error;
                assertEquals(180f, view.getRotation(), 0);
            });
            dialogView(R.id.rowParkingWheelAlignment).check((view, error) -> {
                if (error != null) throw error;
                assertEquals(view.getContext().getString(R.string.settings_options_expanded),
                        ViewCompat.getStateDescription(view));
            });
            dialogView(R.id.switchParkingFrontWheelAlignment).check(matches(not(isChecked()))).perform(click());
            assertTrue(UiPrefs.isParkingFrontWheelAlignmentEnabled(prefs));
            assertFalse(UiPrefs.isParkingRearWheelAlignmentEnabled(prefs));
            dialogView(R.id.switchParkingFrontWheelAlignment).check((view, error) -> {
                if (error != null) throw error;
                assertEquals(Color.rgb(240, 128, 32), ((Switch) view).getTrackTintList()
                        .getColorForState(new int[]{android.R.attr.state_checked}, 0));
            });
            dialogView(R.id.switchParkingRearWheelAlignment).perform(click());
            assertTrue(UiPrefs.isParkingRearWheelAlignmentEnabled(prefs));
            dialogView(R.id.switchParkingFrontWheelAlignment).perform(click());
            assertFalse(UiPrefs.isParkingFrontWheelAlignmentEnabled(prefs));
            assertTrue(UiPrefs.isParkingRearWheelAlignmentEnabled(prefs));

            dialogView(R.id.rowParkingWheelAlignment).perform(click());
            dialogView(R.id.parkingWheelAlignmentOptions).check(matches(withEffectiveVisibility(Visibility.GONE)));
            dialogView(R.id.btnClose).perform(click());
            scenario.recreate();
            openParking(scenario);
            dialogView(R.id.parkingWheelAlignmentOptions).check(matches(withEffectiveVisibility(Visibility.GONE)));
            dialogView(R.id.rowParkingWheelAlignment).perform(click());
            dialogView(R.id.switchParkingFrontWheelAlignment).check(matches(not(isChecked())));
            dialogView(R.id.switchParkingRearWheelAlignment).check(matches(isChecked()));
            dialogView(R.id.switchParkingHighlightMissingAreas).check(matches(not(isChecked()))).perform(click());
            assertTrue(UiPrefs.isParkingHighlightMissingAreasEnabled(prefs));
            assertFalse(UiPrefs.isParkingFrontWheelAlignmentEnabled(prefs));
            assertTrue(UiPrefs.isParkingRearWheelAlignmentEnabled(prefs));
            dialogView(R.id.switchSafetyWarning).check(matches(isChecked()));
            dialogView(R.id.btnClose).perform(click());
        } finally {
            SharedPreferences.Editor editor = prefs.edit();
            for (String key : new String[]{UiPrefs.KEY_PARKING_FRONT_WHEEL_ALIGNMENT,
                    UiPrefs.KEY_PARKING_REAR_WHEEL_ALIGNMENT, UiPrefs.KEY_PARKING_HIGHLIGHT_MISSING_AREAS,
                    UiPrefs.KEY_SAFETY_WARNING, UiPrefs.KEY_ACCENT_COLOR}) {
                Object value = saved.get(key);
                if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                else if (value instanceof String) editor.putString(key, (String) value);
                else editor.remove(key);
            }
            assertTrue(editor.commit());
        }
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
