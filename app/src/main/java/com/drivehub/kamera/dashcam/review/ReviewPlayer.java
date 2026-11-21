package com.drivehub.kamera.dashcam.review;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Surface;
import com.drivehub.kamera.dashcam.ReviewFileAccess;
import com.drivehub.kamera.dev.DevRuntimeLog;
import java.util.ArrayList;
import java.util.List;

/** All MediaPlayer access, including callbacks and release, belongs to one Looper. */
public final class ReviewPlayer implements AutoCloseable {
    public interface Listener { void changed(State state); }
    public static final class State {
        public final ReviewCatalog.Entry selection;
        public final int index, position, duration, width, height;
        public final boolean playing, preparing, error;
        State(ReviewCatalog.Entry selection, int index, int position, int duration,
                int width, int height, boolean playing, boolean preparing, boolean error) {
            this.selection = selection; this.index = index; this.position = position; this.duration = duration;
            this.width = width; this.height = height; this.playing = playing; this.preparing = preparing; this.error = error;
        }
    }
    private final ReviewRepository repository;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("DashcamReviewPlayer");
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final AudioFocusRequest focus;
    private boolean hasFocus, audioTrack, wantsPlay, ready, preparing, failed, finished, suspended = true;
    private volatile boolean closed;
    private int index, position, duration, width, height;
    private ReviewCatalog.Entry selection;
    private ReviewFileAccess.Lease lease;
    private MediaPlayer media;
    private Surface surface;
    private volatile State state = new State(null, 0, 0, 0, 0, 0, false, false, false);
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            publish();
            if (media != null && ready && wantsPlay) worker.postDelayed(this, 250);
        }
    };
    private final Runnable preparationTimeout = () -> {
        if (preparing) fail(new IllegalStateException("preparation_timeout"));
    };

    public ReviewPlayer(Context context, ReviewRepository repository, Listener listener) {
        this.repository = repository; this.listener = listener;
        thread.start(); worker = new Handler(thread.getLooper());
        audio = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setOnAudioFocusChangeListener(change -> {
                    if (change < 0) pauseInternal();
                }, worker).build();
    }

    public State state() { return state; }
    public void restore(ReviewCatalog.Entry entry, int clip, int offset) {
        worker.post(() -> {
            releaseMedia(); releaseLease(); selection = entry;
            index = Math.max(0, Math.min(clip, entry.clips.size() - 1));
            position = Math.max(0, offset); wantsPlay = false; publish();
        });
    }
    public void resume() { worker.post(() -> { suspended = false; publish(); }); }
    public void surface(Surface next) {
        if (closed) { if (next != null) next.release(); return; }
        worker.post(() -> {
            if (closed) { if (next != null) next.release(); return; }
            if (media != null) media.setSurface(next);
            if (surface != null) surface.release();
            surface = next;
            if (surface == null) pauseInternal();
            else if (selection != null && media == null && wantsPlay && !suspended) load();
        });
    }
    public void select(ReviewCatalog.Entry entry) {
        worker.post(() -> {
            if (closed || suspended) return;
            releaseMedia(); releaseLease();
            selection = entry; index = position = duration = width = height = 0;
            finished = failed = false; wantsPlay = true;
            load();
        });
    }
    public void toggle() {
        worker.post(() -> {
            if (closed || suspended || selection == null) return;
            if (wantsPlay && (ready || preparing)) pauseInternal();
            else {
                if (finished) { index = 0; position = 0; finished = false; }
                wantsPlay = true; failed = false;
                if (media == null) load(); else if (ready) startPrepared();
            }
        });
    }
    public void step(int delta) {
        worker.post(() -> {
            if (closed || suspended || selection == null) return;
            int next = index + delta;
            if (next < 0 || next >= selection.clips.size()) return;
            index = next; position = 0; finished = failed = false; wantsPlay = true;
            load();
        });
    }
    public void seek(int milliseconds) {
        worker.post(() -> {
            position = Math.max(0, Math.min(milliseconds, duration));
            finished = false;
            if (media != null && ready) media.seekTo(position, MediaPlayer.SEEK_CLOSEST);
            publish();
        });
    }
    public void suspend() { worker.post(this::suspendInternal); }
    private void suspendInternal() {
        suspended = true; wantsPlay = false;
        rememberPosition(); releaseMedia(); releaseLease(); publish();
    }
    private void pauseInternal() {
        wantsPlay = false;
        rememberPosition();
        if (media != null && ready) {
            media.pause();
            worker.removeCallbacks(tick);
            abandonFocus();
        } else releaseMedia();
        publish();
    }
    public void clear() {
        worker.post(() -> {
            rememberPosition(); wantsPlay = false; releaseMedia(); releaseLease();
            selection = null; index = position = duration = 0; failed = false; publish();
        });
    }

    private void load() {
        releaseMedia();
        if (closed || suspended || selection == null || selection.clips.isEmpty()) { publish(); return; }
        if (lease == null) {
            List<String> keys = new ArrayList<>();
            if (selection.event) keys.add(selection.node.key);
            for (ReviewCatalog.Node clip : selection.clips) keys.add(clip.key);
            lease = ReviewFileAccess.acquire(keys);
            if (lease == null) { fail(new IllegalStateException("file_busy_or_storage_closed")); return; }
        }
        if (surface == null) { publish(); return; }
        MediaPlayer current = new MediaPlayer();
        media = current; preparing = true; ready = false; failed = false;
        current.setOnPreparedListener(player -> {
            if (media != player || closed || suspended) return;
            worker.removeCallbacks(preparationTimeout);
            preparing = false; ready = true;
            duration = player.getDuration();
            width = player.getVideoWidth(); height = player.getVideoHeight();
            if (position > 0) player.seekTo(Math.min(position, duration), MediaPlayer.SEEK_CLOSEST);
            audioTrack = false;
            for (MediaPlayer.TrackInfo track : player.getTrackInfo())
                if (track.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) audioTrack = true;
            if (wantsPlay) startPrepared(); else publish();
        });
        current.setOnSeekCompleteListener(player -> { if (media == player) publish(); });
        current.setOnVideoSizeChangedListener((player, w, h) -> {
            if (media == player) { width = w; height = h; publish(); }
        });
        current.setOnCompletionListener(player -> {
            if (media != player) return;
            if (index + 1 < selection.clips.size()) { index++; position = 0; load(); }
            else {
                position = duration; finished = true; wantsPlay = false;
                releaseMedia(); releaseLease(); publish();
            }
        });
        current.setOnErrorListener((player, what, extra) -> {
            if (media == player) fail(new IllegalStateException("media_error:" + what + "/" + extra));
            return true;
        });
        try (ParcelFileDescriptor fd = repository.open(selection.clips.get(index))) {
            current.setSurface(surface);
            current.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            current.setDataSource(fd.getFileDescriptor());
            current.prepareAsync();
            worker.postDelayed(preparationTimeout, 15_000);
            publish();
        } catch (Exception error) { fail(error); }
    }

    private void rememberPosition() {
        if (media != null && ready) try { position = media.getCurrentPosition(); }
        catch (IllegalStateException ignored) { }
    }
    private void startPrepared() {
        if (surface == null || media == null || !ready) return;
        if (audioTrack && audio != null && !hasFocus) {
            hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!hasFocus) { fail(new IllegalStateException("audio_focus_unavailable")); return; }
        }
        media.start();
        worker.removeCallbacks(tick); worker.post(tick);
    }
    private void fail(Exception error) {
        DevRuntimeLog.add("DashcamReview", "Playback failed: " + error);
        failed = true; wantsPlay = false;
        releaseMedia(); releaseLease(); publish();
    }
    private void releaseMedia() {
        worker.removeCallbacks(tick);
        worker.removeCallbacks(preparationTimeout);
        MediaPlayer old = media; media = null; ready = preparing = false;
        if (old != null) old.release();
        audioTrack = false;
        abandonFocus();
    }
    private void abandonFocus() {
        if (hasFocus && audio != null) audio.abandonAudioFocusRequest(focus);
        hasFocus = false;
    }
    private void releaseLease() { if (lease != null) { lease.close(); lease = null; } }
    private void publish() {
        rememberPosition();
        State next = new State(selection, index, position, duration, width, height,
                ready && wantsPlay, preparing, failed);
        state = next;
        main.post(() -> { if (!closed) listener.changed(next); });
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        worker.post(() -> {
            suspendInternal();
            if (surface != null) { surface.release(); surface = null; }
            thread.quitSafely();
        });
    }
}
