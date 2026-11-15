package com.drivehub.kamera.steering;

/** Immutable read result. A held angle may have an old sensor timestamp but a fresh successful read. */
public final class SteeringSnapshot {
    public static final long MAX_AGE_MS = 500;
    public static final float MAX_RAW_DEGREES = 516.95f;
    public static final SteeringSnapshot EMPTY = unavailable("paused", 0);
    public final float rawDegrees;
    public final boolean available;
    public final long readElapsedMs, sensorTimestampNs;
    public final String state;

    private SteeringSnapshot(float raw, boolean available, long readMs, long timestampNs, String state) {
        this.rawDegrees=raw; this.available=available; this.readElapsedMs=readMs;
        this.sensorTimestampNs=timestampNs; this.state=state;
    }

    public static SteeringSnapshot value(float raw, int status, long readMs, long timestampNs) {
        boolean valid=status==0 && Float.isFinite(raw) && Math.abs(raw)<=MAX_RAW_DEGREES;
        return new SteeringSnapshot(raw, valid, readMs, timestampNs,
                status!=0 ? "property status="+status : valid ? "available" : "invalid angle");
    }

    public static SteeringSnapshot unavailable(String reason, long readMs) {
        return new SteeringSnapshot(Float.NaN, false, readMs, 0, reason);
    }

    public boolean fresh(long nowMs) {
        return available && nowMs>=readElapsedMs && nowMs-readElapsedMs<=MAX_AGE_MS;
    }

    public SteeringSnapshot from(String source){return new SteeringSnapshot(rawDegrees,available,readElapsedMs,sensorTimestampNs,source+": "+state);}

    /** Public legacy CarSensorEvent contract: vendor property ID, Float payload and timestamp. */
    public static SteeringSnapshot decodeSensor(Object event,long readMs) throws ReflectiveOperationException {
        if(event==null)return unavailable("no sensor event",readMs);
        Class<?> type=event.getClass();Object id=type.getField("sensorType").get(event);
        if(!(id instanceof Integer)||(Integer)id!=SteeringReader.PROPERTY)return unavailable("wrong steering sensor",readMs);
        Object raw=type.getField("floatValues").get(event);
        if(!(raw instanceof float[])||((float[])raw).length<1)return unavailable("missing Float sensor value",readMs);
        long timestamp=0;
        try{Object stamp=type.getField("timestamp").get(event);if(stamp instanceof Number)timestamp=((Number)stamp).longValue();}
        catch(NoSuchFieldException older){}
        return value(((float[])raw)[0],0,readMs,timestamp);
    }

    /** Accept CarPropertyValue<Float> from either overload, and legacy direct Float returns. */
    public static SteeringSnapshot decode(Object value, boolean typed, long readMs) throws ReflectiveOperationException {
        if (value==null) return unavailable("no value",readMs);
        if (typed && value instanceof Float) return value((Float)value,0,readMs,0);
        Class<?> type=value.getClass();
        Object status;
        try { status=type.getMethod("getStatus").invoke(value); }
        catch (NoSuchMethodException wrongType) { return unavailable("expected Float property",readMs); }
        Object raw=type.getMethod("getValue").invoke(value);
        if (status instanceof Integer && (Integer)status!=0)
            return value(raw instanceof Float?(Float)raw:Float.NaN,(Integer)status,readMs,0);
        long timestamp=0;
        try { timestamp=((Number)type.getMethod("getTimestamp").invoke(value)).longValue(); }
        catch (NoSuchMethodException ignored) { /* Old OEM CarPropertyValue has no timestamp. */ }
        if (!(status instanceof Integer) || !(raw instanceof Float)) return unavailable("expected Float/status",readMs);
        return value((Float)raw,(Integer)status,readMs,timestamp);
    }
}
