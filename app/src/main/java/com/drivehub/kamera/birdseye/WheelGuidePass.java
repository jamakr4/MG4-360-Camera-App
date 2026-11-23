package com.drivehub.kamera.birdseye;

import com.drivehub.kamera.rivela.PdcBars;
import com.drivehub.kamera.steering.SteeringModel;
import com.drivehub.kamera.steering.WheelGuides;
import java.util.List;

/** Shared cached ground geometry and style for orthographic and orbit guidance. */
final class WheelGuidePass {
    private WheelGuidePath reverse,forward;
    void draw(float[] mvp,RivelaScene scene,WheelSteering.Angles angles,WheelGuides.Direction direction,
              WheelGuides.Style style,boolean maskBody,float progress) {
        draw(mvp,scene,angles,direction,style,com.drivehub.kamera.steering.GuidelineWidth.DEFAULT_MM,WheelGuides.Geometry.FRONT_BUMPER,maskBody,progress,1);
    }
    void draw(float[] mvp,RivelaScene scene,GuidelineFrame frame,boolean maskBody) {
        draw(mvp,scene,frame.outgoing(),maskBody);draw(mvp,scene,frame.incoming(),maskBody);
    }
    private void draw(float[] mvp,RivelaScene scene,GuidelineFrame.Layer layer,boolean maskBody) {
        if(layer.progress>0&&layer.angles!=null)
            draw(mvp,scene,layer.angles,layer.direction,layer.style,layer.widthMm,layer.geometry,maskBody,layer.progress,layer.opacity);
    }
    private void draw(float[] mvp,RivelaScene scene,WheelSteering.Angles angles,WheelGuides.Direction direction,
                      WheelGuides.Style style,int widthMm,WheelGuides.Geometry geometry,boolean maskBody,float progress,float opacity) {
        if(progress<=0)return;
        WheelGuidePath path;
        if(direction==WheelGuides.Direction.REVERSE){if(reverse==null)reverse=new WheelGuidePath();path=reverse;}
        else {if(forward==null)forward=new WheelGuidePath();path=forward;}
        path.draw(mvp,scene,angles,direction,style,widthMm,geometry,maskBody,progress,opacity);
    }
    void close(){if(reverse!=null)reverse.close();if(forward!=null)forward.close();}
}

/** Separate caches avoid rebuilding both paths on every frame of a gear transition. */
final class WheelGuidePath {
    private static final float END_FADE_MM=700;
    private static final float REVEAL_FEATHER_MM=240;
    private final PdcGroundPass ground=new PdcGroundPass();
    private final WheelGuides geometry=new WheelGuides();
    private final SteeringModel model=new SteeringModel();
    private RivelaScene cachedScene;
    private double cachedCurvature=Double.NaN;
    private WheelGuides.Direction cachedDirection;
    private int cachedWidthMm;
    private WheelGuides.Geometry cachedGeometry;
    private SteeringModel.Solution pose;
    private List<PdcBars.Bar> lines,blueLines,outerLines;
    private WheelGuides.Corridor corridor;
    private float[] corridorFade;
    private final float[] revealPlane=new float[4];
    private WheelGuides.Style boundsStyle;
    private double minY,maxY;

    void draw(float[] mvp,RivelaScene scene,WheelSteering.Angles angles,WheelGuides.Direction direction,
              WheelGuides.Style style,int widthMm,WheelGuides.Geometry guideGeometry,boolean maskBody,float progress,float opacity) {
        if(progress<=0)return;
        ground.setOpacity(opacity);
        if(scene!=cachedScene||angles.curvaturePerMm!=cachedCurvature||direction!=cachedDirection||widthMm!=cachedWidthMm||guideGeometry!=cachedGeometry) {
            cachedScene=scene;cachedCurvature=angles.curvaturePerMm;cachedDirection=direction;cachedWidthMm=widthMm;
            cachedGeometry=guideGeometry;
            pose=model.fromCurvature(cachedCurvature);lines=null;blueLines=null;outerLines=null;corridor=null;boundsStyle=null;
        }
        if(style==WheelGuides.Style.SIMPLE) {
            if(corridor==null) {
                corridor=geometry.corridor(pose,scene.car.centerX,scene.car.centerY,direction,widthMm,guideGeometry);
                corridorFade=endPlane(corridor);
            }
            float[] reveal=reveal(corridor.boundaries,direction,style,progress);
            ground.drawFill(mvp,scene,corridor.triangles,WheelGuides.CORRIDOR_FILL_COLOR,maskBody,END_FADE_MM,corridorFade,reveal);
            ground.draw(mvp,scene,corridor.boundaries,maskBody,END_FADE_MM,corridorFade,reveal);
        }else {
            List<PdcBars.Bar> paths;
            if(style==WheelGuides.Style.LINES) {
                if(blueLines==null)blueLines=geometry.clearanceLines(pose,scene.car.centerX,scene.car.centerY,direction,widthMm,guideGeometry);
                paths=blueLines;
            }else if(style==WheelGuides.Style.OUTER_LINES) {
                if(outerLines==null)outerLines=geometry.outerLines(pose,scene.car.centerX,scene.car.centerY,direction,widthMm,guideGeometry);
                paths=outerLines;
            }else {
                if(lines==null)lines=geometry.lines(pose,scene.car.centerX,scene.car.centerY,direction);
                paths=lines;
            }
            ground.draw(mvp,scene,paths,maskBody,END_FADE_MM,null,reveal(paths,direction,style,progress));
        }
    }
    /** One ground-space sweep for all four wheels and the filled corridor. Front is negative Y. */
    private float[] reveal(List<PdcBars.Bar> paths,WheelGuides.Direction direction,WheelGuides.Style style,float progress) {
        if(progress>=1)return null;
        if(boundsStyle!=style) {
            minY=Double.POSITIVE_INFINITY;maxY=Double.NEGATIVE_INFINITY;
            for(PdcBars.Bar path:paths)for(int i=1;i<path.xy.length;i+=2) {
                minY=Math.min(minY,path.xy[i]);maxY=Math.max(maxY,path.xy[i]);
            }
            boundsStyle=style;
        }
        if(!Double.isFinite(minY)||!Double.isFinite(maxY))return null;
        double sign=direction==WheelGuides.Direction.REVERSE?1:-1,start=sign>0?minY:maxY;
        double edge=progress*(maxY-minY+2*REVEAL_FEATHER_MM)-REVEAL_FEATHER_MM;
        revealPlane[0]=0;revealPlane[1]=(float)-sign;
        revealPlane[2]=(float)(edge+REVEAL_FEATHER_MM+sign*start);revealPlane[3]=2*REVEAL_FEATHER_MM;
        return revealPlane;
    }
    /** Signed ground distance from the far cap, positive towards the vehicle. */
    private static float[] endPlane(WheelGuides.Corridor area) {
        if(area.boundaries.size()<2)return null;
        double[] a=area.boundaries.get(0).xy,b=area.boundaries.get(1).xy;
        int ae=a.length-2,be=b.length-2;
        double nx=b[be+1]-a[ae+1],ny=a[ae]-b[be],length=Math.hypot(nx,ny);
        if(length<1e-6)return null;
        if(nx*((a[0]+b[0])/2-a[ae])+ny*((a[1]+b[1])/2-a[ae+1])<0){nx=-nx;ny=-ny;}
        return new float[]{(float)(nx/length),(float)(ny/length),(float)(-(nx*a[ae]+ny*a[ae+1])/length)};
    }
    void close(){ground.close();}
}
