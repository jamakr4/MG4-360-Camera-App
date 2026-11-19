package com.drivehub.kamera.settings;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Switch;
import com.drivehub.kamera.R;
import com.drivehub.kamera.steering.GuidelinePolicy;
import com.drivehub.kamera.steering.WheelGuides;

/** Parking preferences for both orthographic and orbit guidance. */
final class GuidelineSettings {
    private GuidelineSettings() {}
    static void bind(Dialog dialog,SharedPreferences prefs) {
        Switch limit=dialog.findViewById(R.id.switchGuidelineSpeedLimit);
        EditText speed=dialog.findViewById(R.id.etGuidelineSpeed);
        SegmentedControl style=dialog.findViewById(R.id.segGuidelineStyle);
        limit.setChecked(UiPrefs.isParkingGuideSpeedLimitEnabled(prefs));
        speed.setText(String.valueOf(UiPrefs.getParkingGuideSpeedKmh(prefs)));
        enabled(speed,limit.isChecked());
        limit.setOnCheckedChangeListener((button,checked)->{
            prefs.edit().putBoolean(UiPrefs.KEY_PARKING_GUIDE_SPEED_LIMIT,checked).apply();
            enabled(speed,checked);
        });
        speed.addTextChangedListener(new SimpleTextWatcher(){
            @Override public void afterTextChanged(android.text.Editable text) {
                try {
                    int value=GuidelinePolicy.clampSpeedKmh(Integer.parseInt(text.toString().trim()));
                    prefs.edit().putInt(UiPrefs.KEY_PARKING_GUIDE_SPEED_KMH,value).apply();
                }catch(NumberFormatException ignored){ /* Keep the saved value during incomplete edits. */ }
            }
        });
        speed.setOnFocusChangeListener((view,focused)->{
            if(!focused)speed.setText(String.valueOf(UiPrefs.getParkingGuideSpeedKmh(prefs)));
        });
        speed.setOnEditorActionListener((view,action,event)->{
            if(action!=EditorInfo.IME_ACTION_DONE)return false;
            speed.clearFocus();return false;
        });
        style.check(UiPrefs.getParkingGuideStyle(prefs)==WheelGuides.Style.LINES?R.id.segGuidelineLines:R.id.segGuidelineSimple);
        for(int i=0;i<style.getChildCount();i++) {
            android.widget.TextView child=(android.widget.TextView)style.getChildAt(i);
            child.setContentDescription(dialog.getContext().getString(R.string.guidelines_style)+": "+child.getText());
        }
        style.addOnButtonCheckedListener((group,id,checked)->{
            if(checked)prefs.edit().putString(UiPrefs.KEY_PARKING_GUIDE_STYLE,id==R.id.segGuidelineLines?"lines":"simple").apply();
        });
    }
    private static void enabled(EditText speed,boolean enabled){speed.setEnabled(enabled);speed.setAlpha(enabled?1f:.45f);}
}
