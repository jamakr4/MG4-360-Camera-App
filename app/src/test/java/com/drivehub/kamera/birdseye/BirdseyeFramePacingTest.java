package com.drivehub.kamera.birdseye;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BirdseyeFramePacingTest {
    private static final long VSYNC = 16_667;

    /** Simulates 60 Hz display callbacks over 10 s; returns {draws, skipped camera frames, frames}. */
    private static long[] simulate(long periodUs, long[] phase, long jitterUs, long seed) {
        Random random = new Random(seed);
        BirdseyeFramePacing pacing = new BirdseyeFramePacing();
        long[] next = phase.clone(), latest = new long[4], shown = new long[4];
        long lastDraw = 0, draws = 0, skipped = 0, frames = 0;
        boolean[] unseen = new boolean[4];
        for (long now = 0; now < 10_000_000; now += VSYNC) {
            for (int i = 0; i < 4; i++) {
                while (next[i] <= now) {
                    long receipt = next[i] + (jitterUs == 0 ? 0 : (long) (random.nextDouble() * jitterUs));
                    if (receipt > now) break;
                    if (unseen[i]) skipped++;
                    latest[i] = receipt; unseen[i] = true; frames++;
                    next[i] += periodUs;
                }
            }
            if (pacing.shouldDraw(latest, shown, now, lastDraw)) {
                draws++; lastDraw = now;
                for (int i = 0; i < 4; i++) { shown[i] = latest[i]; unseen[i] = false; }
            }
        }
        return new long[]{draws, skipped, frames};
    }

    @Test public void spreadCamerasAt25FpsSkipNoFrameAndDrawFarBelowVsync() {
        long[] result = simulate(40_000, new long[]{0, 9_000, 23_000, 31_000}, 3_000, 1);
        assertEquals("camera frames never shown", 0, result[1]);
        assertTrue("draws/s=" + result[0] / 10.0, result[0] / 10.0 <= 35);
    }

    @Test public void spreadCamerasAt30FpsSkipNoFrame() {
        long[] result = simulate(33_333, new long[]{0, 7_000, 15_000, 29_000}, 3_000, 2);
        assertEquals(0, result[1]);
        assertTrue("draws/s=" + result[0] / 10.0, result[0] / 10.0 <= 45);
    }

    @Test public void alignedCamerasDrawOncePerRound() {
        long[] result = simulate(40_000, new long[]{1_000, 1_500, 2_000, 2_500}, 0, 3);
        assertEquals(0, result[1]);
        assertEquals(25.0, result[0] / 10.0, 1.0);
    }

    @Test public void slowlyDriftingCamerasSkipNoFrame() {
        BirdseyeFramePacing pacing = new BirdseyeFramePacing();
        long[] latest = new long[4], shown = new long[4];
        long[] periods = {40_000, 40_050, 39_950, 40_100}, next = {0, 10_000, 20_000, 30_000};
        boolean[] unseen = new boolean[4];
        long lastDraw = 0, skipped = 0;
        for (long now = 0; now < 30_000_000; now += VSYNC) {
            for (int i = 0; i < 4; i++) while (next[i] <= now) {
                if (unseen[i]) skipped++;
                latest[i] = next[i]; unseen[i] = true; next[i] += periods[i];
            }
            if (pacing.shouldDraw(latest, shown, now, lastDraw)) {
                lastDraw = now;
                for (int i = 0; i < 4; i++) { shown[i] = latest[i]; unseen[i] = false; }
            }
        }
        assertEquals(0, skipped);
    }

    @Test public void stalledOrMissingCamerasDoNotHoldTheRound() {
        BirdseyeFramePacing pacing = new BirdseyeFramePacing();
        long[] shown = {0, 1_000_000, 1_000_000, 1_000_000};
        // Camera 0 never delivered; camera 3 stalled long ago.
        long[] latest = {0, 2_000_000, 2_000_000, 1_000_000};
        assertTrue(pacing.shouldDraw(latest, shown, 2_001_000, 1_900_000));
    }

    @Test public void idleRedrawKeepsOverlaysTickingWithoutNewImages() {
        BirdseyeFramePacing pacing = new BirdseyeFramePacing();
        long[] shown = {1_000_000, 1_000_000, 1_000_000, 1_000_000};
        long[] latest = shown.clone();
        assertFalse(pacing.shouldDraw(latest, shown, 1_050_000, 1_000_000));
        assertTrue(pacing.shouldDraw(latest, shown, 1_000_000 + BirdseyeFramePacing.IDLE_REDRAW_US, 1_000_000));
    }
}
