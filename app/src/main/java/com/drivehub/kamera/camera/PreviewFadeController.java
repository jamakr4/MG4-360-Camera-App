package com.drivehub.kamera.camera;

import android.view.View;

/** Main-thread transitions for a camera panel, gated by camera health and an actual texture frame. */
final class PreviewFadeController implements AutoCloseable {
    static final long FADE_IN_MS = 110L;
    static final long FADE_OUT_MS = 90L;
    static final float FADE_IN_START_ALPHA = 0.18f;

    private final View view;
    private boolean enabled, textureReady, fresh, revealed, hiding, closed;
    private int generation;
    private Runnable onHidden;

    PreviewFadeController(View view, boolean enabled) {
        this.view = view;
        this.enabled = enabled;
        view.setAlpha(0f);
    }

    boolean onTextureFrame() {
        textureReady = true;
        return revealIfReady();
    }

    boolean onFrameHealth(boolean fresh) {
        this.fresh = fresh;
        return revealIfReady();
    }

    boolean isRevealed() { return revealed; }

    private boolean revealIfReady() {
        if (closed || hiding || revealed || !textureReady || !fresh) return false;
        revealed = true;
        view.setAlpha(enabled ? FADE_IN_START_ALPHA : 1f);
        if (enabled) view.animate().alpha(1f).setDuration(FADE_IN_MS).setStartDelay(0).start();
        return true;
    }

    void hide(Runnable onHidden) {
        if (closed || hiding) return;
        hiding = true;
        this.onHidden = onHidden;
        cancelAnimation();
        if (!enabled || !revealed || view.getAlpha() == 0f) {
            finishHide();
            return;
        }
        int token = generation;
        view.animate().alpha(0f).setDuration(FADE_OUT_MS).setStartDelay(0)
                .withEndAction(() -> {
                    if (!closed && hiding && token == generation) finishHide();
                }).start();
    }

    void setEnabled(boolean enabled) {
        if (closed || this.enabled == enabled) return;
        this.enabled = enabled;
        if (!enabled) {
            cancelAnimation();
            if (hiding) finishHide();
            else if (revealed) view.setAlpha(1f);
        }
    }

    private void finishHide() {
        view.setAlpha(0f);
        Runnable callback = onHidden;
        onHidden = null;
        if (callback != null) callback.run();
    }

    private void cancelAnimation() {
        generation++;
        view.animate().withEndAction(null).cancel();
    }

    @Override public void close() {
        closed = true;
        onHidden = null;
        cancelAnimation();
    }
}
