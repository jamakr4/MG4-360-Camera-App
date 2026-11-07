package com.drivehub.kamera.side;

import android.opengl.GLES20;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Two shared NV12 textures provide two full side views or four wheel views. No OEM SDK needed. */
final class SideCameraRenderer {
    final int[] textures = new int[4]; // left Y/UV, right Y/UV; GL-thread owned
    private final int program, position, uv;
    private final FloatBuffer[] sideVertices = new FloatBuffer[2];
    private final FloatBuffer[] wheelVertices = new FloatBuffer[4];

    SideCameraRenderer() {
        int vertex = shader(GLES20.GL_VERTEX_SHADER,
                "attribute vec2 p;attribute vec2 t;varying vec2 v;void main(){v=t;gl_Position=vec4(p,0.,1.);}");
        int fragment = shader(GLES20.GL_FRAGMENT_SHADER,
                "precision mediump float;varying vec2 v;uniform sampler2D yTex;uniform sampler2D uvTex;"
                + "void main(){float y=1.16438356*(texture2D(yTex,v).r-.0627451);"
                + "vec2 c=texture2D(uvTex,v).ra-vec2(.5019608);"
                + "gl_FragColor=vec4(y+1.596027*c.y,y-.391762*c.x-.812968*c.y,y+2.017232*c.x,1.);}");
        program=GLES20.glCreateProgram();
        GLES20.glAttachShader(program,vertex);GLES20.glAttachShader(program,fragment);
        GLES20.glLinkProgram(program);GLES20.glDeleteShader(vertex);GLES20.glDeleteShader(fragment);
        int[] ok=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0);
        if(ok[0]==0)throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
        position=GLES20.glGetAttribLocation(program,"p");uv=GLES20.glGetAttribLocation(program,"t");
        GLES20.glUseProgram(program);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"yTex"),0);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uvTex"),1);
        GLES20.glGenTextures(4,textures,0);
        for(int i=0;i<4;i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textures[i]);
            int format=i%2==0?GLES20.GL_LUMINANCE:GLES20.GL_LUMINANCE_ALPHA;
            int divisor=i%2==0?1:2;
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,format,720/divisor,480/divisor,0,
                    format,GLES20.GL_UNSIGNED_BYTE,null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        }
        for(int i=0;i<2;i++) {
            boolean left=i==0;
            sideVertices[i]=buffer(SideCameraGeometry.vertices(left,SideCameraGeometry.side(left)));
            wheelVertices[i]=buffer(SideCameraGeometry.vertices(left,SideCameraGeometry.wheel(left,true)));
            wheelVertices[i+2]=buffer(SideCameraGeometry.vertices(left,SideCameraGeometry.wheel(left,false)));
        }
    }
    int uploadFrames() {
        return SideCameraNative.updateTextures(textures[0],textures[1],textures[2],textures[3]);
    }
    void draw(boolean wheels,int freshMask,int canvasWidth,int canvasHeight) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_CULL_FACE);GLES20.glUseProgram(program);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,0);GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER,0);
        for(int side=0;side<2;side++) {
            if((freshMask&(1<<side))==0)continue;
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textures[side*2]);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textures[side*2+1]);
            if(wheels) {
                tile(side,76,224,wheelVertices[side],canvasWidth,canvasHeight);
                tile(side,308,224,wheelVertices[side+2],canvasWidth,canvasHeight);
            } else tile(side,76,456,sideVertices[side],canvasWidth,canvasHeight);
        }
        GLES20.glDisableVertexAttribArray(position);GLES20.glDisableVertexAttribArray(uv);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }
    private void tile(int side,int top,int height,FloatBuffer vertices,int w,int h) {
        int x0=Math.round((12+side*262)*w/540f),x1=Math.round((266+side*262)*w/540f);
        int y0=Math.round((640-top-height)*h/640f),y1=Math.round((640-top)*h/640f);
        GLES20.glViewport(x0,y0,x1-x0,y1-y0);
        vertices.position(0);GLES20.glEnableVertexAttribArray(position);
        GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,16,vertices);
        vertices.position(2);GLES20.glEnableVertexAttribArray(uv);
        GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,vertices);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
    }
    void release() { GLES20.glDeleteTextures(4,textures,0);GLES20.glDeleteProgram(program); }
    private static FloatBuffer buffer(float[] values) {
        FloatBuffer b=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(values).position(0);return b;
    }
    private static int shader(int type,String source) {
        int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,source);GLES20.glCompileShader(s);
        int[] ok=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0){String error=GLES20.glGetShaderInfoLog(s);GLES20.glDeleteShader(s);throw new IllegalStateException(error);}
        return s;
    }
}
