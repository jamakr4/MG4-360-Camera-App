package com.drivehub.kamera.maneuver;

import com.drivehub.kamera.dev.DevRuntimeLog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;

import androidx.core.content.ContextCompat;

final class ManeuverHardkeySuppressor {

    interface Callback {
        boolean isCaptureActive();

        boolean shouldOwnSteeringStick();

        void onRawHardkey(RawEvent event, BroadcastAbort abort);

        void onLogicalHardkey(LogicalEvent event, BroadcastAbort abort);

        void onStickDown(int rawCode);

        void onSuppressorRearmRequested();
    }

    interface BroadcastAbort {
        boolean isOrdered();

        void abort();
    }

    static final class RawEvent {
        final int rawCode;
        final boolean down;
        final boolean longPress;
        final boolean stickCode;
        final boolean ownsStick;
        final boolean captureActive;

        RawEvent(
                int rawCode,
                boolean down,
                boolean longPress,
                boolean stickCode,
                boolean ownsStick,
                boolean captureActive
        ) {
            this.rawCode = rawCode;
            this.down = down;
            this.longPress = longPress;
            this.stickCode = stickCode;
            this.ownsStick = ownsStick;
            this.captureActive = captureActive;
        }
    }

    static final class LogicalEvent {
        final int logicalCode;
        final boolean down;
        final boolean stickCode;
        final boolean ownsStick;

        LogicalEvent(int logicalCode, boolean down, boolean stickCode, boolean ownsStick) {
            this.logicalCode = logicalCode;
            this.down = down;
            this.stickCode = stickCode;
            this.ownsStick = ownsStick;
        }
    }

    static final int RAW_SWC_UP = 0x129;
    static final int RAW_SWC_DOWN = 0x12a;
    static final int RAW_SWC_LEFT = 0x12b;
    static final int RAW_SWC_RIGHT = 0x12c;
    static final int RAW_SWC_CENTER = 0x12d;

    private static final String TAG = "ManeuverHardkeySuppressor";
    private static final String ACTION_RAW_HARDKEY = "com.saic.keyevent.hardkey.report";
    private static final String ACTION_SYSTEMUI_HARDKEY = "com.android.systemui.ACTION_HARD_KEY_EVENT";
    private static final String EXTRA_RAW_KEYCODE = "android.intent.extra.hardkey.keycode";
    private static final String EXTRA_RAW_KEYCODE_ALT = "keycode";
    private static final String EXTRA_RAW_KEYCODE_CAMEL = "keyCode";
    private static final String EXTRA_RAW_DOWN = "android.intent.extra.hardkey.down";
    private static final String EXTRA_RAW_DOWN_ALT = "down";
    private static final String EXTRA_RAW_LONGPRESS = "android.intent.extra.hardkey.longpress";
    private static final String EXTRA_RAW_LONGPRESS_ALT = "longpress";
    private static final String EXTRA_RAW_LONGPRESS_CAMEL = "longPress";
    private static final String EXTRA_LOGICAL_KEY_CODE = "KEY_CODE";
    private static final String EXTRA_LOGICAL_DOWN = "DOWN";

    private final Context context;
    private final Callback callback;
    private BroadcastReceiver hardkeyReceiver;
    private BroadcastReceiver logicalHardkeyReceiver;
    private boolean registered;

