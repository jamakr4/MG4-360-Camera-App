package com.drivehub.kamera.parking;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.birdseye.BirdseyeCalibration;
import com.drivehub.kamera.birdseye.BirdseyeSynthetic;
import com.drivehub.kamera.birdseye.RivelaScene;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Synthetic PDC/calibration only: never opens cameras or reads vehicle properties. */
@RunWith(AndroidJUnit4.class)
public class PdcOverlayInstrumentedTest {
    private static void ui(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicLong mainAge = new AtomicLong(0);
        final AtomicBoolean rearShown = new AtomicBoolean(true);
        final PdcGeometry geometry = new PdcGeometry();
        final PdcOverlayView overlay;
        final TextView status;
        RivelaScene scene = new RivelaScene(BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes()));

        Fixture() {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            overlay = new PdcOverlayView(context, null);
            status = new TextView(context);
            overlay.bind(() -> scene, geometry, status, rearShown::get, mainAge::get);
            overlay.layout(0, 0, 720, 480);
            freshSensors();
            overlay.resumeDisplay();
        }

        void freshSensors() {
            geometry.set(new PdcSnapshot(new int[]{1, 0, 0, 0}, 80, 0, SystemClock.elapsedRealtime()));
        }

        int drawnPixels() {
            Bitmap bitmap = Bitmap.createBitmap(720, 480, Bitmap.Config.ARGB_8888);
            overlay.draw(new Canvas(bitmap));
            int[] pixels = new int[720 * 480];
            bitmap.getPixels(pixels, 0, 720, 0, 0, 720, 480);
            bitmap.recycle();
            int count = 0;
            for (int pixel : pixels) if ((pixel >>> 24) != 0) count++;
            return count;
        }

