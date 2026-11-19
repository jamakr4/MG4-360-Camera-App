package com.drivehub.kamera.birdseye;

import android.opengl.GLES20;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Straight-alpha vehicle artwork above the 2D ground overlays, including transparent corners. */
final class BirdseyeCarPass {
    private final int program=GlPrograms.link(
            "attribute vec2 aUv;uniform vec4 uRect;uniform vec2 uScale;varying vec2 uv;void main(){uv=aUv;"
            +"vec2 ground=uRect.xy+aUv*uRect.zw;gl_Position=vec4(ground*uScale,0.0,1.0);}",
            com.drivehub.kamera.rivela.BowlShaders.PRECISION
            +"uniform sampler2D uCar;varying vec2 uv;void main(){gl_FragColor=texture2D(uCar,uv);}");
    private final FloatBuffer quad=ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    BirdseyeCarPass(){quad.put(new float[]{0,0,1,0,0,1,1,1}).flip();}
    void draw(int texture,BirdseyeCarGeometry geometry,float groundWidth,float groundHeight) {
        GLES20.glUseProgram(program);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,0);GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER,0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uCar"),4);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program,"uScale"),2/groundWidth,-2/groundHeight);
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program,"uRect"),geometry.leftMm,geometry.topMm,geometry.widthMm,geometry.heightMm);
        int pos=GLES20.glGetAttribLocation(program,"aUv");quad.position(0);
        GLES20.glEnableVertexAttribArray(pos);GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,0,quad);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE_MINUS_SRC_ALPHA,GLES20.GL_ONE,GLES20.GL_ONE_MINUS_SRC_ALPHA);
        try{GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);}
        finally{GLES20.glDisable(GLES20.GL_BLEND);GLES20.glDisableVertexAttribArray(pos);}
    }
}
