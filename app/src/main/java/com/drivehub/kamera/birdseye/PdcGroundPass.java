package com.drivehub.kamera.birdseye;
import android.opengl.GLES20;
import com.drivehub.kamera.rivela.PdcBars;
import java.nio.*;
import java.util.List;
/** The original Rivela ground bands, reused in the orthographic and orbit views. */
final class PdcGroundPass {
    private final int program=GlPrograms.link(
        "uniform mat4 uMvp;attribute vec3 aPos;varying vec2 world;void main(){world=aPos.xy;gl_Position=uMvp*vec4(aPos,1.0);}",
        com.drivehub.kamera.rivela.BowlShaders.PRECISION+"uniform vec4 uColor,uCar;varying vec2 world;void main(){if(abs(world.x-uCar.x)<uCar.z&&abs(world.y-uCar.y)<uCar.w)discard;gl_FragColor=uColor;}");
    private final FloatBuffer vertices=ByteBuffer.allocateDirect(16*6*3*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    void draw(float[] mvp,RivelaScene scene,List<PdcBars.Bar> bars) {
        if(bars.isEmpty())return;
        GLES20.glUseProgram(program);GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,0);GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER,0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"uMvp"),1,false,mvp,0);
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program,"uCar"),(float)scene.car.centerX,(float)scene.car.centerY,(float)scene.car.width/2,(float)scene.car.length/2);
        int pos=GLES20.glGetAttribLocation(program,"aPos");GLES20.glEnableVertexAttribArray(pos);
        for(PdcBars.Bar bar:bars) {
            vertices.clear();
            for(int i=0;i+3<bar.xy.length;i+=2) {
                double x=bar.xy[i],y=bar.xy[i+1],xx=bar.xy[i+2],yy=bar.xy[i+3];
                double length=Math.hypot(xx-x,yy-y);if(length<1e-6)continue;
                double dx=-(yy-y)/length*bar.widthMm/2,dy=(xx-x)/length*bar.widthMm/2;
                put(x+dx,y+dy);put(x-dx,y-dy);put(xx+dx,yy+dy);
                put(xx+dx,yy+dy);put(x-dx,y-dy);put(xx-dx,yy-dy);
            }
            vertices.flip();GLES20.glVertexAttribPointer(pos,3,GLES20.GL_FLOAT,false,12,vertices);
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program,"uColor"),((bar.color>>16)&255)/255f,((bar.color>>8)&255)/255f,(bar.color&255)/255f,1);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,vertices.limit()/3);
        }
        GLES20.glDisableVertexAttribArray(pos);
    }
    void close() { GLES20.glDeleteProgram(program); }
    private void put(double x,double y) { vertices.put((float)x).put((float)y).put(-2); }
}
