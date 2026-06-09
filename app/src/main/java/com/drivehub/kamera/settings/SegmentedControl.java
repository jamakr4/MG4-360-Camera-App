package com.drivehub.kamera.settings;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight single-select segmented control that keeps Android view IDs and child order intact,
 * so existing settings code can treat it similarly to the old Material toggle group.
 */
public final class SegmentedControl extends LinearLayout {

    public interface OnButtonCheckedListener {
        void onButtonChecked(SegmentedControl group, int checkedId, boolean isChecked);
    }

    private final List<OnButtonCheckedListener> listeners = new ArrayList<>();
    private int checkedId = View.NO_ID;

    public SegmentedControl(Context context) {
        super(context);
    }

    public SegmentedControl(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SegmentedControl(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        bindChildren();
    }

    public void check(int id) {
        if (id == View.NO_ID || id == checkedId) {
            return;
        }

        int previousId = checkedId;
        checkedId = id;
        syncChildStates();

        if (previousId != View.NO_ID) {
            dispatchChecked(previousId, false);
        }
        dispatchChecked(checkedId, true);
    }

    public int getCheckedId() {
        return checkedId;
    }

    public void addOnButtonCheckedListener(OnButtonCheckedListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private void bindChildren() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.setOnClickListener(v -> check(v.getId()));
            child.setClickable(true);
            child.setFocusable(true);
        }
        syncChildStates();
    }

    private void syncChildStates() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.setSelected(child.getId() == checkedId);
        }
    }

    private void dispatchChecked(int id, boolean isChecked) {
        for (OnButtonCheckedListener listener : listeners) {
            listener.onButtonChecked(this, id, isChecked);
        }
    }
}