    ManeuverHardkeySuppressor(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    void register() {
        if (registered) return;
        registered = true;
        hardkeyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleRawHardkey(intent, createAbortHandle(this));
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_RAW_HARDKEY);
        filter.setPriority(1000);
        try {
            ContextCompat.registerReceiver(context, hardkeyReceiver, filter,
                    ContextCompat.RECEIVER_EXPORTED);
            DevRuntimeLog.add("Maneuver", "raw hardkey receiver registered");
        } catch (Throwable t) {
            DevRuntimeLog.add("Maneuver", "raw hardkey receiver failed: " + t.getClass().getSimpleName());
            Log.w(TAG, "Failed to register raw hardkey receiver", t);
        }

        logicalHardkeyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleLogicalHardkey(intent, createAbortHandle(this));
            }
        };
        IntentFilter logicalFilter = new IntentFilter(ACTION_SYSTEMUI_HARDKEY);
        logicalFilter.setPriority(1000);
        try {
            ContextCompat.registerReceiver(context, logicalHardkeyReceiver, logicalFilter,
                    ContextCompat.RECEIVER_EXPORTED);
            DevRuntimeLog.add("Maneuver", "logical hardkey receiver registered");
        } catch (Throwable t) {
            DevRuntimeLog.add("Maneuver", "logical receiver failed: " + t.getClass().getSimpleName());
            Log.w(TAG, "Failed to register logical hardkey receiver", t);
        }
    }

    void unregister() {
        registered = false;
        if (hardkeyReceiver != null) {
            try {
                context.unregisterReceiver(hardkeyReceiver);
            } catch (Throwable ignored) {
            }
        }
        hardkeyReceiver = null;
        if (logicalHardkeyReceiver != null) {
            try {
                context.unregisterReceiver(logicalHardkeyReceiver);
            } catch (Throwable ignored) {
            }
        }
        logicalHardkeyReceiver = null;
    }

    static boolean isStickCode(int rawCode) {
        return rawCode == RAW_SWC_UP
                || rawCode == RAW_SWC_DOWN
                || rawCode == RAW_SWC_LEFT
                || rawCode == RAW_SWC_RIGHT
                || rawCode == RAW_SWC_CENTER;
    }

    static boolean isVolumeMappedStickCode(int rawCode) {
        return rawCode == RAW_SWC_UP || rawCode == RAW_SWC_DOWN;
    }

    static boolean isLogicalStickCode(int logicalCode) {
        return logicalCode >= 1 && logicalCode <= 15;
    }

    static String formatHex(int value) {
        if (value < 0) return String.valueOf(value);
        return "0x" + Integer.toHexString(value);
    }

    private void handleRawHardkey(Intent intent, BroadcastAbort abort) {
        if (intent == null) return;
        int rawCode = readRawKeyCode(intent);
        boolean isDown = readBooleanExtra(intent, EXTRA_RAW_DOWN, EXTRA_RAW_DOWN_ALT);
        boolean isLongPress = readBooleanExtra(intent, EXTRA_RAW_LONGPRESS,
                EXTRA_RAW_LONGPRESS_ALT, EXTRA_RAW_LONGPRESS_CAMEL);
        boolean stickCode = isStickCode(rawCode);
        boolean ownsStick = callback.shouldOwnSteeringStick() && stickCode;
        RawEvent event = new RawEvent(rawCode, isDown, isLongPress, stickCode,
                ownsStick, callback.isCaptureActive());

        if (stickCode) {
            DevRuntimeLog.add("Maneuver", "raw=" + formatHex(rawCode)
                    + " down=" + isDown + " long=" + isLongPress
                    + " capture=" + event.captureActive);
        }

        callback.onRawHardkey(event, abort);
        if (ownsStick) {
            callback.onSuppressorRearmRequested();
        }
        if (!isDown || !ownsStick) {
            return;
        }
        callback.onStickDown(rawCode);
    }

    private void handleLogicalHardkey(Intent intent, BroadcastAbort abort) {
        if (intent == null) return;
        int logicalCode = readIntExtra(intent, EXTRA_LOGICAL_KEY_CODE,
                EXTRA_RAW_KEYCODE, EXTRA_RAW_KEYCODE_ALT, EXTRA_RAW_KEYCODE_CAMEL);
        boolean stickCode = isLogicalStickCode(logicalCode);
        boolean ownsStick = callback.shouldOwnSteeringStick() && stickCode;
        boolean down = readBooleanExtra(intent, EXTRA_LOGICAL_DOWN, EXTRA_RAW_DOWN_ALT);
        LogicalEvent event = new LogicalEvent(logicalCode, down, stickCode, ownsStick);

        if (!stickCode) return;
        if (down) {
            DevRuntimeLog.add("Maneuver", "logical=" + logicalCode);
        }
        callback.onLogicalHardkey(event, abort);
        if (ownsStick) {
            callback.onSuppressorRearmRequested();
        }
    }

    private static BroadcastAbort createAbortHandle(BroadcastReceiver receiver) {
        return new BroadcastAbort() {
            @Override
            public boolean isOrdered() {
                return receiver != null && receiver.isOrderedBroadcast();
            }

            @Override
            public void abort() {
                if (receiver != null) {
                    receiver.abortBroadcast();
                }
            }
        };
    }

    private static int readRawKeyCode(Intent intent) {
        return readIntExtra(intent, EXTRA_RAW_KEYCODE, EXTRA_RAW_KEYCODE_ALT, EXTRA_RAW_KEYCODE_CAMEL);
    }

    private static int readIntExtra(Intent intent, String... keys) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return -1;
        }
        for (String key : keys) {
            if (!extras.containsKey(key)) {
                continue;
            }
            Object value = extras.get(key);
            if (value instanceof Number) {
                int intValue = ((Number) value).intValue();
                if (intValue >= 0) {
                    return intValue;
                }
            } else if (value instanceof String) {
                try {
                    int intValue = Integer.parseInt((String) value);
                    if (intValue >= 0) {
                        return intValue;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }

    private static boolean readBooleanExtra(Intent intent, String... keys) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return false;
        }
        for (String key : keys) {
            if (!extras.containsKey(key)) {
                continue;
            }
            Object value = extras.get(key);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
            if (value instanceof Number) {
                return ((Number) value).intValue() > 0;
            }
            if (value instanceof String) {
                String text = ((String) value).trim();
                return "true".equalsIgnoreCase(text) || "1".equals(text);
            }
            return false;
        }
        return false;
    }
}
