package com.drivehub.kamera.side;

import android.opengl.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.nio.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SideCameraRendererInstrumentedTest {
    @Test public void yuvRotationWheelMappingAndSingleCameraLoss() throws Exception {
        withGl(() -> {
            SideCameraRenderer renderer=new SideCameraRenderer();
            try {
                ByteBuffer y=ByteBuffer.allocateDirect(720*480),uv=ByteBuffer.allocateDirect(720*240);
                for(int row=0;row<480;row++)for(int x=0;x<720;x++)y.put((byte)(16+219*x/719));
                for(int i=0;i<720*240;i++)uv.put((byte)128);
                y.flip();uv.flip();
                for(int side=0;side<2;side++)upload(renderer,side,y,uv);
                for(boolean wheels:new boolean[]{true,false}) {
                    clear();renderer.draw(wheels,3,540,640);
                    assertTrue("Left front",gray(100,100)>220);
                    assertTrue("Left rear",gray(100,510)<35);
                    assertTrue("Right front",gray(400,100)<35);
                    assertTrue("Right rear",gray(400,510)>220);
                    assertEquals(GLES20.GL_NO_ERROR,GLES20.glGetError());
                }
                clear();renderer.draw(true,1,540,640);
                assertTrue(gray(100,100)>220);assertEquals(0,gray(400,100));
                clear();renderer.draw(true,2,540,640);
                assertEquals(0,gray(100,100));assertTrue(gray(400,510)>220);
                clear();renderer.draw(true,0,540,640);
                assertEquals(0,gray(100,100));assertEquals(0,gray(400,100));
                assertEquals("No capture feeds should be opened by renderer tests",0,
                        renderer.uploadFrames());
            } finally {renderer.release();}
        });
    }
    private static void upload(SideCameraRenderer r,int side,ByteBuffer y,ByteBuffer uv) {
        y.position(0);uv.position(0);GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT,1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,r.textures[side*2]);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,720,480,GLES20.GL_LUMINANCE,GLES20.GL_UNSIGNED_BYTE,y);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,r.textures[side*2+1]);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,360,240,GLES20.GL_LUMINANCE_ALPHA,GLES20.GL_UNSIGNED_BYTE,uv);
    }
    private static int gray(int x,int yFromTop) {
        ByteBuffer pixel=ByteBuffer.allocateDirect(4);
        GLES20.glReadPixels(x,639-yFromTop,1,1,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixel);
        int r=pixel.get(0)&255,g=pixel.get(1)&255,b=pixel.get(2)&255;
        assertTrue(Math.abs(r-g)<3&&Math.abs(g-b)<3);return r;
    }
    private static void clear() {
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);GLES20.glClearColor(0,0,0,1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    }
    private interface GlCheck {void run() throws Exception;}
    private static void withGl(GlCheck check) throws Exception {
        EGLDisplay display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        assertTrue(EGL14.eglInitialize(display,new int[2],0,new int[2],0));
        EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
        assertTrue(EGL14.eglChooseConfig(display,new int[]{EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,
                EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_NONE},0,configs,0,1,count,0));
        assertTrue(count[0]>0);
        EGLSurface surface=EGL14.eglCreatePbufferSurface(display,configs[0],new int[]{EGL14.EGL_WIDTH,540,EGL14.EGL_HEIGHT,640,EGL14.EGL_NONE},0);
        EGLContext context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
        try {assertTrue(EGL14.eglMakeCurrent(display,surface,surface,context));check.run();}
        finally {
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroyContext(display,context);EGL14.eglDestroySurface(display,surface);EGL14.eglTerminate(display);
        }
    }
}
