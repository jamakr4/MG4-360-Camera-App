package com.drivehub.kamera.side;

/** Independent rendering choice for each axle. Existing booleans map only to NONE/BASIC. */
public enum WheelAlignmentMode {
    NONE("none",0), BASIC("basic",1), ADVANCED("advanced",2);
    public final String id;
    public final int code;
    WheelAlignmentMode(String id,int code){this.id=id;this.code=code;}
    public static WheelAlignmentMode fromId(String id,boolean legacyEnabled){
        for(WheelAlignmentMode mode:values())if(mode.id.equals(id))return mode;
        return legacyEnabled?BASIC:NONE;
    }
}
