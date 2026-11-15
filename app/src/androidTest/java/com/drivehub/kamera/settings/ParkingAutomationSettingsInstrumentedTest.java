package com.drivehub.kamera.settings;

import android.content.SharedPreferences;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.R;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ParkingAutomationSettingsInstrumentedTest {
    @Test public void defaultsEditingPersistenceAndDisabledField() {
        SharedPreferences prefs = UiPrefs.getPrefs(InstrumentationRegistry.getInstrumentation().getTargetContext());
        Map<String, ?> saved = prefs.getAll();
        String[] keys = {UiPrefs.KEY_PARKING_AUTO_CLOSE, UiPrefs.KEY_PARKING_AUTO_OPEN_DRIVE,
                UiPrefs.KEY_PARKING_AUTO_CLOSE_SPEED};
        SharedPreferences.Editor clear = prefs.edit();
        for (String key : keys) clear.remove(key);
        clear.commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            openParking();
            onView(withId(R.id.switchParkingAutoClose)).inRoot(isDialog()).perform(scrollTo()).check(matches(isChecked()));
            onView(withId(R.id.etParkingAutoCloseSpeed)).inRoot(isDialog()).check(matches(withText("25"))).check(matches(isEnabled()));
            onView(withId(R.id.switchParkingAutoOpenDrive)).inRoot(isDialog()).perform(scrollTo()).check(matches(not(isChecked())));
            onView(withId(R.id.etParkingAutoCloseSpeed)).inRoot(isDialog()).perform(scrollTo(), replaceText("41"), pressImeActionButton(), closeSoftKeyboard());
            onView(withId(R.id.switchParkingAutoOpenDrive)).inRoot(isDialog()).perform(scrollTo(), click());
            assertTrue(UiPrefs.isParkingAutoCloseEnabled(prefs));
            assertTrue(UiPrefs.isParkingAutoOpenDriveEnabled(prefs));
            assertEquals(41, UiPrefs.getParkingAutoCloseSpeedKmh(prefs));
            close(); scenario.recreate(); openParking();
            onView(withId(R.id.switchParkingAutoClose)).inRoot(isDialog()).perform(scrollTo()).check(matches(isChecked()));
            onView(withId(R.id.etParkingAutoCloseSpeed)).inRoot(isDialog()).check(matches(withText("41"))).check(matches(isEnabled()));
            onView(withId(R.id.etParkingAutoCloseSpeed)).inRoot(isDialog()).perform(replaceText(""), closeSoftKeyboard());
            assertEquals(41, UiPrefs.getParkingAutoCloseSpeedKmh(prefs));
            onView(withId(R.id.etParkingAutoCloseSpeed)).inRoot(isDialog()).perform(replaceText("0"), pressImeActionButton(), closeSoftKeyboard());
            assertEquals(1, UiPrefs.getParkingAutoCloseSpeedKmh(prefs));
            onView(withId(R.id.etParkingAutoCloseSpeed)).inRoot(isDialog()).perform(replaceText("999"), pressImeActionButton(), closeSoftKeyboard());
            assertEquals(130, UiPrefs.getParkingAutoCloseSpeedKmh(prefs));
            onView(withId(R.id.switchParkingAutoClose)).inRoot(isDialog()).perform(click());
            onView(withId(R.id.etParkingAutoCloseSpeed)).inRoot(isDialog()).check(matches(not(isEnabled())));
            assertTrue(UiPrefs.isParkingAutoOpenDriveEnabled(prefs));
            assertFalse(UiPrefs.isParkingAutoCloseEnabled(prefs));
            close(); scenario.recreate(); openParking();
            onView(withId(R.id.switchParkingAutoClose)).inRoot(isDialog()).perform(scrollTo()).check(matches(not(isChecked())));
            onView(withId(R.id.etParkingAutoCloseSpeed)).inRoot(isDialog()).check(matches(not(isEnabled())));
            close();
        } finally {
            SharedPreferences.Editor restore = prefs.edit();
            for (String key : keys) {
                Object value = saved.get(key);
                if (value instanceof Boolean) restore.putBoolean(key, (Boolean) value);
                else if (value instanceof Integer) restore.putInt(key, (Integer) value);
                else restore.remove(key);
            }
            restore.commit();
        }
    }
    private void openParking() {
        onView(withId(R.id.btnSettings)).perform(click());
        onView(withId(R.id.tabParking)).inRoot(isDialog()).perform(scrollTo(), click());
    }
    private void close() { onView(withId(R.id.btnClose)).inRoot(isDialog()).perform(click()); }
}
