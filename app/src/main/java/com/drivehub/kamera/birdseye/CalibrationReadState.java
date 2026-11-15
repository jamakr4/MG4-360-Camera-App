package com.drivehub.kamera.birdseye;

/** Session-local grace for a transient file IO failure; malformed data is never reused. */
final class CalibrationReadState {
    static final long IO_GRACE_MS=2000;
    private BirdseyeCalibration current;
    private long failureSince=-1;
    void success(BirdseyeCalibration calibration) {current=calibration;failureSince=-1;}
    void ioFailure(long now) {
        if(failureSince<0)failureSince=now;
        if(now-failureSince>=IO_GRACE_MS)current=null;
    }
    void invalid() {current=null;failureSince=-1;}
    BirdseyeCalibration current() {return current;}
    boolean retrying() {return current!=null&&failureSince>=0;}
}
