package com.drivehub.kamera.dashcam.review;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import com.drivehub.kamera.dashcam.DashcamStorageManager;
import com.drivehub.kamera.dashcam.ReviewFileAccess;
import com.drivehub.kamera.dashcam.StorageIoGate;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reads native recording roots and SAF documents without changing recording preferences. */
public final class ReviewRepository implements ReviewCatalog.Source {
    private final Context context;
    private volatile CancellationSignal query;
    public ReviewRepository(Context context) { this.context = context.getApplicationContext(); }

    public ReviewCatalog.Node recordingRoot() throws IOException {
        if (!StorageIoGate.begin()) throw new IOException("storage_closed");
        try {
            File file = DashcamStorageManager.readableRecordsDirectory(context);
            if (file == null || !file.isDirectory() || !file.canRead()) throw new IOException("root_unavailable");
            return fileNode(file);
        } finally { StorageIoGate.end(); }
    }

    public ReviewCatalog.Node treeRoot(Uri tree) throws IOException {
        Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        List<ReviewCatalog.Node> nodes = documents(document);
        if (nodes.isEmpty() || !nodes.get(0).directory) throw new IOException("root_unavailable");
        return nodes.get(0);
    }

    @Override public List<ReviewCatalog.Node> children(ReviewCatalog.Node directory) throws IOException {
        if (!directory.directory) throw new IOException("not_directory");
        Uri uri = Uri.parse(directory.location);
        if ("content".equals(uri.getScheme())) return documents(DocumentsContract.buildChildDocumentsUriUsingTree(
                uri, DocumentsContract.getDocumentId(uri)));
        if (!StorageIoGate.begin()) throw new IOException("storage_closed");
        try {
            File[] children = new File(uri.getPath()).listFiles();
            if (children == null) throw new IOException("directory_unavailable");
            List<ReviewCatalog.Node> nodes = new ArrayList<>();
            for (File child : children) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
                // Do not follow directory links outside the chosen tree or into cycles.
                if (!Files.isSymbolicLink(child.toPath())) nodes.add(fileNode(child));
            }
            return nodes;
        } finally { StorageIoGate.end(); }
    }

    private static ReviewCatalog.Node fileNode(File file) {
        long created = 0;
        try { created = Files.readAttributes(file.toPath(), BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).creationTime().toMillis(); }
        catch (IOException | RuntimeException ignored) { }
        return new ReviewCatalog.Node(Uri.fromFile(file).toString(), ReviewFileAccess.key(file), file.getName(),
                "", file.isDirectory(), file.lastModified(), created, file.length());
    }

    private List<ReviewCatalog.Node> documents(Uri uri) throws IOException {
        if (!StorageIoGate.begin()) throw new IOException("storage_closed");
        CancellationSignal signal = new CancellationSignal();
        query = signal;
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE};
        try (Cursor cursor = context.getContentResolver().query(uri, columns, null, null, null, signal)) {
            if (cursor == null) throw new IOException("provider_unavailable");
            List<ReviewCatalog.Node> nodes = new ArrayList<>();
            while (cursor.moveToNext()) {
                signal.throwIfCanceled();
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                Uri document = DocumentsContract.buildDocumentUriUsingTree(uri, id);
                String key = document.toString();
                if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
                    int colon = id.indexOf(':');
                    if (colon > 0) key = "volume:" + id.substring(0, colon).toLowerCase(Locale.ROOT)
                            + (colon + 1 < id.length() ? "/" + id.substring(colon + 1) : "");
                }
                nodes.add(new ReviewCatalog.Node(document.toString(), key, name == null ? id : name,
                        mime == null ? "" : mime, DocumentsContract.Document.MIME_TYPE_DIR.equals(mime),
                        cursor.isNull(3) ? 0 : cursor.getLong(3), 0,
                        cursor.isNull(4) ? -1 : cursor.getLong(4)));
            }
            return nodes;
        } catch (RuntimeException error) { throw new IOException("provider_unavailable", error); }
        finally { if (query == signal) query = null; StorageIoGate.end(); }
    }

    public ParcelFileDescriptor open(ReviewCatalog.Node node) throws IOException {
        Uri uri = Uri.parse(node.location);
        if ("file".equals(uri.getScheme())) return ParcelFileDescriptor.open(new File(uri.getPath()),
                ParcelFileDescriptor.MODE_READ_ONLY);
        ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r");
        if (fd == null) throw new IOException("file_unavailable");
        return fd;
    }

    public void cancelPending() { CancellationSignal signal = query; if (signal != null) signal.cancel(); }
}
