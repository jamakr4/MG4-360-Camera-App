package com.drivehub.kamera.settings;

import android.view.View;
import android.widget.TextView;
import com.drivehub.kamera.R;
import com.drivehub.kamera.steering.WheelGuides;

/** Independent geometry and style choices, shared by parking settings and the simulator. */
public final class GuidelineAppearanceControl {
    public interface Listener { void changed(WheelGuides.Geometry geometry,WheelGuides.Style style); }
    private WheelGuides.Geometry geometry;
    private WheelGuides.Style style;

    public GuidelineAppearanceControl(View root,WheelGuides.Geometry geometry,WheelGuides.Style style,Listener listener) {
        this.geometry=geometry;this.style=style;
        SegmentedControl picker=root.findViewById(R.id.segGuidelineGeometry);
        String[] labels={root.getContext().getString(R.string.guidelines_geometry_axles),
                root.getContext().getString(R.string.guidelines_geometry_front_bumper)};
        picker.check(geometry==WheelGuides.Geometry.AXLES?R.id.segGuidelineAxles:R.id.segGuidelineFrontBumper);
        for(int i=0;i<picker.getChildCount();i++)picker.getChildAt(i).setContentDescription(
                root.getContext().getString(R.string.guidelines_geometry)+": "+labels[i]);
        picker.addOnButtonCheckedListener((group,id,checked)->{
            if(!checked)return;
            WheelGuides.Geometry selected=id==R.id.segGuidelineAxles?WheelGuides.Geometry.AXLES:WheelGuides.Geometry.FRONT_BUMPER;
            if(selected==GuidelineAppearanceControl.this.geometry)return;
            GuidelineAppearanceControl.this.geometry=selected;
            listener.changed(selected,GuidelineAppearanceControl.this.style);
        });
        SegmentedControl segments=root.findViewById(R.id.segGuidelineStyle);
        segments.check(style==WheelGuides.Style.LINES?R.id.segGuidelineLines:
                style==WheelGuides.Style.OUTER_LINES?R.id.segGuidelineOuterLines:R.id.segGuidelineSimple);
        for(int i=0;i<segments.getChildCount();i++) {
            TextView child=(TextView)segments.getChildAt(i);
            child.setContentDescription(root.getContext().getString(R.string.guidelines_style)+": "+child.getText());
        }
        segments.addOnButtonCheckedListener((group,id,checked)->{
            if(!checked)return;
            GuidelineAppearanceControl.this.style=id==R.id.segGuidelineLines?WheelGuides.Style.LINES:
                    id==R.id.segGuidelineOuterLines?WheelGuides.Style.OUTER_LINES:WheelGuides.Style.SIMPLE;
            listener.changed(GuidelineAppearanceControl.this.geometry,GuidelineAppearanceControl.this.style);
        });
    }
    public WheelGuides.Geometry geometry(){return geometry;}
    public WheelGuides.Style style(){return style;}
}
