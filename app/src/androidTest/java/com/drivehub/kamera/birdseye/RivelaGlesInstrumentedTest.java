package com.drivehub.kamera.birdseye;
import android.graphics.Bitmap;
import android.opengl.*;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.parking.*;
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
        BirdseyeRenderer r=new BirdseyeRenderer(frames);r.setMesh(new BirdseyeMesh(frames.calibration));r.setScene(scene);r.setModel(model);return r;
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
            pdc.set(PdcSnapshot.EMPTY);frames.missingMask=15;frames.fixedClockUs+=BirdseyeFrameHealth.MAX_AGE_US+1;r.setThreeD(true);r.onDrawFrame(null);
            assertNull(r.error);assertTrue(r.threeDActive);assertEquals(0,r.freshMask);assertEquals(0,color(pixels(),point[0],point[1]));
            frames.missingMask=0;
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
