package com.drivehub.kamera.dashcam.review;

import com.drivehub.kamera.dashcam.ReviewFileAccess;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReviewCatalogTest {
    private final Map<String, List<ReviewCatalog.Node>> tree = new HashMap<>();
    private ReviewCatalog.Node node(String path, boolean directory, long modified) {
        return new ReviewCatalog.Node(path, path, new File(path).getName(), "", directory, modified, 0, directory ? 0 : 1024);
    }
    private ReviewCatalog.Node dir(String path, ReviewCatalog.Node... children) {
        ReviewCatalog.Node node = node(path, true, 1);
        tree.put(path, Arrays.asList(children)); return node;
    }
    private final ReviewCatalog.Source source = node -> tree.getOrDefault(node.location, Collections.emptyList());

    @Test public void recognizesLegacyAndDedicatedBufferWithoutMisclassifyingForeignContent() throws Exception {
        ReviewCatalog.Node root = dir("/dashcam", node("/dashcam/261120140000000.mp4", false, 1),
                dir("/dashcam/buffer", node("/dashcam/buffer/261120150000000.mp4", false, 1)),
                dir("/dashcam/test", node("/dashcam/test/261120120000000.mp4", false, 1)),
                dir("/dashcam/events", dir("/dashcam/events/event_261120130000000",
                        node("/dashcam/events/event_261120130000000/261120130000000.mp4", false, 1))),
                node("/dashcam/holiday.mp4", false, 1), node("/dashcam/readme.txt", false, 1),
                dir("/dashcam/archive"));
        Map<ReviewCatalog.Category, List<ReviewCatalog.Entry>> result = ReviewCatalog.scan(source, root);
        assertEquals(2, result.get(ReviewCatalog.Category.BUFFER).size());
        assertEquals("261120150000000.mp4", result.get(ReviewCatalog.Category.BUFFER).get(0).node.name);
        assertEquals(1, result.get(ReviewCatalog.Category.TEST).size());
        assertEquals(1, result.get(ReviewCatalog.Category.EVENTS).size());
        assertEquals(3, result.get(ReviewCatalog.Category.OTHER).size());
        assertFalse(result.get(ReviewCatalog.Category.OTHER).stream()
                .filter(entry -> entry.node.name.equals("readme.txt")).findFirst().get().playable());
    }

    @Test public void newestEventsFirstButSelectedEventClipsRunChronologicallyAndExcludePartialFiles() throws Exception {
        ReviewCatalog.Node old = dir("/events/event_261119120000000", node("/events/261119120000000.mp4", false, 900));
        ReviewCatalog.Node newer = dir("/events/event_261120120000000",
                node("/events/261120120100000.mp4", false, 1), node("/events/261120120000000.mp4", false, 999),
                node("/events/261120120200000.mp4.tmp", false, 1000), node("/events/261120120300000.mp4.part", false, 1000));
        ReviewCatalog.Node root = dir("/dashcam", dir("/dashcam/events", old, newer));
        List<ReviewCatalog.Entry> events = ReviewCatalog.scan(source, root).get(ReviewCatalog.Category.EVENTS);
        assertEquals(newer.name, events.get(0).node.name);
        assertEquals(2, events.get(0).clips.size());
        assertEquals("261120120000000.mp4", events.get(0).clips.get(0).name);
        assertEquals("261120120100000.mp4", events.get(0).clips.get(1).name);
    }

    @Test public void captureTimeWinsAndInvalidTimestampFallsBackToCreationThenModification() {
        ReviewCatalog.Node capture = node("261120120000000.mp4", false, Long.MAX_VALUE);
        assertTrue(ReviewCatalog.timestamp(capture) < Long.MAX_VALUE);
        assertEquals(37, ReviewCatalog.timestamp(node("261399120000000.mp4", false, 37)));
        assertEquals(31, ReviewCatalog.timestamp(new ReviewCatalog.Node("x", "x", "holiday.mp4", "", false, 37, 31, 1)));
        assertEquals(0, ReviewCatalog.timestamp(node("unknown", false, 0)));
    }

    @Test public void activeMp4IsHiddenUntilRecorderFinalizesIt() throws Exception {
        File active = new File("/dashcam/261120120000000.mp4");
        ReviewCatalog.Node clip = new ReviewCatalog.Node(active.toString(), ReviewFileAccess.key(active),
                active.getName(), "", false, 1, 0, 1024);
        ReviewCatalog.Node root = dir("/dashcam", clip);
        ReviewFileAccess.beginWrite(active);
        try { assertTrue(ReviewCatalog.scan(source, root).get(ReviewCatalog.Category.BUFFER).isEmpty()); }
        finally { ReviewFileAccess.endWrite(active); }
        assertEquals(1, ReviewCatalog.scan(source, root).get(ReviewCatalog.Category.BUFFER).size());
    }

    @Test public void emptyRootAndOtherSubfolderAreBrowsableWithoutCreatingEntries() throws Exception {
        ReviewCatalog.Node root = dir("/empty");
        assertTrue(ReviewCatalog.scan(source, root).values().stream().allMatch(List::isEmpty));
        ReviewCatalog.Node nested = dir("/archive", node("/archive/video.mov", false, 2), node("/archive/notes.txt", false, 1));
        List<ReviewCatalog.Entry> entries = ReviewCatalog.browse(source, nested);
        assertTrue(entries.get(0).playable());
        assertFalse(entries.get(1).playable());
    }
}
