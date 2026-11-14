package com.drivehub.kamera.rivela;

/**
 * GLSL ES 2.0 shaders for drawing a {@link Bowl}.
 * <p>
 * Contributed by andneg7 (Rivela 360). The vertex shader passes the four texture coordinates and weights; the fragment
 * shader samples each camera only where its weight is not negligible and mixes them. Two fragment shaders are
 * provided: one for RGBA camera textures, one for planar YUV (a luminance texture plus an interleaved chroma texture
 * uploaded as LUMINANCE_ALPHA, which is what Rivela 360 uses to avoid converting frames on the CPU).
 * <p>
 * Attributes (stride {@link Bowl#STRIDE} floats): aPos = 3 floats at 0, aT0..aT3 = 2 floats at 3, 5, 7, 9,
 * aW = 4 floats at 11. Uniform uMvp: projection * view (world in mm, z down; with android.opengl.Matrix.setLookAtM
 * use up = (0, 0, -1)).
 */
public final class BowlShaders {
    private BowlShaders() {}

    public static final String PRECISION =
        "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n";

    public static final String VERTEX =
        "uniform mat4 uMvp; attribute vec3 aPos; attribute vec2 aT0; attribute vec2 aT1; attribute vec2 aT2; attribute vec2 aT3; attribute vec4 aW;\n" +
        "varying vec2 t0; varying vec2 t1; varying vec2 t2; varying vec2 t3; varying vec4 w;\n" +
        "void main() { gl_Position = uMvp * vec4(aPos, 1.0); t0 = aT0; t1 = aT1; t2 = aT2; t3 = aT3; w = aW; }\n";

    /** camera textures: tex0..tex3 (LEFT, RIGHT, FRONT, REAR), RGBA */
    public static final String FRAGMENT_RGBA = PRECISION +
        "uniform sampler2D tex0; uniform sampler2D tex1; uniform sampler2D tex2; uniform sampler2D tex3;\n" +
        "varying vec2 t0; varying vec2 t1; varying vec2 t2; varying vec2 t3; varying vec4 w;\n" +
        "void main() {\n" +
        "  vec3 c = vec3(0.0); float s = 0.0;\n" +
        "  if (w.x > 0.004) { c += w.x * texture2D(tex0, t0).rgb; s += w.x; }\n" +
        "  if (w.y > 0.004) { c += w.y * texture2D(tex1, t1).rgb; s += w.y; }\n" +
        "  if (w.z > 0.004) { c += w.z * texture2D(tex2, t2).rgb; s += w.z; }\n" +
        "  if (w.w > 0.004) { c += w.w * texture2D(tex3, t3).rgb; s += w.w; }\n" +
        "  gl_FragColor = vec4(s > 0.0 ? c / s : vec3(0.0), 1.0);\n" +
        "}\n";

    /** camera textures as planar YUV: y0..y3 (LUMINANCE) and c0..c3 (LUMINANCE_ALPHA with U in .r and V in .a) */
    public static final String FRAGMENT_YUV = PRECISION +
        "uniform sampler2D y0; uniform sampler2D c0; uniform sampler2D y1; uniform sampler2D c1;\n" +
        "uniform sampler2D y2; uniform sampler2D c2; uniform sampler2D y3; uniform sampler2D c3;\n" +
        "varying vec2 t0; varying vec2 t1; varying vec2 t2; varying vec2 t3; varying vec4 w;\n" +
        "vec3 yuv(sampler2D sy, sampler2D sc, vec2 t) {\n" +
        "  float Y = 1.164 * (texture2D(sy, t).r - 0.0627);\n" +
        "  vec2 c = texture2D(sc, t).ra - 0.5;\n" +
        "  return vec3(Y + 1.596 * c.y, Y - 0.392 * c.x - 0.813 * c.y, Y + 2.017 * c.x);\n" +
        "}\n" +
        "void main() {\n" +
        "  vec3 c = vec3(0.0); float s = 0.0;\n" +
        "  if (w.x > 0.004) { c += w.x * yuv(y0, c0, t0); s += w.x; }\n" +
        "  if (w.y > 0.004) { c += w.y * yuv(y1, c1, t1); s += w.y; }\n" +
        "  if (w.z > 0.004) { c += w.z * yuv(y2, c2, t2); s += w.z; }\n" +
        "  if (w.w > 0.004) { c += w.w * yuv(y3, c3, t3); s += w.w; }\n" +
        "  gl_FragColor = vec4(s > 0.0 ? c / s : vec3(0.0), 1.0);\n" +
        "}\n";
}
