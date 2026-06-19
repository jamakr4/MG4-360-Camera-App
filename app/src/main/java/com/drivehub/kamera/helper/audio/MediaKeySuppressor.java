package com.drivehub.kamera.helper.audio;

import com.drivehub.kamera.dev.DevRuntimeLog;

import android.content.Context;
import android.content.Intent;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.view.KeyEvent;

public final class MediaKeySuppressor {

    private final Handler handler;
    private final MediaSession mediaSession;
    private boolean active;

    public MediaKeySuppressor(Context context, Handler handler) {
        this.handler = handler;
        mediaSession = new MediaSession(context.getApplicationContext(), "ManeuverModeSuppressor");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                KeyEvent event = mediaButtonIntent == null ? null
                        : mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                    DevRuntimeLog.add("Maneuver", "media key suppressed=" + event.getKeyCode());
                }
                return true;
            }

            @Override
            public void onPlay() {
                DevRuntimeLog.add("Maneuver", "media play suppressed");
            }

            @Override
            public void onPause() {
                DevRuntimeLog.add("Maneuver", "media pause suppressed");
            }

            @Override
            public void onSkipToNext() {
                DevRuntimeLog.add("Maneuver", "media next suppressed");
            }

            @Override
            public void onSkipToPrevious() {
                DevRuntimeLog.add("Maneuver", "media previous suppressed");
            }
        }, handler);
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .setActions(PlaybackState.ACTION_PLAY
                        | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_STOP)
                .build());
    }

    public void setActive(boolean enabled) {
        handler.post(() -> {
            if (active == enabled) return;
            active = enabled;
            mediaSession.setActive(enabled);
            DevRuntimeLog.add("Maneuver", "media suppressor=" + enabled);
        });
    }

    public void release() {
        handler.post(() -> {
            active = false;
            mediaSession.setActive(false);
            mediaSession.release();
        });
    }
}
