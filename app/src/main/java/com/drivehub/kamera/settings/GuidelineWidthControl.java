package com.drivehub.kamera.settings;

import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import com.drivehub.kamera.R;
import com.drivehub.kamera.steering.GuidelineWidth;
import com.drivehub.kamera.steering.VehicleGeometry;

/** Shared width editor. Its caller decides whether changes are saved or previewed locally. */
public final class GuidelineWidthControl {
    public interface Listener { void changed(GuidelineWidth.Mode mode,int customMm); }
    private GuidelineWidth.Mode mode;
    private int customMm;

    public GuidelineWidthControl(View root,GuidelineWidth.Mode mode,int customMm,Listener listener) {
        this.mode=mode;
        this.customMm=GuidelineWidth.validOrDefault(customMm);
        Spinner picker=root.findViewById(R.id.spinnerGuidelineWidth);
        View customRow=root.findViewById(R.id.rowGuidelineCustomWidth);
        EditText input=root.findViewById(R.id.etGuidelineCustomWidth);
        String[] labels={root.getContext().getString(R.string.guidelines_width_mirrors,GuidelineWidth.DEFAULT_MM),
                root.getContext().getString(R.string.guidelines_width_body,(int)VehicleGeometry.MG4.widthMm),
                root.getContext().getString(R.string.guidelines_width_custom)};
        ArrayAdapter<String> adapter=new ArrayAdapter<>(root.getContext(),R.layout.item_settings_spinner,labels);
        adapter.setDropDownViewResource(R.layout.item_settings_spinner_dropdown);
        picker.setAdapter(adapter);
        picker.setSelection(mode.ordinal());
        customRow.setVisibility(mode==GuidelineWidth.Mode.CUSTOM?View.VISIBLE:View.GONE);
        input.setText(String.valueOf(this.customMm));
        Runnable restoreInput=()->{input.setText(String.valueOf(this.customMm));input.setError(null);};
        picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id) {
                GuidelineWidth.Mode selected=GuidelineWidth.Mode.values()[position];
                if(selected==GuidelineWidthControl.this.mode)return;
                restoreInput.run();
                input.clearFocus();
                GuidelineWidthControl.this.mode=selected;
                customRow.setVisibility(selected==GuidelineWidth.Mode.CUSTOM?View.VISIBLE:View.GONE);
                listener.changed(selected,GuidelineWidthControl.this.customMm);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        input.addTextChangedListener(new SimpleTextWatcher(){
            @Override public void afterTextChanged(android.text.Editable text) {
                Integer value=GuidelineWidth.parseCustomMm(text.toString());
                input.setError(value==null?root.getContext().getString(R.string.guidelines_width_error,
                        GuidelineWidth.MIN_MM,GuidelineWidth.MAX_MM):null);
                if(value!=null&&value!=GuidelineWidthControl.this.customMm) {
                    GuidelineWidthControl.this.customMm=value;
                    listener.changed(GuidelineWidthControl.this.mode,value);
                }
            }
        });
        input.setOnFocusChangeListener((view,focused)->{
            if(!focused)restoreInput.run();
        });
        input.setOnEditorActionListener((view,action,event)->{
            if(action!=EditorInfo.IME_ACTION_DONE)return false;
            restoreInput.run();
            input.clearFocus();return false;
        });
    }
    public GuidelineWidth.Mode mode(){return mode;}
    public int customMm(){return customMm;}
    public int widthMm(){return GuidelineWidth.resolveMm(mode,customMm);}
}
