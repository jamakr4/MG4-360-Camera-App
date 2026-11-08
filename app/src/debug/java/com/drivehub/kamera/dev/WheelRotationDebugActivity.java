package com.drivehub.kamera.dev;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.Guideline;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import com.drivehub.kamera.R;
import com.drivehub.kamera.settings.UiPrefs;
import com.drivehub.kamera.ui.AppSystemBars;
import java.io.IOException;
import java.util.Locale;

/** Local optical experiment. Only compiled/registered in Debug; opens no camera devices. */
public final class WheelRotationDebugActivity extends AppCompatActivity {
    private WheelRotationPreview preview;
    private SharedPreferences testPrefs;
    private SeekBar left, right;
    private TextView leftValue, rightValue, explanation;
    private CheckBox linked, pinned, original;
    private final float[][] angles = new float[2][2];
    private final boolean[] linkedAxes = new boolean[2];
    private final Button[] axleButtons = new Button[2];
    private int selectedAxle;
    private ScrollView controls;
    private int accent;
    private boolean syncing;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        testPrefs = getSharedPreferences("wheel_rotation_local_test", MODE_PRIVATE);
        angles[0][0] = initialAngle("front_left_degrees", "front_left");
        angles[0][1] = initialAngle("front_right_degrees", "front_right");
        angles[1][0] = initialAngle("rear_left_degrees", "left");
        angles[1][1] = initialAngle("rear_right_degrees", "right");
        linkedAxes[0] = testPrefs.getBoolean("front_linked", true);
        linkedAxes[1] = testPrefs.getBoolean("linked", true);
        for (int axle = 0; axle < 2; axle++) if (linkedAxes[axle]) angles[axle][1] = -angles[axle][0];
        accent = UiPrefs.getAccentColorInt(UiPrefs.getPrefs(this));
        CameraReferenceFrames frames;
        try { frames = new CameraReferenceFrames(this); }
        catch (IOException missing) {
            TextView error = text("Lokale Referenzbilder fehlen:\n"+missing.getMessage(), 20);
            error.setPadding(dp(24), dp(24), dp(24), dp(24));
            setContentView(error);
            return;
        }
        preview = new WheelRotationPreview(this, frames);
        ConstraintLayout root = new ConstraintLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        Guideline limit = new Guideline(this);
        limit.setId(View.generateViewId());
        ConstraintLayout.LayoutParams guide = new ConstraintLayout.LayoutParams(0, 0);
        guide.orientation = ConstraintLayout.LayoutParams.VERTICAL;
        guide.guidePercent = .4f;
        root.addView(limit, guide);
        PreviewPanel panel = new PreviewPanel();
        panel.setId(View.generateViewId());
        ConstraintLayout.LayoutParams panelBounds = new ConstraintLayout.LayoutParams(0, 0);
        panelBounds.startToStart = panelBounds.topToTop = panelBounds.bottomToBottom = 0;
        panelBounds.endToStart = limit.getId();
        panelBounds.dimensionRatio = "540:640";
        panelBounds.horizontalBias = 0;
        root.addView(panel, panelBounds);

