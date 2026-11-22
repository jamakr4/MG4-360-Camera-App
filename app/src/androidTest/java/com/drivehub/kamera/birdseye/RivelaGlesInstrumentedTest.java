package com.drivehub.kamera.birdseye;
import android.graphics.Bitmap;
import android.opengl.*;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.parking.*;
import com.drivehub.kamera.steering.WheelGuides;
import com.drivehub.kamera.steering.SteeringModel;
import com.drivehub.kamera.steering.GuidelinePolicy;
import com.drivehub.kamera.steering.VehicleMotionSnapshot;
import org.junit.*;
import org.junit.runner.RunWith;
import java.nio.*;
import java.io.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class RivelaGlesInstrumentedTest {
    private static final int W=450,H=640;
    private static VehicleModel model;
    @BeforeClass public static void loadPreparedModel() throws Exception {
        model=VehicleModel.load(InstrumentationRegistry.getInstrumentation().getTargetContext().getAssets());
        assertTrue(model.vertexCount<=300000);assertEquals(256,model.atlas.getWidth());
        assertEquals(5,model.parts.size());assertEquals(2,model.parts.stream().filter(p->p.steers).count());
    }
    @Test public void preparedPaintsMatchParkingChoicesAndPreserveEveryOtherMaterial() throws Exception {
        android.content.res.AssetManager assets=InstrumentationRegistry.getInstrumentation().getTargetContext().getAssets();
        org.json.JSONObject top;
        try(InputStream in=assets.open("mg4/birdseye/color-renders.json")) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] chunk=new byte[1024];int n;
            while((n=in.read(chunk))!=-1)bytes.write(chunk,0,n);
            top=new org.json.JSONObject(bytes.toString("UTF-8")).getJSONObject("variants");
        }
        int size=model.atlas.getWidth(),left=Math.round(model.paintRect[0]*size),right=Math.round(model.paintRect[2]*size),
                upper=Math.round(model.paintRect[1]*size),lower=Math.round(model.paintRect[3]*size);
        int[] reference=new int[size*size];model.atlas.getPixels(reference,0,size,0,0,size,size);
        int memory=0;
        for(BirdseyeCarColor choice:BirdseyeCarColor.values()) {
            VehicleModel.Paint paint=model.paint(choice);memory+=paint.atlas.getAllocationByteCount();
            org.json.JSONObject info=top.getJSONObject(choice.id);
            assertEquals(info.getDouble("metallic"),paint.metallic,.0001);
            assertEquals(info.getDouble("roughness"),paint.roughness,.0001);
            assertEquals(android.graphics.Color.parseColor(info.getString("swatch")),choice.swatch);
            int pixel=paint.atlas.getPixel((left+right)/2,(upper+lower)/2);
            for(int shift=0;shift<24;shift+=8)assertEquals((choice.swatch>>shift)&255,(pixel>>shift)&255,1);
            int[] pixels=new int[size*size];paint.atlas.getPixels(pixels,0,size,0,0,size,size);
            for(int y=0;y<size;y++)for(int x=0;x<size;x++)if(x<left||x>=right||y<upper||y>=lower)
                assertEquals("Non-paint material changed: "+choice.id,reference[y*size+x],pixels[y*size+x]);
            android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            try(InputStream in=assets.open(VehicleModel.DIRECTORY+"paints/"+choice.id+".png")) {
                android.graphics.BitmapFactory.decodeStream(in,null,bounds);
            }
            assertEquals(1024,bounds.outWidth);assertEquals(1024,bounds.outHeight);
        }
        assertTrue("All eight palettes must fit in 2 MiB",memory<=2*1024*1024);
        assertTrue(model.paint(BirdseyeCarColor.HUNTER_GREEN).roughness>.8);
        for(VehicleModel.Part part:model.parts)if(!part.name.equals("body"))
            for(int i=part.firstVertex*8;i<(part.firstVertex+part.vertexCount)*8;i+=8) {
                float u=model.vertices.get(i+6),v=1-model.vertices.get(i+7);
                assertFalse("Wheel assembly must not use the body-paint cell: "+part.name,
                        u>=model.paintRect[0]&&u<=model.paintRect[2]&&v>=model.paintRect[1]&&v<=model.paintRect[3]);
            }
    }
    @Test public void allEightPaintsRenderWithoutMovingRoofWheelsOrGroundAndSurviveContextLoss() throws Exception {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;frames.missingMask=15;
        RivelaScene scene=new RivelaScene(frames.calibration);scene.prepareBowl();BirdseyeRenderer r=renderer(frames,scene);
        java.util.List<ByteBuffer> images=new java.util.ArrayList<>();ByteBuffer green=null;
        for(int context=0;context<2;context++)try(Egl egl=new Egl()) {
            r.onSurfaceCreated(null,null);r.onSurfaceChanged(null,W,H);r.setThreeD(true);
            if(context==1) {
                r.onDrawFrame(null);assertTrue(r.threeDActive);assertNull(r.threeDError);
                assertEquals(0,differences(green,pixels(),new int[]{0,0,W,H}));continue;
            }
            for(BirdseyeCarColor choice:BirdseyeCarColor.values()) {
                r.setCarColor(choice);r.onDrawFrame(null);assertTrue(r.threeDActive);assertNull(r.threeDError);assertNull(r.error);
                ByteBuffer image=pixels();save(image,"rivela-color-"+choice.id+"-synthetic.png");
                for(ByteBuffer previous:images)assertTrue("Paints must render distinctly: "+choice.id,differences(previous,image,new int[]{0,0,W,H})>150);
                if(!images.isEmpty()) {
                    ByteBuffer reference=images.get(0);
                    for(float x:new float[]{-200,0,200})for(float y:new float[]{-300,0,300}) {
                        int[] roof=projectPoint(r,scene,x,y,-1540);
                        assertEquals("Black roof must be retained",color(reference,roof[0],roof[1]),color(image,roof[0],roof[1]));
                    }
                    // Wheel pivots can be occluded by painted arches from this view.
                    // The atlas test checks every wheel UV/material instead of sampling an occluded point.
                    assertEquals(color(reference,10,10),color(image,10,10));
                }
                images.add(image);green=image;
            }
            r.setCarColor(BirdseyeCarColor.HUNTER_GREEN);r.onDrawFrame(null);green=pixels();
        }
    }
    private static final class Egl implements AutoCloseable {
        EGLDisplay d;EGLContext c;EGLSurface s;
        Egl() {
            d=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);assertTrue(EGL14.eglInitialize(d,new int[2],0,new int[2],0));
            int[] attrs={EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_DEPTH_SIZE,16,
                EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_RENDERABLE_TYPE,4,EGL14.EGL_NONE};
            EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];assertTrue(EGL14.eglChooseConfig(d,attrs,0,configs,0,1,count,0));assertTrue(count[0]>0);
            c=EGL14.eglCreateContext(d,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
            s=EGL14.eglCreatePbufferSurface(d,configs[0],new int[]{EGL14.EGL_WIDTH,W,EGL14.EGL_HEIGHT,H,EGL14.EGL_NONE},0);
            assertTrue(EGL14.eglMakeCurrent(d,s,s,c));
        }
        public void close(){EGL14.eglMakeCurrent(d,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);EGL14.eglDestroySurface(d,s);EGL14.eglDestroyContext(d,c);EGL14.eglTerminate(d);}
    }
    private static ByteBuffer pixels(){ByteBuffer p=ByteBuffer.allocateDirect(W*H*4);GLES20.glReadPixels(0,0,W,H,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,p);GlPrograms.check();return p;}
    private static int color(ByteBuffer p,int x,int y){int i=((H-1-y)*W+x)*4;return ((p.get(i)&255)<<16)|((p.get(i+1)&255)<<8)|(p.get(i+2)&255);}
    private static void save(ByteBuffer data,String name) throws Exception {
        int[] p=new int[W*H];for(int y=0;y<H;y++)for(int x=0;x<W;x++)p[y*W+x]=0xFF000000|color(data,x,y);
        Bitmap b=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);b.setPixels(p,0,W,0,0,W,H);
        android.graphics.Canvas canvas=new android.graphics.Canvas(b);android.graphics.Paint label=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        label.setColor(0xDD000000);canvas.drawRect(0,0,W,30,label);label.setColor(0xFFFFFFFF);label.setTextSize(12);
        canvas.drawText("SYNTHETIC · not a vehicle camera image",8,20,label);
        try(FileOutputStream out=new FileOutputStream(new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(),name))){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}finally{b.recycle();}
    }
    @Test public void aCompleted3dViewDoesNotAdvertiseLoadingWhileItsNextFrameIsBeingDrawn() throws Exception {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();
        RivelaScene scene=new RivelaScene(frames.calibration);scene.prepareBowl();BirdseyeRenderer r=renderer(frames,scene);
        java.util.concurrent.atomic.AtomicBoolean watching=new java.util.concurrent.atomic.AtomicBoolean(true),
                falsePulse=new java.util.concurrent.atomic.AtomicBoolean();
        try(Egl egl=new Egl()) {
            r.onSurfaceCreated(null,null);r.onSurfaceChanged(null,W,H);r.setThreeD(true);r.onDrawFrame(null);
            assertTrue(r.threeDActive);
            java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1);
            Thread observer=new Thread(()->{started.countDown();while(watching.get()){if(!r.threeDActive)falsePulse.set(true);Thread.yield();}});
            observer.start();assertTrue(started.await(2,java.util.concurrent.TimeUnit.SECONDS));
            try{for(int frame=0;frame<20;frame++){r.onDrawFrame(null);assertNull(r.error);assertTrue(r.threeDActive);}}
            finally{watching.set(false);observer.join(2000);}
            assertFalse("In-flight rendering must not make the UI show a full-screen Loading overlay",falsePulse.get());
        }
    }
    private static BirdseyeRenderer renderer(BirdseyeSynthetic frames,RivelaScene scene) {
        // Static geometry comparisons intentionally use a zero-duration reveal. Animation is checked with a controlled clock below.
        BirdseyeRenderer r=new BirdseyeRenderer(frames,null,()->0L,0);r.setMesh(new BirdseyeMesh(frames.calibration));r.setScene(scene);r.setModel(model);r.setGuideStyle(WheelGuides.Style.COLORED);return r;
    }
    @Test public void packedGroundBowlModelAndPdcSurviveContextRecreation() throws Exception {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;
        RivelaScene scene=new RivelaScene(frames.calibration);scene.prepareBowl();BirdseyeRenderer r=renderer(frames,scene);
        PdcGeometry pdc=SyntheticRearPdc.geometry();r.setPdcGeometry(pdc);
        for(int context=0;context<2;context++)try(Egl egl=new Egl()) {
            r.onSurfaceCreated(null,null);r.onSurfaceChanged(null,W,H);r.setThreeD(true);r.onDrawFrame(null);
            assertNull(r.error);assertNull(r.threeDError);assertTrue(r.depthAvailable);assertTrue(r.threeDActive);assertEquals(15,r.freshMask);
            ByteBuffer before=pixels();save(before,"rivela-3d-synthetic.png");
            int[] point=rearPoint(r,scene,0,3100);int expected=BirdseyeSynthetic.groundColor(0,3100),actual=color(before,point[0],point[1]);
            for(int shift=0;shift<24;shift+=8)assertEquals((expected>>shift)&255,(actual>>shift)&255,7);
            long now=SystemClock.elapsedRealtime();pdc.set(SyntheticRearPdc.sample(now));assertEquals(4,pdc.bars(scene.car,now).size());r.onDrawFrame(null);
            save(pixels(),"rivela-3d-pdc-synthetic.png");
            assertPdcColorsVisible(pixels());
            r.setThreeD(false);r.onDrawFrame(null);assertFalse(r.threeDActive);assertEquals(15,r.freshMask);
            assertPdcColorsVisible(pixels());save(pixels(),"rivela-2d-pdc-synthetic.png");
            pdc.set(PdcSnapshot.EMPTY);frames.missingMask=15;frames.fixedClockUs+=BirdseyeFrameHealth.DISPLAY_HOLD_US+1;r.setThreeD(true);r.onDrawFrame(null);
            assertNull(r.error);assertTrue(r.threeDActive);assertEquals(0,r.freshMask);assertEquals(0,color(pixels(),point[0],point[1]));
            frames.missingMask=0;
        }
    }
    @Test public void linesStyleDrawsFourBluePathsWithoutFillInNormalWideAndThreeD() throws Exception {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;
        ProjectionAssets projection=ProjectionAssets.get(frames.calibration);projection.scene.prepareBowl();
        RivelaScene scene=projection.scene;BirdseyeRenderer r=renderer(frames,scene);r.setWideMesh(projection.wideMesh,null);
        r.orbit.rotate(-35/.35f,(75-55)/.25f);r.orbit.zoom(11000f/16000);
        com.drivehub.kamera.steering.WheelGuides guides=new com.drivehub.kamera.steering.WheelGuides();
        com.drivehub.kamera.steering.SteeringModel.Solution pose=new com.drivehub.kamera.steering.SteeringModel().solve(-441.3f);
        try(Egl egl=new Egl()) {
            r.onSurfaceCreated(null,null);r.onSurfaceChanged(null,W,H);
            for(int view=0;view<3;view++)for(com.drivehub.kamera.steering.WheelGuides.Direction direction:com.drivehub.kamera.steering.WheelGuides.Direction.values()) {
                r.setThreeD(view==2);r.setWide(view==1);
                r.steering.setSolution(pose,true,direction);r.setGuideStyle(com.drivehub.kamera.steering.WheelGuides.Style.LINES);
                r.setWheelGuidePreview(true);r.onDrawFrame(null);ByteBuffer lines=pixels();
                assertNull(r.error);assertNull(r.threeDError);
                r.setWheelGuidePreview(false);r.onDrawFrame(null);ByteBuffer background=pixels();
                int changed=differences(lines,background,new int[]{0,0,W,H});assertTrue(changed>100);
                BirdseyeMesh mesh=view==1?projection.wideMesh:projection.mesh;
                for(com.drivehub.kamera.rivela.PdcBars.Bar path:guides.clearanceLines(pose,scene.car.centerX,scene.car.centerY,direction)) {
                    boolean found=false;
                    for(int i=(path.xy.length/4)*2;i<path.xy.length*.85-2&&!found;i+=2) {
                        int[] p=view==2?projectPoint(r,scene,(float)path.xy[i],(float)path.xy[i+1],-2):new int[]{
                                Math.round((float)((path.xy[i]-scene.car.centerX)/mesh.groundWidth+.5)*W),
                                Math.round((float)((path.xy[i+1]-scene.car.centerY)/mesh.groundHeight+.5)*H)};
                        for(int x=Math.max(0,p[0]-3);x<=Math.min(W-1,p[0]+3);x++)for(int y=Math.max(0,p[1]-3);y<=Math.min(H-1,p[1]+3);y++) {
                            int c=color(lines,x,y);
                            if(c!=color(background,x,y)&&Math.abs(((c>>16)&255)-115)<=3&&Math.abs(((c>>8)&255)-217)<=3&&(c&255)>=252)found=true;
                        }
                    }
                    assertTrue("Missing blue wheel path in view="+view+" "+direction,found);
                }
                r.setWheelGuidePreview(true);r.setGuideStyle(com.drivehub.kamera.steering.WheelGuides.Style.SIMPLE);r.onDrawFrame(null);
                assertTrue("Lines must omit the filled area",differences(pixels(),background,new int[]{0,0,W,H})>changed*2);
                save(lines,"guidelines-lines-"+view+"-"+direction+".png");
            }
        }
    }
    @Test public void speedThresholdReversesTheSameGroundSweepForBothStylesAndAllViews() throws Exception {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;
        ProjectionAssets projection=ProjectionAssets.get(frames.calibration);projection.scene.prepareBowl();
        long[] clock={1000};long duration=GuidelineAnimation.DURATION_MS;
        BirdseyeRenderer r=new BirdseyeRenderer(frames,null,()->clock[0],duration);
        r.setMesh(projection.mesh);r.setWideMesh(projection.wideMesh,null);r.setScene(projection.scene);r.setModel(model);
        r.orbit.rotate(-35/.35f,(75-55)/.25f);r.orbit.zoom(11000f/16000);
        try(Egl egl=new Egl()) {
            r.onSurfaceCreated(null,null);r.onSurfaceChanged(null,W,H);
            for(int view=0;view<3;view++)for(WheelGuides.Style style:new WheelGuides.Style[]{WheelGuides.Style.LINES,WheelGuides.Style.SIMPLE})
                for(WheelGuides.Direction direction:WheelGuides.Direction.values()) {
                    String label=view+"-"+style+"-"+direction;
                    r.setThreeD(view==2);r.setWide(view==1);r.setGuideStyle(style);
                    r.steering.setSolution(new SteeringModel().solve(0),true,direction);
                    r.setWheelGuidePreview(false);clock[0]+=duration*2;r.onDrawFrame(null);ByteBuffer background=pixels();
                    r.setWheelGuidePreview(GuidelinePolicy.visible(true,true,25,VehicleMotionSnapshot.value(25,2,clock[0]),clock[0]));
                    r.onDrawFrame(null);assertEquals(label,0,differences(background,pixels(),new int[]{0,0,W,H}));
                    clock[0]+=duration*3/5;r.onDrawFrame(null);ByteBuffer entering=pixels();
                    clock[0]+=duration*2/5;r.onDrawFrame(null);ByteBuffer full=pixels();
                    int partial=differences(entering,background,new int[]{0,0,W,H}),complete=differences(full,background,new int[]{0,0,W,H});
                    assertTrue(label+" must build progressively",partial>20&&complete>partial+20);
                    if(view<2) {
                        double near=changedMeanY(entering,background),far=changedMeanY(full,background);
                        assertTrue(label+" wrong build direction",direction==WheelGuides.Direction.REVERSE?near<far:near>far);
                    }
                    r.setWheelGuidePreview(GuidelinePolicy.visible(true,true,25,VehicleMotionSnapshot.value(25.1f,2,clock[0]),clock[0]));
                    r.onDrawFrame(null);assertEquals(label,0,differences(full,pixels(),new int[]{0,0,W,H}));
                    clock[0]+=duration*2/5;r.onDrawFrame(null);ByteBuffer leaving=pixels();
                    assertEquals(label+" hide must reverse the reveal",0,differences(entering,leaving,new int[]{0,0,W,H}));
                    clock[0]+=duration*3/5;r.onDrawFrame(null);assertEquals(label,0,differences(background,pixels(),new int[]{0,0,W,H}));
                    assertNull(r.error);assertNull(r.threeDError);
                    save(entering,"guideline-animation-"+label+"-partial.png");save(full,"guideline-animation-"+label+"-full.png");
                }
        }
    }
    @Test public void gearChangesDrawBothDirectionsTogetherAndNeverWaitForTheOutgoingPath() throws Exception {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;
        ProjectionAssets projection=ProjectionAssets.get(frames.calibration);projection.scene.prepareBowl();
        long[] clock={1000};long duration=GuidelineAnimation.DURATION_MS;
        BirdseyeRenderer r=new BirdseyeRenderer(frames,null,()->clock[0],duration);
        r.setMesh(projection.mesh);r.setWideMesh(projection.wideMesh,null);r.setScene(projection.scene);r.setModel(model);
        r.orbit.rotate(-35/.35f,(75-55)/.25f);r.orbit.zoom(11000f/16000);
        SteeringModel.Solution pose=new SteeringModel().solve(0);
        try(Egl egl=new Egl()) {
            r.onSurfaceCreated(null,null);r.onSurfaceChanged(null,W,H);
            for(int view=0;view<3;view++)for(WheelGuides.Style style:new WheelGuides.Style[]{WheelGuides.Style.LINES,WheelGuides.Style.SIMPLE})
                for(WheelGuides.Direction from:WheelGuides.Direction.values()) {
                    WheelGuides.Direction to=from==WheelGuides.Direction.REVERSE?WheelGuides.Direction.FORWARD:WheelGuides.Direction.REVERSE;
                    String label=view+"-"+style+"-"+from;
                    r.setThreeD(view==2);r.setWide(view==1);r.setGuideStyle(style);
                    r.steering.setSolution(pose,true,from);r.setWheelGuidePreview(false);clock[0]+=duration*2;r.onDrawFrame(null);ByteBuffer background=pixels();
                    r.setWheelGuidePreview(true);r.onDrawFrame(null);clock[0]+=duration;r.onDrawFrame(null);ByteBuffer original=pixels();
                    r.steering.setSolution(pose,true,to);r.onDrawFrame(null);
                    assertEquals(label+" must retain the visible outgoing path",0,differences(original,pixels(),new int[]{0,0,W,H}));
                    assertEquals(0,r.guideProgress,0);assertEquals(1,r.outgoingGuideProgress,0);
                    clock[0]+=duration/2;r.onDrawFrame(null);ByteBuffer mixed=pixels();
                    assertEquals(label,.5,r.guideProgress,1e-6);assertEquals(label,.5,r.outgoingGuideProgress,1e-6);
                    // The held camera image stays the same; only the stale outgoing prediction disappears.
                    frames.missingMask=from==WheelGuides.Direction.REVERSE?8:4;frames.fixedClockUs+=BirdseyeFrameHealth.MAX_AGE_US+1;
                    r.onDrawFrame(null);ByteBuffer incomingOnly=pixels();
                    assertTrue(label+" must actually draw the outgoing layer during the shared transition",
                            differences(mixed,incomingOnly,new int[]{0,0,W,H})>10);
                    assertEquals(0,r.outgoingGuideProgress,0);assertEquals(.5,r.guideProgress,1e-6);
                    float y=to==WheelGuides.Direction.FORWARD?-3000:3000;
                    BirdseyeMesh mesh=view==1?projection.wideMesh:projection.mesh;
                    int[] point=view==2?projectPoint(r,projection.scene,1030,y,-2):new int[]{
                            Math.round((float)((1030-projection.scene.car.centerX)/mesh.groundWidth+.5)*W),
                            Math.round((float)((y-projection.scene.car.centerY)/mesh.groundHeight+.5)*H)};
                    assertTrue(label+" must blend the complete incoming path instead of replaying its build",
                            differences(incomingOnly,background,new int[]{point[0]-4,point[1]-4,point[0]+5,point[1]+5})>0);
                    frames.missingMask=0;r.onDrawFrame(null);
                    assertEquals(label+" stale outgoing geometry must not reappear",0,differences(incomingOnly,pixels(),new int[]{0,0,W,H}));
                    clock[0]+=duration-duration/2;r.onDrawFrame(null);
                    assertEquals(1,r.guideProgress,0);assertEquals(0,r.outgoingGuideProgress,0);
                    assertNull(r.error);assertNull(r.threeDError);
                    save(mixed,"guideline-gear-transition-"+label+".png");
                }
        }
    }
    @Test public void widthChangesRebuildBothStylesInEveryViewWithoutChangingSteering() throws Exception {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;
        ProjectionAssets projection=ProjectionAssets.get(frames.calibration);projection.scene.prepareBowl();
        BirdseyeRenderer r=new BirdseyeRenderer(frames,null,()->0L,0);
        r.setMesh(projection.mesh);r.setWideMesh(projection.wideMesh,null);r.setScene(projection.scene);r.setModel(model);
        r.orbit.rotate(-35/.35f,(75-55)/.25f);r.orbit.zoom(11000f/16000);
        r.setWheelGuidePreview(true);
        try(Egl egl=new Egl()) {
            r.onSurfaceCreated(null,null);r.onSurfaceChanged(null,W,H);
            for(int view=0;view<3;view++)for(WheelGuides.Style style:new WheelGuides.Style[]{WheelGuides.Style.LINES,WheelGuides.Style.SIMPLE})
                for(WheelGuides.Direction direction:WheelGuides.Direction.values()) {
                    String label=view+"-"+style+"-"+direction;
                    r.setThreeD(view==2);r.setWide(view==1);r.setGuideStyle(style);
                    SteeringModel.Solution pose=new SteeringModel().solve(0);
                    r.steering.setSolution(pose,true,direction);
                    r.setGuideWidthMm(2060);r.onDrawFrame(null);ByteBuffer mirrors=pixels();
                    r.setGuideWidthMm(1836);r.onDrawFrame(null);ByteBuffer body=pixels();
                    assertTrue(label+" body width must change visible pixels",differences(mirrors,body,new int[]{0,0,W,H})>20);
                    r.setGuideWidthMm(3000);r.onDrawFrame(null);ByteBuffer custom=pixels();
                    assertTrue(label+" custom width must change visible pixels",differences(body,custom,new int[]{0,0,W,H})>20);
                    r.setGuideWidthMm(2060);r.onDrawFrame(null);
                    assertEquals(label+" returning to mirrors must restore the original picture",0,differences(mirrors,pixels(),new int[]{0,0,W,H}));
                    assertEquals(pose.leftDegrees,r.steering.angles().left,0);assertEquals(pose.rightDegrees,r.steering.angles().right,0);
                    r.setGuideWidthMm(3000);
                    for(float lock:new float[]{-516.95f,516.95f}) {
                        r.steering.setSolution(new SteeringModel().solve(lock),true,direction);r.onDrawFrame(null);
                        assertNull(label,r.error);assertNull(label,r.threeDError);
                        assertEquals(label,view==2,r.threeDActive);
                    }
                }
        }
    }
    @Test public void gearTransitionsRetainEachLayersWidthUntilThatDirectionUpdates() throws Exception {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;
        ProjectionAssets projection=ProjectionAssets.get(frames.calibration);projection.scene.prepareBowl();
        long[] clock={1000};BirdseyeRenderer r=new BirdseyeRenderer(frames,null,()->clock[0],350);
        r.setMesh(projection.mesh);r.setScene(projection.scene);r.setModel(model);r.setGuideStyle(WheelGuides.Style.SIMPLE);
        r.setWheelGuidePreview(true);
        java.lang.reflect.Field field=BirdseyeRenderer.class.getDeclaredField("guideFrame");field.setAccessible(true);
        try(Egl egl=new Egl()) {
            r.onSurfaceCreated(null,null);r.onSurfaceChanged(null,W,H);
            r.setGuideWidthMm(1836);r.steering.setSolution(new SteeringModel().solve(0),true,WheelGuides.Direction.REVERSE);
            r.onDrawFrame(null);clock[0]+=350;r.onDrawFrame(null);
            r.setGuideWidthMm(3000);r.steering.setSolution(new SteeringModel().solve(0),true,WheelGuides.Direction.FORWARD);
            r.onDrawFrame(null);clock[0]+=175;r.onDrawFrame(null);
            GuidelineFrame frame=(GuidelineFrame)field.get(r);
            assertEquals(1836,frame.outgoing().widthMm);assertEquals(3000,frame.incoming().widthMm);
            assertEquals(.5,frame.outgoing().level,1e-6);assertEquals(.5,frame.incoming().level,1e-6);
            r.setGuideWidthMm(2500);r.onDrawFrame(null);
            assertEquals(1836,frame.outgoing().widthMm);assertEquals(2500,frame.incoming().widthMm);
            assertNull(r.error);assertNull(r.threeDError);
        }
    }
    private static double changedMeanY(ByteBuffer picture,ByteBuffer background) {
        long sum=0,count=0;
        for(int y=0;y<H;y++)for(int x=0;x<W;x++)if(color(picture,x,y)!=color(background,x,y)){sum+=y;count++;}
        return sum/(double)count;
    }
    @Test public void allFourIndividualWheelPathsFadeAtTheirFarEndsInBothGears() {
        try(Egl egl=new Egl()) {
            RivelaScene scene=new RivelaScene(new BirdseyeSynthetic().calibration);
            WheelGuidePass pass=new WheelGuidePass();WheelGuides guides=new WheelGuides();
            float[] mvp=new float[16];Matrix.orthoM(mvp,0,-6500,6500,8000,-8000,-100,100);
            try {
                for(WheelGuides.Direction direction:WheelGuides.Direction.values())for(float raw:new float[]{-441.3f,441.3f}) {
                    GLES20.glViewport(0,0,W,H);GLES20.glClearColor(0,0,0,1);GLES20.glDepthMask(true);
                    GLES20.glDisable(GLES20.GL_DEPTH_TEST);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);
                    SteeringModel.Solution pose=new SteeringModel().solve(raw);WheelSteering steering=new WheelSteering();steering.setSolution(pose,true,direction);
                    pass.draw(mvp,scene,steering.angles(),direction,WheelGuides.Style.LINES,false,1);
                    ByteBuffer picture=pixels();
                    for(com.drivehub.kamera.rivela.PdcBars.Bar path:guides.clearanceLines(pose,scene.car.centerX,scene.car.centerY,direction)) {
                        int nearEnd=blueAlongPath(picture,path.xy,150),beforeFade=blueAlongPath(picture,path.xy,1100);
                        assertTrue("Far end must fade: "+direction+" "+raw+" "+nearEnd+"/"+beforeFade,nearEnd<200&&beforeFade>240);
                    }
                }
            }finally{pass.close();}
        }
    }
    private static int blueAlongPath(ByteBuffer picture,double[] xy,double remaining) {
        double x=xy[0],y=xy[1];
        for(int i=xy.length-2;i>=2;i-=2) {
            double length=Math.hypot(xy[i]-xy[i-2],xy[i+1]-xy[i-1]);
            if(remaining<=length) {double t=remaining/length;x=xy[i]+t*(xy[i-2]-xy[i]);y=xy[i+1]+t*(xy[i-1]-xy[i+1]);break;}
            remaining-=length;
        }
        int px=Math.round((float)((x+6500)/13000*W)),py=Math.round((float)((y+8000)/16000*H)),blue=0;
        for(int xx=Math.max(0,px-2);xx<=Math.min(W-1,px+2);xx++)for(int yy=Math.max(0,py-2);yy<=Math.min(H-1,py+2);yy++)blue=Math.max(blue,color(picture,xx,yy)&255);
        return blue;
    }
    @Test public void cameraGapsHoldForTwoSecondsAndUseTheFirstRecoveryFrameInEveryView() throws Exception {
        for(String mode:new String[]{"normal","wide","3d"})try(Egl egl=new Egl()) {
            BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;
            int[] luma={-1};
            BirdseyeRenderer.Frames source=new BirdseyeRenderer.Frames(){
                public boolean allowed(){return frames.allowed();}
                public long clockUs(){return frames.clockUs();}
                public long copy(int camera,ByteBuffer into,long afterUs){
                    long receipt=frames.copy(camera,into,afterUs);
                    if(receipt>0&&luma[0]>=0)for(int i=0;i<into.capacity();i+=4){
                        into.put(i,(byte)128);into.put(i+1,(byte)luma[0]);
                        into.put(i+2,(byte)128);into.put(i+3,(byte)luma[0]);
                    }
                    return receipt;
                }
            };
            ProjectionAssets projection=ProjectionAssets.get(frames.calibration);projection.scene.prepareBowl();
            BirdseyeRenderer r=new BirdseyeRenderer(source);
            r.setScene(projection.scene);r.setModel(model);r.setMesh(projection.mesh);r.setWideMesh(projection.wideMesh,null);
            r.setThreeD(mode.equals("3d"));r.setWide(mode.equals("wide"));
            r.onSurfaceCreated(null,null);r.onSurfaceChanged(null,W,H);r.onDrawFrame(null);
            assertNull(r.error);assertNull(r.threeDError);assertEquals(mode.equals("3d"),r.threeDActive);
            assertEquals(mode.equals("wide"),r.wideActive);assertEquals(15,r.displayMask);
            ByteBuffer original=pixels();int[] all={0,0,W,H};

            frames.missingMask=15;frames.fixedClockUs+=200_000;r.onDrawFrame(null);
            assertEquals(mode,15,r.displayMask);assertEquals(mode,0,differences(original,pixels(),all));
            frames.fixedClockUs+=1_800_000;r.onDrawFrame(null);
            assertEquals(0,r.freshMask);assertEquals(mode,15,r.displayMask);
            assertEquals(mode,0,differences(original,pixels(),all));
            frames.fixedClockUs++;r.onDrawFrame(null);
            assertEquals(mode,0,r.displayMask);assertTrue(mode,differences(original,pixels(),all)>100);

            // The very first real frame after expiry must be uploaded and shown immediately.
            frames.missingMask=0;luma[0]=235;frames.fixedClockUs++;r.onDrawFrame(null);
            assertEquals(15,r.freshMask);assertEquals(15,r.displayMask);
            ByteBuffer white=pixels();assertTrue(mode,differences(original,white,all)>100);
            // Legitimately black camera data is a frame too, not a missing-image signal.
            luma[0]=16;frames.fixedClockUs++;r.onDrawFrame(null);
            assertEquals(15,r.displayMask);ByteBuffer black=pixels();assertTrue(mode,differences(white,black,all)>100);

            // Holding one camera must leave all other streams free to update.
            frames.missingMask=4;luma[0]=235;frames.fixedClockUs+=200_000;r.onDrawFrame(null);
            assertEquals(11,r.freshMask);assertEquals(15,r.displayMask);
            assertTrue(mode,differences(black,pixels(),all)>100);
            frames.fixedClockUs+=1_800_000;r.onDrawFrame(null);assertEquals(15,r.displayMask);
            frames.fixedClockUs++;r.onDrawFrame(null);assertEquals(11,r.displayMask);

            // Ownership loss and a new EGL context never reuse held images.
            frames.enabled=false;r.onDrawFrame(null);assertEquals(0,r.displayMask);assertEquals(0,r.freshMask);
            frames.enabled=true;frames.missingMask=15;r.onSurfaceCreated(null,null);r.onDrawFrame(null);
            assertEquals(0,r.displayMask);assertEquals(0,r.freshMask);assertNull(r.error);
            frames.missingMask=0;frames.fixedClockUs++;r.onDrawFrame(null);assertEquals(15,r.displayMask);
        }
    }
    @Test public void missingDepthFallsBackToProvenTwoDWithoutStoppingFrames() {
        try(Egl egl=new Egl()) {
            // An impossible depth request forces the actual EGL chooser's fallback on this driver.
            EGLConfig fallback=BirdseyeSurface.chooseWindowConfig(egl.d,65536);int[] attribute=new int[1];
            assertTrue(EGL14.eglGetConfigAttrib(egl.d,fallback,EGL14.EGL_RENDERABLE_TYPE,attribute,0));assertTrue((attribute[0]&4)!=0);
            assertTrue(EGL14.eglGetConfigAttrib(egl.d,fallback,EGL14.EGL_SURFACE_TYPE,attribute,0));assertTrue((attribute[0]&EGL14.EGL_WINDOW_BIT)!=0);
            EGLContext context=EGL14.eglCreateContext(egl.d,fallback,EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
            assertNotEquals(EGL14.EGL_NO_CONTEXT,context);assertTrue(EGL14.eglDestroyContext(egl.d,context));
            BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;
            RivelaScene scene=new RivelaScene(frames.calibration);scene.prepareBowl();BirdseyeRenderer r=renderer(frames,scene);
            r.onSurfaceCreated(null,null);r.onSurfaceChanged(null,W,H);r.depthAvailable=false;r.setThreeD(true);r.onDrawFrame(null);
            assertNull(r.error);assertNotNull(r.threeDError);assertFalse(r.threeDActive);assertFalse(r.threeDRequested);assertEquals(15,r.freshMask);
            assertEquals(ThreeDFailure.Kind.DEPTH,r.threeDFailure.kind);
            assertTrue(r.threeDError.contains("No 16-bit depth buffer"));
            assertEquals(0,color(pixels(),W/2,H/2));
        }
    }
    @Test public void centralRearProjectionMatchesSurfaceStretchAndMirror() {
        assertArrayEquals(new float[]{719*930f/720,0},PdcOverlayView.imageToView(0,0,930,640),.001f);
        assertArrayEquals(new float[]{0,640},PdcOverlayView.imageToView(719,480,930,640),.001f);
    }
    @Test public void steeringMovesBothFrontWheelsWhileRearAndBodyStayFixed() throws Exception {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;frames.missingMask=15;
        RivelaScene scene=new RivelaScene(frames.calibration);scene.prepareBowl();BirdseyeRenderer r=renderer(frames,scene);
        r.orbit.rotate((180-35)/.35f,(75-55)/.25f);r.orbit.zoom(11000f/8000);
        ByteBuffer afterRight=null;
        for(int context=0;context<2;context++)try(Egl egl=new Egl()) {
            r.onSurfaceCreated(null,null);r.onSurfaceChanged(null,W,H);r.setThreeD(true);
            if(context==1) {
                r.onDrawFrame(null);assertTrue(r.threeDActive);assertNull(r.threeDError);
                assertEquals(0,differences(afterRight,pixels(),new int[]{0,0,W,H}));continue;
            }
            r.steering.setDegrees(0);r.onDrawFrame(null);ByteBuffer straight=pixels();save(straight,"rivela-steering-straight-synthetic.png");
            r.steering.setDegrees(30);r.onDrawFrame(null);afterRight=pixels();save(afterRight,"rivela-steering-right-synthetic.png");
            java.util.List<int[]> movingRects=new java.util.ArrayList<>();
            for(VehicleModel.Part part:model.parts)if(!part.name.equals("body")) {
                int[] rect=wheelRect(r,scene,part);
                assertTrue("Wheel outside test viewport",rect[2]>rect[0]&&rect[3]>rect[1]);
                if(part.steers){movingRects.add(rect);assertTrue("Front wheel did not move: "+part.name,differences(straight,afterRight,rect)>10);}
                else assertEquals("Rear wheel moved: "+part.name,0,differences(straight,afterRight,rect));
            }
            for(int y=0;y<H;y++)for(int x=0;x<W;x++) {
                boolean wheel=false;for(int[] rect:movingRects)if(x>=rect[0]&&x<rect[2]&&y>=rect[1]&&y<rect[3])wheel=true;
                if(!wheel)assertEquals("Body/background changed outside front wheel regions",color(straight,x,y),color(afterRight,x,y));
            }
            r.steering.setDegrees(-30);r.onDrawFrame(null);ByteBuffer left=pixels();save(left,"rivela-steering-left-synthetic.png");
            assertTrue(differences(afterRight,left,new int[]{0,0,W,H})>100);
            r.steering.setDegrees(0);r.onDrawFrame(null);assertEquals(0,differences(straight,pixels(),new int[]{0,0,W,H}));
            r.steering.setDegrees(30);
        }
    }
    private static int differences(ByteBuffer a,ByteBuffer b,int[] rect) {
        int count=0;for(int y=rect[1];y<rect[3];y++)for(int x=rect[0];x<rect[2];x++)if(color(a,x,y)!=color(b,x,y))count++;return count;
    }
    @Test public void liveGearPublicationControlsTheBlueAreaAcrossThreeDAndTwoD() throws Exception {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;
        RivelaScene scene=ProjectionAssets.get(frames.calibration).scene;scene.prepareBowl();BirdseyeRenderer r=renderer(frames,scene);
        r.orbit.rotate(-35/.35f,(75-55)/.25f);r.orbit.zoom(11000f/16000);
        com.drivehub.kamera.steering.SteeringGuidance guidance=new com.drivehub.kamera.steering.SteeringGuidance();
        try(Egl egl=new Egl()) {
            r.onSurfaceCreated(null,null);r.onSurfaceChanged(null,W,H);r.setThreeD(true);
            r.setWheelGuidePreview(true);r.setBlueGuideCorridor(true);
            r.setGuideDirection(com.drivehub.kamera.steering.WheelGuides.Direction.FORWARD); // The live publication must override this.
            publishLive(r,guidance,0,2,true,1000);r.onDrawFrame(null);ByteBuffer reverse=pixels();
            assertNull(r.error);assertNull(r.threeDError);assertTrue(r.threeDActive);
            r.setWheelGuidePreview(false);r.onDrawFrame(null);ByteBuffer without=pixels();
            assertTrue(differences(reverse,without,new int[]{0,0,W,H})>200);
            for(float x:new float[]{-725,725}) {
                int[] point=projectPoint(r,scene,x,5239.5f,-2);
                assertTrue("Production floor hides far guide endpoint",differences(reverse,without,
                        new int[]{point[0]-4,point[1]-4,point[0]+5,point[1]+5})>0);
            }
            r.setWheelGuidePreview(true);frames.missingMask=8;frames.fixedClockUs+=BirdseyeFrameHealth.MAX_AGE_US+1;
            r.onDrawFrame(null);ByteBuffer missingRear=pixels();r.setWheelGuidePreview(false);r.onDrawFrame(null);
            assertEquals(0,differences(missingRear,pixels(),new int[]{0,0,W,H}));
            r.setWheelGuidePreview(true);r.setGuideDirection(com.drivehub.kamera.steering.WheelGuides.Direction.REVERSE);
            publishLive(r,guidance,0,4,true,1010);r.onDrawFrame(null);ByteBuffer forward=pixels();
            r.setWheelGuidePreview(false);r.onDrawFrame(null);assertTrue(differences(forward,pixels(),new int[]{0,0,W,H})>100);
            r.setWheelGuidePreview(true);frames.missingMask=4;frames.fixedClockUs+=BirdseyeFrameHealth.MAX_AGE_US+1;
            r.onDrawFrame(null);ByteBuffer missingFront=pixels();r.setWheelGuidePreview(false);r.onDrawFrame(null);
            assertEquals(0,differences(missingFront,pixels(),new int[]{0,0,W,H}));
            frames.missingMask=0;r.setWheelGuidePreview(true);r.setThreeD(false);publishLive(r,guidance,0,4,true,1020);
            r.onDrawFrame(null);ByteBuffer twoD=pixels();
            r.setWheelGuidePreview(false);r.onDrawFrame(null);
            assertFalse(r.threeDActive);assertTrue(differences(twoD,pixels(),new int[]{0,0,W,H})>100);
            r.setWheelGuidePreview(true);r.setThreeD(true);r.onDrawFrame(null);
            assertTrue(r.threeDActive);assertNull(r.error);assertNull(r.threeDError);
            save(reverse,"rivela-live-reverse-guidance.png");save(forward,"rivela-live-forward-guidance.png");
        }
    }
    private static void publishLive(BirdseyeRenderer renderer,com.drivehub.kamera.steering.SteeringGuidance guidance,
                                    float raw,int gear,boolean active,long now) {
        com.drivehub.kamera.steering.SteeringGuidance.Frame frame=guidance.update(
                com.drivehub.kamera.steering.SteeringSnapshot.value(raw,0,now,1),
                com.drivehub.kamera.steering.GearSnapshot.value(gear,now),active,now);
        renderer.steering.setSolution(frame.wheels,frame.guidanceAvailable,frame.direction);
    }
    @Test public void corridorFillUsesSourceAlphaOnceAndRestoresDepthAndBlendState() {
        try(Egl egl=new Egl()) {
            GLES20.glViewport(0,0,W,H);GLES20.glClearColor(40/255f,60/255f,80/255f,1);
            GLES20.glDepthMask(true);GLES20.glEnable(GLES20.GL_DEPTH_TEST);GLES20.glDepthFunc(GLES20.GL_LEQUAL);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);
            RivelaScene scene=new RivelaScene(new BirdseyeSynthetic().calibration);
            float[] mvp=new float[16];Matrix.orthoM(mvp,0,-1000,1000,-1000,1000,-100,100);
            PdcGroundPass pass=new PdcGroundPass();
            try {
                pass.drawFill(mvp,scene,new double[]{-800,-800,800,-800,-800,800,-800,800,800,-800,800,800},
                        com.drivehub.kamera.steering.WheelGuides.CORRIDOR_FILL_COLOR);
                ByteBuffer picture=pixels();
                // RGB = source*(85/255) + background*(170/255), including pixels on the shared diagonal.
                for(int x:new int[]{110,225,340})for(int y:new int[]{200,320,440}) {
                    int actual=color(picture,x,y);
                    assertEquals(41,(actual>>16)&255,1);assertEquals(88,(actual>>8)&255,1);assertEquals(138,actual&255,1);
                }
                assertFalse(GLES20.glIsEnabled(GLES20.GL_BLEND));
                int[] depth=new int[1];GLES20.glGetIntegerv(GLES20.GL_DEPTH_WRITEMASK,depth,0);assertEquals(1,depth[0]);
            }finally{pass.close();}
        }
    }
    @Test public void blueBoundariesIncludeBothAxlesWithOneTranslucentFill() {
        try(Egl egl=new Egl()) {
            GLES20.glViewport(0,0,W,H);GLES20.glDisable(GLES20.GL_CULL_FACE);GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);GLES20.glDepthFunc(GLES20.GL_LEQUAL);GLES20.glDepthMask(true);
            RivelaScene scene=new RivelaScene(new BirdseyeSynthetic().calibration,4000);
            com.drivehub.kamera.steering.WheelGuides guides=new com.drivehub.kamera.steering.WheelGuides();
            com.drivehub.kamera.steering.SteeringModel solver=new com.drivehub.kamera.steering.SteeringModel();
            float[] mvp=new float[16];Matrix.orthoM(mvp,0,-1200,1200,2500,-1800,-100,100);
            PdcGroundPass pass=new PdcGroundPass();
            try {
                for(com.drivehub.kamera.steering.WheelGuides.Direction direction:com.drivehub.kamera.steering.WheelGuides.Direction.values())
                    for(float raw:new float[]{-.45f,0,.45f}) {
                    GLES20.glClearColor(0,0,0,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);
                    com.drivehub.kamera.steering.SteeringModel.Solution pose=solver.solve(raw);
                    com.drivehub.kamera.steering.WheelGuides.Corridor area=guides.corridor(pose,0,0,direction);
                    pass.drawFill(mvp,scene,area.triangles,com.drivehub.kamera.steering.WheelGuides.CORRIDOR_FILL_COLOR);
                    pass.draw(mvp,scene,area.boundaries,false);ByteBuffer picture=pixels();
                    // Isolate the ground pass so car occlusion cannot hide a missing/extra section.
                    float[] depths={-1200,-600,0,1000};
                    for(float x:new float[]{-1030,1030})for(float y:depths) {
                        int px=Math.round((x+1200)/2400*W),py=Math.round((y+1800)/4300*H);boolean visible=false;
                        for(int xx=px-3;xx<=px+3;xx++)for(int yy=py-3;yy<=py+3;yy++) {
                            int c=color(picture,xx,yy);
                            if(Math.abs(((c>>16)&255)-115)<=2&&Math.abs(((c>>8)&255)-217)<=2&&(c&255)>=253)visible=true;
                        }
                        assertTrue("Missing blue boundary in "+direction+" at "+raw+" x="+x+" y="+y,visible);
                    }
                    if(direction==com.drivehub.kamera.steering.WheelGuides.Direction.FORWARD)
                        for(float y:new float[]{0,1000}) {
                            int py=Math.round((y+1800)/4300*H),c=color(picture,W/2,py);
                            assertEquals(14,(c>>16)&255,1);assertEquals(48,(c>>8)&255,1);assertEquals(85,c&255,1);
                        }
                }
            }finally{pass.close();}
        }
    }
    @Test public void blueForwardDrawsMirrorWidthEdgesAndOmitsWheelLinesAtBothLocks() {
        try(Egl egl=new Egl()) {
            GLES20.glViewport(0,0,W,H);GLES20.glDisable(GLES20.GL_CULL_FACE);GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);GLES20.glDepthFunc(GLES20.GL_LEQUAL);GLES20.glDepthMask(true);
            RivelaScene scene=new RivelaScene(new BirdseyeSynthetic().calibration,4000);
            com.drivehub.kamera.steering.WheelGuides guides=new com.drivehub.kamera.steering.WheelGuides();
            com.drivehub.kamera.steering.SteeringModel solver=new com.drivehub.kamera.steering.SteeringModel();
            float[] mvp=new float[16];Matrix.orthoM(mvp,0,-2500,2500,2500,-3500,-100,100);
            PdcGroundPass pass=new PdcGroundPass();
            try {
                for(float raw:new float[]{-516.95f,516.95f}) {
                    GLES20.glClearColor(0,0,0,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);
                    com.drivehub.kamera.steering.SteeringModel.Solution pose=solver.solve(raw);
                    java.util.List<com.drivehub.kamera.rivela.PdcBars.Bar> traces=guides.lines(pose,0,0,com.drivehub.kamera.steering.WheelGuides.Direction.FORWARD);
                    com.drivehub.kamera.steering.WheelGuides.Corridor area=guides.corridor(pose,0,0,com.drivehub.kamera.steering.WheelGuides.Direction.FORWARD);
                    pass.drawFill(mvp,scene,area.triangles,com.drivehub.kamera.steering.WheelGuides.CORRIDOR_FILL_COLOR);
                    pass.draw(mvp,scene,area.boundaries,false);ByteBuffer picture=pixels();
                    for(int side=0;side<2;side++)for(int i=0;i<=16;i+=4) {
                        double[] front=area.boundaries.get(side).xy;
                        int px=(int)Math.round((front[i]+2500)/5000*W),py=(int)Math.round((front[i+1]+3500)/6000*H);
                        boolean visible=false;
                        for(int x=px-3;x<=px+3;x++)for(int y=py-3;y<=py+3;y++) {
                            int c=color(picture,x,y);
                            if(Math.abs(((c>>16)&255)-115)<=2&&Math.abs(((c>>8)&255)-217)<=2&&(c&255)>=253)visible=true;
                        }
                        assertTrue("Outer guide omitted at "+raw+" side="+side+" sample="+i,visible);
                    }
                    double[] inner=traces.get(raw>0?0:1).xy;
                    int px=(int)Math.round((inner[20]+2500)/5000*W),py=(int)Math.round((inner[21]+3500)/6000*H);
                    int c=color(picture,px,py);
                    assertFalse("Inner front curve remains highlighted",Math.abs(((c>>16)&255)-115)<=2
                            &&Math.abs(((c>>8)&255)-217)<=2&&(c&255)>=253);
                }
            }finally{pass.close();}
        }
    }
    @Test public void blueCorridorCanBeComparedWithFourTracksAndDisappearsWithUnavailableInput() throws Exception {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;
        RivelaScene scene=new RivelaScene(frames.calibration,4000);scene.prepareBowl();BirdseyeRenderer r=renderer(frames,scene);
        r.orbit.rotate(-35/.35f,(75-55)/.25f);r.orbit.zoom(11000f/16000);
        com.drivehub.kamera.steering.SteeringModel solver=new com.drivehub.kamera.steering.SteeringModel();
        try(Egl egl=new Egl()) {
            r.onSurfaceCreated(null,null);r.onSurfaceChanged(null,W,H);r.setThreeD(true);r.setWheelGuidePreview(true);
            for(float raw:new float[]{-516.95f,516.95f,0}) {
                r.steering.setSolution(solver.solve(raw),true);r.setGuideStyle(WheelGuides.Style.COLORED);r.onDrawFrame(null);ByteBuffer separate=pixels();
                r.setBlueGuideCorridor(true);r.onDrawFrame(null);ByteBuffer blue=pixels();
                assertNull(r.error);assertNull(r.threeDError);assertTrue(differences(separate,blue,new int[]{0,0,W,H})>200);
                int edges=0;
                for(int y=0;y<H;y++)for(int x=0;x<W;x++) {
                    int c=color(blue,x,y);if(Math.abs(((c>>16)&255)-115)<=2&&Math.abs(((c>>8)&255)-217)<=2&&(c&255)>=253)edges++;
                }
                assertTrue("Light-blue outer edges missing",edges>10);
                r.setGuideStyle(WheelGuides.Style.COLORED);r.onDrawFrame(null);assertEquals(0,differences(separate,pixels(),new int[]{0,0,W,H}));
                if(raw<0)save(blue,"rivela-blue-reverse-corridor.png");
            }
            r.setWheelGuidePreview(false);r.onDrawFrame(null);ByteBuffer without=pixels();
            r.setWheelGuidePreview(true);r.setBlueGuideCorridor(true);r.steering.setSolution(solver.solve(0),false);r.onDrawFrame(null);
            assertEquals(0,differences(without,pixels(),new int[]{0,0,W,H}));
            r.steering.setSolution(solver.solve(0),true);frames.missingMask=8;frames.fixedClockUs+=BirdseyeFrameHealth.MAX_AGE_US+1;
            r.onDrawFrame(null);ByteBuffer stale=pixels();r.setGuideStyle(WheelGuides.Style.COLORED);r.onDrawFrame(null);
            assertEquals(0,differences(stale,pixels(),new int[]{0,0,W,H}));
            frames.missingMask=0;r.setGuideDirection(com.drivehub.kamera.steering.WheelGuides.Direction.FORWARD);
            r.onDrawFrame(null);ByteBuffer forward=pixels();r.setBlueGuideCorridor(true);r.onDrawFrame(null);
            assertTrue(differences(forward,pixels(),new int[]{0,0,W,H})>100);
        }
    }
    @Test public void reversePreviewRendersFrontAndRearTracksInFourDistinctColours() throws Exception {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;
        RivelaScene scene=new RivelaScene(frames.calibration,4000);scene.prepareBowl();BirdseyeRenderer r=renderer(frames,scene);
        r.orbit.rotate(-35/.35f,(75-55)/.25f);r.orbit.zoom(11000f/14500);
        com.drivehub.kamera.steering.SteeringModel solver=new com.drivehub.kamera.steering.SteeringModel();
        try(Egl egl=new Egl()) {
            r.onSurfaceCreated(null,null);r.onSurfaceChanged(null,W,H);r.setThreeD(true);r.setWheelGuidePreview(true);
            for(float raw:new float[]{-516.95f,516.95f}) {
                r.steering.setSolution(solver.solve(raw),true);r.onDrawFrame(null);
                assertNull(r.error);assertNull(r.threeDError);assertTrue(r.threeDActive);
                ByteBuffer picture=pixels();
                for(int expected:new int[]{0x00FFFF,0xFFFF00,0x00FF00,0xFF66FF}) {
                    int count=0;
                    for(int y=0;y<H;y++)for(int x=0;x<W;x++) {
                        int actual=color(picture,x,y);
                        if(Math.abs(((actual>>16)&255)-((expected>>16)&255))<=2
                                &&Math.abs(((actual>>8)&255)-((expected>>8)&255))<=2
                                &&Math.abs((actual&255)-(expected&255))<=2)count++;
                    }
                    assertTrue("Missing wheel trace colour "+Integer.toHexString(expected)+" at "+raw,count>10);
                }
                save(picture,raw<0?"rivela-four-reverse-tracks-right.png":"rivela-four-reverse-tracks-left.png");
            }
        }
    }
    @Test public void forwardBlueOuterGuidesRestoreAllFourIndividualColoursWhenDisabled() throws Exception {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;
        RivelaScene scene=new RivelaScene(frames.calibration,4000);scene.prepareBowl();BirdseyeRenderer r=renderer(frames,scene);
        r.orbit.rotate((180-35)/.35f,(75-55)/.25f);r.orbit.zoom(11000f/14500);
        com.drivehub.kamera.steering.SteeringModel solver=new com.drivehub.kamera.steering.SteeringModel();
        try(Egl egl=new Egl()) {
            r.onSurfaceCreated(null,null);r.onSurfaceChanged(null,W,H);r.setThreeD(true);r.setWheelGuidePreview(true);
            r.setGuideDirection(com.drivehub.kamera.steering.WheelGuides.Direction.FORWARD);
            for(float raw:new float[]{-516.95f,516.95f}) {
                r.steering.setSolution(solver.solve(raw),true);r.setWheelGuidePreview(false);r.onDrawFrame(null);ByteBuffer without=pixels();
                r.setWheelGuidePreview(true);r.setGuideStyle(WheelGuides.Style.COLORED);r.onDrawFrame(null);ByteBuffer separate=pixels();
                assertNull(r.error);assertNull(r.threeDError);int rear=0;
                for(int y=0;y<H;y++)for(int x=0;x<W;x++) {
                    int c=color(separate,x,y);if((c==0x00FF00||c==0xFF66FF)&&c!=color(without,x,y))rear++;
                }
                assertTrue("Forward rear display curves missing at "+raw,rear>10);
                r.setBlueGuideCorridor(true);r.onDrawFrame(null);ByteBuffer blue=pixels();
                assertNull(r.error);assertNull(r.threeDError);assertTrue(differences(separate,blue,new int[]{0,0,W,H})>200);
                for(int y=0;y<H;y++)for(int x=0;x<W;x++)assertNotEquals(0x00FF00,color(blue,x,y));
                r.setGuideStyle(WheelGuides.Style.COLORED);r.onDrawFrame(null);assertEquals(0,differences(separate,pixels(),new int[]{0,0,W,H}));
                save(separate,raw<0?"rivela-four-forward-tracks-right.png":"rivela-four-forward-tracks-left.png");
                save(blue,raw<0?"rivela-blue-forward-right.png":"rivela-blue-forward-left.png");
            }
        }
    }
    @Test public void forwardGuidesSwitchDirectionWithoutNewSteeringAndUseFrontFrameFreshness() throws Exception {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;
        RivelaScene scene=new RivelaScene(frames.calibration,4000);scene.prepareBowl();BirdseyeRenderer r=renderer(frames,scene);
        r.orbit.rotate((180-35)/.35f,(75-55)/.25f);r.orbit.zoom(11000f/14500);
        com.drivehub.kamera.steering.SteeringModel solver=new com.drivehub.kamera.steering.SteeringModel();
        try(Egl egl=new Egl()) {
            r.onSurfaceCreated(null,null);r.onSurfaceChanged(null,W,H);r.setThreeD(true);
            r.steering.setSolution(solver.solve(0),true);
            r.setGuideDirection(com.drivehub.kamera.steering.WheelGuides.Direction.FORWARD);
            r.onDrawFrame(null);ByteBuffer without=pixels();
            r.setWheelGuidePreview(true);r.onDrawFrame(null);ByteBuffer forward=pixels();
            assertNull(r.error);assertNull(r.threeDError);
            assertTrue(differences(without,forward,new int[]{0,0,W,H})>100);
            for(float x:new float[]{-725,725}) {
                int[] point=projectPoint(r,scene,x,-4295.5f,-2);
                int[] rect={Math.max(0,point[0]-5),Math.max(0,point[1]-5),Math.min(W,point[0]+6),Math.min(H,point[1]+6)};
                assertTrue(rect[2]>rect[0]&&rect[3]>rect[1]);assertTrue(differences(without,forward,rect)>0);
            }
            r.setGuideDirection(com.drivehub.kamera.steering.WheelGuides.Direction.REVERSE);r.onDrawFrame(null);
            assertTrue(differences(forward,pixels(),new int[]{0,0,W,H})>100);
            r.setGuideDirection(com.drivehub.kamera.steering.WheelGuides.Direction.FORWARD);r.onDrawFrame(null);
            assertEquals(0,differences(forward,pixels(),new int[]{0,0,W,H}));
            frames.missingMask=8;frames.fixedClockUs+=BirdseyeFrameHealth.MAX_AGE_US+1;
            r.onDrawFrame(null);ByteBuffer rearMissing=pixels();r.setWheelGuidePreview(false);r.onDrawFrame(null);
            assertTrue("Forward guides incorrectly depend on rear frames",differences(rearMissing,pixels(),new int[]{0,0,W,H})>100);
            frames.missingMask=4;frames.fixedClockUs+=BirdseyeFrameHealth.MAX_AGE_US+1;
            r.setWheelGuidePreview(true);r.onDrawFrame(null);ByteBuffer frontMissing=pixels();
            r.setWheelGuidePreview(false);r.onDrawFrame(null);
            assertEquals("Stale front frame retained forward guidance",0,differences(frontMissing,pixels(),new int[]{0,0,W,H}));
        }
    }
    @Test public void reverseWheelGuidesReachFarEndpointsAndHideOnStaleSteeringOrRearFrames() throws Exception {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;
        RivelaScene scene=new RivelaScene(frames.calibration,4000);scene.prepareBowl();BirdseyeRenderer r=renderer(frames,scene);
        r.orbit.rotate(-35/.35f,(75-55)/.25f);r.orbit.zoom(11000f/14500);
        com.drivehub.kamera.steering.SteeringModel solver=new com.drivehub.kamera.steering.SteeringModel();
        try(Egl egl=new Egl()) {
            r.onSurfaceCreated(null,null);r.onSurfaceChanged(null,W,H);r.setThreeD(true);
            r.steering.setSolution(solver.solve(0),true);r.onDrawFrame(null);ByteBuffer without=pixels();
            r.setWheelGuidePreview(true);r.onDrawFrame(null);ByteBuffer straight=pixels();
            assertNull(r.error);assertNull(r.threeDError);assertTrue(r.threeDActive);
            assertTrue(differences(without,straight,new int[]{0,0,W,H})>100);
            // Both 3.1 m-beyond-bumper endpoints must survive the ground-pass buffer and bowl depth test.
            for(float x:new float[]{-725,725}) {
                int[] far=projectPoint(r,scene,x,5239.5f,-2);
                int[] rect={Math.max(0,far[0]-5),Math.max(0,far[1]-5),Math.min(W,far[0]+6),Math.min(H,far[1]+6)};
                assertTrue("Guide endpoint outside view",rect[2]>rect[0]&&rect[3]>rect[1]);
                assertTrue("Guide did not reach OEM far depth",differences(without,straight,rect)>0);
            }
            r.steering.setSolution(solver.solve(0),false);r.onDrawFrame(null);
            assertEquals(0,differences(without,pixels(),new int[]{0,0,W,H}));
            r.steering.setSolution(solver.solve(-516.95f),true);r.onDrawFrame(null);ByteBuffer curved=pixels();
            assertTrue(differences(straight,curved,new int[]{0,0,W,H})>100);save(curved,"rivela-reverse-wheel-guides-synthetic.png");
            frames.missingMask=8;frames.fixedClockUs+=BirdseyeFrameHealth.MAX_AGE_US+1;
            r.onDrawFrame(null);ByteBuffer staleRear=pixels();r.setWheelGuidePreview(false);r.onDrawFrame(null);
            assertEquals(0,differences(staleRear,pixels(),new int[]{0,0,W,H}));
        }
    }
    @Test public void independentWheelPairSelectsCorrectMeshPartAndCanExceedLegacyLimit() throws Exception {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;frames.missingMask=15;
        RivelaScene scene=new RivelaScene(frames.calibration);scene.prepareBowl();BirdseyeRenderer r=renderer(frames,scene);
        r.orbit.rotate((180-35)/.35f,(75-55)/.25f);r.orbit.zoom(11000f/8000);
        ByteBuffer held=null;
        for(int context=0;context<2;context++)try(Egl egl=new Egl()) {
            r.onSurfaceCreated(null,null);r.onSurfaceChanged(null,W,H);r.setThreeD(true);
            if(context==1){r.onDrawFrame(null);assertEquals(0,differences(held,pixels(),new int[]{0,0,W,H}));continue;}
            r.steering.setAngles(0,0);r.onDrawFrame(null);ByteBuffer straight=pixels();
            for(boolean left:new boolean[]{true,false}) {
                r.steering.setAngles(left?28.8493f:0,left?0:38.0802f);r.onDrawFrame(null);ByteBuffer turned=pixels();
                assertNull(r.error);assertNull(r.threeDError);assertTrue(r.threeDActive);
                for(VehicleModel.Part part:model.parts)if(!part.name.equals("body")) {
                    boolean moves=part.name.equals(left?"front_left":"front_right");
                    int changed=differences(straight,turned,wheelRect(r,scene,part));
                    if(moves)assertTrue("Requested wheel did not move: "+part.name,changed>10);
                    else assertEquals("Other wheel moved: "+part.name,0,changed);
                }
            }
            r.steering.setAngles(28.8493f,35);r.onDrawFrame(null);ByteBuffer limited=pixels();
            r.steering.setAngles(28.8493f,38.0802f);r.onDrawFrame(null);held=pixels();
            assertTrue("OEM-derived angle was clipped to legacy 35 degrees",differences(limited,held,new int[]{0,0,W,H})>5);
            save(held,"rivela-oem-steering-right-synthetic.png");
        }
    }
    private static int[] wheelRect(BirdseyeRenderer renderer,RivelaScene scene,VehicleModel.Part part) {
        int[] rect={W,H,0,0};
        for(int x:new int[]{-650,650})for(int y:new int[]{-650,650})for(int z:new int[]{-650,650}) {
            int[] p=projectPoint(renderer,scene,part.pivotX+x,part.pivotY+y,part.pivotZ+z);
            rect[0]=Math.min(rect[0],p[0]);rect[1]=Math.min(rect[1],p[1]);rect[2]=Math.max(rect[2],p[0]+1);rect[3]=Math.max(rect[3],p[1]+1);
        }
        for(int i=0;i<4;i++)rect[i]=Math.max(0,Math.min((i&1)==0?W:H,rect[i]));return rect;
    }
    @Test public void orbitExtremesKeepVehicleVisibleInsteadOfLookingThroughOuterWall() throws Exception {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;frames.missingMask=15;
        RivelaScene scene=new RivelaScene(frames.calibration);scene.prepareBowl();BirdseyeRenderer r=renderer(frames,scene);
        try(Egl egl=new Egl()) {
            r.onSurfaceCreated(null,null);r.onSurfaceChanged(null,W,H);r.setThreeD(true);
            for(float distance:new float[]{11000,16000,5000})for(int yaw=0;yaw<360;yaw+=45)for(int direction:new int[]{-1,1}) {
                r.orbit.reset();r.orbit.zoom(11000/distance);r.orbit.rotate((yaw-35)/.35f,direction*10000);
                r.onDrawFrame(null);assertNull(r.error);assertNull(r.threeDError);assertTrue(r.threeDActive);
                ByteBuffer picture=pixels();int blue=0;
                // No camera textures: only the blue model can contribute these pixels.
                for(int y=0;y<H;y++)for(int x=0;x<W;x++) {
                    int c=color(picture,x,y),red=(c>>16)&255,green=(c>>8)&255,b=c&255;
                    if(b>40&&b>red*1.5&&b>green*1.1)blue++;
                }
                if(distance==11000&&yaw==0&&direction==-1)save(picture,"rivela-orbit-low-synthetic.png");
                assertTrue("Vehicle hidden: distance="+distance+" yaw="+yaw+" pitch="+r.orbit.values()[1]+" blue pixels="+blue,blue>120);
            }
        }
    }
    private static void assertPdcColorsVisible(ByteBuffer pixels) {
        for(int expected:new int[]{0xFF0000,0xFF9800,0xFFFF00,0x00FF00}) {
            int count=0;for(int y=0;y<H;y++)for(int x=0;x<W;x++) {
                int c=color(pixels,x,y);
                if(Math.abs(((c>>16)&255)-((expected>>16)&255))<10&&Math.abs(((c>>8)&255)-((expected>>8)&255))<10&&Math.abs((c&255)-(expected&255))<10)count++;
            }
            assertTrue("PDC color missing: "+Integer.toHexString(expected),count>20);
        }
    }
    private static int[] rearPoint(BirdseyeRenderer r,RivelaScene scene,float x,float y) {
        int[] point=projectPoint(r,scene,x,y,0);assertTrue(point[0]>=0&&point[0]<W&&point[1]>=0&&point[1]<H);return point;
    }
    private static int[] projectPoint(BirdseyeRenderer r,RivelaScene scene,float x,float y,float z) {
        float[] pose=r.orbit.values(),view=new float[16],projection=new float[16],mvp=new float[16],point=new float[4];
        double yaw=Math.toRadians(pose[0]),pitch=Math.toRadians(pose[1]);float cx=(float)scene.car.centerX,cy=(float)scene.car.centerY;
        Matrix.setLookAtM(view,0,cx+(float)(Math.sin(yaw)*Math.cos(pitch))*pose[2],cy+(float)(Math.cos(yaw)*Math.cos(pitch))*pose[2],-(float)Math.sin(pitch)*pose[2],cx,cy,-400,0,0,-1);
        Matrix.perspectiveM(projection,0,45,W/(float)H,150,60000);Matrix.multiplyMM(mvp,0,projection,0,view,0);
        Matrix.multiplyMV(point,0,mvp,0,new float[]{x,y,z,1},0);
        int px=Math.round((point[0]/point[3]+1)*W/2),py=Math.round((1-point[1]/point[3])*H/2);
        return new int[]{px,py};
    }
}
