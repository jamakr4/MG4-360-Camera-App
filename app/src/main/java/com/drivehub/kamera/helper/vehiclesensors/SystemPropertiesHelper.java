package com.drivehub.kamera.helper.vehiclesensors;

/**
 * Reads SAIC-specific {@code arcsoft.avm.*} system properties via reflection.
 * <p>
 * These properties are published by the OEM AVM stack and are the only source
 * of real-time vehicle sensor data (speed, gear, turn signal) available to
 * third-party apps on this platform. The {@code android.os.SystemProperties}
 * class is hidden from the public SDK, so reflection is the only option.
 */
public final class SystemPropertiesHelper {

    private SystemPropertiesHelper() {
    }

    /**
     * Reads a system property as an integer.
     *
     * @return the parsed value, or {@code defaultValue} if the property is
     *         missing, empty, unparseable, or reflection fails.
     */
    public static int getInt(String key, int defaultValue) {
        String value = get(key);
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /**
     * Reads a system property as a float.
     *
     * @return the parsed value, or {@code defaultValue} if the property is
     *         missing, empty, unparseable, or reflection fails.
     */
    public static float getFloat(String key, float defaultValue) {
        String value = get(key);
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /**
     * Reads a system property as a string.
     *
     * @return the property value, or {@code ""} if missing or reflection fails.
     */
    public static String getString(String key) {
        return get(key);
    }

    private static String get(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            String value = (String) get.invoke(null, key, "");
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }
}
