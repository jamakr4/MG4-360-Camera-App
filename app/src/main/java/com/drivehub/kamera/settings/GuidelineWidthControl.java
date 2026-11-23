package com.drivehub.kamera.settings;

import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
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
        SegmentedControl picker=root.findViewById(R.id.segGuidelineWidth);
        View customRow=root.findViewById(R.id.rowGuidelineCustomWidth);
        EditText input=root.findViewById(R.id.etGuidelineCustomWidth);
        TextView widthValue=root.findViewById(R.id.textGuidelineWidthValue);
        String[] labels={root.getContext().getString(R.string.guidelines_width_mirrors,GuidelineWidth.DEFAULT_MM),
                root.getContext().getString(R.string.guidelines_width_body,(int)VehicleGeometry.MG4.widthMm),
                root.getContext().getString(R.string.guidelines_width_custom)};
        int[] choices={R.id.segGuidelineWidthMirrors,R.id.segGuidelineWidthBody,R.id.segGuidelineWidthCustom};
        picker.check(choices[mode.ordinal()]);
        Runnable updateValue=()->{
            boolean custom=GuidelineWidthControl.this.mode==GuidelineWidth.Mode.CUSTOM;
            widthValue.setVisibility(custom?View.GONE:View.VISIBLE);
            widthValue.setText(custom?"":labels[GuidelineWidthControl.this.mode.ordinal()]);
            for(int i=0;i<choices.length;i++)picker.findViewById(choices[i]).setContentDescription(
                    root.getContext().getString(R.string.guidelines_width)+": "+(i==2
                            ?root.getContext().getString(R.string.guidelines_width_custom_value,GuidelineWidthControl.this.customMm):labels[i]));
        };
        updateValue.run();
        customRow.setVisibility(mode==GuidelineWidth.Mode.CUSTOM?View.VISIBLE:View.GONE);
        input.setText(String.valueOf(this.customMm));
        Runnable restoreInput=()->{input.setText(String.valueOf(this.customMm));input.setError(null);};
        picker.addOnButtonCheckedListener((group,id,checked)->{
            if(!checked)return;
            GuidelineWidth.Mode selected=id==R.id.segGuidelineWidthMirrors?GuidelineWidth.Mode.MIRRORS:
                    id==R.id.segGuidelineWidthBody?GuidelineWidth.Mode.BODY:GuidelineWidth.Mode.CUSTOM;
            if(selected==GuidelineWidthControl.this.mode)return;
            restoreInput.run();
            input.clearFocus();
            GuidelineWidthControl.this.mode=selected;
            customRow.setVisibility(selected==GuidelineWidth.Mode.CUSTOM?View.VISIBLE:View.GONE);
            updateValue.run();
            listener.changed(selected,GuidelineWidthControl.this.customMm);
        });
        input.addTextChangedListener(new SimpleTextWatcher(){
            @Override public void afterTextChanged(android.text.Editable text) {
                Integer value=GuidelineWidth.parseCustomMm(text.toString());
                input.setError(value==null?root.getContext().getString(R.string.guidelines_width_error,
                        GuidelineWidth.MIN_MM,GuidelineWidth.MAX_MM):null);
                if(value!=null&&value!=GuidelineWidthControl.this.customMm) {
                    GuidelineWidthControl.this.customMm=value;
                    updateValue.run();
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
