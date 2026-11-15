package com.drivehub.kamera.birdseye;
import android.opengl.GLES20;
/** 720x240 top-field sampler, reference pixels 720x480.
 * Sample exact packed-column centers: hardware interpolates rows, never U/Y/V
 * channels or adjacent pixel pairs. Horizontal luma parity stays explicit.
 * Mediump-only GPUs keep nearest filtering and the original four-tap path:
 * their texture-coordinate precision may be insufficient for exact centers. */
final class PackedUyvyShader {
    static int textureFilter() {
        int[] range=new int[2],precision=new int[1];
        GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER,GLES20.GL_HIGH_FLOAT,range,0,precision,0);
        return precision[0]>0?GLES20.GL_LINEAR:GLES20.GL_NEAREST;
    }
    static final String SAMPLING =
            "vec3 uyvyPixel(sampler2D t,vec2 p){vec4 q=texture2D(t,vec2((floor(p.x/2.0)+0.5)/360.0,(p.y+0.5)/240.0));" +
            "return vec3(mod(p.x,2.0)<0.5?q.g:q.a,q.r,q.b);}\n" +
            "vec3 camera(sampler2D t,vec2 uv){vec2 p=clamp(vec2(uv.x,uv.y*0.5),vec2(0.0),vec2(719.0,239.0));" +
            "\n#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "float x=floor(p.x);vec3 c=mix(uyvyPixel(t,vec2(x,p.y))," +
            "uyvyPixel(t,vec2(min(x+1.0,719.0),p.y)),fract(p.x));" +
            "\n#else\n" +
            "vec2 a=floor(p),d=fract(p);vec3 c=mix(mix(uyvyPixel(t,a),uyvyPixel(t,vec2(min(a.x+1.0,719.0),a.y)),d.x)," +
            "mix(uyvyPixel(t,vec2(a.x,min(a.y+1.0,239.0))),uyvyPixel(t,min(a+1.0,vec2(719.0,239.0))),d.x),d.y);" +
            "\n#endif\n" +
            "float y=1.16438356*(c.x-16.0/255.0),u=c.y-128.0/255.0,v=c.z-128.0/255.0;" +
            "return clamp(vec3(y+1.59602678*v,y-0.39176229*u-0.81296765*v,y+2.01723214*u),0.0,1.0);}\n";
}
