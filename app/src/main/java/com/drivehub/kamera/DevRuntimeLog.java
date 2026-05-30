package com.drivehub.kamera;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

final class DevRuntimeLog {
    private static final int MAX_LINES = 120;
    private static final Object LOCK = new Object();
    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private DevRuntimeLog() {
    }

    static void add(String source, String message) {
        String line = TIME_FORMAT.format(new Date()) + "  [" + source + "] " + message;
        synchronized (LOCK) {
            LINES.addLast(line);
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
        }
    }

    static String snapshot() {
        synchronized (LOCK) {
            if (LINES.isEmpty()) {
                return "No events yet";
            }
            StringBuilder sb = new StringBuilder();
            for (String line : LINES) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            return sb.toString();
        }
    }

    static void clear() {
        synchronized (LOCK) {
            LINES.clear();
        }
    }
}
