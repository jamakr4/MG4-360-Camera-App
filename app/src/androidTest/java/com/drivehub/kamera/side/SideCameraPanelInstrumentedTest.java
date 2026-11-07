package com.drivehub.kamera.side;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.Button;
import android.widget.ImageButton;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.R;
import com.drivehub.kamera.settings.UiPrefs;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SideCameraPanelInstrumentedTest {
    @Test public void modePersistsAndAccentUpdatesAcrossActivityAndSurfaceRecreation() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        Map<String, ?> saved = prefs.getAll();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                Button wheels = activity.findViewById(R.id.modeWheels);
                wheels.performClick();
                assertTrue(prefs.getBoolean(UiPrefs.KEY_MAIN_SIDE_CAMERA_WHEELS, false));
                assertTrue(wheels.isSelected());
                assertFalse(activity.findViewById(R.id.modeSides).isSelected());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.modeWheels).isSelected());
                SideCameraPanel panel = activity.findViewById(R.id.sideCameraPanel);
                assertEquals(540f / 640, panel.getWidth() / (float) panel.getHeight(), .003f);
                ImageButton close = activity.findViewById(R.id.btnClose);
                ImageButton settings = activity.findViewById(R.id.btnSettings);
                float density = activity.getResources().getDisplayMetrics().density;
                assertEquals(Math.round(148 * density), close.getWidth());
                assertEquals(Math.round(34 * density), close.getPaddingLeft());
                assertEquals(Math.round(108 * density), settings.getWidth());
                assertEquals(Math.round(24 * density), settings.getPaddingLeft());
                prefs.edit().putString(UiPrefs.KEY_ACCENT_COLOR, "#F08020").apply();
            });
            // Preference listeners execute on the main thread after the edit.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                Button wheels = activity.findViewById(R.id.modeWheels);
                Button sides = activity.findViewById(R.id.modeSides);
                RippleDrawable ripple = (RippleDrawable) wheels.getBackground();
                GradientDrawable selected = (GradientDrawable) ripple.getDrawable(0);
                assertEquals(Color.rgb(240, 128, 32), selected.getColor().getDefaultColor());
                assertEquals(Color.rgb(240, 128, 32), sides.getCurrentTextColor());
                GradientDrawable unselected = (GradientDrawable) ((RippleDrawable) sides.getBackground()).getDrawable(0);
                assertEquals(Color.TRANSPARENT, unselected.getColor().getDefaultColor());
                sides.performClick();
                assertFalse(prefs.getBoolean(UiPrefs.KEY_MAIN_SIDE_CAMERA_WHEELS, true));
            });
            for (int i = 0; i < 5; i++) {
                scenario.moveToState(Lifecycle.State.CREATED);
                scenario.moveToState(Lifecycle.State.RESUMED);
            }
            scenario.recreate();
            scenario.onActivity(activity -> assertTrue(activity.findViewById(R.id.modeSides).isSelected()));
        } finally {
            SharedPreferences.Editor editor = prefs.edit();
            restore(editor, saved, UiPrefs.KEY_MAIN_SIDE_CAMERA_WHEELS);
            restore(editor, saved, UiPrefs.KEY_ACCENT_COLOR);
            assertTrue(editor.commit());
        }
    }

    private static void restore(SharedPreferences.Editor editor, Map<String, ?> saved, String key) {
        Object value = saved.get(key);
        if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof String) editor.putString(key, (String) value);
        else editor.remove(key);
    }
}
