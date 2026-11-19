package com.drivehub.kamera.birdseye;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

/** Executes the production mesh, shader, UYVY upload and bob sampler on Android EGL. */
@RunWith(AndroidJUnit4.class)
public class BirdseyeGlesInstrumentedTest {
    private static final int W=450,H=640;
    private static final class Pbuffer implements AutoCloseable {
        final EGLDisplay display;
        final EGLContext context;
        final EGLSurface surface;
        Pbuffer() {
            display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            assertTrue(EGL14.eglInitialize(display,new int[2],0,new int[2],0));
            int[] attrs={EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                    EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_RENDERABLE_TYPE,4,EGL14.EGL_NONE};
            EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
            assertTrue(EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0)); assertTrue(count[0]>0);
            context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
            surface=EGL14.eglCreatePbufferSurface(display,configs[0],
                    new int[]{EGL14.EGL_WIDTH,W,EGL14.EGL_HEIGHT,H,EGL14.EGL_NONE},0);
            assertTrue(EGL14.eglMakeCurrent(display,surface,surface,context));
        }
        @Override public void close() {
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display,surface); EGL14.eglDestroyContext(display,context);
            EGL14.eglTerminate(display);
        }
    }
    private BirdseyeRenderer setup(BirdseyeRenderer.Frames frames,BirdseyeCalibration c) {
        BirdseyeRenderer r=new BirdseyeRenderer(frames);r.setMesh(new BirdseyeMesh(c));
        r.onSurfaceCreated(null,null);r.onSurfaceChanged(null,W,H);r.onDrawFrame(null);
        assertNull(r.error);return r;
    }
    private ByteBuffer read() {
        ByteBuffer pixels=ByteBuffer.allocateDirect(W*H*4);
        GLES20.glReadPixels(0,0,W,H,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels);
        assertEquals(GLES20.GL_NO_ERROR,GLES20.glGetError());return pixels;
    }
    @Test public void rearSelfBodyContourIsFiniteOnMediumpGles() {
        try(Pbuffer egl=new Pbuffer()) {
            int program=GlPrograms.link("attribute vec2 p;void main(){gl_Position=vec4(p,0.0,1.0);}",
                    "precision mediump float;uniform vec2 uv;"+BirdseyeRearMask.GLSL
                            +"void main(){gl_FragColor=vec4(rearBodyVisibility(uv),0.0,0.0,1.0);}");
            java.nio.FloatBuffer quad=ByteBuffer.allocateDirect(8*4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(new float[]{-1,-1,1,-1,-1,1,1,1}).flip();
            int position=GLES20.glGetAttribLocation(program,"p");
            try {
                GLES20.glUseProgram(program);GLES20.glViewport(0,0,1,1);
                GLES20.glEnableVertexAttribArray(position);
                GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,quad);
                ByteBuffer pixel=ByteBuffer.allocateDirect(4);
                for(float[] uv:new float[][]{{350,390},{350,460},{350,414},{100,390},
                        {0,300},{719,300},{-10000,300},{10000,460}}) {
                    GLES20.glUniform2f(GLES20.glGetUniformLocation(program,"uv"),uv[0],uv[1]);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);pixel.position(0);
                    GLES20.glReadPixels(0,0,1,1,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixel);
                    assertEquals(Math.round(BirdseyeRearMask.visibility(uv[0],uv[1])*255),pixel.get(0)&255,8);
                }
                assertEquals(GLES20.GL_NO_ERROR,GLES20.glGetError());
            } finally {GLES20.glDisableVertexAttribArray(position);GLES20.glDeleteProgram(program);}
        }
    }
    @Test public void linearPackedSamplerMatchesFourTapReferenceAtParityRowsAndBorders() {
        // High-frequency luma/chroma reveal accidental interpolation between
        // packed columns, even/odd Y selection and clamping errors at field edges.
        ByteBuffer input=ByteBuffer.allocateDirect(720*240*2);
        for(int y=0;y<240;y++)for(int pair=0;pair<360;pair++) {
            input.put((byte)(32+(pair*31+y*17)%190));
            input.put((byte)(32+(pair*34+y*29)%190));
            input.put((byte)(32+(pair*13+y*37)%190));
            input.put((byte)(32+((pair*2+1)*17+y*29)%190));
        }
        input.flip();
        String vertex="attribute vec2 p;void main(){gl_Position=vec4(p,0.0,1.0);}";
        String header=com.drivehub.kamera.rivela.BowlShaders.PRECISION
                +"uniform sampler2D tex;uniform vec2 uv;";
        String output="void main(){gl_FragColor=vec4(camera(tex,uv),1.0);}";
        String reference="vec3 pixel(sampler2D t,vec2 p){"
                +"vec4 q=texture2D(t,vec2((floor(p.x/2.0)+0.5)/360.0,(p.y+0.5)/240.0));"
                +"return vec3(mod(p.x,2.0)<0.5?q.g:q.a,q.r,q.b);}"
                +"vec3 camera(sampler2D t,vec2 uv){"
                +"vec2 p=clamp(vec2(uv.x,uv.y*0.5),vec2(0.0),vec2(719.0,239.0)),a=floor(p),d=fract(p);"
                +"vec3 c=mix(mix(pixel(t,a),pixel(t,vec2(min(a.x+1.0,719.0),a.y)),d.x),"
                +"mix(pixel(t,vec2(a.x,min(a.y+1.0,239.0))),pixel(t,min(a+1.0,vec2(719.0,239.0))),d.x),d.y);"
                +"float y=1.16438356*(c.x-16.0/255.0),u=c.y-128.0/255.0,v=c.z-128.0/255.0;"
                +"return clamp(vec3(y+1.59602678*v,y-0.39176229*u-0.81296765*v,y+2.01723214*u),0.0,1.0);}";
        try(Pbuffer egl=new Pbuffer()) {
            int[] programs={GlPrograms.link(vertex,header+reference+output),
                    GlPrograms.link(vertex,header+PackedUyvyShader.SAMPLING+output)};
            int[] texture=new int[1];GLES20.glGenTextures(1,texture,0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,360,240,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,input);
            java.nio.FloatBuffer quad=ByteBuffer.allocateDirect(8*4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(new float[]{-1,-1,1,-1,-1,1,1,1}).flip();
            ByteBuffer pixel=ByteBuffer.allocateDirect(4);int[] expected=new int[3];
            GLES20.glViewport(0,0,1,1);
            try {
                for(float u:new float[]{-4,0,.35f,1,1.5f,2.3f,357.75f,358.5f,718.75f,719,724})
                    for(float v:new float[]{-4,0,.6f,1,3.3f,235.25f,477,478,480}) {
                        for(int variant=0;variant<2;variant++) {
                            int filter=variant==0?GLES20.GL_NEAREST:PackedUyvyShader.textureFilter(),program=programs[variant];
                            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,filter);
                            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,filter);
                            GLES20.glUseProgram(program);GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"tex"),0);
                            GLES20.glUniform2f(GLES20.glGetUniformLocation(program,"uv"),u,v);
                            int position=GLES20.glGetAttribLocation(program,"p");quad.position(0);
                            GLES20.glEnableVertexAttribArray(position);GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,quad);
                            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);pixel.position(0);
                            GLES20.glReadPixels(0,0,1,1,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixel);
                            GLES20.glDisableVertexAttribArray(position);
                            for(int c=0;c<3;c++) {
                                int actual=pixel.get(c)&255;
                                if(variant==0)expected[c]=actual;
                                else assertEquals("Packed sample at "+u+","+v+" channel "+c,expected[c],actual,3);
                            }
                        }
                    }
                assertEquals(GLES20.GL_NO_ERROR,GLES20.glGetError());
            } finally {
                GLES20.glDeleteTextures(1,texture,0);for(int program:programs)GLES20.glDeleteProgram(program);
            }
        }
    }
    private int pixel(ByteBuffer p,int x,int y) {
        int i=((H-1-y)*W+x)*4;
        return ((p.get(i)&255)<<16)|((p.get(i+1)&255)<<8)|(p.get(i+2)&255);
    }
    private void save(ByteBuffer pixels) throws Exception {save(pixels,"birdseye-gles-synthetic.png");}
    private void save(ByteBuffer pixels,String name) throws Exception {
        int[] rgb=new int[W*H];
        for(int y=0;y<H;y++)for(int x=0;x<W;x++)rgb[y*W+x]=0xFF000000|pixel(pixels,x,y);
        Bitmap image=Bitmap.createBitmap(rgb,W,H,Bitmap.Config.ARGB_8888);
        File file=new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getFilesDir(),name);
        try(FileOutputStream out=new FileOutputStream(file)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}
        image.recycle();
    }
    @Test public void actualShaderReconstructsDirectionalGroundAcrossCameraSeamsAndMasksCar() throws Exception {
        try(Pbuffer egl=new Pbuffer()) {
            BirdseyeSynthetic frames=new BirdseyeSynthetic(); frames.fixedClockUs=1_000_000;
            BirdseyeRenderer r=setup(frames,frames.calibration);assertEquals(15,r.freshMask);
            ByteBuffer pixels=read(); int tested=0;double totalError=0;int maxError=0;
            for(int y=16;y<H-16;y+=17)for(int x=16;x<W-16;x+=17) {
                double gx=(x+0.5-W/2.0)*BirdseyeMesh.GROUND_WIDTH/W;
                double gy=(y+0.5-H/2.0)*BirdseyeMesh.GROUND_HEIGHT/H;
                if(Math.abs(gx)<BirdseyeMesh.CAR_WIDTH/2+80 && Math.abs(gy)<BirdseyeMesh.CAR_LENGTH/2+80)continue;
                double weight=0;for(int c=0;c<4;c++)weight+=frames.calibration.project(c,gx,gy).weight;
                if(weight<0.002)continue;
                int expected=BirdseyeSynthetic.groundColor(gx,gy), actual=pixel(pixels,x,y);
                for(int shift=0;shift<24;shift+=8){int error=Math.abs(((expected>>shift)&255)-((actual>>shift)&255));
                    maxError=Math.max(maxError,error);totalError+=error;}
                tested++;
            }
            Log.i("BirdseyeTest","GLES samples="+tested+" meanRGB="+totalError/(tested*3)+" max="+maxError);
            assertTrue(tested>300);assertTrue("RGB max error="+maxError,maxError<=14);
            assertTrue(totalError/(tested*3)<3);
            assertEquals(0x000000,pixel(pixels,W/2,H/2));
            save(pixels);
            long start=System.nanoTime();
            for(int i=0;i<40;i++){frames.fixedClockUs+=50_000;r.onDrawFrame(null);}
            GLES20.glFinish();
            Log.i("BirdseyeTest","40 synthetic GLES frames incl finish ms="+(System.nanoTime()-start)/1e6+
                    "; GPU="+GLES20.glGetString(GLES20.GL_RENDERER));
        }
    }
    @Test public void missingStaleSkewedOemAndContextRecreationInvalidateTextures() {
        BirdseyeSynthetic frames=new BirdseyeSynthetic(); frames.fixedClockUs=1_000_000;
        try(Pbuffer egl=new Pbuffer()) {
            BirdseyeRenderer r=setup(frames,frames.calibration);assertEquals(15,r.freshMask);
            frames.missingMask=1;frames.fixedClockUs+=200_000;r.onDrawFrame(null);assertEquals(14,r.freshMask);
            frames.missingMask=15;frames.fixedClockUs+=600_000;r.onDrawFrame(null);assertEquals(0,r.freshMask);
            assertEquals(15,r.displayMask);
            frames.fixedClockUs+=BirdseyeFrameHealth.DISPLAY_HOLD_US;r.onDrawFrame(null);assertEquals(0,r.displayMask);
            int uncovered=pixel(read(),20,20);assertTrue(((uncovered>>8)&255)<60);
            frames.missingMask=0;frames.delayedUs[2]=200_000;frames.fixedClockUs+=50_000;
            r.onDrawFrame(null);assertEquals(11,r.freshMask);
            frames.enabled=false;r.invalidateFrames();r.onDrawFrame(null);assertEquals(0,r.freshMask);assertEquals(0,r.displayMask);
            frames.enabled=true;frames.delayedUs[2]=0;frames.fixedClockUs+=50_000;
            r.onDrawFrame(null);assertEquals(15,r.freshMask);
        }
        try(Pbuffer recreated=new Pbuffer()) {
            frames.missingMask=15;
            BirdseyeRenderer r=setup(frames,frames.calibration);assertEquals(0,r.freshMask);assertEquals(0,r.displayMask);
            frames.missingMask=0;frames.fixedClockUs+=50_000;r.onDrawFrame(null);assertEquals(15,r.freshMask);
        }
    }
    @Test public void outputFailureDoesNotAffectAnotherRenderer() {
        try(Pbuffer egl=new Pbuffer()) {
            BirdseyeSynthetic good=new BirdseyeSynthetic();
            BirdseyeRenderer failed=new BirdseyeRenderer(new BirdseyeRenderer.Frames(){
                public long clockUs(){return 1_000_000;}public boolean allowed(){return true;}
                public long copy(int c,ByteBuffer b,long t){throw new IllegalStateException("Injected upload failure");}
            });
            failed.setMesh(new BirdseyeMesh(good.calibration));failed.onSurfaceCreated(null,null);
            failed.onSurfaceChanged(null,W,H);failed.onDrawFrame(null);
            assertNotNull(failed.error);assertEquals(0,failed.freshMask);
            BirdseyeRenderer delayed=new BirdseyeRenderer(good);
            delayed.onSurfaceCreated(null,null);delayed.onSurfaceChanged(null,W,H);delayed.onDrawFrame(null);
            assertEquals(0,delayed.freshMask);assertNull(delayed.error);
            BirdseyeRenderer healthy=setup(good,good.calibration);assertEquals(15,healthy.freshMask);
            assertNull(healthy.error);
            delayed.setMesh(new BirdseyeMesh(good.calibration));delayed.onDrawFrame(null);
            assertEquals(15,delayed.freshMask);assertNull(delayed.error);
        }
    }
    private int groundPixel(ByteBuffer pixels,double x,double y) {
        int px=(int)Math.floor(W/2.0+x*W/BirdseyeMesh.GROUND_WIDTH);
        int py=(int)Math.floor(H/2.0+y*H/BirdseyeMesh.GROUND_HEIGHT);
        return pixel(pixels,px,py);
    }
    private BirdseyeCarArtwork syntheticArtwork() {
        Bitmap image=Bitmap.createBitmap(40,80,Bitmap.Config.ARGB_8888);
        image.setPremultiplied(false);
        for(int y=8;y<72;y++)for(int x=8;x<32;x++) {
            if(x<14 && y<14)continue;
            image.setPixel(x,y,y<40?0xFFFF0000:0xFF0000FF);
        }
        for(int y=38;y<43;y++)for(int x=18;x<23;x++)image.setPixel(x,y,0);
        for(int y=24;y<29;y++)for(int x=18;x<23;x++)image.setPixel(x,y,0x8000FF00);
        return new BirdseyeCarArtwork(image,new BirdseyeCarGeometry(40,80,.2,.1,.8,.9));
    }
    @Test public void carTextureKeepsFrontUpCompositesStraightAlphaAndReloadsAfterContextLoss() {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;
        java.util.concurrent.atomic.AtomicInteger loads=new java.util.concurrent.atomic.AtomicInteger();
        BirdseyeRenderer renderer=new BirdseyeRenderer(frames,color->{loads.incrementAndGet();return syntheticArtwork();});
        for(int context=0;context<2;context++)try(Pbuffer egl=new Pbuffer()) {
            renderer.setMesh(new BirdseyeMesh(frames.calibration));
            renderer.onSurfaceCreated(null,null);renderer.onSurfaceChanged(null,W,H);renderer.onDrawFrame(null);
            assertNull(renderer.error);assertTrue(renderer.hasCarArtwork);assertEquals(15,renderer.freshMask);
            ByteBuffer pixels=read();
            assertEquals(0xFF0000,groundPixel(pixels,-300,-1000));
            assertEquals(0x0000FF,groundPixel(pixels,-300,1000));
            for(double[] point:new double[][]{{0,0},{-700,-2000},{880,0}}) {
                int shown=groundPixel(pixels,point[0],point[1]),groundColor=BirdseyeSynthetic.groundColor(point[0],point[1]);
                for(int shift=0;shift<24;shift+=8)assertEquals("Transparent car pixels must reveal fresh ground, not a rectangle",
                        (groundColor>>shift)&255,(shown>>shift)&255,5);
            }
            int alpha=groundPixel(pixels,0,-900),background=BirdseyeSynthetic.groundColor(0,-900);
            for(int shift=0;shift<24;shift+=8){int foreground=shift==8?255:0;
                int expected=Math.round((foreground*128+((background>>shift)&255)*127)/255f);
                assertEquals(expected,(alpha>>shift)&255,5);}
            int ground=groundPixel(pixels,1600,0),expected=BirdseyeSynthetic.groundColor(1600,0);
            for(int shift=0;shift<24;shift+=8)assertEquals((expected>>shift)&255,(ground>>shift)&255,3);
            assertEquals(context+1,loads.get());
        }
    }
    private static BirdseyeRenderer stillRenderer(BirdseyeRenderer.Frames frames,BirdseyeCarArtwork.Source artwork) {
        return new BirdseyeRenderer(frames,artwork,()->0L,0);
    }
    @Test public void normalWideSwitchPreservesMetricGuidesArtworkAndOriginalNormalImage() throws Exception {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;
        ProjectionAssets projection=ProjectionAssets.get(frames.calibration);assertNull(projection.wideError);assertNotNull(projection.wideMesh);
        BirdseyeRenderer renderer=stillRenderer(frames,color->BirdseyeCarArtwork.load(InstrumentationRegistry.getInstrumentation().getTargetContext().getAssets(),color));
        renderer.setMesh(projection.mesh);renderer.setWideMesh(projection.wideMesh,null);renderer.setScene(projection.scene);
        renderer.setBlueGuideCorridor(true);renderer.setWheelGuidePreview(true);
        ByteBuffer savedWide=null;
        for(int context=0;context<2;context++)try(Pbuffer egl=new Pbuffer()) {
            renderer.onSurfaceCreated(null,null);renderer.onSurfaceChanged(null,W,H);
            if(context==1){renderer.onDrawFrame(null);assertTrue(renderer.wideActive);assertEquals(0,differences(savedWide,read()));continue;}
            for(com.drivehub.kamera.steering.WheelGuides.Direction direction:com.drivehub.kamera.steering.WheelGuides.Direction.values()) {
                renderer.steering.setSolution(new com.drivehub.kamera.steering.SteeringModel().solve(0),true,direction);
                renderer.setWide(false);renderer.onDrawFrame(null);ByteBuffer normal=read();assertFalse(renderer.wideActive);
                renderer.setWide(true);renderer.onDrawFrame(null);ByteBuffer wide=read();
                assertTrue(renderer.wideActive);assertFalse(renderer.threeDActive);assertNull(renderer.error);
                assertTrue(differences(normal,wide)>10000);
                double y=direction==com.drivehub.kamera.steering.WheelGuides.Direction.REVERSE?3000:-3000;
                for(double x:new double[]{-1030,1030})assertEquals(0x73D9FF,groundPixel(wide,x/2,y/2));
                // Both halves of the opaque roof remain above the fill at the new image scale.
                assertEquals(groundPixel(normal,0,0),groundPixel(wide,0,0));
                save(wide,"birdseye-wide-"+direction+"-synthetic.png");
                renderer.setWide(false);renderer.onDrawFrame(null);assertEquals(0,differences(normal,read()));
                renderer.setWide(true);renderer.onDrawFrame(null);savedWide=read();
            }
        }
        try(Pbuffer egl=new Pbuffer()) {
            renderer.setWideMesh(null,"Unsupported wide calibration");renderer.onSurfaceCreated(null,null);renderer.onSurfaceChanged(null,W,H);
            renderer.onDrawFrame(null);assertFalse(renderer.wideActive);assertNull(renderer.error);
        }
    }
    @Test public void twoDGuidesUseMirrorWidthGroundProjectionAndNeedNoThreeDModelOrDepthBuffer() throws Exception {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;
        BirdseyeRenderer renderer=stillRenderer(frames,color->BirdseyeCarArtwork.load(InstrumentationRegistry.getInstrumentation().getTargetContext().getAssets(),color));
        renderer.setMesh(new BirdseyeMesh(frames.calibration));renderer.setScene(new RivelaScene(frames.calibration));
        renderer.setWheelGuidePreview(true);renderer.setBlueGuideCorridor(true);
        com.drivehub.kamera.steering.SteeringModel model=new com.drivehub.kamera.steering.SteeringModel();
        ByteBuffer beforeContextLoss=null;
        for(int context=0;context<2;context++)try(Pbuffer egl=new Pbuffer()) {
            renderer.onSurfaceCreated(null,null);renderer.onSurfaceChanged(null,W,H);
            if(context==1) {
                renderer.onDrawFrame(null);assertNull(renderer.error);
                assertEquals(0,differences(beforeContextLoss,read()));continue;
            }
            for(com.drivehub.kamera.steering.WheelGuides.Direction direction:com.drivehub.kamera.steering.WheelGuides.Direction.values())
                for(float raw:new float[]{-516.95f,516.95f,0}) {
                    renderer.steering.setSolution(model.solve(raw),true,direction);
                    renderer.onDrawFrame(null);ByteBuffer shown=read();
                    assertNull(renderer.error);assertFalse(renderer.threeDActive);assertTrue(renderer.hasCarArtwork);
                    renderer.setWheelGuidePreview(false);renderer.onDrawFrame(null);ByteBuffer ground=read();
                    assertTrue("No 2D guides in "+direction+" at "+raw,differences(shown,ground)>300);
                    // The opaque roof is above the ground fill; transparent ground remains visible beside it.
                    assertEquals(groundPixel(ground,0,0),groundPixel(shown,0,0));
                    if(raw==0) {
                        double y=direction==com.drivehub.kamera.steering.WheelGuides.Direction.REVERSE?3000:-3000;
                        for(double x:new double[]{-1030,1030})assertEquals(0x73D9FF,groundPixel(shown,x,y));
                        assertBlend(groundPixel(ground,0,y),groundPixel(shown,0,y),1);
                    }
                    save(shown,"birdseye-guidance-2d-"+direction+"-"+(int)raw+"-synthetic.png");
                    renderer.setWheelGuidePreview(true);renderer.onDrawFrame(null);
                    assertEquals(0,differences(shown,read()));
                    beforeContextLoss=shown;
                }
        }
    }
    @Test public void twoDGuidanceIsBelowOpaqueMirrorsAndStraightAlphaArtwork() {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;
        BirdseyeRenderer renderer=stillRenderer(frames,color->{
            BirdseyeCarArtwork art=syntheticArtwork();
            for(int y=24;y<29;y++)for(int x:new int[]{3,4,5,6,33,34,35,36})art.bitmap.setPixel(x,y,0xFFFFFFFF);
            return art;
        });
        renderer.setMesh(new BirdseyeMesh(frames.calibration));renderer.setScene(new RivelaScene(frames.calibration));
        renderer.setBlueGuideCorridor(true);
        renderer.steering.setSolution(new com.drivehub.kamera.steering.SteeringModel().solve(0),true,
                com.drivehub.kamera.steering.WheelGuides.Direction.FORWARD);
        try(Pbuffer egl=new Pbuffer()) {
            renderer.onSurfaceCreated(null,null);renderer.onSurfaceChanged(null,W,H);renderer.onDrawFrame(null);
            ByteBuffer ground=read();renderer.setWheelGuidePreview(true);renderer.onDrawFrame(null);ByteBuffer shown=read();
            assertNull(renderer.error);
            for(double x:new double[]{-1030,1030}) {
                assertEquals("Opaque mirror must cover the blue boundary",0xFFFFFF,groundPixel(shown,x,-900));
                assertEquals(groundPixel(ground,x,-900),groundPixel(shown,x,-900));
            }
            assertEquals(0xFF0000,groundPixel(shown,-300,-1000));
            assertBlend(groundPixel(ground,0,0),groundPixel(shown,0,0),1); // Transparent hole shows the fill.
            int under=blend(BirdseyeSynthetic.groundColor(0,-900)),actual=groundPixel(shown,0,-900);
            for(int shift=0;shift<24;shift+=8) {
                int foreground=shift==8?255:0;
                int expected=Math.round((foreground*128+((under>>shift)&255)*127)/255f);
                assertEquals("Artwork alpha must be applied once above the fill",expected,(actual>>shift)&255,5);
            }
        }
    }
    @Test public void twoDGuidanceRequiresLiveGearSteeringAndTheCorrespondingCameraAndKeepsTheMissingCarMask() {
        BirdseyeSynthetic frames=new BirdseyeSynthetic();frames.fixedClockUs=1_000_000;
        BirdseyeRenderer renderer=stillRenderer(frames,null);
        renderer.setMesh(new BirdseyeMesh(frames.calibration));renderer.setScene(new RivelaScene(frames.calibration));
        renderer.setBlueGuideCorridor(true);
        com.drivehub.kamera.steering.SteeringGuidance guidance=new com.drivehub.kamera.steering.SteeringGuidance();
        try(Pbuffer egl=new Pbuffer()) {
            renderer.onSurfaceCreated(null,null);renderer.onSurfaceChanged(null,W,H);
            for(int gear:new int[]{2,4}) {
                com.drivehub.kamera.steering.SteeringSnapshot steering=com.drivehub.kamera.steering.SteeringSnapshot.value(0,0,1000,1);
                com.drivehub.kamera.steering.GearSnapshot fresh=com.drivehub.kamera.steering.GearSnapshot.value(gear,1000);
                publish(renderer,guidance,steering,fresh,1000);
                renderer.setGuideDirection(gear==2?com.drivehub.kamera.steering.WheelGuides.Direction.FORWARD:
                        com.drivehub.kamera.steering.WheelGuides.Direction.REVERSE);
                renderer.setWheelGuidePreview(true);renderer.onDrawFrame(null);ByteBuffer shown=read();
                renderer.setWheelGuidePreview(false);renderer.onDrawFrame(null);
                assertTrue(differences(shown,read())>300);assertEquals(0,groundPixel(shown,0,0));
                for(int invalid:new int[]{0,1,3}) {
                    publish(renderer,guidance,steering,com.drivehub.kamera.steering.GearSnapshot.value(invalid,1000),1000);
                    assertGuidesHidden(renderer);
                }
                publish(renderer,guidance,steering,fresh,1501);assertGuidesHidden(renderer); // Both reads stale.
                publish(renderer,guidance,com.drivehub.kamera.steering.SteeringSnapshot.EMPTY,fresh,1000);assertGuidesHidden(renderer);
                publish(renderer,guidance,steering,fresh,1000);
                // Losing an unrelated camera must not hide guides over the still-current driving camera.
                frames.missingMask=gear==2?4:8;frames.fixedClockUs+=BirdseyeFrameHealth.MAX_AGE_US+1;
                renderer.setWheelGuidePreview(true);renderer.onDrawFrame(null);shown=read();
                renderer.setWheelGuidePreview(false);renderer.onDrawFrame(null);assertTrue(differences(shown,read())>300);
                frames.missingMask=gear==2?8:4;frames.fixedClockUs+=BirdseyeFrameHealth.MAX_AGE_US+1;
                assertGuidesHidden(renderer);
                frames.missingMask=0;frames.fixedClockUs+=BirdseyeFrameHealth.MAX_AGE_US+1;
            }
            frames.enabled=false;assertGuidesHidden(renderer);assertNull(renderer.error);
        }
    }
    private void publish(BirdseyeRenderer renderer,com.drivehub.kamera.steering.SteeringGuidance guidance,
                         com.drivehub.kamera.steering.SteeringSnapshot steering,com.drivehub.kamera.steering.GearSnapshot gear,long now) {
        com.drivehub.kamera.steering.SteeringGuidance.Frame frame=guidance.update(steering,gear,true,now);
        renderer.steering.setSolution(frame.wheels,frame.guidanceAvailable,frame.direction);
    }
    private void assertGuidesHidden(BirdseyeRenderer renderer) {
        renderer.setWheelGuidePreview(true);renderer.onDrawFrame(null);ByteBuffer hidden=read();
        renderer.setWheelGuidePreview(false);renderer.onDrawFrame(null);assertEquals(0,differences(hidden,read()));
    }
    private int differences(ByteBuffer a,ByteBuffer b) {
        int count=0;for(int y=0;y<H;y++)for(int x=0;x<W;x++)if(pixel(a,x,y)!=pixel(b,x,y))count++;
        return count;
    }
    private int blend(int background) {
        int output=0;
        for(int shift=0;shift<24;shift+=8)output|=Math.round((((background>>shift)&255)*170+
                ((com.drivehub.kamera.steering.WheelGuides.CORRIDOR_FILL_COLOR>>shift)&255)*85)/255f)<<shift;
        return output;
    }
    private void assertBlend(int background,int actual,int tolerance) {
        int expected=blend(background);
        for(int shift=0;shift<24;shift+=8)assertEquals((expected>>shift)&255,(actual>>shift)&255,tolerance);
    }

    @Test public void brokenOptionalArtworkKeepsFreshGroundAndBlackMask() {
        try(Pbuffer egl=new Pbuffer()) {
            BirdseyeSynthetic frames=new BirdseyeSynthetic();
            BirdseyeRenderer renderer=new BirdseyeRenderer(frames,color->{throw new IllegalArgumentException("Bad artwork");});
            renderer.setMesh(new BirdseyeMesh(frames.calibration));
            renderer.onSurfaceCreated(null,null);renderer.onSurfaceChanged(null,W,H);renderer.onDrawFrame(null);
            assertNull(renderer.error);assertFalse(renderer.hasCarArtwork);assertEquals(15,renderer.freshMask);
            assertEquals(0,pixel(read(),W/2,H/2));
        }
    }

    @Test public void changingPaintUploadsOnceWithoutResettingFreshCameraTextures() {
        BirdseyeSynthetic frames = new BirdseyeSynthetic(); frames.fixedClockUs = 1_000_000;
        java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
        BirdseyeRenderer renderer = new BirdseyeRenderer(frames, color -> {
            loads.incrementAndGet();
            Bitmap image = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888);
            image.eraseColor(color.swatch);
            return new BirdseyeCarArtwork(image, new BirdseyeCarGeometry(40,80,.1,.05,.9,.95));
        });
        renderer.setMesh(new BirdseyeMesh(frames.calibration));
        try (Pbuffer egl = new Pbuffer()) {
            renderer.onSurfaceCreated(null,null); renderer.onSurfaceChanged(null,W,H);
            renderer.onDrawFrame(null);
            int initialGround = groundPixel(read(),1600,0);
            int expectedLoads = 1;
            BirdseyeCarColor previous = BirdseyeCarColor.PICCADILLY_BLUE;
            for (BirdseyeCarColor color : BirdseyeCarColor.values()) {
                renderer.setCarColor(color);
                if (color != previous) expectedLoads++;
                renderer.onDrawFrame(null);
                assertNull(renderer.error); assertTrue(renderer.hasCarArtwork);
                assertEquals(15,renderer.freshMask);
                assertEquals(color.swatch & 0xFFFFFF,groundPixel(read(),0,0));
                assertEquals(initialGround,groundPixel(read(),1600,0));
                renderer.onDrawFrame(null); renderer.setCarColor(color); renderer.onDrawFrame(null);
                assertEquals(expectedLoads,loads.get()); // Never decode again on ordinary frames or repeated taps.
                previous = color;
            }
        }
        int beforeContextLoss = loads.get();
        try (Pbuffer egl = new Pbuffer()) {
            renderer.onSurfaceCreated(null,null); renderer.onSurfaceChanged(null,W,H); renderer.onDrawFrame(null);
            assertEquals(beforeContextLoss+1,loads.get());
            assertEquals(BirdseyeCarColor.HUNTER_GREEN.swatch & 0xFFFFFF,groundPixel(read(),0,0));
        }
    }

    @Test public void bundledPaintVariantsDecodeAndProduceDistinctPreparedTextures() throws Exception {
        android.content.res.AssetManager assets = InstrumentationRegistry.getInstrumentation().getTargetContext().getAssets();
        String[] bundled = assets.list(BirdseyeCarArtwork.DIRECTORY+"colors");
        org.junit.Assume.assumeTrue("Optional local paint artwork is absent",bundled != null && bundled.length > 0);
        BirdseyeSynthetic frames = new BirdseyeSynthetic(); frames.fixedClockUs = 1_000_000;
        BirdseyeRenderer renderer = new BirdseyeRenderer(frames,assets);
        renderer.setMesh(new BirdseyeMesh(frames.calibration));
        java.util.Set<Integer> hoodColors = new java.util.HashSet<>();
        try (Pbuffer egl = new Pbuffer()) {
            renderer.onSurfaceCreated(null,null); renderer.onSurfaceChanged(null,W,H);
            for (BirdseyeCarColor color : BirdseyeCarColor.values()) {
                renderer.setCarColor(color); renderer.onDrawFrame(null);
                assertNull(renderer.error); assertTrue(color.id,renderer.hasCarArtwork);
                assertEquals(15,renderer.freshMask);
                hoodColors.add(groundPixel(read(),0,-1700));
                int corner=groundPixel(read(),900,-2100),expected=BirdseyeSynthetic.groundColor(900,-2100);
                for(int shift=0;shift<24;shift+=8)assertEquals("No rectangle at the rounded body corner: "+color.id,
                        (expected>>shift)&255,(corner>>shift)&255,5);
            }
            assertEquals(BirdseyeCarColor.values().length,hoodColors.size());
        }
    }

}
