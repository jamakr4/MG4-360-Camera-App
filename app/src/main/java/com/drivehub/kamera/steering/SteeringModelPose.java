package com.drivehub.kamera.steering;

/** Single-owner display filter. Unavailable input holds the last geometry and marks it unavailable. */
public final class SteeringModelPose {
    public static final double TIME_CONSTANT_MS = 80;
    public static final class Frame {
        public final boolean available, hasValue;
        public final SteeringModel.Solution displayed;
        private Frame(boolean available, boolean hasValue, SteeringModel.Solution displayed) {
            this.available = available; this.hasValue = hasValue; this.displayed = displayed;
        }
    }
    private final SteeringModel model;
    private boolean initialized;
    private long lastMs = -1;
    private SteeringModel.Solution displayed;
    public SteeringModelPose(SteeringModel model) { this.model = model; reset(); }
    public Frame update(SteeringSnapshot sample, long nowMs) {
        SteeringModel.Solution target = sample.fresh(nowMs) ? model.solve(sample.rawDegrees) : SteeringModel.INVALID;
        if (target.valid) {
            if (!initialized) { displayed = target; initialized = true; }
            else {
                long dt = lastMs < 0 ? 0 : Math.max(0, Math.min(200, nowMs - lastMs));
                double alpha = -Math.expm1(-dt / TIME_CONSTANT_MS);
                displayed = model.fromCurvature(displayed.curvaturePerMm
                        + (target.curvaturePerMm - displayed.curvaturePerMm) * alpha);
            }
        }
        lastMs = nowMs;
        return new Frame(target.valid, initialized, displayed);
    }
    public void pause() { lastMs = -1; }
    public void reset() { initialized = false; lastMs = -1; displayed = model.solve(0); }
    public void restore(double curvature) {
        SteeringModel.Solution restored = model.fromCurvature(curvature);
        if (restored.valid) { displayed = restored; initialized = true; lastMs = -1; }
    }
}
