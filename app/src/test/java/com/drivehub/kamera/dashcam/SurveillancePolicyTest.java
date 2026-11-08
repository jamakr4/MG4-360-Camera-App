package com.drivehub.kamera.dashcam;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;
import static com.drivehub.kamera.dashcam.SurveillancePolicy.*;

public class SurveillancePolicyTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void parsesStringMinutesWithAbsoluteTwoHourMaximum() {
        assertEquals(7, minutes(" 7 "));
        assertEquals(120, minutes("121"));
        assertEquals(120, minutes("9223372036854775807"));
        for (String value : new String[]{null, "", " ", "bad", "0", "-1", "1.5", "99999999999999999999999"})
            assertEquals(120, minutes(value));
    }

    @Test public void explicitStoragePoliciesAndSafeDefault() {
        assertEquals("recycle", policy(null));
        assertEquals("recycle", policy("  "));
        assertEquals("stop", policy(" stop "));
        assertEquals("warn", policy("warn"));
        assertThrows(IllegalArgumentException.class, () -> policy("overwrite"));
    }

    @Test public void everyStorageWarningBoundary() {
        assertEquals("unknown", storageLevel(-1));
        assertEquals("very_low", storageLevel(0));
        assertEquals("very_low", storageLevel(GIB - 1));
        assertEquals("low", storageLevel(GIB));
        assertEquals("low", storageLevel(3 * GIB - 1));
        assertEquals("limited", storageLevel(3 * GIB));
        assertEquals("limited", storageLevel(9 * GIB - 1));
        assertEquals("ok", storageLevel(9 * GIB));
    }

    @Test public void usbAliasesAndDotSegmentsCannotBypassIsolation() throws Exception {
        assertTrue(overlaps(new File("/mnt/media_rw/ABCD/dashcam"), new File("/storage/ABCD/dashcam/sub/../surv")));
        assertTrue(overlaps(new File("/storage/ABCD"), new File("/mnt/media_rw/ABCD/dashcam")));
        assertTrue(overlaps(new File("/sdcard/Download/dashcam"), new File("/storage/emulated/0/Download/dashcam")));
        assertTrue(overlaps(new File("/storage/self/primary/Download/dashcam"), new File("/sdcard/Download/dashcam")));
        assertFalse(overlaps(new File("/storage/ABCD/dashcam"), new File("/mnt/media_rw/ABCD/surveillance")));
        assertFalse(overlaps(new File("/tmp/dashcam"), new File("/tmp/dashcam-other")));
        assertTrue(overlaps(new File("/"), new File("/tmp/surveillance")));
    }

    @Test public void resolvesSymlinkAliases() throws Exception {
        File normal = temp.newFolder("normal");
        File alias = new File(temp.getRoot(), "alias");
        Files.createSymbolicLink(alias.toPath(), normal.toPath());
        assertTrue(overlaps(normal, new File(alias, "nested")));
    }

    @Test public void ownedLookingSymlinkCannotDeleteAnOutsideClip() throws Exception {
        File normal = temp.newFile("261108123456789.mp4");
        File alias = new File(temp.getRoot(), "surv_20261108123456001_aaaaaaaa.mp4");
        Files.createSymbolicLink(alias.toPath(), normal.toPath());
        assertTrue(candidates(temp.getRoot().listFiles(), Collections.emptySet()).isEmpty());
        assertTrue(normal.exists());
    }

    @Test public void candidatesExcludeNormalForeignPartialDirectoriesAndProtectedClips() throws Exception {
        File old = clip(1), protectedClip = clip(2), newest = clip(3);
        temp.newFile("261108123456789.mp4");
        temp.newFile("holiday.mp4");
        temp.newFile("surv_20261108123456004_aaaaaaaa.mp4.part");
        temp.newFolder("events");
        List<File> found = candidates(temp.getRoot().listFiles(), Collections.singleton(base(protectedClip)));
        assertEquals(java.util.Arrays.asList(old, newest), found);
        assertEquals(Collections.emptyList(), candidates(null, Collections.emptySet()));
    }

    @Test public void recyclesOldestUntilReserveAndNeverDeletesProtectedOrDashcamClips() throws Exception {
        File old = clip(1), protectedClip = clip(2), next = clip(3);
        File normal = temp.newFile("261108123456789.mp4");
        FakeIo io = new FakeIo(0, REQUIRED_FREE_BYTES / 2 + 1);
        ensureSpace("recycle", temp.getRoot(), Collections.singleton(base(protectedClip)), () -> false, io);
        assertEquals(java.util.Arrays.asList(old, next), io.deleted);
        assertTrue(protectedClip.exists());
        assertTrue(normal.exists());
    }

    @Test public void staleFreeSpaceStopsAfterOneDeletion() throws Exception {
        File first = clip(1), second = clip(2);
        FakeIo io = new FakeIo(0, 0);
        IOException error = assertThrows(IOException.class,
                () -> ensureSpace("recycle", temp.getRoot(), Collections.emptySet(), () -> false, io));
        assertEquals("storage_reclaim_unconfirmed", error.getMessage());
        assertEquals(Collections.singletonList(first), io.deleted);
        assertTrue(second.exists());
    }

    @Test public void failedDeletionStopsWithoutTryingOtherFiles() throws Exception {
        clip(1); File second = clip(2);
        FakeIo io = new FakeIo(0, REQUIRED_FREE_BYTES);
        io.failDelete = true;
        assertThrows(IOException.class, () -> ensureSpace("recycle", temp.getRoot(), Collections.emptySet(), () -> false, io));
        assertEquals(1, io.deleted.size());
        assertTrue(second.exists());
    }

    @Test public void stopPolicyPreservesAllFiles() throws Exception {
        File file = clip(1);
        FakeIo io = new FakeIo(REQUIRED_FREE_BYTES - 1, REQUIRED_FREE_BYTES);
        assertThrows(IOException.class, () -> ensureSpace("stop", temp.getRoot(), Collections.emptySet(), () -> false, io));
        assertTrue(file.exists());
        assertTrue(io.deleted.isEmpty());
    }

    @Test public void warnPolicyAllowsUnknownOrLowSpaceWithoutDeletion() throws Exception {
        clip(1);
        FakeIo io = new FakeIo(-1, 0);
        ensureSpace("warn", temp.getRoot(), Collections.emptySet(), () -> false, io);
        assertTrue(io.deleted.isEmpty());
    }

    @Test public void unknownFreeSpaceDoesNotDeleteAnything() throws Exception {
        clip(1);
        FakeIo io = new FakeIo(-1, REQUIRED_FREE_BYTES);
        assertThrows(IOException.class, () -> ensureSpace("recycle", temp.getRoot(), Collections.emptySet(), () -> false, io));
        assertTrue(io.deleted.isEmpty());
    }

    @Test public void insufficientOwnedFootageStopsWithoutDeletingForeignFiles() throws Exception {
        File normal = temp.newFile("261108123456789.mp4");
        FakeIo io = new FakeIo(0, REQUIRED_FREE_BYTES);
        assertThrows(IOException.class, () -> ensureSpace("recycle", temp.getRoot(), Collections.emptySet(), () -> false, io));
        assertTrue(normal.exists());
        assertTrue(io.deleted.isEmpty());
    }

    @Test public void sufficientSpaceAndCancellationDoNotDelete() throws Exception {
        clip(1);
        FakeIo io = new FakeIo(REQUIRED_FREE_BYTES, 0);
        ensureSpace("recycle", temp.getRoot(), Collections.emptySet(), () -> false, io);
        io.free = 0;
        ensureSpace("recycle", temp.getRoot(), Collections.emptySet(), () -> true, io);
        assertTrue(io.deleted.isEmpty());
    }

    private File clip(int ordinal) throws Exception {
        return temp.newFile(String.format(java.util.Locale.ROOT, "surv_20261108123456%03d_aaaaaaaa.mp4", ordinal));
    }
    private String base(File file) { return file.getName().replace(".mp4", ""); }
    private static final class FakeIo implements SpaceIo {
        long free, reclaimed;
        boolean failDelete;
        final List<File> deleted = new ArrayList<>();
        FakeIo(long free, long reclaimed) { this.free = free; this.reclaimed = reclaimed; }
        public long available(File directory) { return free; }
        public boolean deleteAndSync(File file) {
            deleted.add(file);
            if (failDelete) return false;
            if (!file.delete()) return false;
            free += reclaimed;
            return true;
        }
    }
}
