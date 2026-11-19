package com.drivehub.kamera.steering;

import com.drivehub.kamera.birdseye.BirdseyeCalibration;
import com.drivehub.kamera.birdseye.BirdseyeSynthetic;
import com.drivehub.kamera.birdseye.RivelaScene;
import com.drivehub.kamera.birdseye.WheelSteering;
import com.drivehub.kamera.rivela.GroundProjector;
import com.drivehub.kamera.rivela.PdcBars;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class WheelGuidesTest {
    private final SteeringModel model=new SteeringModel();
    private final WheelGuides guides=new WheelGuides();

    @Test public void straightLinesStartAtFrontWheelGroundPointsAndEndAtOemDepth() {
        List<PdcBars.Bar> lines=guides.lines(model.solve(0),100,200);
        assertEquals(4,lines.size());
        for(int side=0;side<2;side++) {
            double[] xy=lines.get(side).xy;double x=100+(side==0?-725:725);
            assertEquals(x,xy[0],0);assertEquals(200-1295.5,xy[1],0);
            assertEquals(200+5239.5,xy[xy.length-1],1e-8);
            for(int i=0;i<xy.length;i+=2)assertEquals(x,xy[i],0);
            assertTrue(xy.length/2>100); // Exercises longer strips than the original 16-segment PDC arcs.
        }
    }

    @Test public void wheelTrajectoriesShareTheSteeringCenterAndMatchInitialWheelTangents() {
        for(float raw:new float[]{-516.95f,-199.3f,199.3f,516.95f}) {
            SteeringModel.Solution pose=model.solve(raw);
            List<PdcBars.Bar> lines=guides.lines(pose,0,0);
            double centerX=1/pose.curvaturePerMm,centerY=1394.5;
            for(int side=0;side<2;side++) {
                double[] xy=lines.get(side).xy;
                double radius=Math.hypot(xy[0]-centerX,xy[1]-centerY);
                for(int i=0;i<xy.length;i+=2) {
                    assertEquals(radius,Math.hypot(xy[i]-centerX,xy[i+1]-centerY),1e-8);
                    if(i>0)assertTrue(Math.hypot(xy[i]-xy[i-2],xy[i+1]-xy[i-1])<=50.0001);
                }
                double tangent=Math.toDegrees(Math.atan2(-(xy[2]-xy[0]),xy[3]-xy[1]));
                assertEquals(side==0?pose.leftDegrees:pose.rightDegrees,tangent,.4);
            }
        }
    }

    @Test public void farEndpointsFollowOemLongitudinalDepthAndMirrorWithSteeringDirection() {
        List<PdcBars.Bar> right=guides.lines(model.solve(-516.95f),0,0),left=guides.lines(model.solve(516.95f),0,0);
        assertEquals(1394.5+3540.5756,lastY(right.get(0)),.002);
        assertEquals(1394.5+3039.4603,lastY(right.get(1)),.002);
        for(int side=0;side<2;side++) {
            double[] a=right.get(side).xy,b=left.get(1-side).xy;
            assertEquals(a.length,b.length);
            for(int i=0;i<a.length;i+=2){assertEquals(-a[i],b[i],1e-8);assertEquals(a[i+1],b[i+1],1e-8);}
        }
        double[] straight=guides.lines(model.solve(0),0,0).get(0).xy;
        double[] tiny=guides.lines(model.solve(.0001f),0,0).get(0).xy;
        assertEquals(straight[straight.length-1],tiny[tiny.length-1],.01);
        assertEquals(straight[straight.length-2],tiny[tiny.length-2],.01);
        assertTrue(guides.lines(SteeringModel.INVALID,0,0).isEmpty());
    }

    @Test public void theSameGroundBandsProjectThroughTheExistingRearPdcProjector() {
        RivelaScene scene=new RivelaScene(BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes()));
        for(float raw:new float[]{0,-516.95f,516.95f}) {
            List<PdcBars.Bar> lines=guides.lines(model.solve(raw),scene.car.centerX,scene.car.centerY);
            for(PdcBars.Bar line:lines)assertFalse(GroundProjector.band(scene.camera(3),line.xy,line.widthMm).isEmpty());
            List<Integer> colors=new ArrayList<>();
            for(float[] poly:GroundProjector.bars(scene.camera(3),lines,colors))for(float value:poly)assertTrue(Float.isFinite(value));
            assertTrue(colors.contains(WheelGuides.LEFT_COLOR));assertTrue(colors.contains(WheelGuides.RIGHT_COLOR));
            assertTrue(colors.contains(WheelGuides.REAR_LEFT_COLOR));assertTrue(colors.contains(WheelGuides.REAR_RIGHT_COLOR));
        }
    }

    @Test public void unavailableHeldWheelsCannotLeaveGuidanceMarkedCurrent() {
        WheelSteering wheels=new WheelSteering();SteeringModel.Solution pose=model.solve(199.3f);
        wheels.setSolution(pose,true);assertTrue(wheels.angles().guidanceAvailable);
        wheels.setSolution(pose,false);assertFalse(wheels.angles().guidanceAvailable);
        assertEquals(pose.leftDegrees,wheels.angles().left,0);
        wheels.setSolution(pose,true);wheels.setSolution(SteeringModel.INVALID,true);
        assertFalse(wheels.angles().guidanceAvailable);
    }
    private static double lastY(PdcBars.Bar line){return line.xy[line.xy.length-1];}
    @Test public void forwardTracesStartAtTheSameWheelsAndAdvanceInTheirSteeringDirection() {
        for(float raw:new float[]{0,-199.3f,199.3f,-516.95f,516.95f}) {
            SteeringModel.Solution pose=model.solve(raw);
            List<PdcBars.Bar> forward=guides.lines(pose,0,0,WheelGuides.Direction.FORWARD);
            assertEquals(4,forward.size());
            for(int index=0;index<4;index++) {
                int side=index%2;boolean rear=index>=2;double[] xy=forward.get(index).xy;
                int[] colours={WheelGuides.LEFT_COLOR,WheelGuides.RIGHT_COLOR,WheelGuides.REAR_LEFT_COLOR,WheelGuides.REAR_RIGHT_COLOR};
                assertEquals(colours[index],forward.get(index).color);
                assertEquals(side==0?-725:725,xy[0],0);assertEquals(rear?1394.5:-1295.5,xy[1],0);
                assertTrue(xy[3]<xy[1]);
                double angle=Math.toDegrees(Math.atan2(xy[2]-xy[0],-(xy[3]-xy[1])));
                assertEquals(rear?0:side==0?pose.leftDegrees:pose.rightDegrees,angle,rear?.5:.4);
                for(int i=2;i<xy.length;i+=2)
                    assertTrue(Math.hypot(xy[i]-xy[i-2],xy[i+1]-xy[i-1])<=50.0001);
                if(raw==0){assertEquals(xy[0],xy[xy.length-2],0);assertEquals(-4295.5,lastY(forward.get(index)),1e-8);}
            }
        }
    }
    @Test public void forwardEndpointsAreOneRigidFrontAxleAfterThreeMetresOfRearAxleTravel() {
        for(float raw:new float[]{-516.95f,-199.3f,0,199.3f,516.95f}) {
            SteeringModel.Solution pose=model.solve(raw);
            List<PdcBars.Bar> forward=guides.lines(pose,0,0,WheelGuides.Direction.FORWARD);
            double yaw=pose.curvaturePerMm*3000;
            double rearX=yaw==0?0:(1-Math.cos(yaw))/pose.curvaturePerMm;
            double rearY=1394.5+(yaw==0?-3000:-Math.sin(yaw)/pose.curvaturePerMm);
            double[] left=forward.get(0).xy,right=forward.get(1).xy;
            double lx=left[left.length-2],ly=left[left.length-1],rx=right[right.length-2],ry=right[right.length-1];
            assertEquals(1450,Math.hypot(rx-lx,ry-ly),1e-8);
            assertEquals(yaw,Math.atan2(ry-ly,rx-lx),1e-12);
            assertEquals(rearX+2690*Math.sin(yaw),(lx+rx)/2,1e-8);
            assertEquals(rearY-2690*Math.cos(yaw),(ly+ry)/2,1e-8);
            if(raw!=0)for(PdcBars.Bar line:forward.subList(0,2)){
                double radius=Math.hypot(line.xy[0]-1/pose.curvaturePerMm,line.xy[1]-1394.5);
                for(int i=0;i<line.xy.length;i+=2)
                    assertEquals(radius,Math.hypot(line.xy[i]-1/pose.curvaturePerMm,line.xy[i+1]-1394.5),1e-8);
            }
        }
    }
    @Test public void forwardRearDisplayCurvesShareExactFrontEndpointsAndArrivalDirections() {
        for(float raw:new float[]{0,-.0001f,.0001f,-.45f,.45f,-199.3f,199.3f,-516.95f,516.95f}) {
            List<PdcBars.Bar> lines=guides.lines(model.solve(raw),125,-300,WheelGuides.Direction.FORWARD);
            for(int side=0;side<2;side++) {
                double[] front=lines.get(side).xy,rear=lines.get(side+2).xy;
                int f=front.length-2,r=rear.length-2;
                assertEquals(front[f],rear[r],0);assertEquals(front[f+1],rear[r+1],0);
                assertEquals(125+(side==0?-725:725),rear[0],0);assertEquals(1094.5,rear[1],0);
                double fx=front[f]-front[f-2],fy=front[f+1]-front[f-1];
                double rx=rear[r]-rear[r-2],ry=rear[r+1]-rear[r-1];
                assertTrue(Math.abs(Math.toDegrees(Math.atan2(cross(fx,fy,rx,ry),fx*rx+fy*ry)))<2);
                for(int i=2;i<rear.length;i+=2) {
                    assertTrue(Double.isFinite(rear[i]));assertTrue(Double.isFinite(rear[i+1]));
                    assertTrue(rear[i+1]<rear[i-1]);
                    assertTrue(Math.hypot(rear[i]-rear[i-2],rear[i+1]-rear[i-1])<=50.0001);
                }
            }
        }
    }
    @Test public void forwardLinesProjectThroughTheFrontCameraAndMirrorForOppositeSteering() {
        RivelaScene scene=new RivelaScene(BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes()));
        List<PdcBars.Bar> right=guides.lines(model.solve(-516.95f),0,0,WheelGuides.Direction.FORWARD);
        List<PdcBars.Bar> left=guides.lines(model.solve(516.95f),0,0,WheelGuides.Direction.FORWARD);
        for(int index=0;index<4;index++) {
            double[] a=right.get(index).xy,b=left.get(index^1).xy;assertEquals(a.length,b.length);
            for(int i=0;i<a.length;i+=2){assertEquals(-a[i],b[i],1e-8);assertEquals(a[i+1],b[i+1],1e-8);}
            assertFalse(GroundProjector.band(scene.camera(2),a,60).isEmpty());
        }
    }
    @Test public void reverseRearTracesStartAtRearWheelsAndShareTheFrontSteeringCenter() {
        for(float raw:new float[]{0,-199.3f,199.3f,-516.95f,516.95f}) {
            SteeringModel.Solution pose=model.solve(raw);List<PdcBars.Bar> lines=guides.lines(pose,0,0);
            assertEquals(4,lines.size());
            for(int side=0;side<2;side++) {
                PdcBars.Bar rear=lines.get(2+side);double[] xy=rear.xy;
                assertEquals(side==0?-725:725,xy[0],0);assertEquals(1394.5,xy[1],0);
                assertEquals(side==0?WheelGuides.REAR_LEFT_COLOR:WheelGuides.REAR_RIGHT_COLOR,rear.color);
                assertEquals(lastY(lines.get(side)),lastY(rear),1e-8);
                assertTrue(xy[3]>xy[1]);
                assertEquals(0,Math.toDegrees(Math.atan2(xy[2]-xy[0],xy[3]-xy[1])),.5);
                for(int i=2;i<xy.length;i+=2) {
                    assertTrue(Math.hypot(xy[i]-xy[i-2],xy[i+1]-xy[i-1])<=50.0001);
                    assertTrue(Double.isFinite(xy[i]));assertTrue(Double.isFinite(xy[i+1]));
                }
                if(pose.curvaturePerMm==0)for(int i=0;i<xy.length;i+=2)assertEquals(xy[0],xy[i],0);
                else {
                    double turnCenter=1/pose.curvaturePerMm,radius=Math.abs(turnCenter-xy[0]);
                    for(int i=0;i<xy.length;i+=2)assertEquals(radius,Math.hypot(xy[i]-turnCenter,xy[i+1]-1394.5),1e-8);
                    double[] front=lines.get(side).xy;
                    assertEquals(Math.signum(pose.curvaturePerMm),Math.signum(xy[2]-xy[0]),0);
                    assertEquals(-Math.signum(pose.curvaturePerMm),Math.signum(front[2]-front[0]),0);
                }
            }
        }
    }
    @Test public void rearTracesMirrorAndUseInnerAndOuterRearWheelRadii() {
        List<PdcBars.Bar> right=guides.lines(model.solve(-516.95f),0,0),left=guides.lines(model.solve(516.95f),0,0);
        double center=1/model.solve(-516.95f).curvaturePerMm;
        assertEquals(4883.1313,Math.abs(center-right.get(2).xy[0]),.002);
        assertEquals(3433.1313,Math.abs(center-right.get(3).xy[0]),.002);
        for(int side=0;side<2;side++) {
            double[] a=right.get(2+side).xy,b=left.get(2+1-side).xy;assertEquals(a.length,b.length);
            for(int i=0;i<a.length;i+=2){assertEquals(-a[i],b[i],1e-8);assertEquals(a[i+1],b[i+1],1e-8);}
        }
        List<PdcBars.Bar> straight=guides.lines(model.solve(0),0,0),tiny=guides.lines(model.solve(.0001f),0,0);
        for(int index:new int[]{2,3}) {
            double[] a=straight.get(index).xy,b=tiny.get(index).xy;
            assertEquals(a[a.length-2],b[b.length-2],.01);assertEquals(a[a.length-1],b[b.length-1],.01);
        }
    }
    @Test public void individualClearanceLinesShareMirrorWidthAndFarEndpointsWithSimple() {
        for(WheelGuides.Direction direction:WheelGuides.Direction.values())for(float raw:new float[]{0,-.45f,.45f,-199.3f,199.3f,-516.95f,516.95f}) {
            SteeringModel.Solution pose=model.solve(raw);
            List<PdcBars.Bar> lines=guides.clearanceLines(pose,125,-300,direction);
            WheelGuides.Corridor simple=guides.corridor(pose,125,-300,direction);
            assertEquals(4,lines.size());
            for(int i=0;i<lines.size();i++) {
                PdcBars.Bar line=lines.get(i);
                assertEquals(WheelGuides.CORRIDOR_EDGE_COLOR,line.color);
                assertEquals(125+(i%2==0?-1030:1030),line.xy[0],1e-8);
                assertEquals(-300+(i<2?-1295.5:1394.5),line.xy[1],1e-8);
                if(direction==WheelGuides.Direction.FORWARD&&i>=2) {
                    double[] front=lines.get(i-2).xy;
                    assertEquals(front[front.length-2],line.xy[line.xy.length-2],1e-8);
                    assertEquals(front[front.length-1],line.xy[line.xy.length-1],1e-8);
                }
            }
            for(PdcBars.Bar edge:simple.boundaries) {
                boolean sameEndpoint=false;
                for(PdcBars.Bar line:lines)sameEndpoint|=Math.hypot(edge.xy[edge.xy.length-2]-line.xy[line.xy.length-2],
                        edge.xy[edge.xy.length-1]-line.xy[line.xy.length-1])<1e-8;
                assertTrue("Simple must use the same mirror-width curves",sameEndpoint);
            }
            assertEquals(725,guides.lines(pose,0,0,direction).get(1).xy[0],0);
        }
        assertTrue(guides.clearanceLines(SteeringModel.INVALID,0,0,WheelGuides.Direction.REVERSE).isEmpty());
    }
    @Test public void blueCorridorUsesMirrorWidthAndSameSteeringMotionWithoutChangingWheelTraces() {
        VehicleGeometry car=VehicleGeometry.MG4;
        assertEquals(2060,car.widthWithMirrorsMm,0);
        assertEquals(1068,car.oemRadiusOffsetMm(),0);
        for(WheelGuides.Direction direction:WheelGuides.Direction.values())for(float raw:new float[]{0,-.45f,.45f,-199.3f,199.3f,-516.95f,516.95f}) {
            SteeringModel.Solution pose=model.solve(raw);List<PdcBars.Bar> traces=guides.lines(pose,0,0,direction);
            double[][] original=traces.stream().map(line->line.xy.clone()).toArray(double[][]::new);
            int[] originalColours=traces.stream().mapToInt(line->line.color).toArray();
            WheelGuides.Corridor area=guides.corridor(pose,0,0,direction);
            assertEquals(2,area.boundaries.size());
            for(int side=0;side<2;side++) {
                double x=(side==0?-1:1)*car.widthWithMirrorsMm/2;
                double[] edge=area.boundaries.get(side).xy;
                assertEquals(x,edge[0],0);
                assertEquals(direction==WheelGuides.Direction.REVERSE?-1295.5:1394.5,edge[1],0);
                assertEquals((side==0?-1:1)*725,traces.get(side).xy[0],0);
                int end=edge.length-2;
                double k=pose.curvaturePerMm;
                if(direction==WheelGuides.Direction.FORWARD) {
                    double angle=-k*WheelGuides.FORWARD_TRAVEL_MM;
                    double tx=k==0?0:2*Math.pow(Math.sin(angle/2),2)/k;
                    double ty=k==0?-WheelGuides.FORWARD_TRAVEL_MM:Math.sin(angle)/k;
                    assertEquals(tx+x*Math.cos(angle)-car.wheelbaseMm*Math.sin(angle),edge[end],1e-8);
                    assertEquals(1394.5+ty-x*Math.sin(angle)-car.wheelbaseMm*Math.cos(angle),edge[end+1],1e-8);
                }else {
                    assertEquals(1394.5+guides.oemEndY(k,side==0?-1:1),edge[end+1],1e-8);
                    boolean rear=side==0?k<0:k>0;
                    if(k!=0)assertEquals(Math.hypot(1/k-x,rear?0:car.wheelbaseMm),
                            Math.hypot(edge[end]-1/k,edge[end+1]-1394.5),1e-8);
                }
                if(raw==0)for(int i=0;i<edge.length;i+=2)assertEquals(x,edge[i],1e-8);
                assertEquals(WheelGuides.CORRIDOR_EDGE_COLOR,area.boundaries.get(side).color);
            }
            if(direction==WheelGuides.Direction.FORWARD) {
                double[] left=area.boundaries.get(0).xy,right=area.boundaries.get(1).xy;
                assertEquals(car.widthWithMirrorsMm,Math.hypot(left[left.length-2]-right[right.length-2],
                        left[left.length-1]-right[right.length-1]),1e-8);
            }
            for(int i=0;i<traces.size();i++)assertArrayEquals(original[i],traces.get(i).xy,0);
            assertArrayEquals(originalColours,traces.stream().mapToInt(line->line.color).toArray());
        }
        assertTrue(guides.corridor(model.solve(Float.NaN),0,0,WheelGuides.Direction.REVERSE).boundaries.isEmpty());
    }
    @Test public void corridorTrianglesCoverExactlyTheirBoundaryPolygonWithoutFoldedOrOverlappingQuads() {
        List<Float> samples=new ArrayList<>();
        for(float raw:new float[]{0,-.0001f,.0001f,-.45f,.45f,-99.65f,99.65f,-199.3f,199.3f,-398.61f,398.61f,-516.95f,516.95f})samples.add(raw);
        for(int raw=-516;raw<=516;raw++)samples.add((float)raw);
        for(WheelGuides.Direction direction:WheelGuides.Direction.values())
            for(float raw:samples) {
                SteeringModel.Solution pose=model.solve(raw);
                WheelGuides.Corridor area=guides.corridor(pose,125,-300,direction);
                double[] a=area.boundaries.get(0).xy,b=area.boundaries.get(1).xy,t=area.triangles;
                assertEquals((a.length/2+b.length/2-2)*6,t.length);
                double polygon=0;
                for(int i=0;i<a.length-2;i+=2)polygon+=cross(a[i],a[i+1],a[i+2],a[i+3]);
                polygon+=cross(a[a.length-2],a[a.length-1],b[b.length-2],b[b.length-1]);
                for(int i=b.length-2;i>=2;i-=2)polygon+=cross(b[i],b[i+1],b[i-2],b[i-1]);
                polygon+=cross(b[0],b[1],a[0],a[1]);
                double sum=0,absoluteSum=0;
                for(int i=0;i<t.length;i+=6) {
                    for(int j=0;j<6;j++)assertTrue(Double.isFinite(t[i+j]));
                    double triangle=cross(t[i+2]-t[i],t[i+3]-t[i+1],t[i+4]-t[i],t[i+5]-t[i+1]);
                    assertTrue("Folded/degenerate corridor at "+raw+" "+direction,triangle*polygon>0);
                    sum+=triangle;absoluteSum+=Math.abs(triangle);
                }
                assertEquals(polygon,sum,.001);assertEquals(Math.abs(polygon),absoluteSum,.001);
            }
    }
    private static double cross(double ax,double ay,double bx,double by){return ax*by-ay*bx;}
    @Test public void blueMirrorWidthFieldEnclosesWheelGuidesBeforeTheUnequalReverseEndCap() {
        for(WheelGuides.Direction direction:WheelGuides.Direction.values())for(float raw:new float[]{0,-.45f,.45f,-199.3f,199.3f,-398.61f,398.61f,-516.95f,516.95f}) {
            SteeringModel.Solution pose=model.solve(raw);
            List<PdcBars.Bar> traces=guides.lines(pose,0,0,direction);
            WheelGuides.Corridor area=guides.corridor(pose,0,0,direction);
            double[] left=area.boundaries.get(0).xy,right=area.boundaries.get(1).xy;
            double capStartY=Math.min(left[left.length-1],right[right.length-1]);
            for(int side=0;side<2;side++)for(int index:new int[]{side,side+2}) {
                double[] source=traces.get(index).xy;
                for(int i=0;i<source.length;i+=2) {
                    // R preserves unequal OEM endpoint depths; its sloping cap is not a final vehicle pose.
                    if(direction==WheelGuides.Direction.REVERSE&&source[i+1]>capStartY)continue;
                    assertTrue("Wheel guide outside mirror-width field in "+direction+" at "+raw+" side="+side+" sample="+i,
                            contains(area,source[i],source[i+1]));
                }
            }
        }
    }
    private static boolean contains(WheelGuides.Corridor area,double x,double y) {
        // Include the far closing edge: mirror-side tips rotate to different depths in a turn.
        double[] left=area.boundaries.get(0).xy,right=area.boundaries.get(1).xy;
        int n=(left.length+right.length)/2;boolean inside=false;
        for(int i=0;i<n;i++) {
            int j=(i+1)%n;
            double ax=i<left.length/2?left[2*i]:right[2*(n-1-i)];
            double ay=i<left.length/2?left[2*i+1]:right[2*(n-1-i)+1];
            double bx=j<left.length/2?left[2*j]:right[2*(n-1-j)];
            double by=j<left.length/2?left[2*j+1]:right[2*(n-1-j)+1];
            double dx=bx-ax,dy=by-ay,t=((x-ax)*dx+(y-ay)*dy)/(dx*dx+dy*dy);
            t=Math.max(0,Math.min(1,t));
            if(Math.hypot(x-ax-t*dx,y-ay-t*dy)<1e-6)return true;
            if((ay>y)!=(by>y)&&x<ax+(y-ay)*dx/dy)inside=!inside;
        }
        return inside;
    }
    @Test public void blueAreaRemainsFiniteWhileSmoothedCurvatureSettlesToStraight() {
        for(WheelGuides.Direction direction:WheelGuides.Direction.values())for(int sign:new int[]{-1,1})for(int step=0;step<=60;step++) {
            SteeringModel.Solution pose=model.fromCurvature(sign*Math.scalb(model.solve(-516.95f).curvaturePerMm,-step));
            WheelGuides.Corridor area;
            try{area=guides.corridor(pose,0,0,direction);}
            catch(IllegalArgumentException e){throw new AssertionError("Straightening at sign="+sign+" step="+step+" k="+pose.curvaturePerMm,e);}
            assertEquals(2,area.boundaries.size());assertTrue(area.triangles.length>0);
            for(double value:area.triangles)assertTrue(Double.isFinite(value));
        }
    }
    @Test public void forwardBlueBoundariesMirrorAtBothLocksAndRetainOuterGuides() {
        for(float raw:new float[]{0,.45f,199.3f,516.95f}) {
            SteeringModel.Solution left=model.solve(raw),right=model.solve(-raw);
            WheelGuides.Corridor a=guides.corridor(left,0,0,WheelGuides.Direction.FORWARD);
            WheelGuides.Corridor b=guides.corridor(right,0,0,WheelGuides.Direction.FORWARD);
            for(int side=0;side<2;side++) {
                double[] xy=a.boundaries.get(side).xy,mirror=b.boundaries.get(side^1).xy;
                assertEquals(xy.length,mirror.length);
                for(int i=0;i<xy.length;i+=2) {
                    assertEquals(-xy[i],mirror[i],1e-8);assertEquals(xy[i+1],mirror[i+1],1e-8);
                    if(i<2)continue;
                    double dx=xy[i]-xy[i-2],dy=xy[i+1]-xy[i-1];
                    assertTrue("Reversing outer guide at "+raw,dy<=0);
                }
            }
        }
    }
}
