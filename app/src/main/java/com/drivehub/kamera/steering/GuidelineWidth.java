package com.drivehub.kamera.steering;

/** Display width only; never changes the vehicle's steering or wheel-track geometry. */
public final class GuidelineWidth {
    public static final int MIN_MM=1, MAX_MM=3000;
    public static final int DEFAULT_MM=(int)VehicleGeometry.MG4.widthWithMirrorsMm;
    public enum Mode {
        MIRRORS("mirrors"), BODY("body"), CUSTOM("custom");
        public final String id;
        Mode(String id){this.id=id;}
        public static Mode fromId(String id) {
            for(Mode mode:values())if(mode.id.equals(id))return mode;
            return MIRRORS;
        }
    }
    private GuidelineWidth() {}
    public static int validOrDefault(int widthMm) {
        return widthMm>=MIN_MM&&widthMm<=MAX_MM?widthMm:DEFAULT_MM;
    }
    public static Integer parseCustomMm(String text) {
        try {
            int value=Integer.parseInt(text.trim());
            return value>=MIN_MM&&value<=MAX_MM?value:null;
        }catch(NumberFormatException ignored){return null;}
    }
    public static int resolveMm(Mode mode,int customMm) {
        if(mode==Mode.BODY)return (int)VehicleGeometry.MG4.widthMm;
        return mode==Mode.CUSTOM?validOrDefault(customMm):DEFAULT_MM;
    }
}
