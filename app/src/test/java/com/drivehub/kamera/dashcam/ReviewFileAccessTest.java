package com.drivehub.kamera.dashcam;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ReviewFileAccessTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Before public void openStorage() { StorageIoGate.reopen(); }
    @After public void resetStorage() { StorageIoGate.reopen(); }

    @Test public void nativeAndDocumentTreeUsbAliasesShareProtection() {
        assertEquals("volume:primary/Download/dashcam/clip.mp4", ReviewFileAccess.key(
                new File("/storage/emulated/0/Download/dashcam/clip.mp4")));
        assertEquals("volume:abcd-1234", ReviewFileAccess.key(new File("/storage/ABCD-1234")));
        File raw = new File("/mnt/media_rw/ABCD-1234/dashcam/events/event_1/clip.mp4");
        File exposed = new File("/storage/ABCD-1234/dashcam/events/event_1/clip.mp4");
        assertEquals(ReviewFileAccess.key(raw), ReviewFileAccess.key(exposed));
        try (ReviewFileAccess.Lease lease = ReviewFileAccess.acquire(Collections.singleton(
                "volume:abcd-1234/dashcam/events/event_1"))) {
            assertNotNull(lease);
            assertTrue(ReviewFileAccess.isProtected(raw));
            assertTrue(ReviewFileAccess.isProtected(exposed.getParentFile()));
            assertFalse(ReviewFileAccess.isProtected(new File("/storage/ABCD-1234/dashcam/events/event_10")));
        }
        assertFalse(ReviewFileAccess.isProtected(raw));
    }

    @Test public void activeWriterCannotBeOpenedAndReferenceCountsReleaseIndependently() {
        File file = new File("/test/active.mp4"); String key = ReviewFileAccess.key(file);
        ReviewFileAccess.beginWrite(file);
        try { assertNull(ReviewFileAccess.acquire(Collections.singleton(key))); }
        finally { ReviewFileAccess.endWrite(file); }
        ReviewFileAccess.Lease first = ReviewFileAccess.acquire(Collections.singleton(key));
        try (ReviewFileAccess.Lease second = ReviewFileAccess.acquire(Collections.singleton(key))) {
            first.close(); first.close(); assertTrue(ReviewFileAccess.isProtected(file));
        }
        assertFalse(ReviewFileAccess.isProtected(file));
    }

    @Test public void retentionCannotTruncateASelectedClipOnAnotherThread() throws Exception {
        File clip = temp.newFile("261120120000000.mp4");
        try (FileOutputStream out = new FileOutputStream(clip)) { out.write(new byte[2048]); }
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean deleted = new AtomicBoolean(true);
        try (ReviewFileAccess.Lease lease = ReviewFileAccess.acquire(Collections.singleton(ReviewFileAccess.key(clip)))) {
            assertTrue(RecordingFiles.deletionCandidates(new File[]{clip}, 0, Collections.emptySet()).isEmpty());
            new Thread(() -> {
                try { deleted.set(RecordingFiles.deleteFreeingSpace(clip)); }
                catch (Exception error) { throw new AssertionError(error); }
                finally { done.countDown(); }
            }).start();
            assertTrue(done.await(2, TimeUnit.SECONDS)); assertFalse(deleted.get());
            assertEquals(2048, clip.length());
        }
        assertTrue(RecordingFiles.deleteFreeingSpace(clip));
    }

    @Test public void ejectClosesReadersBeforeReportingIdleAndRejectsNewReaders() throws Exception {
        ReviewFileAccess.Lease lease = ReviewFileAccess.acquire(Collections.singleton("/test/video.mp4"));
        assertNotNull(lease);
        Runnable close = lease::close;
        assertTrue(StorageIoGate.addCloseListener(close));
        try {
            assertFalse(StorageIoGate.awaitIdle(1));
            StorageIoGate.close();
            assertTrue(StorageIoGate.awaitIdle(100));
            assertNull(ReviewFileAccess.acquire(Collections.singleton("/test/another.mp4")));
        } finally { StorageIoGate.removeCloseListener(close); lease.close(); }
    }
}