        @Override public void close() { overlay.pauseDisplay(); }
    }

    @Test public void freshMainOutputDrawsWithoutAnyBirdseyeOutput() {
        ui(() -> {
            try (Fixture fixture = new Fixture()) {
                // No Birdseye view/renderer/frame source exists in this fixture.
                assertTrue(fixture.drawnPixels() > 0);
                fixture.mainAge.set(500);
                assertTrue(fixture.drawnPixels() > 0);
            }
        });
    }
    @Test public void mainRearImageProjectsEverySectorIndividuallyAcrossNearAndFarDistances() {
        ui(() -> {
            try(Fixture fixture=new Fixture()) {
                for(int cm:new int[]{5,10,20,40,80,150,200})for(int sector=0;sector<4;sector++) {
                    int[] levels=new int[4];levels[sector]=1;
                    fixture.geometry.set(new PdcSnapshot(levels,cm,0,SystemClock.elapsedRealtime()));
                    assertTrue("Main camera sector "+sector+" missing at "+cm+" cm",fixture.drawnPixels()>20);
                }
                fixture.geometry.set(new PdcSnapshot(new int[]{1,3,6,9},80,0,SystemClock.elapsedRealtime()));
                java.util.List<com.drivehub.kamera.rivela.PdcBars.Bar> bars=fixture.geometry.bars(fixture.scene.car,SystemClock.elapsedRealtime());
                assertEquals(4,bars.size());
                for(com.drivehub.kamera.rivela.PdcBars.Bar bar:bars)
                    assertFalse(com.drivehub.kamera.rivela.GroundProjector.band(fixture.scene.camera(3),bar.xy,bar.widthMm).isEmpty());
                assertTrue(fixture.drawnPixels()>0);
                assertTrue(PdcOverlayView.projectionDiagnostic().contains("bars=4, polygons=4"));
                fixture.mainAge.set(501);assertEquals(0,fixture.drawnPixels());
                assertTrue(PdcOverlayView.projectionDiagnostic().contains("no fresh main camera frame; bars=0, polygons=0"));
            }
        });
    }

    @Test public void unavailableOrStalledMainOutputHidesBarsAndRecoversWithFreshFrames() {
        ui(() -> {
            try (Fixture fixture = new Fixture()) {
                for (long age : new long[]{-1, 501, 10_000}) {
                    fixture.freshSensors();
                    fixture.mainAge.set(age);
                    assertEquals("Main frame age " + age, 0, fixture.drawnPixels());
                }
                fixture.mainAge.set(0);
                fixture.freshSensors();
                assertTrue(fixture.drawnPixels() > 0);
            }
        });
    }

    @Test public void calibrationRearSelectionLifecycleAndPdcExpiryStillGateProjection() {
        ui(() -> {
            try (Fixture fixture = new Fixture()) {
                fixture.rearShown.set(false);
                assertEquals(0, fixture.drawnPixels());
                fixture.rearShown.set(true);
                fixture.scene = null;
                assertEquals(0, fixture.drawnPixels());
                fixture.scene = new RivelaScene(BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes()));
                fixture.geometry.set(new PdcSnapshot(new int[]{1, 0, 0, 0}, 80, 0,
                        SystemClock.elapsedRealtime() - 501));
                assertEquals(0, fixture.drawnPixels());
                fixture.freshSensors();
                assertTrue(fixture.drawnPixels() > 0);
                fixture.overlay.pauseDisplay();
                assertEquals(0, fixture.drawnPixels());
            }
        });
    }

    @Test public void missingNativeMainOutputReportsUnavailableWithoutOpeningCamera() {
        assertEquals(-1, CameraProbe.mainPreviewFrameAgeMs(-1));
    }

    @Test public void statusShowsOnlySectorDotsAndReportedCentimetres() {
        ui(() -> {
            try (Fixture fixture = new Fixture()) {
                fixture.geometry.set(new PdcSnapshot(new int[]{0, 3, 3, 0}, 77, 0, SystemClock.elapsedRealtime()));
                fixture.overlay.updateStatus();
                assertEquals("○   ●   ●   ○   77 cm", fixture.status.getText().toString());
                assertEquals("PDC 0, 3, 3, 0; 77 cm", fixture.status.getContentDescription().toString());

                fixture.geometry.set(new PdcSnapshot(new int[]{1, 3, 6, 9}, 80, 0, SystemClock.elapsedRealtime()));
                fixture.overlay.updateStatus();
                assertEquals(4, fixture.geometry.bars(fixture.scene.car, SystemClock.elapsedRealtime()).size());
                assertTrue(fixture.drawnPixels() > 0);
                assertEquals("●   ●   ●   ●   80 cm", fixture.status.getText().toString());
                assertEquals("PDC 1, 3, 6, 9; 80 cm", fixture.status.getContentDescription().toString());

                fixture.geometry.set(new PdcSnapshot(new int[]{1, -1, 255, 9}, 80, 0, SystemClock.elapsedRealtime()));
                fixture.overlay.updateStatus();
                assertEquals(2, fixture.geometry.bars(fixture.scene.car, SystemClock.elapsedRealtime()).size());
                assertTrue(fixture.drawnPixels() > 0);
                assertEquals("●   –   –   ●   80 cm", fixture.status.getText().toString());
                assertEquals("PDC 1, –, –, 9; 80 cm", fixture.status.getContentDescription().toString());

                fixture.geometry.set(new PdcSnapshot(new int[]{2, 2, 0, 0}, 90, 0, SystemClock.elapsedRealtime()));
                fixture.overlay.updateStatus();
                assertEquals("●   ●   ○   ○   90 cm", fixture.status.getText().toString());

                fixture.geometry.set(new PdcSnapshot(new int[]{0, 0, 0, 0}, 90, 0, SystemClock.elapsedRealtime()));
                fixture.overlay.updateStatus();
                assertEquals("○   ○   ○   ○", fixture.status.getText().toString());
                assertEquals("PDC 0, 0, 0, 0", fixture.status.getContentDescription().toString());

                fixture.geometry.set(new PdcSnapshot(new int[]{1,3,6,9},255,0,SystemClock.elapsedRealtime()));
                fixture.overlay.updateStatus();
                assertEquals(4,fixture.geometry.bars(fixture.scene.car,SystemClock.elapsedRealtime()).size());
                assertTrue(fixture.drawnPixels()>0);
                assertEquals("●   ●   ●   ●", fixture.status.getText().toString());
                assertEquals("PDC 1, 3, 6, 9", fixture.status.getContentDescription().toString());

                fixture.geometry.set(PdcSnapshot.EMPTY);
                fixture.overlay.updateStatus();
                assertEquals(android.view.View.GONE, fixture.status.getVisibility());
            }
        });
    }
}
