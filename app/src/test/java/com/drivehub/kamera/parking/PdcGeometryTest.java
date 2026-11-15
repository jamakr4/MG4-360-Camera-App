package com.drivehub.kamera.parking;
import com.drivehub.kamera.rivela.*;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
public class PdcGeometryTest {
    private static final CarFrame CAR=new CarFrame(0,0,4287,1836);
    @Test public void snapshotsDefendArraysValidateRangesAndExpire() {
        int[] values={1,2,0,9};PdcSnapshot s=new PdcSnapshot(values,100,77,1000);values[0]=9;
        assertEquals(1,s.level(0));s.spatialLevels()[0]=8;assertEquals(1,s.level(0));
        assertTrue(s.diagnostic(1000).contains("raw=[1, 2, 0, 9]"));
        PdcSnapshot invalidSample=new PdcSnapshot(new int[]{255,2,0,9},255,77,1000);
        assertEquals(-1,invalidSample.level(0));assertEquals(-1,invalidSample.closestCm);
        assertTrue(invalidSample.diagnostic(1000).contains("raw=[255, 2, 0, 9] closest=255cm"));
        assertTrue(s.fresh(1500));assertFalse(s.fresh(1501));assertFalse(s.fresh(999));
        for(int invalid:new int[]{0,255,-1,999})assertEquals(-1,new PdcSnapshot(values,invalid,0,1000).closestCm);
        assertArrayEquals(new int[4],new PdcSnapshot(new int[]{1,-1,0,0},30,0,1000).spatialLevels());
        assertArrayEquals(new int[]{1,0,0,0},new PdcSnapshot(new int[]{1,0,0,0},0,0,1000).spatialLevels());
    }
    @Test public void explicitlyUnknownTableStillKeepsOnlyClosestLevelSectors() {
        PdcGeometry geometry=new PdcGeometry(level->-1);geometry.set(new PdcSnapshot(new int[]{2,4,2,0},100,0,1000));
        List<PdcBars.Bar> bars=geometry.bars(CAR,1000);assertEquals(2,bars.size());
        for(PdcBars.Bar bar:bars) {
            assertEquals(0xFFFFFF00,bar.color);assertEquals(120,bar.widthMm,0);
            for(int i=0;i<bar.xy.length;i+=2)assertEquals(1500+1000-60,Math.hypot(bar.xy[i],bar.xy[i+1]-(CAR.rear()-1500)),1e-6);
        }
        assertTrue(geometry.bars(CAR,1501).isEmpty());
        geometry.set(PdcSnapshot.EMPTY);assertTrue(geometry.bars(CAR,1600).isEmpty());
    }
    @Test public void originalBandColorsAndAnimationAreUsedWithoutStaleGeometry() {
        assertEquals(0,PdcBars.band(1));for(int i=2;i<=6;i++)assertEquals(1,PdcBars.band(i));for(int i=7;i<=9;i++)assertEquals(2,PdcBars.band(i));assertEquals(-1,PdcBars.band(10));
        PdcGeometry g=new PdcGeometry();g.set(new PdcSnapshot(new int[]{1,0,0,0},100,0,1000));
        double initial=radius(g.bars(CAR,1000).get(0));
        g.set(new PdcSnapshot(new int[]{1,0,0,0},50,0,1100));double middle=radius(g.bars(CAR,1100).get(0));
        assertTrue(middle<initial);assertTrue(middle>1940);
        g.bars(CAR,1200);g.bars(CAR,1300);double end=radius(g.bars(CAR,1400).get(0));assertEquals(1940,end,15);
        g.set(new PdcSnapshot(new int[4],40,0,1450));assertTrue(g.bars(CAR,1450).isEmpty());
    }
    @Test public void explicitDistancesOverrideTheLiveRivelaTable() {
        PdcSnapshot sample=new PdcSnapshot(new int[]{1,3,6,9},50,0,1000);
        PdcGeometry normal=new PdcGeometry();normal.set(sample);assertEquals(4,normal.bars(CAR,1000).size());
        assertTrue(normal.hasEstimatedPositions(1000));
        PdcGeometry fixture=new PdcGeometry(level->level==3?80:level==6?110:level==9?140:-1);fixture.set(sample);
        List<PdcBars.Bar> bars=fixture.bars(CAR,1000);assertEquals(4,bars.size());
        assertFalse(fixture.hasEstimatedPositions(1000));
        int[] cm={50,80,110,140},colors={0xFFFF0000,0xFFFFFF00,0xFFFFFF00,0xFF00FF00};
        for(int i=0;i<4;i++){assertEquals(1500+cm[i]*10-60,radius(bars.get(i)),1e-6);assertEquals(colors[i],bars.get(i).color);}
        assertTrue(fixture.bars(CAR,1501).isEmpty());
    }
    @Test public void liveDefaultDrawsFourLevelsWithMeasuredMinimumAndFixedTableDistances() {
        PdcGeometry g=new PdcGeometry();g.set(new PdcSnapshot(new int[]{1,3,6,9},20,0,1000));
        List<PdcBars.Bar> bars=g.bars(CAR,1000);assertEquals(4,bars.size());
        int[] cm={20,50,95,150};
        for(int i=0;i<cm.length;i++)assertEquals(1500+cm[i]*10-60,radius(bars.get(i)),1e-6);
        assertTrue(g.hasEstimatedPositions(1500));
        assertFalse(g.hasEstimatedPositions(1501));assertTrue(g.bars(CAR,1501).isEmpty());
    }
    @Test public void swappingNearestLevelsKeepsBothSectorsVisibleThroughoutAnimation() {
        PdcGeometry g=new PdcGeometry();long now=1000;
        for(int[] levels:new int[][]{{2,3,0,0},{2,2,0,0},{3,2,0,0},{2,3,0,0}}) {
            g.set(new PdcSnapshot(levels,40,0,now));
            assertEquals(levels[0]!=levels[1],g.hasEstimatedPositions(now));
            for(int frame=0;frame<6;frame++) {
                List<PdcBars.Bar> bars=g.bars(CAR,now+frame*16);assertEquals(2,bars.size());
                for(PdcBars.Bar bar:bars)assertTrue(radius(bar)>=1500+400-60-1e-6);
            }
            now+=100;
        }
    }
    @Test public void unknownDistanceTurnsOffImmediatelyAndReactivationDoesNotGlideFromOff() {
        PdcGeometry g=new PdcGeometry(level->-1);
        g.set(new PdcSnapshot(new int[]{2,2,0,0},80,0,1000));assertEquals(2,g.bars(CAR,1000).size());
        g.set(new PdcSnapshot(new int[]{2,3,0,0},80,0,1100));assertEquals(1,g.bars(CAR,1100).size());
        g.set(new PdcSnapshot(new int[]{2,2,0,0},80,0,1200));
        List<PdcBars.Bar> bars=g.bars(CAR,1200);assertEquals(2,bars.size());
        for(PdcBars.Bar bar:bars)assertEquals(1500+800-60,radius(bar),1e-6);
    }
    @Test public void missingMinimumUsesTablePositionsWithoutInventingAReportedCentimetreValue() {
        for(int invalid:new int[]{-1,0,255,999}) {
            PdcGeometry g=new PdcGeometry();g.set(new PdcSnapshot(new int[]{1,3,6,9},invalid,0,1000));
            List<PdcBars.Bar> bars=g.bars(CAR,1000);assertEquals(4,bars.size());
            int[] cm={15,50,95,150};
            for(int i=0;i<4;i++)assertEquals(1500+cm[i]*10-60,radius(bars.get(i)),1e-6);
            assertEquals(-1,g.snapshot().closestCm);assertTrue(g.hasEstimatedPositions(1000));
            assertTrue(g.bars(CAR,1501).isEmpty());assertFalse(g.hasEstimatedPositions(1501));
        }
    }
    @Test public void rivelaTableStillClearsUnknownSectorsAndPausedSnapshots() {
        PdcGeometry g=new PdcGeometry();
        for(PdcSnapshot sample:new PdcSnapshot[]{
                new PdcSnapshot(new int[]{1,-1,3,9},80,0,1000),
                new PdcSnapshot(new int[4],80,0,1000),PdcSnapshot.EMPTY}) {
            g.set(sample);assertTrue(g.bars(CAR,1000).isEmpty());assertFalse(g.hasEstimatedPositions(1000));
        }
    }
    @Test public void originalGroundProjectorProducesFiniteRearImagePolygons() {
        com.drivehub.kamera.birdseye.RivelaScene scene=new com.drivehub.kamera.birdseye.RivelaScene(
                com.drivehub.kamera.birdseye.BirdseyeCalibration.parse(com.drivehub.kamera.birdseye.BirdseyeSynthetic.calibrationBytes()));
        PdcGeometry g=new PdcGeometry();g.set(new PdcSnapshot(new int[]{1,0,0,0},80,0,1000));java.util.ArrayList<Integer> colors=new java.util.ArrayList<>();
        List<float[]> polys=GroundProjector.bars(scene.camera(3),g.bars(scene.car,1000),colors);assertFalse(polys.isEmpty());assertEquals(polys.size(),colors.size());
        for(float[] p:polys){assertTrue(p.length>=8);for(int i=0;i<p.length;i++)assertTrue(Float.isFinite(p[i])&&p[i]>=0&&p[i]<=((i&1)==0?720:480));}
    }
    private static double radius(PdcBars.Bar b){return Math.hypot(b.xy[0],b.xy[1]-(CAR.rear()-1500));}
}
