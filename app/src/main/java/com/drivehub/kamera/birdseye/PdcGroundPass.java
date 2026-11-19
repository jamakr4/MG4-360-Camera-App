package com.drivehub.kamera.birdseye;
import android.opengl.GLES20;
import com.drivehub.kamera.rivela.PdcBars;
import java.nio.*;
import java.util.List;
/** The original Rivela ground bands, reused in the orthographic and orbit views. */
final class PdcGroundPass {
    private final int program=GlPrograms.link(
        "uniform mat4 uMvp;attribute vec3 aPos;varying vec2 world;void main(){world=aPos.xy;gl_Position=uMvp*vec4(aPos,1.0);}",
        com.drivehub.kamera.rivela.BowlShaders.PRECISION+"uniform vec4 uColor,uCar,uReveal;uniform vec3 uFadePlane;uniform float uFadeMm,uOpacity;varying vec2 world;"
        +"void main(){if(abs(world.x-uCar.x)<uCar.z&&abs(world.y-uCar.y)<uCar.w)discard;"
        +"float fade=uFadeMm>0.0?smoothstep(0.0,uFadeMm,dot(uFadePlane,vec3(world,1.0))):1.0;"
        +"float reveal=uReveal.w>0.0?smoothstep(0.0,uReveal.w,dot(uReveal.xyz,vec3(world,1.0))):1.0;"
        +"gl_FragColor=vec4(uColor.rgb,uColor.a*fade*reveal*uOpacity);}");
    private float opacity=1;
    void setOpacity(float opacity){this.opacity=Math.max(0,Math.min(1,opacity));}
    private FloatBuffer vertices=ByteBuffer.allocateDirect(16*6*3*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    void draw(float[] mvp,RivelaScene scene,List<PdcBars.Bar> bars) {
        draw(mvp,scene,bars,true);
    }
    void draw(float[] mvp,RivelaScene scene,List<PdcBars.Bar> bars,boolean maskBody) {
        draw(mvp,scene,bars,maskBody,0,null);
    }
    /** A shared end plane fades a corridor together; null uses each line's final tangent. */
    void draw(float[] mvp,RivelaScene scene,List<PdcBars.Bar> bars,boolean maskBody,float fadeMm,float[] fadePlane) {
        draw(mvp,scene,bars,maskBody,fadeMm,fadePlane,null);
    }
    void draw(float[] mvp,RivelaScene scene,List<PdcBars.Bar> bars,boolean maskBody,float fadeMm,float[] fadePlane,float[] revealPlane) {
        if(bars.isEmpty())return;
        int pos=bind(mvp,scene,maskBody);
        reveal(revealPlane);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uFadeMm"),fadeMm);
        if(fadeMm>0)beginBlend();
        try {
        for(PdcBars.Bar bar:bars) {
            if(fadeMm>0) {
                if(fadePlane!=null)GLES20.glUniform3fv(GLES20.glGetUniformLocation(program,"uFadePlane"),1,fadePlane,0);
                else lineFade(bar.xy);
            }
            int required=Math.max(0,bar.xy.length/2-1)*6*3;
            ensureCapacity(required);
            vertices.clear();
            for(int i=0;i+3<bar.xy.length;i+=2) {
                double x=bar.xy[i],y=bar.xy[i+1],xx=bar.xy[i+2],yy=bar.xy[i+3];
                double length=Math.hypot(xx-x,yy-y);if(length<1e-6)continue;
                double dx=-(yy-y)/length*bar.widthMm/2,dy=(xx-x)/length*bar.widthMm/2;
                put(x+dx,y+dy);put(x-dx,y-dy);put(xx+dx,yy+dy);
                put(xx+dx,yy+dy);put(x-dx,y-dy);put(xx-dx,yy-dy);
            }
            vertices.flip();GLES20.glVertexAttribPointer(pos,3,GLES20.GL_FLOAT,false,12,vertices);
            color(bar.color);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,vertices.limit()/3);
        }
        }finally {
            if(fadeMm>0)endBlend();
            GLES20.glDisableVertexAttribArray(pos);
        }
    }
    /** Single translucent mesh avoids dark seams from painting overlapping wide bands. */
    void drawFill(float[] mvp,RivelaScene scene,double[] triangles,int argb) {
        drawFill(mvp,scene,triangles,argb,false);
    }
    void drawFill(float[] mvp,RivelaScene scene,double[] triangles,int argb,boolean maskBody) {
        drawFill(mvp,scene,triangles,argb,maskBody,0,null);
    }
    void drawFill(float[] mvp,RivelaScene scene,double[] triangles,int argb,boolean maskBody,float fadeMm,float[] fadePlane) {
        drawFill(mvp,scene,triangles,argb,maskBody,fadeMm,fadePlane,null);
    }
    void drawFill(float[] mvp,RivelaScene scene,double[] triangles,int argb,boolean maskBody,float fadeMm,float[] fadePlane,float[] revealPlane) {
        if(triangles.length==0)return;
        int pos=bind(mvp,scene,maskBody);
        reveal(revealPlane);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uFadeMm"),fadeMm);
        if(fadePlane!=null)GLES20.glUniform3fv(GLES20.glGetUniformLocation(program,"uFadePlane"),1,fadePlane,0);
        ensureCapacity(triangles.length/2*3);vertices.clear();
        for(int i=0;i<triangles.length;i+=2)vertices.put((float)triangles[i]).put((float)triangles[i+1]).put(-1);
        vertices.flip();GLES20.glVertexAttribPointer(pos,3,GLES20.GL_FLOAT,false,12,vertices);
        color(argb);
        beginBlend();
        try {GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,vertices.limit()/3);}
        finally {endBlend();GLES20.glDisableVertexAttribArray(pos);}
    }
    private int bind(float[] mvp,RivelaScene scene,boolean maskBody) {
        GLES20.glUseProgram(program);GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,0);GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER,0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uOpacity"),opacity);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"uMvp"),1,false,mvp,0);
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program,"uCar"),(float)scene.car.centerX,(float)scene.car.centerY,maskBody?(float)scene.car.width/2:0,maskBody?(float)scene.car.length/2:0);
        int pos=GLES20.glGetAttribLocation(program,"aPos");GLES20.glEnableVertexAttribArray(pos);return pos;
    }
    private void ensureCapacity(int required) {
        if(required>vertices.capacity())vertices=ByteBuffer.allocateDirect(required*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }
    private void color(int argb) {
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program,"uColor"),((argb>>16)&255)/255f,((argb>>8)&255)/255f,(argb&255)/255f,(argb>>>24)/255f);
    }
    private void reveal(float[] plane) {
        int uniform=GLES20.glGetUniformLocation(program,"uReveal");
        if(plane==null)GLES20.glUniform4f(uniform,0,0,0,0);
        else GLES20.glUniform4fv(uniform,1,plane,0);
    }
    private void lineFade(double[] xy) {
        int end=xy.length-2;
        if(end<2)return;
        double dx=xy[end-2]-xy[end],dy=xy[end-1]-xy[end+1],length=Math.hypot(dx,dy);
        if(length<1e-6)return;
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program,"uFadePlane"),(float)(dx/length),(float)(dy/length),
                (float)(-(dx*xy[end]+dy*xy[end+1])/length));
    }
    private void beginBlend() {
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE_MINUS_SRC_ALPHA,GLES20.GL_ONE,GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
    }
    private void endBlend() { GLES20.glDepthMask(true);GLES20.glDisable(GLES20.GL_BLEND); }
    void close() { GLES20.glDeleteProgram(program); }
    private void put(double x,double y) { vertices.put((float)x).put((float)y).put(-2); }
}
