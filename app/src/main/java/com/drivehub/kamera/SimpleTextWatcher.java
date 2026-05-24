package com.drivehub.kamera;

import android.text.Editable;
import android.text.TextWatcher;

abstract class SimpleTextWatcher implements TextWatcher {
    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public abstract void afterTextChanged(Editable s);
}