        controls = new ScrollView(this);
        controls.setFillViewport(true);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(24), dp(12), dp(24), dp(20));
        controls.addView(column);
        ConstraintLayout.LayoutParams controlBounds = new ConstraintLayout.LayoutParams(0, 0);
        controlBounds.startToEnd = panel.getId();
        controlBounds.endToEnd = controlBounds.topToTop = controlBounds.bottomToBottom = 0;
        root.addView(controls, controlBounds);
        column.addView(text("Lokaler Rad-Drehungstest", 25));
        column.addView(text("Referenzbilder · kein Livebild\nVorder- und Hinterachse getrennt einstellen.", 15));
        LinearLayout references = new LinearLayout(this);
        String[] names = {"Vorne", "Hinten", "Links", "Rechts"};
        for (int i = 0; i < 4; i++) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(names[i], 13);
            name.setGravity(Gravity.CENTER);
            item.addView(name);
            ImageView image = new ImageView(this);
            image.setImageBitmap(frames.sources[i]);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setContentDescription("Referenzkamera "+names[i]);
            item.addView(image, new LinearLayout.LayoutParams(-1, dp(78)));
            references.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
        column.addView(references);
        LinearLayout axleRow = new LinearLayout(this);
        for (int axle = 0; axle < 2; axle++) {
            final int index = axle;
            Button selector = button("");
            selector.setId(axle == 0 ? R.id.debug_front_axle : R.id.debug_rear_axle);
            selector.setOnClickListener(v -> selectAxle(index));
            LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(0, dp(56), 1);
            if (axle == 0) bounds.rightMargin = dp(8);
            axleRow.addView(selector, bounds);
            axleButtons[axle] = selector;
        }
        column.addView(axleRow);
        left = rotationControl(column, true);
        right = rotationControl(column, false);
        linked = check("Diese Achse gegensinnig koppeln (links + / rechts −)", true);
        linked.setId(R.id.debug_rotation_linked);
        column.addView(linked);
        pinned = check("Auch die Bildpunkte an den Ecken festhalten", testPrefs.getBoolean("pinned", false));
        pinned.setId(R.id.debug_rotation_pinned);
        column.addView(pinned);
        original = check("Mit Original vergleichen (0°)", false);
        original.setId(R.id.debug_rotation_original);
        column.addView(original);
        CheckBox grid = check("Feste Rahmen und Raster einblenden", false);
        column.addView(grid);
        explanation = text("", 14);
        column.addView(explanation);
        Button reset = button("Aktuelle Achse zurücksetzen");
        column.addView(reset, new LinearLayout.LayoutParams(-1, dp(48)));
        reset.setOnClickListener(v -> {
            syncing = true; left.setProgress(90); right.setProgress(90); syncing = false;
            original.setChecked(false); updateAngles();
        });
        linked.setOnCheckedChangeListener((v, checked) -> {
            if (syncing) return;
            linkedAxes[selectedAxle] = checked;
            if (checked) mirror(true);
            updateAngles();
        });
        pinned.setOnCheckedChangeListener((v, checked) -> updateAngles());
        original.setOnCheckedChangeListener((v, checked) -> updateAngles());
        grid.setOnCheckedChangeListener((v, checked) -> { preview.grid = checked; preview.invalidate(); });
        selectAxle(Math.max(0, Math.min(1, testPrefs.getInt("selected_axle", 0))));
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets content = AppSystemBars.contentInsets(insets, UiPrefs.isStatusBarVisible(UiPrefs.getPrefs(this)));
            view.setPadding(content.left, content.top, content.right, content.bottom);
            return insets;
        });
        AppSystemBars.apply(getWindow());
    }

    private float initialAngle(String extra, String key) {
        float angle = getIntent().getFloatExtra(extra, testPrefs.getFloat(key, 0));
        return Math.round(Math.max(-45, Math.min(45, angle))*2)/2f;
    }

    private void selectAxle(int axle) {
        syncing = true;
        selectedAxle = axle;
        left.setProgress(Math.round(angles[axle][0]*2)+90);
        right.setProgress(Math.round(angles[axle][1]*2)+90);
        linked.setChecked(linkedAxes[axle]);
        syncing = false;
        updateAngles();
    }

    private SeekBar rotationControl(LinearLayout column, boolean isLeft) {
        TextView label = text("", 20);
        label.setId(isLeft ? R.id.debug_axle_left_value : R.id.debug_axle_right_value);
        if (isLeft) leftValue = label; else rightValue = label;
        column.addView(label);
        LinearLayout row = new LinearLayout(this);
        SeekBar seek = new SeekBar(this);
        seek.setId(isLeft ? R.id.debug_axle_left_rotation : R.id.debug_axle_right_rotation);
        seek.setMax(180); // Half-degree steps, -45..+45.
        seek.setProgress(90);
        seek.setProgressTintList(ColorStateList.valueOf(accent));
        seek.setThumbTintList(ColorStateList.valueOf(accent));
        Button minus = button("−1°"), plus = button("+1°");
        row.addView(minus, new LinearLayout.LayoutParams(dp(76), dp(48)));
        row.addView(seek, new LinearLayout.LayoutParams(0, dp(48), 1));
        row.addView(plus, new LinearLayout.LayoutParams(dp(76), dp(48)));
        column.addView(row);
        minus.setOnClickListener(v -> seek.setProgress(Math.max(0, seek.getProgress()-2)));
        plus.setOnClickListener(v -> seek.setProgress(Math.min(180, seek.getProgress()+2)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (syncing || left == null || right == null || linked == null) return;
                if (linked.isChecked()) mirror(isLeft);
                updateAngles();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        return seek;
    }

    private void mirror(boolean fromLeft) {
        syncing = true;
        if (fromLeft) right.setProgress(180-left.getProgress());
        else left.setProgress(180-right.getProgress());
        syncing = false;
    }

    private void updateAngles() {
        angles[selectedAxle][0] = (left.getProgress()-90)/2f;
        angles[selectedAxle][1] = (right.getProgress()-90)/2f;
        preview.frontLeftDegrees = angles[0][0];
        preview.frontRightDegrees = angles[0][1];
        preview.rearLeftDegrees = angles[1][0];
        preview.rearRightDegrees = angles[1][1];
        preview.pinCorners = pinned.isChecked();
        preview.showOriginal = original.isChecked();
        String position = selectedAxle == 0 ? "Vorne" : "Hinten";
        leftValue.setText(String.format(Locale.GERMAN, "%s links  %+.1f°", position, angles[selectedAxle][0]));
        rightValue.setText(String.format(Locale.GERMAN, "%s rechts  %+.1f°", position, angles[selectedAxle][1]));
        left.setContentDescription("Drehwinkel "+position.toLowerCase(Locale.GERMAN)+" links");
        right.setContentDescription("Drehwinkel "+position.toLowerCase(Locale.GERMAN)+" rechts");
        for (int axle = 0; axle < 2; axle++) {
            axleButtons[axle].setText(String.format(Locale.GERMAN, "%s\nL %+.1f°  ·  R %+.1f°",
                    axle == 0 ? "Vorderachse" : "Hinterachse", angles[axle][0], angles[axle][1]));
            style(axleButtons[axle], axle == selectedAxle);
        }
        explanation.setText(preview.pinCorners
                ? "Rahmen und Bildpunkte am Rand bleiben fest. Der Inhalt wird innen verformt; kein Zoom."
                : "Feste Kacheln · gleicher Bildmittelpunkt · Zoom 1,00×. Schraffur = außerhalb des Kamerabilds.");
        preview.invalidate();
    }

    @Override protected void onStop() {
        if (preview != null) testPrefs.edit().putFloat("front_left", angles[0][0])
                .putFloat("front_right", angles[0][1]).putFloat("left", angles[1][0])
                .putFloat("right", angles[1][1]).putBoolean("pinned", preview.pinCorners)
                .putBoolean("front_linked", linkedAxes[0]).putBoolean("linked", linkedAxes[1])
                .putInt("selected_axle", selectedAxle).apply();
        super.onStop();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) AppSystemBars.apply(getWindow());
    }

    private TextView text(String value, int size) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextColor(Color.WHITE); text.setTextSize(size);
        text.setPadding(0, dp(4), 0, dp(4));
        return text;
    }

    private CheckBox check(String text, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(text); box.setTextColor(Color.WHITE); box.setTextSize(15);
        box.setButtonTintList(ColorStateList.valueOf(accent)); box.setChecked(checked);
        return box;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label); button.setTextSize(15); button.setAllCaps(false);
        button.setMinWidth(0); button.setMinHeight(0); button.setPadding(dp(4), 0, dp(4), 0);
        style(button, false);
        return button;
    }

    private void style(Button button, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8)); bg.setStroke(dp(1), accent);
        bg.setColor(selected ? accent : Color.TRANSPARENT);
        button.setBackgroundTintList(null); button.setBackground(bg);
        button.setTextColor(selected ? (UiPrefs.isLightColor(accent) ? Color.BLACK : Color.WHITE) : accent);
        button.setSelected(selected);
    }

    private int dp(float value) { return Math.round(value*getResources().getDisplayMetrics().density); }

    private final class PreviewPanel extends ViewGroup {
        private final Button wheels = button("Wheels"), sides = button("Sides");

        PreviewPanel() {
            super(WheelRotationDebugActivity.this);
            add(preview, new int[]{0, 0, 540, 640});
            add(wheels, new int[]{140, 560, 188, 64}); add(sides, new int[]{336, 560, 188, 64});
            style(wheels, true);
            wheels.setOnClickListener(v -> setMode(true)); sides.setOnClickListener(v -> setMode(false));
            addIcon(R.drawable.ic_close, "Debug-Test schließen", new int[]{12, 0, 64, 64}, 8, () -> finish());
            addIcon(R.drawable.ic_settings_material, "Drehregler anzeigen", new int[]{12, 560, 112, 64}, 12,
                    () -> controls.smoothScrollTo(0, 0));
        }

        private void setMode(boolean wheelMode) {
            preview.wheels = wheelMode;
            style(wheels, wheelMode); style(sides, !wheelMode); preview.invalidate();
        }

        private void add(View view, int[] bounds) { view.setTag(bounds); addView(view); }

        private void addIcon(int drawable, String label, int[] bounds, int inset, Runnable action) {
            ImageButton icon = new ImageButton(WheelRotationDebugActivity.this);
            icon.setImageResource(drawable); icon.setColorFilter(Color.WHITE);
            icon.setContentDescription(label); icon.setBackgroundColor(Color.TRANSPARENT);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setTag(R.id.debug_rotation_pinned, inset);
            icon.setOnClickListener(v -> action.run()); add(icon, bounds);
        }

        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
            setMeasuredDimension(w, h);
            float scale = Math.min(w/540f, h/640f);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i); int[] rect = (int[]) child.getTag();
                child.measure(MeasureSpec.makeMeasureSpec(Math.round(rect[2]*scale), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.round(rect[3]*scale), MeasureSpec.EXACTLY));
                if (child instanceof Button) ((Button) child).setTextSize(TypedValue.COMPLEX_UNIT_PX, 26*scale);
                if (child instanceof ImageButton) {
                    int p = Math.round((int) child.getTag(R.id.debug_rotation_pinned)*scale);
                    child.setPadding(p, p, p, p);
                }
            }
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            float scale = Math.min((r-l)/540f, (b-t)/640f);
            int dx = Math.round(((r-l)-540*scale)/2), dy = Math.round(((b-t)-640*scale)/2);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i); int[] rect = (int[]) child.getTag();
                int x = dx+Math.round(rect[0]*scale), y = dy+Math.round(rect[1]*scale);
                child.layout(x, y, x+child.getMeasuredWidth(), y+child.getMeasuredHeight());
            }
        }
    }
}
