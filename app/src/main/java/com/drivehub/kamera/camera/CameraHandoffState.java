package com.drivehub.kamera.camera;

import java.util.HashMap;
import java.util.Map;

/** Pure state shared by the handoff coordinator and the existing camera start paths. */
final class CameraHandoffState {
    private final Map<String, Integer> demand = new HashMap<>();
    private long generation;
    private boolean paused;

    synchronized long transition(boolean value) {
        if (paused != value) {
            paused = value;
            generation++;
            demand.replaceAll((key, ready) -> 0);
        }
        return generation;
    }
    synchronized long generation() { return generation; }
    synchronized void require(String key, boolean needed) {
        if (!needed) demand.remove(key);
        else demand.putIfAbsent(key, 0);
    }
    synchronized void ready(String key, boolean value, long token) {
        if (token == generation && demand.containsKey(key)) demand.put(key, value ? 1 : 0);
    }
    synchronized void failed(String key, long token) {
        if (token == generation && demand.containsKey(key)) demand.put(key, -1);
    }
    synchronized String evaluate(boolean release, long token, int devices, int opening,
                                 boolean blocked, boolean prepared, boolean expired) {
        if (token != generation) return "error";
        if (release && blocked && devices == 0 && opening == 0) return "released";
        if (!release && prepared) {
            if (blocked) return "blocked";
            if (demand.containsValue(-1)) return "error";
            if (opening == 0 && !demand.containsValue(0)) {
                if (devices > 0) return "claimed";
                if (demand.isEmpty()) return "idle";
            }
        }
        return expired ? "timeout" : null;
    }
    static boolean restorePause(boolean saved, int savedBoot, int currentBoot) {
        return saved && (currentBoot < 0 || savedBoot == currentBoot);
    }
}
