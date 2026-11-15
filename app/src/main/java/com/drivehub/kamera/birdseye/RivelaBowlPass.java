package com.drivehub.kamera.birdseye;

import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import com.drivehub.kamera.parking.PdcGeometry;
import com.drivehub.kamera.rivela.Bowl;
import com.drivehub.kamera.rivela.BowlShaders;
import java.nio.*;

/** Android adapter for andneg7's original Bowl and shader contribution (PR #102, GPL-3.0). */
final class RivelaBowlPass {
    private int bowlProgram,modelProgram,atlas;
    private final int[] buffers=new int[3];
    private RivelaScene uploadedScene;
    private VehicleModel uploadedModel;
    private VehicleModel.Paint uploadedPaint;
    private final float[] projection=new float[16],view=new float[16],mvp=new float[16];
    private PdcGroundPass pdc;
    private static String packedFragment() {
        String shader=BowlShaders.FRAGMENT_RGBA;
        shader=shader.replace("varying vec2 t0;", "uniform vec4 uFresh; varying vec2 t0;");
        shader=shader.replace("void main() {",PackedUyvyShader.SAMPLING+"\nvoid main() { vec4 live=max(w,vec4(0.0))*uFresh;");
        for(String component:new String[]{"x","y","z","w"})shader=shader.replace("w."+component,"live."+component);
        for(int i=0;i<4;i++)shader=shader.replace("texture2D(tex"+i+", t"+i+").rgb","camera(tex"+i+",t"+i+"*vec2(720.0,480.0))");
        return shader;
    }
    RivelaBowlPass() {
        try {
            bowlProgram=GlPrograms.link(BowlShaders.VERTEX,packedFragment());
            modelProgram=GlPrograms.link(
                "uniform mat4 uMvp;uniform vec3 uCenter,uPivot;uniform vec2 uSteer;"
                +"attribute vec3 aPos,aNormal;attribute vec2 aUv;varying vec2 uv;varying vec3 normal,world;"
                +"void main(){mat2 rotation=mat2(uSteer.x,uSteer.y,-uSteer.y,uSteer.x);"
                +"vec3 p=aPos-uPivot;p.xy=rotation*p.xy;normal=aNormal;normal.xy=rotation*normal.xy;"
                +"world=p+uPivot+uCenter;gl_Position=uMvp*vec4(world,1.0);uv=vec2(aUv.x,1.0-aUv.y);}",
                BowlShaders.PRECISION+"uniform sampler2D uAtlas;uniform vec3 uEye;uniform vec4 uPaintRect;"
                +"uniform vec2 uFinish;varying vec2 uv;varying vec3 normal,world;void main(){"
                +"vec3 base=texture2D(uAtlas,uv).rgb,n=normalize(normal),l=normalize(vec3(-0.3,-0.4,-1.0));"
                +"float light=0.45+0.55*max(0.0,dot(n,l));"
                +"float paint=step(uPaintRect.x,uv.x)*step(uPaintRect.y,uv.y)*step(uv.x,uPaintRect.z)*step(uv.y,uPaintRect.w);"
                +"vec3 h=normalize(l+normalize(uEye-world));float rough=uFinish.y;"
                +"float exponent=clamp(2.0/max(0.0001,rough*rough)-2.0,2.0,180.0);"
                +"float highlight=paint*pow(max(0.0,dot(n,h)),exponent)*mix(0.24,0.025,rough);"
                +"vec3 specular=mix(vec3(1.0),base,uFinish.x*0.65);"
                +"gl_FragColor=vec4(clamp(base*light+specular*highlight,0.0,1.0),1.0);}");
            GLES20.glGenBuffers(3,buffers,0);int[] t=new int[1];GLES20.glGenTextures(1,t,0);atlas=t[0];
            pdc=new PdcGroundPass();GlPrograms.check();
        }catch(RuntimeException e){close();throw e;}
    }
    void draw(int width,int height,RivelaScene scene,VehicleModel model,BirdseyeCarColor color,int[] textures,int freshMask,OrbitCamera orbit,float steeringDegrees,PdcGeometry geometry,long nowMs) {
        if(scene!=uploadedScene) {
            Bowl mesh=scene.bowl();
            FloatBuffer v=ByteBuffer.allocateDirect(mesh.vertices.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();v.put(mesh.vertices).flip();
            ShortBuffer idx=ByteBuffer.allocateDirect(mesh.indices.length*2).order(ByteOrder.nativeOrder()).asShortBuffer();idx.put(mesh.indices).flip();
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,buffers[0]);GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,mesh.vertices.length*4,v,GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER,buffers[1]);GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER,mesh.indices.length*2,idx,GLES20.GL_STATIC_DRAW);uploadedScene=scene;
        }
        if(model!=uploadedModel) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,buffers[2]);FloatBuffer data=model.vertices.duplicate();data.position(0);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,data.remaining()*4,data,GLES20.GL_STATIC_DRAW);
            uploadedModel=model;uploadedPaint=null;
        }
        VehicleModel.Paint paint=model.paint(color);
        if(paint!=uploadedPaint) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE4);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,atlas);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,paint.atlas,0);uploadedPaint=paint;
        }
        float[] pose=orbit.values();double yaw=Math.toRadians(pose[0]),pitch=Math.toRadians(pose[1]);
        float cx=(float)scene.car.centerX,cy=(float)scene.car.centerY,distance=pose[2];
        Matrix.perspectiveM(projection,0,45,Math.max(1,width)/(float)Math.max(1,height),150,60000);
        float eyeX=cx+(float)(Math.sin(yaw)*Math.cos(pitch))*distance,
                eyeY=cy+(float)(Math.cos(yaw)*Math.cos(pitch))*distance,eyeZ=-(float)Math.sin(pitch)*distance;
        Matrix.setLookAtM(view,0,eyeX,eyeY,eyeZ,cx,cy,-400,0,0,-1);
        Matrix.multiplyMM(mvp,0,projection,0,view,0);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);GLES20.glDepthFunc(GLES20.GL_LEQUAL);GLES20.glDepthMask(true);GLES20.glDisable(GLES20.GL_CULL_FACE);GLES20.glDisable(GLES20.GL_BLEND);
        // The contributed grid winds toward the bowl exterior (floor normal +Z/down).
        // Draw its interior only: an outside orbit must see through the near wall to the car.
        GLES20.glFrontFace(GLES20.GL_CW);GLES20.glCullFace(GLES20.GL_BACK);GLES20.glEnable(GLES20.GL_CULL_FACE);
        disableAttributes();GLES20.glUseProgram(bowlProgram);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(bowlProgram,"uMvp"),1,false,mvp,0);
        GLES20.glUniform4f(GLES20.glGetUniformLocation(bowlProgram,"uFresh"),freshMask&1,(freshMask>>1)&1,(freshMask>>2)&1,(freshMask>>3)&1);
        for(int i=0;i<4;i++) {GLES20.glActiveTexture(GLES20.GL_TEXTURE0+i);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textures[i]);GLES20.glUniform1i(GLES20.glGetUniformLocation(bowlProgram,"tex"+i),i);}
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,buffers[0]);GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER,buffers[1]);
        attribute(bowlProgram,"aPos",3,60,0);
        for(int i=0;i<4;i++)attribute(bowlProgram,"aT"+i,2,60,(3+2*i)*4);
        attribute(bowlProgram,"aW",4,60,44);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES,scene.bowl().indices.length,GLES20.GL_UNSIGNED_SHORT,0);
        GLES20.glDisable(GLES20.GL_CULL_FACE);GLES20.glFrontFace(GLES20.GL_CCW);
        disableAttributes();
        if(geometry!=null&&(freshMask&8)!=0)pdc.draw(mvp,scene,geometry.bars(scene.car,nowMs));
        disableAttributes();GLES20.glUseProgram(modelProgram);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(modelProgram,"uMvp"),1,false,mvp,0);
        GLES20.glUniform3f(GLES20.glGetUniformLocation(modelProgram,"uCenter"),cx,cy,0);
        GLES20.glUniform3f(GLES20.glGetUniformLocation(modelProgram,"uEye"),eyeX,eyeY,eyeZ);
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(modelProgram,"uPaintRect"),1,model.paintRect,0);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(modelProgram,"uFinish"),paint.metallic,paint.roughness);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,atlas);GLES20.glUniform1i(GLES20.glGetUniformLocation(modelProgram,"uAtlas"),4);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,buffers[2]);GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER,0);
        attribute(modelProgram,"aPos",3,32,0);attribute(modelProgram,"aNormal",3,32,12);attribute(modelProgram,"aUv",2,32,24);
        int pivotUniform=GLES20.glGetUniformLocation(modelProgram,"uPivot"),steerUniform=GLES20.glGetUniformLocation(modelProgram,"uSteer");
        double angle=Math.toRadians(steeringDegrees);float cosine=(float)Math.cos(angle),sine=(float)Math.sin(angle);
        for(VehicleModel.Part part:model.parts) {
            GLES20.glUniform3f(pivotUniform,part.pivotX,part.pivotY,part.pivotZ);
            GLES20.glUniform2f(steerUniform,part.steers?cosine:1,part.steers?sine:0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,part.firstVertex,part.vertexCount);
        }
        disableAttributes();GLES20.glDisable(GLES20.GL_DEPTH_TEST);GlPrograms.check();
    }
    private static void attribute(int program,String name,int size,int stride,int offset) {
        int a=GLES20.glGetAttribLocation(program,name);if(a>=0){GLES20.glEnableVertexAttribArray(a);GLES20.glVertexAttribPointer(a,size,GLES20.GL_FLOAT,false,stride,offset);}
    }
    static void disableAttributes() {for(int i=0;i<8;i++)GLES20.glDisableVertexAttribArray(i);}
    void close() {
        if(bowlProgram!=0)GLES20.glDeleteProgram(bowlProgram);if(modelProgram!=0)GLES20.glDeleteProgram(modelProgram);
        GLES20.glDeleteBuffers(3,buffers,0);if(atlas!=0)GLES20.glDeleteTextures(1,new int[]{atlas},0);
        if(pdc!=null)pdc.close();
    }
}
