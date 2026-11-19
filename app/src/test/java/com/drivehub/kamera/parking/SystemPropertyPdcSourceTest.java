package com.drivehub.kamera.parking;

import com.drivehub.kamera.rivela.CarFrame;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class SystemPropertyPdcSourceTest {
    private static final String SECTORS = "arcsoft.avm.mPdcBackObsShow";
    private static final String CLOSEST_CM = "arcsoft.avm.mBackObsClosestDistance";
    private static final String STATUS = "arcsoft.avm.ID_PDC_FRONT_REAR_STS";
    private static final CarFrame CAR = new CarFrame(0, 0, 4287, 1836);

    @Test public void readsTheOemSectorListOnceAndBothScalarProperties() {
        List<String> reads = new ArrayList<>();
        Map<String, String> values = values("[1, 3, 6, 9]", "80", "2");
        SystemPropertyPdcSource source = new SystemPropertyPdcSource(key -> {
            reads.add(key);
            return values.get(key);
        }, () -> 1000L);
        PdcSnapshot sample = source.read();
        assertEquals(Arrays.asList(SECTORS, CLOSEST_CM, STATUS), reads);
        assertArrayEquals(new int[]{1, 3, 6, 9}, sample.spatialLevels());
        assertEquals(80, sample.closestCm);
        assertEquals(2, sample.status);
        assertEquals(1000L, sample.sampledMs);
        PdcGeometry geometry = new PdcGeometry();
        geometry.set(sample);
        assertEquals(4, geometry.bars(CAR, 1000).size());
        assertTrue(source.diagnostic().contains(SECTORS));
    }

    @Test public void invalidTokensAffectOnlyTheirOwnPhysicalSector() {
        for (int sector = 0; sector < 4; sector++) {
            for (String invalid : new String[]{"-1", "10", "255", "unknown", "", "999999999999"}) {
                String[] tokens = {"1", "3", "6", "9"};
                tokens[sector] = invalid;
                Map<String, String> values = values("[" + String.join(",", tokens) + "]", "80", "0");
                PdcSnapshot sample = new SystemPropertyPdcSource(values::get, () -> 1000L).read();
                int[] expected = {1, 3, 6, 9};
                expected[sector] = 0;
                assertArrayEquals(expected, sample.spatialLevels());
                assertEquals(-1, sample.level(sector));
                assertEquals(80, sample.closestCm);
                PdcGeometry geometry = new PdcGeometry();
                geometry.set(sample);
                assertEquals(3, geometry.bars(CAR, 1000).size());
            }
        }
    }

    @Test public void whitespaceAndMultipleUnknownSectorsPreserveValidNeighboursAndRawDiagnostics() {
        Map<String, String> values = values(" [ 1, unknown, 255, 9 ] ", " 80 ", " 3 ");
        PdcSnapshot sample = new SystemPropertyPdcSource(values::get, () -> 1000L).read();
        assertArrayEquals(new int[]{1, 0, 0, 9}, sample.spatialLevels());
        assertEquals(80, sample.closestCm);
        assertEquals(3, sample.status);
        assertTrue(sample.diagnostic(1000).contains("raw=[1, -1, 255, 9]"));
        PdcGeometry geometry = new PdcGeometry();
        geometry.set(sample);
        assertEquals(2, geometry.bars(CAR, 1000).size());
    }

    @Test public void malformedOrMissingListsDoNotGuessSectorPositions() {
        for (String invalid : new String[]{null, "", "[]", "1,3,6,9", "[1,3,6]", "[1,3,6,9,2]", "[1,3,6,9"}) {
            Map<String, String> values = values(invalid, "80", "0");
            PdcSnapshot sample = new SystemPropertyPdcSource(values::get, () -> 1000L).read();
            assertFalse(sample.hasSectors());
            assertArrayEquals(new int[4], sample.spatialLevels());
            assertEquals(80, sample.closestCm);
        }
    }

    @Test public void unavailableMinimumOrStatusDoesNotHideValidSectors() {
        for (String invalid : new String[]{null, "", "unknown", "0", "255", "256", "-1"}) {
            Map<String, String> values = values("[1,3,6,9]", invalid, "unknown");
            PdcSnapshot sample = new SystemPropertyPdcSource(values::get, () -> 1000L).read();
            assertEquals(-1, sample.closestCm);
            assertEquals(-1, sample.status);
            PdcGeometry geometry = new PdcGeometry();
            geometry.set(sample);
            assertEquals(4, geometry.bars(CAR, 1000).size());
        }
    }

    @Test public void everyPollReplacesPreviousValuesIncludingAfterClose() {
        Map<String, String> values = values("[1,3,6,9]", "80", "0");
        AtomicLong now = new AtomicLong(1000);
        SystemPropertyPdcSource source = new SystemPropertyPdcSource(values::get, now::get);
        assertTrue(source.read().hasObstacle());
        source.close();
        now.set(1100);
        values.clear();
        PdcSnapshot missing = source.read();
        assertFalse(missing.hasSectors());
        assertEquals(-1, missing.closestCm);
        assertEquals(-1, missing.status);
        values.putAll(values("[0,0,0,0]", "256", "3"));
        PdcSnapshot off = source.read();
        assertTrue(off.hasSectors());
        assertFalse(off.hasObstacle());
        assertEquals(1100L, off.sampledMs);
    }

    private static Map<String, String> values(String sectors, String cm, String status) {
        Map<String, String> values = new HashMap<>();
        values.put(SECTORS, sectors);
        values.put(CLOSEST_CM, cm);
        values.put(STATUS, status);
        return values;
    }
}
