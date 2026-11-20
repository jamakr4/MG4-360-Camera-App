package com.drivehub.kamera.signal;

/** Main-thread UI policy only. It never owns cameras or creates activities. */
final class ReverseForegroundPolicy {
    static final long SETTLE_MS=300, RETRY_MS=1000, RETURN_TIMEOUT_MS=3000, CONFIRM_MS=2000;
    static final int MAX_ATTEMPTS=3;
    enum Foreground { FOREIGN, OWN, PROTECTED, UNKNOWN }
    private boolean enabled, freshReverse, allowed, visible, dismissed, exhausted;
    private int task=-1, attempts;
    private long generation, otherSince=-1, lastAttempt=-1, waitingSince=-1, visibleSince=-1;
    private String other="", state="idle";

    void configure(boolean enabled,int freshGear,boolean allowed) {
        boolean wasEligible=eligible();
        this.enabled=enabled;this.allowed=allowed;
        freshReverse=freshGear==2;
        if(!enabled || freshGear>=1&&freshGear<=4&&freshGear!=2) {
            reset();state=enabled?"outside R":"disabled";return;
        }
        if(wasEligible&&!eligible())invalidatePending();
    }
    void cameraVisible(int id,boolean userOpen,long now) {
        if(!enabled||!freshReverse||!allowed)return;
        if((dismissed||exhausted)&&!userOpen)return;
        if(task!=id||dismissed||exhausted||userOpen) {
            generation++;task=id;attempts=0;lastAttempt=-1;
            dismissed=exhausted=false;invalidatePending();
        }
        if(!visible)visibleSince=now;
        visible=true;waitingSince=-1;otherSince=-1;other="";
    }
    void cameraPaused(int id) {
        if(task!=id)return;
        visible=false;visibleSince=-1;waitingSince=-1;otherSince=-1;other="";
    }
    void dismiss(String reason) {
        generation++;dismissed=true;visible=false;invalidatePending();state=reason;
    }
    void abandon(String reason) { dismiss(reason);exhausted=true; }
    void reset() {
        generation++;task=-1;attempts=0;dismissed=exhausted=visible=false;
        visibleSince=lastAttempt=-1;invalidatePending();state="idle";
    }
    private void invalidatePending(){generation++;waitingSince=otherSince=-1;other="";}
    boolean anchored(){return enabled&&task>=0&&!dismissed&&!exhausted;}
    private boolean eligible(){return anchored()&&freshReverse&&allowed;}
    boolean monitor(){return eligible()&&!visible;}
    boolean dispatchAllowed(long token){return token==generation&&monitor()&&waitingSince>=0;}
    int task(){return task;}
    long generation(){return generation;}
    boolean pausedCamera(){return !visible;}
    boolean exhausted(){return exhausted;}
    String status(){return state+"; task="+task+"; attempts="+attempts+"/"+MAX_ATTEMPTS;}
    void requestFailed(){waitingSince=-1;state="return failed";}

    /** Returns true once per accepted request. All times use one monotonic clock. */
    boolean update(Foreground foreground,String component,long now) {
        if(!anchored())return false;
        if(!freshReverse||!allowed){state=!freshReverse?"paused: gear unavailable":"paused: camera access";return false;}
        if(visible) {
            if(attempts>0&&now-visibleSince>=CONFIRM_MS){attempts=0;lastAttempt=-1;}
            state=attempts>0?"confirming return":"armed";return false;
        }
        if(foreground!=Foreground.FOREIGN) {
            if(waitingSince>=0||otherSince>=0)invalidatePending();
            state="paused: "+foreground.name().toLowerCase(java.util.Locale.ROOT);return false;
        }
        if(waitingSince>=0) {
            if(now-waitingSince<RETURN_TIMEOUT_MS){state="waiting for return";return false;}
            waitingSince=-1;
        }
        if(attempts>=MAX_ATTEMPTS){exhausted=true;invalidatePending();state="paused: retry limit";return false;}
        if(!other.equals(component)){other=component;otherSince=now;}
        state="interrupted";
        if(now-otherSince<SETTLE_MS||lastAttempt>=0&&now-lastAttempt<RETRY_MS)return false;
        attempts++;waitingSince=lastAttempt=now;generation++;state="return requested";return true;
    }

    static boolean protectedComponent(String pkg,String cls) {
        return "com.saicmotor.hmi.btcall.ECallActivity".equals(cls)
                ||"com.abupdate.ota".equals(pkg)||"com.saicmotor.hmi.engmode".equals(pkg)
                ||"com.saicmotor.hmi.setupwizard".equals(pkg)||"com.saicvehicleservice".equals(pkg);
    }
}
