package com.drivehub.kamera.steering;

import com.drivehub.kamera.rivela.PdcBars;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class OuterGuidelinesTest {
    private final SteeringModel model=new SteeringModel();
    private final WheelGuides guides=new WheelGuides();

    @Test public void outerLinesKeepTheExtremeCurvesAndGiveBothSidesTheSameLongStart() {
        for(WheelGuides.Direction direction:WheelGuides.Direction.values())for(WheelGuides.Geometry geometry:WheelGuides.Geometry.values())
            for(int width:new int[]{1,1836,2060,2500,3000})for(float raw:new float[]{-516.95f,-199.3f,-.001f,0,.001f,199.3f,516.95f}) {
                SteeringModel.Solution pose=model.solve(raw);
                List<PdcBars.Bar> all=guides.clearanceLines(pose,125,-300,direction,width,geometry);
                List<PdcBars.Bar> outer=guides.outerLines(pose,125,-300,direction,width,geometry);
                assertEquals(2,outer.size());double selectedMin=Double.POSITIVE_INFINITY,selectedMax=0;
                double startY=direction==WheelGuides.Direction.FORWARD?-300+1402.5:all.get(0).xy[1];
                for(int side=0;side<2;side++) {
                    PdcBars.Bar line=outer.get(side);assertEquals(WheelGuides.CORRIDOR_EDGE_COLOR,line.color);
                    assertEquals(WheelGuides.WIDTH_MM,line.widthMm,0);
                    assertEquals(125+(side==0?-width/2.0:width/2.0),line.xy[0],0);assertEquals(startY,line.xy[1],0);
                    PdcBars.Bar source=null;
                    for(PdcBars.Bar candidate:all)if(suffixEquals(line.xy,candidate.xy)){source=candidate;break;}
                    assertNotNull("The complete original curve must remain after the lead-in",source);
                    if(pose.curvaturePerMm!=0) {
                        double radius=radius(source,pose);selectedMin=Math.min(selectedMin,radius);selectedMax=Math.max(selectedMax,radius);
                    }
                    for(int i=2;i<line.xy.length;i+=2)assertTrue(Math.hypot(line.xy[i]-line.xy[i-2],line.xy[i+1]-line.xy[i-1])<=50.0001);
                }
                if(pose.curvaturePerMm!=0) {
                    double min=Double.POSITIVE_INFINITY,max=0;
                    for(PdcBars.Bar line:all){double radius=radius(line,pose);min=Math.min(min,radius);max=Math.max(max,radius);}
                    assertEquals(min,selectedMin,1e-8);assertEquals(max,selectedMax,1e-8);
                }
            }
        assertTrue(guides.outerLines(SteeringModel.INVALID,0,0,WheelGuides.Direction.REVERSE,2060,WheelGuides.Geometry.FRONT_BUMPER).isEmpty());
    }
    @Test public void bothLongSidesStayContinuousAcrossTinyLeftAndRightSteeringInputs() {
        for(WheelGuides.Direction direction:WheelGuides.Direction.values())for(WheelGuides.Geometry geometry:WheelGuides.Geometry.values())
            for(int width:new int[]{1836,2060,3000}) {
                List<PdcBars.Bar> straight=guides.outerLines(model.solve(0),125,-300,direction,width,geometry);
                for(float raw:new float[]{-25.2f,-1,-.001f,.001f,1,25.2f}) {
                    List<PdcBars.Bar> turns=guides.outerLines(model.solve(raw),125,-300,direction,width,geometry);
                    for(int side=0;side<2;side++) {
                        assertEquals(straight.get(side).xy[0],turns.get(side).xy[0],0);
                        assertEquals(straight.get(side).xy[1],turns.get(side).xy[1],0);
                        double length=length(turns.get(side).xy);
                        assertTrue("Short side appeared at "+raw+" in "+direction,length>5000);
                        assertEquals("Line length jumps near straight",length(straight.get(side).xy),length,250);
                    }
                }
            }
    }
    private static boolean suffixEquals(double[] full,double[] suffix) {
        if(full.length<suffix.length)return false;int offset=full.length-suffix.length;
        for(int i=0;i<suffix.length;i++)if(full[offset+i]!=suffix[i])return false;
        return true;
    }
    private static double length(double[] xy) {
        double length=0;for(int i=2;i<xy.length;i+=2)length+=Math.hypot(xy[i]-xy[i-2],xy[i+1]-xy[i-1]);return length;
    }
    private static double radius(PdcBars.Bar path,SteeringModel.Solution pose) {
        return Math.hypot(path.xy[0]-125-1/pose.curvaturePerMm,path.xy[1]-(-300+1402.5));
    }
    @Test public void stableStyleIdsRetainAllChoicesAndUnknownValuesFallBackToOuterLines() {
        for(WheelGuides.Style style:WheelGuides.Style.values())assertEquals(style,WheelGuides.Style.fromId(style.id));
        assertEquals(WheelGuides.Style.OUTER_LINES,WheelGuides.Style.DEFAULT);
        assertEquals(WheelGuides.Style.OUTER_LINES,WheelGuides.Style.fromId(null));
        assertEquals(WheelGuides.Style.OUTER_LINES,WheelGuides.Style.fromId("future"));
    }
}
