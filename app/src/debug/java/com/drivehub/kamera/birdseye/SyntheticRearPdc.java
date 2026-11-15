package com.drivehub.kamera.birdseye;

import com.drivehub.kamera.parking.PdcGeometry;
import com.drivehub.kamera.parking.PdcSnapshot;
import com.drivehub.kamera.rivela.SyntheticRearPdcGeometry;

/** Invented test data only: rear sectors left-to-right 50/80/110/140 cm. Never packaged in release. */
final class SyntheticRearPdc {
    private SyntheticRearPdc() {}
    static PdcGeometry geometry() {
        return new SyntheticRearPdcGeometry();
    }
    static PdcSnapshot sample(long nowMs) {
        return new PdcSnapshot(new int[]{1,3,6,9},50,0,nowMs);
    }
}
