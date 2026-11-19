package com.drivehub.kamera.birdseye;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ThreeDFailureTest {
    @Test public void retainsModelCauseAndTextureDetails() {
        IOException texture=new IOException("Cannot decode model paint: piccadilly-blue");
        IOException model=new IOException("Cannot load prepared 3D paints",texture);
        ThreeDFailure failure=ThreeDFailure.at(ThreeDFailure.Kind.MODEL,model);
        assertEquals(ThreeDFailure.Kind.MODEL,failure.kind);
        assertSame(model,failure.getCause());
        assertTrue(failure.getMessage().contains("3D-MODEL"));
        assertTrue(failure.getMessage().contains("Cannot load prepared 3D paints"));
        assertTrue(failure.getMessage().contains("piccadilly-blue"));
    }
    @Test public void exceptionsWithoutTextStillProduceAnError() {
        ThreeDFailure failure=ThreeDFailure.at(ThreeDFailure.Kind.GPU_SETUP,new IllegalStateException());
        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains("3D-GPU-INIT"));
        assertTrue(failure.getMessage().contains("IllegalStateException"));
    }
    @Test public void memoryAndAlreadyClassifiedErrorsKeepTheirRealCause() {
        ThreeDFailure memory=ThreeDFailure.at(ThreeDFailure.Kind.PROJECTION,
                new RuntimeException("Allocating bowl vertices",new OutOfMemoryError()));
        assertEquals(ThreeDFailure.Kind.MEMORY,memory.kind);
        assertTrue(memory.getMessage().contains("Allocating bowl vertices"));
        assertSame(memory,ThreeDFailure.at(ThreeDFailure.Kind.MODEL,memory));
    }
    @Test public void diagnosticTextIsBoundedEvenForLongDriverLogsAndCycles() {
        RuntimeException first=new RuntimeException("driver"),second=new RuntimeException("context",first);
        first.initCause(second);
        assertTrue(ThreeDFailure.details(first).length()<1201);
        assertEquals(1201,ThreeDFailure.details(new RuntimeException(new String(new char[2000]).replace('\0','x'))).length());
    }
}
