package com.drivehub.kamera.dashcam.review;

import com.drivehub.kamera.dashcam.ReviewFileAccess;
import java.io.IOException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Android-independent categorization and playlist policy. No recursive whole-volume scanning. */
public final class ReviewCatalog {
    public enum Category { EVENTS, TEST, BUFFER, OTHER }
    public static final class Node implements java.io.Serializable {
        public final String location, key, name, mime;
        public final boolean directory;
        public final long modified, created, size;
        public Node(String location, String key, String name, String mime, boolean directory,
                long modified, long created, long size) {
            this.location = location; this.key = key; this.name = name; this.mime = mime;
            this.directory = directory; this.modified = modified; this.created = created; this.size = size;
        }
    }
    public interface Source { List<Node> children(Node directory) throws IOException; }
    public static final class Entry implements java.io.Serializable {
        public final Node node;
        public final List<Node> clips;
        public final boolean event;
        public final long time;
        public Entry(Node node, boolean event, List<Node> clips) {
            this.node = node; this.event = event;
            this.clips = Collections.unmodifiableList(new ArrayList<>(clips));
            time = timestamp(node);
        }
        public boolean playable() { return !clips.isEmpty(); }
        public Node preview() { return playable() ? clips.get(0) : null; }
    }
    private static final Pattern STAMP = Pattern.compile("^(?:event_)?([0-9]{15}|[0-9]{12})(?:_[FRXY])?(?:\\.mp4)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Comparator<Node> CHRONOLOGICAL = Comparator.comparingLong(ReviewCatalog::timestamp)
            .thenComparing(n -> n.name).thenComparing(n -> n.location);
    private static final Comparator<Entry> NEWEST = Comparator.comparingLong((Entry e) -> e.time).reversed()
            .thenComparing(e -> e.node.name).thenComparing(e -> e.node.location);

    public static long timestamp(Node node) {
        Matcher match = STAMP.matcher(node.name);
        if (match.matches()) {
            String value = match.group(1);
            SimpleDateFormat format = new SimpleDateFormat(value.length() == 15 ? "yyMMddHHmmssSSS" : "yyMMddHHmmss", Locale.US);
            format.setLenient(false);
            // Avoid SimpleDateFormat's moving 80-year window for the two-digit recording year.
            java.util.Calendar start = java.util.Calendar.getInstance();
            start.clear(); start.set(2000, 0, 1); format.set2DigitYearStart(start.getTime());
            ParsePosition position = new ParsePosition(0);
            java.util.Date parsed = format.parse(value, position);
            if (parsed != null && position.getIndex() == value.length()) return parsed.getTime();
        }
        return node.created > 0 ? node.created : Math.max(0, node.modified);
    }

    public static boolean video(Node node) {
        String name = node.name.toLowerCase(Locale.ROOT);
        if (node.directory || node.size == 0 || name.endsWith(".tmp") || name.endsWith(".part")
                || ReviewFileAccess.isWriting(node.key)) return false;
        return node.mime.startsWith("video/") || name.matches(".*\\.(mp4|m4v|mov|mkv|avi|3gp|webm|ts)$");
    }

    public static EnumMap<Category, List<Entry>> scan(Source source, Node root) throws IOException {
        EnumMap<Category, List<Entry>> result = new EnumMap<>(Category.class);
        for (Category category : Category.values()) result.put(category, new ArrayList<>());
        for (Node child : source.children(root)) {
            if (ReviewFileAccess.isWriting(child.key)) continue;
            String name = child.name.toLowerCase(Locale.ROOT);
            if (child.directory && (name.equals("events") || name.equals("test") || name.equals("buffer"))) {
                Category category = name.equals("events") ? Category.EVENTS : name.equals("test") ? Category.TEST : Category.BUFFER;
                for (Node item : source.children(child)) {
                    if (ReviewFileAccess.isWriting(item.key)) continue;
                    result.get(category).add(entry(source, item, category == Category.EVENTS && item.directory));
                }
            } else {
                boolean buffer = !child.directory && child.name.matches("[0-9]{12,17}(_[FRXY])?\\.mp4");
                result.get(buffer ? Category.BUFFER : Category.OTHER).add(entry(source, child, false));
            }
        }
        for (List<Entry> entries : result.values()) entries.sort(NEWEST);
        return result;
    }

    public static List<Entry> browse(Source source, Node directory) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (Node child : source.children(directory)) if (!ReviewFileAccess.isWriting(child.key))
            entries.add(entry(source, child, false));
        entries.sort(NEWEST);
        return entries;
    }

    private static Entry entry(Source source, Node node, boolean event) throws IOException {
        List<Node> clips = new ArrayList<>();
        if (event) {
            for (Node child : source.children(node)) if (video(child)) clips.add(child);
            clips.sort(CHRONOLOGICAL);
        } else if (video(node)) clips.add(node);
        return new Entry(node, event, clips);
    }
}
