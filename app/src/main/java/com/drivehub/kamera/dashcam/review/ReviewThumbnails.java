package com.drivehub.kamera.dashcam.review;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import com.drivehub.kamera.dashcam.ReviewFileAccess;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One decoder worker and a 4 MiB cache; queued work is bounded while scrolling. */
final class ReviewThumbnails implements AutoCloseable {
    private final ReviewRepository repository;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), task -> new Thread(task, "ReviewThumbnails"));
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private volatile int generation;
    ReviewThumbnails(ReviewRepository repository) { this.repository = repository; }

    void load(ReviewCatalog.Node node, Consumer<Bitmap> callback) {
        String key = node.key + ":" + node.modified + ":" + node.size;
        Bitmap cached = cache.get(key);
        if (cached != null) { callback.accept(cached); return; }
        int token = generation;
        try { worker.execute(() -> {
            if (token != generation) return;
            Bitmap bitmap = null;
            try (ReviewFileAccess.Lease lease = ReviewFileAccess.acquire(Collections.singleton(node.key))) {
                if (lease == null) return;
                try (ParcelFileDescriptor fd = repository.open(node)) {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    try {
                        retriever.setDataSource(fd.getFileDescriptor());
                        bitmap = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 192, 108);
                    } finally { retriever.release(); }
                }
            } catch (Exception ignored) { }
            if (bitmap != null) {
                cache.put(key, bitmap);
                Bitmap result = bitmap;
                main.post(() -> { if (token == generation) callback.accept(result); });
            }
        }); } catch (RejectedExecutionException ignored) { }
    }
    void cancel() { generation++; worker.getQueue().clear(); }
    @Override public void close() { cancel(); worker.shutdownNow(); cache.evictAll(); }
}
