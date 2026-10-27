package com.drivehub.kamera.dashcam;

final class StorageTargetPolicy {
    static final int NONE = -1, USB = 1, INTERNAL = 2;
    static int choose(int target, boolean usbWritable, boolean internalWritable) {
        if (target != INTERNAL && usbWritable) return USB;
        if (target != USB && internalWritable) return INTERNAL;
        return NONE;
    }
}
