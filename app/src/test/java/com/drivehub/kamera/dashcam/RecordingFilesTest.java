package com.drivehub.kamera.dashcam;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

public class RecordingFilesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void ringOnlyOwnsTimestampClips() {
        assertTrue(RecordingFiles.isLoopClip("261027153010.mp4"));
        assertTrue(RecordingFiles.isLoopClip("261027153010_F.mp4"));
        assertFalse(RecordingFiles.isLoopClip("holiday.mp4"));
        assertFalse(RecordingFiles.isLoopClip("event_261027153010.mp4"));
        assertFalse(RecordingFiles.isLoopClip("261027153010.mp4.tmp"));
    }
    @Test public void retentionProtectsEventsAndIgnoresForeignFiles() throws Exception {
        File old = temp.newFile("261027100000.mp4");
        File protectedClip = temp.newFile("261027110000.mp4");
        File newest = temp.newFile("261027120000.mp4");
        File foreign = temp.newFile("holiday.mp4");
        java.util.List<File> candidates = RecordingFiles.deletionCandidates(
                new File[]{newest, foreign, protectedClip, old}, 1,
                java.util.Collections.singleton("261027110000"));
        assertEquals(java.util.Arrays.asList(old, newest), candidates);
        assertTrue(RecordingFiles.deletionCandidates(null, 1, java.util.Collections.emptySet()).isEmpty());
        assertTrue(protectedClip.exists());
    }
    @Test public void failedTruncateDoesNotUnlink() throws Exception {
        File directory = temp.newFolder("not-a-file");
        assertFalse(RecordingFiles.deleteFreeingSpace(directory));
        assertTrue(directory.isDirectory());
    }
    @Test public void deletesDataAndDoesNotDeleteDirectories() throws Exception {
        File clip = temp.newFile("clip.mp4");
        try (FileOutputStream out = new FileOutputStream(clip)) { out.write(new byte[8192]); }
        assertTrue(RecordingFiles.deleteFreeingSpace(clip));
        assertFalse(clip.exists());
        assertFalse(RecordingFiles.deleteFreeingSpace(clip));
        File directory = temp.newFolder("events");
        assertFalse(RecordingFiles.deleteFreeingSpace(directory));
        assertTrue(directory.exists());
    }
}
