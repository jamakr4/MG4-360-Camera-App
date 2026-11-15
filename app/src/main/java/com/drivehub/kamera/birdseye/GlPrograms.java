package com.drivehub.kamera.birdseye;
import android.opengl.GLES20;
final class GlPrograms {
    static int link(String vs,String fs) {
        int v=shader(GLES20.GL_VERTEX_SHADER,vs),f=0,p=0;
        try { f=shader(GLES20.GL_FRAGMENT_SHADER,fs);p=GLES20.glCreateProgram();GLES20.glAttachShader(p,v);GLES20.glAttachShader(p,f);GLES20.glLinkProgram(p);
            int[] ok=new int[1];GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
            if(ok[0]==0)throw new IllegalStateException(GLES20.glGetProgramInfoLog(p));return p;
        }catch(RuntimeException e){if(p!=0)GLES20.glDeleteProgram(p);throw e;}
        finally{GLES20.glDeleteShader(v);if(f!=0)GLES20.glDeleteShader(f);}
    }
    private static int shader(int type,String text) {
        int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,text);GLES20.glCompileShader(s);
        int[] ok=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0){String error=GLES20.glGetShaderInfoLog(s);GLES20.glDeleteShader(s);throw new IllegalStateException(error);}return s;
    }
    static void check() {int e=GLES20.glGetError();if(e!=GLES20.GL_NO_ERROR)throw new IllegalStateException("GLES error 0x"+Integer.toHexString(e));}
}
