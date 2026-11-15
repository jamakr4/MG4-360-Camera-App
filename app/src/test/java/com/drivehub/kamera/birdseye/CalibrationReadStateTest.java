package com.drivehub.kamera.birdseye;
import org.junit.Test;
import static org.junit.Assert.*;
public class CalibrationReadStateTest {
    private BirdseyeCalibration calibration(){return BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes());}
    @Test public void firstMissingReadNeverInventsACalibration() {
        CalibrationReadState s=new CalibrationReadState();s.ioFailure(100);
        assertNull(s.current());assertFalse(s.retrying());
    }
    @Test public void oneTransientIoFailureDoesNotBlackOutAValidatedSessionAndSuccessClearsWarning() {
        CalibrationReadState s=new CalibrationReadState();BirdseyeCalibration c=calibration();s.success(c);
        s.ioFailure(100);assertSame(c,s.current());assertTrue(s.retrying());
        s.ioFailure(2099);assertSame(c,s.current());s.success(c);assertFalse(s.retrying());
        s.ioFailure(5000);assertSame(c,s.current());
    }
    @Test public void persistentIoLossAndMalformedReplacementClearOldCalibration() {
        CalibrationReadState s=new CalibrationReadState();s.success(calibration());s.ioFailure(100);
        s.ioFailure(2100);assertNull(s.current());assertFalse(s.retrying());
        s.success(calibration());s.invalid();assertNull(s.current());
        // A new display session starts empty, even if an older session succeeded.
        assertNull(new CalibrationReadState().current());
    }
}
