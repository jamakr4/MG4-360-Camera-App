package com.drivehub.kamera.camera;

import org.junit.Test;
import static org.junit.Assert.*;

public class IpkStatusStateTest {
    @Test public void aShortSignalIsRememberedEvenBeforeTheFirstPollingTick() {
        IpkStatusState state = new IpkStatusState(null);
        state.update("Kamera startet");
        state.idle();
        assertEquals("IPK2 · aus · zuletzt: Kamera startet", state.snapshot());
    }

    @Test public void repeatedHideDoesNotNestIdleOrLoseTheLastCameraResult() {
        IpkStatusState state = new IpkStatusState(null);
        state.update("live · 25 FPS");
        for (int i = 0; i < 100; i++) state.idle();
        assertEquals("live · 25 FPS", state.lastSession());
        assertEquals("IPK2 · aus · zuletzt: live · 25 FPS", state.snapshot());
    }

    @Test public void serviceRestartRestoresTheActualLastSession() {
        IpkStatusState original = new IpkStatusState(null);
        original.update("tab=0 · Wechsel unbestätigt");
        IpkStatusState restored = new IpkStatusState(original.lastSession());
        assertTrue(restored.snapshot().contains("tab=0 · Wechsel unbestätigt"));
        restored.update("live · 25 FPS");
        assertEquals("IPK2 · live · 25 FPS", restored.snapshot());
    }
}
