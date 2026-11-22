package com.drivehub.kamera.birdseye;

import com.drivehub.kamera.steering.WheelGuides;

/** GL-thread layers: retain the outgoing geometry while both directions share one transition clock. */
final class GuidelineFrame {
    static final class Layer {
        final WheelGuides.Direction direction;
        WheelSteering.Angles angles;
        WheelGuides.Style style;
        int widthMm;
        float level,progress,opacity=1;
        Layer(WheelGuides.Direction direction){this.direction=direction;}
    }
    final Layer reverse=new Layer(WheelGuides.Direction.REVERSE),forward=new Layer(WheelGuides.Direction.FORWARD);
    private WheelGuides.Direction direction=WheelGuides.Direction.REVERSE;
    void update(WheelSteering.Angles angles,WheelGuides.Style style,int widthMm,WheelGuides.Direction direction,GuidelineAnimation animation) {
        this.direction=direction;
        Layer current=direction==WheelGuides.Direction.REVERSE?reverse:forward;
        if(angles.guidanceAvailable){current.angles=angles;current.style=style;current.widthMm=widthMm;}
        update(reverse,animation);update(forward,animation);
    }
    private static void update(Layer layer,GuidelineAnimation animation) {
        layer.level=layer.angles==null?0:animation.progress(layer.direction);
        // Existing paths blend directly. Replaying the sweep here would hide both paths under
        // the car at mid-transition and still look like a removal followed by a new build.
        layer.progress=animation.crossfading()&&layer.level>0?1:layer.level;
        layer.opacity=animation.crossfading()?layer.level:1;
    }
    boolean visible(){return reverse.level>0||forward.level>0;}
    Layer incoming(){return direction==WheelGuides.Direction.REVERSE?reverse:forward;}
    Layer outgoing(){return direction==WheelGuides.Direction.REVERSE?forward:reverse;}
    void reset(){reverse.angles=forward.angles=null;reverse.level=forward.level=reverse.progress=forward.progress=0;}
}
