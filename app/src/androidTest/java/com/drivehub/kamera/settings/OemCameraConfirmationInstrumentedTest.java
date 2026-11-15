package com.drivehub.kamera.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.R;
import com.drivehub.kamera.dashcam.DashcamSettingsController;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isChecked;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class OemCameraConfirmationInstrumentedTest {
    private static final Context CONTEXT = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void cancelAndBackNeverDisableOemAndWarnAgainAfterReopeningSettings() {
        SharedPreferences prefs = UiPrefs.getPrefs(CONTEXT);
        Map<String, ?> saved = prefs.getAll();
        assertTrue(prefs.edit().putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST, true)
                .putBoolean(DashcamSettingsController.KEY_ENABLED, false).commit());
        AtomicInteger disableWrites = new AtomicInteger();
        SharedPreferences.OnSharedPreferenceChangeListener listener = (changed, key) -> {
            if (UiPrefs.KEY_OEM_AVM_COEXIST.equals(key) && !UiPrefs.isOemAvmCoexistEnabled(changed))
                disableWrites.incrementAndGet();
        };
        prefs.registerOnSharedPreferenceChangeListener(listener);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            openSettings();
            attemptDisable(prefs);
            onView(withId(R.id.btnOemDisableCancel)).inRoot(isDialog()).perform(click());
            assertEnabled(prefs);

            attemptDisable(prefs);
            pressBack();
            assertEnabled(prefs);

            onView(withId(R.id.btnClose)).inRoot(isDialog()).perform(click());
            openSettings();
            attemptDisable(prefs);
            onView(withId(R.id.btnOemDisableCancel)).inRoot(isDialog()).perform(click());
            assertEnabled(prefs);
            assertEquals("Cancelled attempts must never publish a disabled preference", 0, disableWrites.get());
            onView(withId(R.id.btnClose)).inRoot(isDialog()).perform(click());
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener);
            restorePrefs(prefs, saved);
        }
    }

    @Test public void onlyConfirmDisablesAndEveryNewDisableRequiresConfirmation() {
        SharedPreferences prefs = UiPrefs.getPrefs(CONTEXT);
        Map<String, ?> saved = prefs.getAll();
        assertTrue(prefs.edit().putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST, true)
                .putBoolean(DashcamSettingsController.KEY_ENABLED, false).commit());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            openSettings();
            for (int attempt = 0; attempt < 2; attempt++) {
                attemptDisable(prefs);
                onView(withId(R.id.btnOemDisableConfirm)).inRoot(isDialog()).perform(click());
                assertFalse(UiPrefs.isOemAvmCoexistEnabled(prefs));
                onView(withId(R.id.switchOemAvmCoexist)).inRoot(isDialog()).check(matches(not(isChecked())));
                onView(withId(R.id.tvOemDisableWarningTitle)).check(doesNotExist());

                // Opening Settings with OEM already disabled must not trigger a warning.
                onView(withId(R.id.btnClose)).inRoot(isDialog()).perform(click());
                openSettings();
                onView(withId(R.id.tvOemDisableWarningTitle)).check(doesNotExist());
                onView(withId(R.id.switchOemAvmCoexist)).inRoot(isDialog()).check(matches(not(isChecked())));
                if (attempt == 0) {
                    onView(withId(R.id.switchOemAvmCoexist)).inRoot(isDialog()).perform(scrollTo(), click());
                } else {
                    onView(withId(R.id.btnSettingsRestoreOem)).inRoot(isDialog()).perform(scrollTo(), click());
                }
                assertEnabled(prefs);
            }
            attemptDisable(prefs);
            onView(withId(R.id.btnOemDisableCancel)).inRoot(isDialog()).perform(click());
            assertEnabled(prefs);
            onView(withId(R.id.btnClose)).inRoot(isDialog()).perform(click());
        } finally {
            restorePrefs(prefs, saved);
        }
    }

    private static void openSettings() {
        onView(withId(R.id.btnSettings)).perform(click());
        onView(withId(R.id.tabSettings)).inRoot(isDialog()).perform(click());
    }

    private static void attemptDisable(SharedPreferences prefs) {
        onView(withId(R.id.switchOemAvmCoexist)).inRoot(isDialog()).perform(scrollTo(), click());
        onView(withId(R.id.tvOemDisableWarningTitle)).inRoot(isDialog()).check(matches(isDisplayed()));
        onView(withId(R.id.tvOemDisableWarningMessage)).inRoot(isDialog()).check(matches(withText(
                CONTEXT.getString(R.string.settings_oem_disable_warning_message,
                        CONTEXT.getString(R.string.parking_oem_restore)))));
        onView(withId(R.id.btnOemDisableCancel)).inRoot(isDialog())
                .check(matches(withText(R.string.settings_oem_disable_cancel)));
        assertTrue("OEM must stay enabled until explicit confirmation", UiPrefs.isOemAvmCoexistEnabled(prefs));
    }

    private static void assertEnabled(SharedPreferences prefs) {
        assertTrue(UiPrefs.isOemAvmCoexistEnabled(prefs));
        onView(withId(R.id.switchOemAvmCoexist)).inRoot(isDialog()).check(matches(isChecked()));
        onView(withId(R.id.tvOemDisableWarningTitle)).check(doesNotExist());
    }

    private static void restorePrefs(SharedPreferences prefs, Map<String, ?> saved) {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : new String[]{UiPrefs.KEY_OEM_AVM_COEXIST, DashcamSettingsController.KEY_ENABLED}) {
            if (saved.containsKey(key)) editor.putBoolean(key, (Boolean) saved.get(key));
            else editor.remove(key);
        }
        assertTrue(editor.commit());
    }
}
