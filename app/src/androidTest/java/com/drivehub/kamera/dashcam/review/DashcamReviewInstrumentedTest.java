package com.drivehub.kamera.dashcam.review;

import android.content.Context;
import android.content.Intent;
import android.app.Instrumentation;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.accessibility.AccessibilityNodeInfo;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.R;
import com.drivehub.kamera.dashcam.DashcamSettingsController;
import com.drivehub.kamera.dashcam.DashcamStorageManager;
import com.drivehub.kamera.dashcam.ReviewFileAccess;
import com.drivehub.kamera.dashcam.StorageIoGate;
import com.drivehub.kamera.settings.UiPrefs;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DashcamReviewInstrumentedTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private File root;
    private String oldPath;
    private int oldTarget;
    private SharedPreferences prefs;
    @Before public void prepare() {
        prefs = UiPrefs.getPrefs(context);
        oldPath = DashcamSettingsController.getConfiguredRecordsPath(prefs);
        oldTarget = DashcamStorageManager.getStorageTarget(prefs);
        root = new File(context.getFilesDir(), "review-test-" + System.nanoTime());
        assertTrue(root.mkdirs());
        DashcamSettingsController.setConfiguredRecordsPath(prefs, root.getAbsolutePath());
        DashcamStorageManager.setStorageTarget(prefs, DashcamStorageManager.TARGET_INTERNAL_ONLY);
        StorageIoGate.reopen();
    }
    @After public void cleanup() throws Exception {
        assertTrue("Review readers did not close", StorageIoGate.awaitIdle(4000));
        StorageIoGate.reopen();
        DashcamSettingsController.setConfiguredRecordsPath(prefs, oldPath);
        DashcamStorageManager.setStorageTarget(prefs, oldTarget);
        delete(root);
    }
    private void delete(File file) {
        if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child); }
        file.delete();
    }
    private File clip(String relative) throws Exception {
        File file = new File(root, relative);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("review/clip.mp4")) {
            Files.copy(input, file.toPath());
        }
        return file;
    }
    private ActivityScenario<DashcamReviewActivity> launch() {
        return ActivityScenario.launch(new Intent(context, DashcamReviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private void await(ActivityScenario<DashcamReviewActivity> scenario, Predicate<DashcamReviewActivity> condition) {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        boolean[] result = {false};
        do {
            scenario.onActivity(activity -> result[0] = condition.test(activity));
            if (result[0]) return;
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        String[] ui = {""};
        scenario.onActivity(a -> ui[0] = ((TextView) a.findViewById(R.id.reviewPath)).getText() + " / "
                + ((TextView) a.findViewById(R.id.reviewEmptyMessage)).getText());
        fail("Review UI did not reach the expected state: " + ui[0] + "\n"
                + com.drivehub.kamera.dev.DevRuntimeLog.snapshot());
    }
    private void clickFirst(ActivityScenario<DashcamReviewActivity> scenario) {
        scenario.onActivity(activity -> {
            ListView list = activity.findViewById(R.id.reviewList);
            list.performItemClick(list.getChildAt(0), 0, 0);
        });
    }
    private int count(DashcamReviewActivity activity) {
        return ((ListView) activity.findViewById(R.id.reviewList)).getCount();
    }
    private boolean titleContains(DashcamReviewActivity activity, String part) {
        return ((TextView) activity.findViewById(R.id.reviewPlayingTitle)).getText().toString().contains(part);
    }

    @Test public void emptyFolderAndPickerCancellationLeaveRecordingSettingsUnchanged() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor cancel = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                return Intent.ACTION_OPEN_DOCUMENT_TREE.equals(intent.getAction())
                        ? new Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null) : null;
            }
        };
        instrumentation.addMonitor(cancel);
        try (ActivityScenario<DashcamReviewActivity> scenario = launch()) {
            await(scenario, a -> a.findViewById(R.id.reviewEmpty).getVisibility() == View.VISIBLE);
            scenario.onActivity(a -> a.findViewById(R.id.reviewEmptyChoose).performClick());
            await(scenario, a -> a.findViewById(R.id.reviewEmpty).getVisibility() == View.VISIBLE);
            assertEquals(root.getAbsolutePath(), DashcamSettingsController.getConfiguredRecordsPath(prefs));
            assertEquals(DashcamStorageManager.TARGET_INTERNAL_ONLY, DashcamStorageManager.getStorageTarget(prefs));
        } finally { instrumentation.removeMonitor(cancel); }
    }

    @Test public void missingRecordingDirectoryIsNotCreatedByReview() throws Exception {
        File missing = new File(root, "not-created-by-review");
        DashcamSettingsController.setConfiguredRecordsPath(prefs, missing.getAbsolutePath());
        try (ActivityScenario<DashcamReviewActivity> scenario = launch()) {
            await(scenario, a -> a.findViewById(R.id.reviewEmpty).getVisibility() == View.VISIBLE);
            assertFalse(missing.exists());
        }
    }

    @Test public void eventPlaysChronologicallyAndFullscreenReturnsToList() throws Exception {
        clip("events/event_261120120000000/261120120000000.mp4");
        clip("events/event_261120120000000/261120120002000.mp4");
        clip("events/event_261119120000000/261119120000000.mp4");
        try (ActivityScenario<DashcamReviewActivity> scenario = launch()) {
            await(scenario, a -> count(a) == 2);
            clickFirst(scenario);
            await(scenario, a -> titleContains(a, "event_261120") && titleContains(a, "1/2")
                    && ((SeekBar) a.findViewById(R.id.reviewSeek)).getMax() >= 1900);
            scenario.onActivity(a -> a.findViewById(R.id.reviewFullscreen).performClick());
            scenario.onActivity(a -> {
                assertEquals(View.GONE, a.findViewById(R.id.reviewHeader).getVisibility());
                a.getOnBackPressedDispatcher().onBackPressed();
                assertEquals(View.VISIBLE, a.findViewById(R.id.reviewHeader).getVisibility());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            SystemClock.sleep(200);
            Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            File outputDirectory = new File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir",
                    context.getExternalFilesDir(null).getAbsolutePath()));
            outputDirectory.mkdirs();
            java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, png);
            // Some Automotive test-output transports decode binary as UTF-8; transfer losslessly as text.
            try (FileOutputStream output = new FileOutputStream(new File(outputDirectory, "review-qa.png.b64"))) {
                output.write(android.util.Base64.encode(png.toByteArray(), android.util.Base64.NO_WRAP));
            }
            screenshot.recycle();
            await(scenario, a -> titleContains(a, "2/2"));
            await(scenario, a -> a.getString(R.string.review_play).contentEquals(a.findViewById(R.id.reviewPlay).getContentDescription()));
        }
    }

    @Test public void seekBackgroundAndRecreationRetainSelectionWithoutAutoplay() throws Exception {
        File video = clip("261120120000000.mp4");
        try (ActivityScenario<DashcamReviewActivity> scenario = launch()) {
            await(scenario, a -> a.findViewById(R.id.reviewContent).getVisibility() == View.VISIBLE);
            scenario.onActivity(a -> a.findViewById(R.id.reviewBuffer).performClick());
            await(scenario, a -> count(a) == 1);
            clickFirst(scenario);
            await(scenario, a -> ((SeekBar) a.findViewById(R.id.reviewSeek)).getMax() >= 1900);
            scenario.onActivity(a -> a.findViewById(R.id.reviewPlay).performClick());
            await(scenario, a -> a.getString(R.string.review_play).contentEquals(a.findViewById(R.id.reviewPlay).getContentDescription()));
            assertTrue("Pausing in the review must retain the selection", ReviewFileAccess.isProtected(video));
            scenario.onActivity(a -> {
                a.findViewById(R.id.reviewForward).performClick();
            });
            await(scenario, a -> ((TextView) a.findViewById(R.id.reviewElapsed)).getText().toString().equals("00:02"));
            scenario.onActivity(a -> a.findViewById(R.id.reviewRewind).performClick());
            await(scenario, a -> ((TextView) a.findViewById(R.id.reviewElapsed)).getText().toString().equals("00:00"));
            scenario.moveToState(Lifecycle.State.CREATED);
            assertTrue(StorageIoGate.awaitIdle(3000));
            assertFalse(ReviewFileAccess.isProtected(video));
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.recreate();
            await(scenario, a -> titleContains(a, video.getName()));
            scenario.onActivity(a -> assertEquals(a.getString(R.string.review_play),
                    a.findViewById(R.id.reviewPlay).getContentDescription().toString()));
        }
    }

    @Test public void missingVideoShowsRecoverableErrorAndEjectReleasesAllReaders() throws Exception {
        File video = clip("261120120000000.mp4");
        try (ActivityScenario<DashcamReviewActivity> scenario = launch()) {
            await(scenario, a -> a.findViewById(R.id.reviewContent).getVisibility() == View.VISIBLE);
            scenario.onActivity(a -> a.findViewById(R.id.reviewBuffer).performClick());
            await(scenario, a -> count(a) == 1);
            assertTrue(video.delete());
            clickFirst(scenario);
            await(scenario, a -> ((TextView) a.findViewById(R.id.reviewPlayerMessage)).getText()
                    .toString().equals(a.getString(R.string.review_playback_error)));
            clip("261120120000000.mp4");
            scenario.onActivity(a -> a.findViewById(R.id.reviewPlay).performClick());
            await(scenario, a -> ((SeekBar) a.findViewById(R.id.reviewSeek)).getMax() >= 1900);
            StorageIoGate.close();
            assertTrue(StorageIoGate.awaitIdle(4000));
            assertFalse(ReviewFileAccess.isProtected(video));
            await(scenario, a -> a.findViewById(R.id.reviewEmpty).getVisibility() == View.VISIBLE);
            StorageIoGate.reopen();
            scenario.onActivity(a -> a.findViewById(R.id.reviewRetry).performClick());
            await(scenario, a -> a.findViewById(R.id.reviewContent).getVisibility() == View.VISIBLE);
        }
    }

    @Test public void systemPickerOpensTreeAndPlaysItWithoutChangingRecordingRoot() throws Exception {
        org.junit.Assume.assumeFalse("Automotive image supplies only a DocumentsUI stub",
                shell("cmd package resolve-activity --brief -a android.intent.action.OPEN_DOCUMENT_TREE")
                        .contains("frameworkpackagestubs"));
        File fixture = new File(context.getExternalFilesDir(null), "picker-clip.mp4");
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("review/clip.mp4")) {
            Files.copy(input, fixture.toPath());
        }
        String folder = "ReviewPicker_" + System.nanoTime();
        assertEquals("", shell("mkdir -p /sdcard/Download/" + folder));
        assertEquals("", shell("cp " + fixture.getAbsolutePath()
                + " /sdcard/Download/" + folder + "/261120120000000.mp4"));
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        android.accessibilityservice.AccessibilityServiceInfo service = instrumentation.getUiAutomation().getServiceInfo();
        service.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        instrumentation.getUiAutomation().setServiceInfo(service);
        Instrumentation.ActivityMonitor initialFolder = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (Intent.ACTION_OPEN_DOCUMENT_TREE.equals(intent.getAction())) intent.putExtra(
                        DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                                "com.android.externalstorage.documents", "primary:Download/" + folder));
                return null;
            }
        };
        instrumentation.addMonitor(initialFolder);
        try (ActivityScenario<DashcamReviewActivity> scenario = launch()) {
            await(scenario, a -> a.findViewById(R.id.reviewEmpty).getVisibility() == View.VISIBLE);
            scenario.onActivity(a -> a.findViewById(R.id.reviewEmptyChoose).performClick());
            clickSystemNode(node -> node.getText() != null && node.getText().toString().equalsIgnoreCase("Use this folder"));
            clickSystemNode(node -> node.getText() != null && node.getText().toString().equalsIgnoreCase("Allow"));
            await(scenario, a -> ((TextView) a.findViewById(R.id.reviewPath)).getText().toString().contains(folder));
            scenario.onActivity(a -> a.findViewById(R.id.reviewBuffer).performClick());
            await(scenario, a -> count(a) == 1);
            clickFirst(scenario);
            await(scenario, a -> ((SeekBar) a.findViewById(R.id.reviewSeek)).getMax() >= 1900);
            assertEquals(root.getAbsolutePath(), DashcamSettingsController.getConfiguredRecordsPath(prefs));
            scenario.onActivity(a -> a.findViewById(R.id.reviewRecordingFolder).performClick());
            await(scenario, a -> a.findViewById(R.id.reviewEmpty).getVisibility() == View.VISIBLE);
        } finally {
            instrumentation.removeMonitor(initialFolder);
            shell("rm -rf /sdcard/Download/" + folder);
            fixture.delete();
        }
    }

    private String shell(String command) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command))) {
            byte[] buffer = new byte[1024]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
        }
        return bytes.toString("UTF-8");
    }
    private void clickSystemNode(Predicate<AccessibilityNodeInfo> predicate) {
        long deadline = SystemClock.elapsedRealtime() + 7000;
        do {
            AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if (root != null && clickNode(root, predicate)) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
        StringBuilder tree = new StringBuilder();
        if (root != null) dumpNodes(root, tree);
        fail("System folder picker control was not available: " + tree);
    }
    private boolean clickNode(AccessibilityNodeInfo node, Predicate<AccessibilityNodeInfo> predicate) {
        if (predicate.test(node) && node.isEnabled() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && clickNode(child, predicate)) return true;
        }
        return false;
    }
    private void dumpNodes(AccessibilityNodeInfo node, StringBuilder result) {
        if (node.getText() != null || node.getViewIdResourceName() != null)
            result.append(node.getViewIdResourceName()).append('=').append(node.getText()).append(';');
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i); if (child != null) dumpNodes(child, result);
        }
    }
}
