package com.drivehub.kamera.dashcam.review;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.drivehub.kamera.R;
import com.drivehub.kamera.dashcam.StorageIoGate;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.settings.UiPrefs;
import com.drivehub.kamera.ui.AppSystemBars;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** A read-only recording browser. Opening this Activity never starts/stops the recorder. */
public final class DashcamReviewActivity extends AppCompatActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor(task -> new Thread(task, "ReviewCatalog"));
    private final List<ReviewCatalog.Entry> entries = new ArrayList<>();
    private final ArrayList<ReviewCatalog.Node> navigation = new ArrayList<>();
    private ReviewCatalog.Category category = ReviewCatalog.Category.EVENTS;
    private ReviewCatalog.Node root;
    private String pendingTree;
    private ReviewRepository repository;
    private ReviewThumbnails thumbnails;
    private ReviewPlayer player;
    private Future<?> loading;
    private int loadVersion, accent;
    private boolean foreground, destroyed, fullscreen, seeking, storageClosed;
    private View header, tabs, listPanel, empty, content, spinner;
    private TextView path, breadcrumb, message, title, elapsed, duration;
    private TextureView video;
    private SeekBar seek;
    private ImageButton play;
    private EntryAdapter adapter;
    private TextView[] tabButtons;
    private final Runnable storageClosing = () -> {
        repository.cancelPending();
        thumbnails.cancel();
        player.clear();
        main.post(() -> {
            if (destroyed) return;
            storageClosed = true;
            cancelLoad();
            showEmpty(R.string.review_storage_closed);
        });
    };
    private final ActivityResultLauncher<Intent> folderPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null
                        || result.getData().getData() == null) return;
                pendingTree = result.getData().getData().toString();
                root = null; navigation.clear();
                player.clear();
                reload();
            });

    @Override @SuppressWarnings({"deprecation", "unchecked"}) protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_dashcam_review);
        AppSystemBars.apply(getWindow());
        repository = new ReviewRepository(this);
        thumbnails = new ReviewThumbnails(repository);
        player = new ReviewPlayer(this, repository, this::renderPlayer);
        accent = UiPrefs.getAccentColorInt(UiPrefs.getPrefs(this));
        header = findViewById(R.id.reviewHeader); tabs = findViewById(R.id.reviewTabs);
        listPanel = findViewById(R.id.reviewListPanel); empty = findViewById(R.id.reviewEmpty);
        content = findViewById(R.id.reviewContent); spinner = findViewById(R.id.reviewLoading);
        path = findViewById(R.id.reviewPath); breadcrumb = findViewById(R.id.reviewBreadcrumb);
        message = findViewById(R.id.reviewPlayerMessage); title = findViewById(R.id.reviewPlayingTitle);
        elapsed = findViewById(R.id.reviewElapsed); duration = findViewById(R.id.reviewDuration);
        video = findViewById(R.id.reviewVideo); seek = findViewById(R.id.reviewSeek);
        play = findViewById(R.id.reviewPlay);
        seek.setProgressTintList(ColorStateList.valueOf(accent));
        seek.setThumbTintList(ColorStateList.valueOf(accent));
        View rootView = findViewById(R.id.reviewRoot);
        int padding = rootView.getPaddingLeft();
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom);
            return insets;
        });
        tabButtons = new TextView[]{findViewById(R.id.reviewEvents), findViewById(R.id.reviewTest),
                findViewById(R.id.reviewBuffer), findViewById(R.id.reviewOther)};
        for (int i = 0; i < tabButtons.length; i++) {
            final ReviewCatalog.Category selected = ReviewCatalog.Category.values()[i];
            tabButtons[i].setOnClickListener(v -> {
                category = selected; navigation.clear(); player.clear(); reload();
            });
        }
        ListView list = findViewById(R.id.reviewList);
        adapter = new EntryAdapter(); list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.reviewListEmpty));
        list.setOnItemClickListener((parent, view, position, id) -> {
            ReviewCatalog.Entry entry = entries.get(position);
            if (entry.node.directory && !entry.event) { navigation.add(entry.node); player.clear(); reload(); }
            else if (entry.playable()) player.select(entry);
        });
        findViewById(R.id.reviewBack).setOnClickListener(v -> back());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        });
        findViewById(R.id.reviewChooseFolder).setOnClickListener(v -> chooseFolder());
        findViewById(R.id.reviewEmptyChoose).setOnClickListener(v -> chooseFolder());
        findViewById(R.id.reviewRecordingFolder).setOnClickListener(v -> {
            pendingTree = null; root = null; navigation.clear(); player.clear(); reload();
        });
        findViewById(R.id.reviewRefresh).setOnClickListener(v -> reload());
        findViewById(R.id.reviewRetry).setOnClickListener(v -> reload());
        play.setOnClickListener(v -> player.toggle());
        findViewById(R.id.reviewPrevious).setOnClickListener(v -> player.step(-1));
        findViewById(R.id.reviewNext).setOnClickListener(v -> player.step(1));
        findViewById(R.id.reviewRewind).setOnClickListener(v -> player.seek(player.state().position - 10_000));
        findViewById(R.id.reviewForward).setOnClickListener(v -> player.seek(player.state().position + 10_000));
        findViewById(R.id.reviewFullscreen).setOnClickListener(v -> setFullscreen(!fullscreen));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user) elapsed.setText(clock(value));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) { seeking = false; player.seek(bar.getProgress()); }
        });
        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                player.surface(new Surface(texture)); transformVideo(player.state());
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { transformVideo(player.state()); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) { player.surface(null); return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
        });
        if (saved != null) {
            root = (ReviewCatalog.Node) saved.getSerializable("root");
            pendingTree = saved.getString("pendingTree");
            category = ReviewCatalog.Category.values()[Math.max(0, Math.min(3, saved.getInt("category")))];
            ArrayList<ReviewCatalog.Node> trail = (ArrayList<ReviewCatalog.Node>) saved.getSerializable("navigation");
            if (trail != null) navigation.addAll(trail);
            ReviewCatalog.Entry selection = (ReviewCatalog.Entry) saved.getSerializable("selection");
            if (selection != null && selection.playable()) player.restore(selection, saved.getInt("clip"), saved.getInt("position"));
            fullscreen = saved.getBoolean("fullscreen");
        }
        renderPlayer(player.state());
    }

    @Override protected void onStart() {
        super.onStart();
        storageClosed = !StorageIoGate.addCloseListener(storageClosing);
        if (storageClosed) storageClosing.run();
    }
    @Override protected void onResume() {
        super.onResume(); foreground = true;
        AppSystemBars.apply(getWindow()); player.resume(); reload();
    }
    @Override protected void onPause() {
        foreground = false; cancelLoad(); thumbnails.cancel(); player.suspend();
        super.onPause();
    }
    @Override protected void onStop() {
        StorageIoGate.removeCloseListener(storageClosing);
        super.onStop();
    }
    @Override protected void onDestroy() {
        destroyed = true; video.setSurfaceTextureListener(null);
        cancelLoad(); thumbnails.close(); player.close(); files.shutdownNow();
        super.onDestroy();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused); if (focused) AppSystemBars.apply(getWindow());
    }
    @Override protected void onSaveInstanceState(Bundle saved) {
        saved.putSerializable("root", root); saved.putString("pendingTree", pendingTree);
        saved.putInt("category", category.ordinal()); saved.putSerializable("navigation", navigation);
        ReviewPlayer.State state = player.state();
        saved.putSerializable("selection", state.selection); saved.putInt("clip", state.index);
        saved.putInt("position", state.position); saved.putBoolean("fullscreen", fullscreen);
        super.onSaveInstanceState(saved);
    }

    private void chooseFolder() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            folderPicker.launch(intent);
        } catch (RuntimeException error) { Toast.makeText(this, R.string.review_picker_unavailable, Toast.LENGTH_LONG).show(); }
    }
    private void back() {
        if (fullscreen) setFullscreen(false);
        else if (!navigation.isEmpty()) { navigation.remove(navigation.size() - 1); player.clear(); reload(); }
        else finish();
    }
    private void cancelLoad() {
        loadVersion++; repository.cancelPending();
        if (loading != null) { loading.cancel(true); loading = null; }
    }
    private void reload() {
        if (!foreground || destroyed) return;
        cancelLoad(); thumbnails.cancel();
        int token = loadVersion;
        ReviewCatalog.Node previousRoot = root;
        String tree = pendingTree;
        ArrayList<ReviewCatalog.Node> trail = new ArrayList<>(navigation);
        ReviewCatalog.Category selected = category;
        spinner.setVisibility(View.VISIBLE);
        findViewById(R.id.reviewRefresh).setEnabled(false);
        loading = files.submit(() -> {
            try {
                ReviewCatalog.Node resolved = tree != null ? repository.treeRoot(Uri.parse(tree))
                        : previousRoot != null ? previousRoot : repository.recordingRoot();
                EnumMap<ReviewCatalog.Category, List<ReviewCatalog.Entry>> catalog = ReviewCatalog.scan(repository, resolved);
                boolean any = false;
                for (List<ReviewCatalog.Entry> list : catalog.values()) if (!list.isEmpty()) any = true;
                List<ReviewCatalog.Entry> visible = trail.isEmpty() ? catalog.get(selected)
                        : ReviewCatalog.browse(repository, trail.get(trail.size() - 1));
                final boolean found = any;
                main.post(() -> {
                    if (!foreground || destroyed || token != loadVersion) return;
                    root = resolved; pendingTree = null;
                    spinner.setVisibility(View.GONE); findViewById(R.id.reviewRefresh).setEnabled(true);
                    path.setText(folderLabel(resolved));
                    if (!found) { showEmpty(R.string.review_no_recordings); return; }
                    empty.setVisibility(View.GONE); content.setVisibility(View.VISIBLE);
                    tabs.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
                    entries.clear(); entries.addAll(visible); adapter.notifyDataSetChanged();
                    breadcrumb.setText(trail.isEmpty() ? tabButtons[selected.ordinal()].getText()
                            : "… / " + trail.get(trail.size() - 1).name);
                    for (int i = 0; i < tabButtons.length; i++) {
                        boolean active = i == selected.ordinal();
                        tabButtons[i].setTextColor(active ? accent : getColor(R.color.settings_text_muted));
                        tabButtons[i].setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                        tabButtons[i].setSelected(active);
                        ViewCompat.setStateDescription(tabButtons[i], getString(active
                                ? R.string.side_camera_selected : R.string.side_camera_not_selected));
                    }
                    setFullscreen(fullscreen);
                });
            } catch (Exception error) {
                DevRuntimeLog.add("DashcamReview", "Folder read failed: " + error + "; cause=" + error.getCause());
                main.post(() -> {
                    if (!foreground || destroyed || token != loadVersion) return;
                    showEmpty(storageClosed ? R.string.review_storage_closed : R.string.review_folder_error);
                });
            }
        });
    }
    private void showEmpty(int text) {
        setFullscreen(false);
        ((TextView) findViewById(R.id.reviewEmptyMessage)).setText(text);
        content.setVisibility(View.GONE); tabs.setVisibility(View.GONE); empty.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.GONE); findViewById(R.id.reviewRefresh).setEnabled(true);
    }
    private String folderLabel(ReviewCatalog.Node node) {
        Uri uri = Uri.parse(node.location);
        if ("file".equals(uri.getScheme())) return uri.getPath();
        if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
            String id = android.provider.DocumentsContract.getDocumentId(uri);
            int separator = id.indexOf(':');
            if (separator > 0) return (id.startsWith("primary:") ? getString(R.string.review_internal_storage)
                    : id.substring(0, separator)) + " / " + id.substring(separator + 1);
        }
        return node.name;
    }
    private void setFullscreen(boolean value) {
        fullscreen = value;
        header.setVisibility(value ? View.GONE : View.VISIBLE);
        tabs.setVisibility(value || empty.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        listPanel.setVisibility(value ? View.GONE : View.VISIBLE);
        findViewById(R.id.reviewFullscreen).setContentDescription(getString(value
                ? R.string.review_exit_fullscreen : R.string.review_fullscreen));
        video.post(() -> transformVideo(player.state()));
    }
    private void renderPlayer(ReviewPlayer.State state) {
        if (destroyed) return;
        boolean selected = state.selection != null;
        play.setEnabled(selected);
        play.setImageResource(state.playing || state.preparing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        play.setContentDescription(getString(state.playing || state.preparing ? R.string.review_pause : R.string.review_play));
        findViewById(R.id.reviewPrevious).setEnabled(selected && state.index > 0);
        findViewById(R.id.reviewNext).setEnabled(selected && state.index + 1 < state.selection.clips.size());
        findViewById(R.id.reviewRewind).setEnabled(selected && state.duration > 0);
        findViewById(R.id.reviewForward).setEnabled(selected && state.duration > 0);
        findViewById(R.id.reviewFullscreen).setEnabled(selected);
        for (int id : new int[]{R.id.reviewPlay, R.id.reviewPrevious, R.id.reviewNext,
                R.id.reviewRewind, R.id.reviewForward, R.id.reviewFullscreen}) {
            View control = findViewById(id); control.setAlpha(control.isEnabled() ? 1f : 0.35f);
        }
        seek.setEnabled(selected && state.duration > 0);
        if (!seeking) { seek.setMax(Math.max(1, state.duration)); seek.setProgress(state.position); elapsed.setText(clock(state.position)); }
        duration.setText(clock(state.duration));
        if (selected) title.setText(state.selection.event ? getString(R.string.review_clip_position,
                state.selection.node.name, state.index + 1, state.selection.clips.size())
                : state.selection.node.name);
        else title.setText("");
        message.setVisibility(!selected || state.error ? View.VISIBLE : View.GONE);
        message.setText(state.error ? R.string.review_playback_error : R.string.review_select_clip);
        findViewById(R.id.reviewPlayerLoading).setVisibility(state.preparing ? View.VISIBLE : View.GONE);
        video.setKeepScreenOn(state.playing);
        transformVideo(state);
        if (adapter != null && !sameSelection(adapter.selectedKey, state)) {
            adapter.selectedKey = selected ? state.selection.node.key : null; adapter.notifyDataSetChanged();
        }
    }
    private static boolean sameSelection(String key, ReviewPlayer.State state) {
        String next = state.selection == null ? null : state.selection.node.key;
        return java.util.Objects.equals(key, next);
    }
    private void transformVideo(ReviewPlayer.State state) {
        if (state.width <= 0 || state.height <= 0 || video.getWidth() == 0 || video.getHeight() == 0) return;
        float viewAspect = (float) video.getWidth() / video.getHeight();
        float videoAspect = (float) state.width / state.height;
        Matrix matrix = new Matrix();
        matrix.setScale(Math.min(1, videoAspect / viewAspect), Math.min(1, viewAspect / videoAspect),
                video.getWidth() / 2f, video.getHeight() / 2f);
        video.setTransform(matrix);
    }
    private static String clock(int milliseconds) {
        int seconds = Math.max(0, milliseconds / 1000);
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private final class EntryAdapter extends BaseAdapter {
        String selectedKey;
        @Override public int getCount() { return entries.size(); }
        @Override public ReviewCatalog.Entry getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) {
            ReviewCatalog.Entry entry = getItem(position);
            return entry.playable() || entry.node.directory && !entry.event;
        }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            View row = recycled == null ? LayoutInflater.from(DashcamReviewActivity.this).inflate(R.layout.item_review, parent, false) : recycled;
            ReviewCatalog.Entry entry = getItem(position);
            ((TextView) row.findViewById(R.id.reviewItemDate)).setText(entry.time > 0
                    ? android.text.format.DateFormat.getMediumDateFormat(DashcamReviewActivity.this).format(new java.util.Date(entry.time))
                        + "  " + android.text.format.DateFormat.getTimeFormat(DashcamReviewActivity.this).format(new java.util.Date(entry.time))
                    : getString(R.string.review_date_unknown));
            ((TextView) row.findViewById(R.id.reviewItemName)).setText(entry.node.name);
            TextView detail = row.findViewById(R.id.reviewItemDetail);
            detail.setText(entry.event ? getString(R.string.review_clip_count, entry.clips.size())
                    : getString(entry.node.directory ? R.string.review_folder : entry.playable() ? R.string.review_video : R.string.review_file_only));
            ImageView image = row.findViewById(R.id.reviewItemImage);
            image.setImageResource(entry.node.directory ? android.R.drawable.ic_menu_agenda
                    : entry.playable() ? android.R.drawable.ic_media_play : android.R.drawable.ic_menu_info_details);
            image.setTag(entry.node.key);
            ReviewCatalog.Node preview = entry.preview();
            if (preview != null) thumbnails.load(preview, bitmap -> {
                if (entry.node.key.equals(image.getTag())) image.setImageBitmap(bitmap);
            });
            row.setAlpha(isEnabled(position) ? 1 : 0.55f);
            row.setBackgroundColor(entry.node.key.equals(selectedKey) ? (accent & 0x00FFFFFF) | 0x30000000 : 0x00000000);
            return row;
        }
    }
}
