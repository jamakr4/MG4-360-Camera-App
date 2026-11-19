package com.drivehub.kamera.birdseye;

import android.content.res.AssetManager;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** GLES2 ground mesh, four raw UYVY textures and normalized independent weights. */
public final class BirdseyeRenderer implements GLSurfaceView.Renderer {
    public interface Frames {
        long copy(int camera, ByteBuffer destination, long afterUs);
        long clockUs();
        boolean allowed();
    }
    private final Frames frames;
    private final BirdseyeCarArtwork.Source artworkSource;
    private final java.util.function.LongSupplier guideClock;
    private final GuidelineAnimation guideAnimation;
    private final GuidelineFrame guideFrame=new GuidelineFrame();
    private final ByteBuffer upload = ByteBuffer.allocateDirect(720*240*2).order(ByteOrder.nativeOrder());
    private final long[] capturedUs = new long[4];
    private final int[] textures = new int[5], buffers = new int[2];
    private final int[] attributes = new int[5];
    private volatile BirdseyeMesh requestedMesh;
    private volatile BirdseyeMesh wideMesh;
    public volatile boolean wideRequested,wideActive;
    public volatile String wideError;
    private BirdseyeMesh uploadedMesh;
    private volatile BirdseyeCarColor requestedCarColor = BirdseyeCarColor.PICCADILLY_BLUE;
    private BirdseyeCarColor uploadedCarColor;
    private int program, freshUniform, width=450, height=640;
    public volatile boolean threeDRequested, threeDActive, depthAvailable;
    public volatile String threeDError;
    public volatile ThreeDFailure threeDFailure;
    private volatile RivelaScene scene;
    private volatile VehicleModel model;
    private com.drivehub.kamera.parking.PdcGeometry pdcGeometry;
    public final OrbitCamera orbit=new OrbitCamera();
    public final WheelSteering steering=new WheelSteering();
    private volatile boolean wheelGuidePreview;
    volatile float guideProgress,outgoingGuideProgress;
    private volatile com.drivehub.kamera.steering.WheelGuides.Style guideStyle=com.drivehub.kamera.steering.WheelGuides.Style.SIMPLE;
    private volatile com.drivehub.kamera.steering.WheelGuides.Direction guideDirection=com.drivehub.kamera.steering.WheelGuides.Direction.REVERSE;
    public void setWheelGuidePreview(boolean enabled){wheelGuidePreview=enabled;}
    public void setBlueGuideCorridor(boolean enabled){guideStyle=enabled?com.drivehub.kamera.steering.WheelGuides.Style.SIMPLE:com.drivehub.kamera.steering.WheelGuides.Style.LINES;}
    public void setGuideStyle(com.drivehub.kamera.steering.WheelGuides.Style style){guideStyle=java.util.Objects.requireNonNull(style);}
    public void setGuideDirection(com.drivehub.kamera.steering.WheelGuides.Direction direction){guideDirection=java.util.Objects.requireNonNull(direction);}
    private RivelaBowlPass bowlPass;
    private PdcGroundPass pdcPass;
    private WheelGuidePass guidePass;
    private BirdseyeCarPass carPass;
    private BirdseyeCarGeometry carArtworkGeometry;
    private boolean pdcUnavailable;
    private boolean guidesUnavailable;
    private final float[] topMvp=new float[16];
    public void setScene(RivelaScene scene) { this.scene=scene; }
    public void setModel(VehicleModel model) { this.model=model; }
    public void setPdcGeometry(com.drivehub.kamera.parking.PdcGeometry geometry) { pdcGeometry=geometry; }
    public void setThreeD(boolean enabled) { if(enabled){threeDError=null;threeDFailure=null;}threeDRequested=enabled; }
    public void failThreeD(ThreeDFailure failure) { threeDFailure=failure;threeDError=failure.getMessage();threeDRequested=false; }

    private volatile int resetGeneration;
    private int appliedGeneration;
    public volatile int freshMask,displayMask;
    public volatile String error;
    public volatile long renderedFrames, lastDrawCpuUs, lastDrawElapsedMs;
    public volatile int contextGeneration;
    public volatile boolean hasCarArtwork;
    private long statsStartedNs, statsFrames, statsSubmitUs;
    private final long[] statsUploads = new long[4];
    private static volatile String latestTiming = "Birdseye: no active renderer";
    private static volatile String samplingDevice = "UYVY/GPU: not initialized";
    private static volatile long timingAtMs;
    public static String timingStatus() {
        return latestTiming + "\n" + samplingDevice + "\nUpdated " + Math.max(0, SystemClock.elapsedRealtime()-timingAtMs) + " ms ago"
                + "\n" + BirdseyeSurface.presentationStatus();
    }
    private void recordTiming(long now) {
        if(statsStartedNs==0)statsStartedNs=now;
        statsFrames++;statsSubmitUs+=lastDrawCpuUs;
        double seconds=(now-statsStartedNs)/1e9;
        if(seconds<1)return;
        latestTiming=String.format(java.util.Locale.ROOT,
                "Birdseye %s: %.1f draw FPS, %.2f ms submit\nSampled camera FPS L/R/F/B: %.1f / %.1f / %.1f / %.1f",
                threeDActive?"3D":"2D",statsFrames/seconds,statsSubmitUs/(1000.0*statsFrames),
                statsUploads[0]/seconds,statsUploads[1]/seconds,statsUploads[2]/seconds,statsUploads[3]/seconds);
        timingAtMs=SystemClock.elapsedRealtime();statsStartedNs=now;statsFrames=0;statsSubmitUs=0;Arrays.fill(statsUploads,0);
    }

    public BirdseyeRenderer(Frames frames) { this(frames, (BirdseyeCarArtwork.Source)null); }
    public BirdseyeRenderer(Frames frames, AssetManager assets) {
        this(frames, assets == null ? (BirdseyeCarArtwork.Source)null : color -> BirdseyeCarArtwork.load(assets, color));
    }
    BirdseyeRenderer(Frames frames, BirdseyeCarArtwork.Source artworkSource) {
        this(frames,artworkSource,SystemClock::elapsedRealtime,GuidelineAnimation.DURATION_MS);
    }
    BirdseyeRenderer(Frames frames,BirdseyeCarArtwork.Source artworkSource,
                     java.util.function.LongSupplier guideClock,long guideDurationMs) {
        this.frames = frames; this.artworkSource = artworkSource;
        this.guideClock=guideClock;guideAnimation=new GuidelineAnimation(guideDurationMs);
    }
    public void setCarColor(BirdseyeCarColor color) {
        requestedCarColor = color == null ? BirdseyeCarColor.PICCADILLY_BLUE : color;
    }
    public void setMesh(BirdseyeMesh mesh) { requestedMesh = mesh; invalidateFrames(); }
    public void setWideMesh(BirdseyeMesh mesh,String error){wideMesh=mesh;wideError=error;}
    public void setWide(boolean enabled){wideRequested=enabled;}
    public void invalidateFrames() { resetGeneration++; freshMask = 0; displayMask = 0; }

    private static final String VERTEX =
            "attribute vec2 aPosition;\n" +
            "attribute vec3 aCam0,aCam1,aCam2,aCam3;\n" +
            "uniform vec2 uGroundHalf;varying vec3 vCam0,vCam1,vCam2,vCam3; varying vec2 vGround;\n" +
            "void main(){gl_Position=vec4(aPosition,0.0,1.0);" +
            "vGround=aPosition*uGroundHalf;" +
            "vCam0=aCam0;vCam1=aCam1;vCam2=aCam2;vCam3=aCam3;}";
    private static final String FRAGMENT =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n" +
            "uniform sampler2D uCam0,uCam1,uCam2,uCam3; uniform vec4 uFresh; uniform float uCarReady;\n" +
            "varying vec3 vCam0,vCam1,vCam2,vCam3; varying vec2 vGround;\n" +
            // Reference (u,v) -> top-field (u,v/2). Top-field pixels occupy rows
            // 0,2,...478; interpolate between those actual positions, clamp the last line.
            PackedUyvyShader.SAMPLING +
            "void main(){vec2 car=vec2(" + BirdseyeMesh.CAR_WIDTH/2 + "," + BirdseyeMesh.CAR_LENGTH/2 + ");" +
            // A valid RGBA car uses its own silhouette; retain the metric mask only if artwork is absent.
            "vec2 g=abs(vGround);vec3 color=vec3(0.0);if(uCarReady>0.5 || g.x>=car.x || g.y>=car.y){" +
            "vec4 w=max(vec4(vCam0.z,vCam1.z,vCam2.z,vCam3.z),vec4(0.0))*uFresh;" +
            "float sum=dot(w,vec4(1.0));if(sum<0.00000001){" +
            "float stripe=step(0.5,fract((vGround.x+vGround.y)/180.0));" +
            "color=mix(vec3(0.18,0.09,0.16),vec3(0.32,0.15,0.26),stripe);" +
            "}else{" +
            "if(w.x>0.0)color+=w.x*camera(uCam0,vCam0.xy);" +
            "if(w.y>0.0)color+=w.y*camera(uCam1,vCam1.xy);" +
            "if(w.z>0.0)color+=w.z*camera(uCam2,vCam2.xy);" +
            "if(w.w>0.0)color+=w.w*camera(uCam3,vCam3.xy);color/=sum;}}" +
            "gl_FragColor=vec4(color,1.0);}";


    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        guideAnimation.reset();
        guideFrame.reset();guideProgress=outgoingGuideProgress=0;
        program = 0; uploadedMesh = null; error = null; hasCarArtwork = false; uploadedCarColor = null;
        bowlPass=null;pdcPass=null;guidePass=null;carPass=null;carArtworkGeometry=null;
        pdcUnavailable=false;guidesUnavailable=false;threeDActive=false;
        int[] depth=new int[1];GLES20.glGetIntegerv(GLES20.GL_DEPTH_BITS,depth,0);depthAvailable=depth[0]>=16;
        Arrays.fill(capturedUs, 0); freshMask = 0; displayMask = 0;
        statsStartedNs=0;statsFrames=0;statsSubmitUs=0;Arrays.fill(statsUploads,0);
        try {
            program = link(VERTEX, FRAGMENT);
            carPass=new BirdseyeCarPass();
            attributes[0] = GLES20.glGetAttribLocation(program, "aPosition");
            for (int i = 0; i < 4; i++) attributes[i+1] = GLES20.glGetAttribLocation(program, "aCam"+i);
            freshUniform = GLES20.glGetUniformLocation(program, "uFresh");
            GLES20.glUseProgram(program);
            GLES20.glGenTextures(5, textures, 0);
            int cameraFilter=PackedUyvyShader.textureFilter();
            samplingDevice="UYVY: "+(cameraFilter==GLES20.GL_LINEAR?"2 taps (hardware rows)":"4 taps (precision fallback)")
                    +"; GPU: "+GLES20.glGetString(GLES20.GL_RENDERER);
            for (int i = 0; i < 4; i++) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0+i);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i]);
                // Hardware row interpolation on highp GPUs, original sampler otherwise.
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, cameraFilter);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, cameraFilter);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 360, 240, 0,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uCam"+i), i);
            }
            initializeCarArtwork();
            GLES20.glGenBuffers(2, buffers, 0);
            checkGl();
        } catch (RuntimeException e) { fail(e); }
        finally { contextGeneration++; }
    }

    @Override public void onSurfaceChanged(GL10 gl, int width, int height) { this.width=width;this.height=height;GLES20.glViewport(0, 0, width, height); }

    @Override public void onDrawFrame(GL10 gl) {
        long start = System.nanoTime();
        GLES20.glClearColor(0.055f, 0.065f, 0.08f, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);
        if (error != null || requestedMesh == null || program == 0) return;
        try {
            int generation = resetGeneration;
            if (generation != appliedGeneration) {
                Arrays.fill(capturedUs, 0); appliedGeneration = generation;
            }
            boolean useWide=wideRequested&&wideMesh!=null;
            BirdseyeMesh mesh = useWide?wideMesh:requestedMesh;
            GLES20.glUseProgram(program);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program,"uGroundHalf"),mesh.groundWidth/2,-mesh.groundHeight/2);
            BirdseyeCarColor color=requestedCarColor;
            if (color != uploadedCarColor) uploadCarArtwork(color);
            if (mesh != uploadedMesh) {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0]);
                mesh.vertices.position(0);
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mesh.vertices.capacity()*4, mesh.vertices, GLES20.GL_STATIC_DRAW);
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buffers[1]);
                mesh.indices.position(0);
                GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.capacity()*2, mesh.indices, GLES20.GL_STATIC_DRAW);
                uploadedMesh = mesh;
            }
            boolean allowed = frames.allowed();
            for (int i = 0; i < 4; i++) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0+i);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i]);
                if (allowed) {
                    upload.position(0);
                    long captured = frames.copy(i, upload, capturedUs[i]);
                    if (captured > capturedUs[i]) {
                        upload.position(0);
                        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 360, 240,
                                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, upload);
                        capturedUs[i] = captured; statsUploads[i]++;
                    }
                }
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE4);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[4]);
            // Check permission/generation again after the copies to invalidate a concurrent handoff.
            int mask = 0,visibleMask = 0;
            if (allowed && frames.allowed() && generation == resetGeneration) {
                long nowUs = frames.clockUs();
                mask = BirdseyeFrameHealth.freshMask(capturedUs, nowUs);
                visibleMask = BirdseyeFrameHealth.displayMask(capturedUs, nowUs);
            }
            boolean drewThreeD=false;
            RivelaScene currentScene=scene;VehicleModel currentModel=model;
            WheelSteering.Angles angles=steering.angles();
            com.drivehub.kamera.steering.WheelGuides.Style style=guideStyle;
            com.drivehub.kamera.steering.WheelGuides.Direction direction=angles.guidanceDirection!=null?angles.guidanceDirection:guideDirection;
            int guideCameraMask=direction==com.drivehub.kamera.steering.WheelGuides.Direction.FORWARD?4:8;
            guideProgress=guideAnimation.update(wheelGuidePreview,
                    currentScene!=null&&angles.guidanceAvailable&&(mask&guideCameraMask)!=0,direction,guideClock.getAsLong());
            if((mask&4)==0)guideAnimation.invalidate(com.drivehub.kamera.steering.WheelGuides.Direction.FORWARD);
            if((mask&8)==0)guideAnimation.invalidate(com.drivehub.kamera.steering.WheelGuides.Direction.REVERSE);
            guideFrame.update(angles,style,direction,guideAnimation);
            outgoingGuideProgress=guideFrame.outgoing().level;
            if(threeDRequested&&currentScene!=null&&currentScene.bowl()!=null&&currentModel!=null) {
                ThreeDFailure.Kind stage=ThreeDFailure.Kind.DEPTH;
                try {
                    if(!depthAvailable)throw new IllegalStateException("No 16-bit depth buffer");
                    stage=ThreeDFailure.Kind.GPU_SETUP;
                    if(bowlPass==null)bowlPass=new RivelaBowlPass();
                    stage=ThreeDFailure.Kind.GPU_DRAW;
                    bowlPass.draw(width,height,currentScene,currentModel,color,textures,visibleMask,mask,orbit,angles,pdcGeometry,SystemClock.elapsedRealtime(),guideFrame);
                    drewThreeD=true;
                } catch(RuntimeException|OutOfMemoryError failure) {
                    failThreeD(ThreeDFailure.at(stage,failure));
                    if(bowlPass!=null){bowlPass.close();bowlPass=null;}
                    GLES20.glDisable(GLES20.GL_DEPTH_TEST);RivelaBowlPass.disableAttributes();
                    for(int drain=0;drain<16&&GLES20.glGetError()!=GLES20.GL_NO_ERROR;drain++){}
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);
                }
            }
            if(!drewThreeD) {
            GLES20.glUseProgram(program);GLES20.glDisable(GLES20.GL_DEPTH_TEST);GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE4);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textures[4]);
            GLES20.glUniform4f(freshUniform, visibleMask&1, (visibleMask>>1)&1, (visibleMask>>2)&1, (visibleMask>>3)&1);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0]);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buffers[1]);
            for (int i = 0; i < 5; i++) {
                GLES20.glEnableVertexAttribArray(attributes[i]);
                GLES20.glVertexAttribPointer(attributes[i], i == 0 ? 2 : 3, GLES20.GL_FLOAT, false,
                        BirdseyeMesh.FLOATS*4, i == 0 ? 0 : (2+(i-1)*3)*4);
            }
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indexCount, GLES20.GL_UNSIGNED_SHORT, 0);
            RivelaBowlPass.disableAttributes();
            if(currentScene!=null) {
                float cx=(float)currentScene.car.centerX,cy=(float)currentScene.car.centerY;
                android.opengl.Matrix.orthoM(topMvp,0,cx-mesh.groundWidth/2,cx+mesh.groundWidth/2,
                        cy+mesh.groundHeight/2,cy-mesh.groundHeight/2,-100,100);
            }
            if(currentScene!=null&&pdcGeometry!=null&&(mask&8)!=0&&!pdcUnavailable) {
                try {
                if(pdcPass==null)pdcPass=new PdcGroundPass();
                pdcPass.draw(topMvp,currentScene,pdcGeometry.bars(currentScene.car,SystemClock.elapsedRealtime()));
                } catch(RuntimeException optionalOverlayFailure) {
                    pdcUnavailable=true;
                    if(pdcPass!=null){pdcPass.close();pdcPass=null;}
                    RivelaBowlPass.disableAttributes();
                    for(int drain=0;drain<16&&GLES20.glGetError()!=GLES20.GL_NO_ERROR;drain++){}
                    Log.w("Birdseye","PDC overlay unavailable; keeping camera view",optionalOverlayFailure);
                }
            }
            if(currentScene!=null&&guideFrame.visible()&&!guidesUnavailable) {
                try {
                    if(guidePass==null)guidePass=new WheelGuidePass();
                    guidePass.draw(topMvp,currentScene,guideFrame,!hasCarArtwork);
                }catch(RuntimeException optionalOverlayFailure) {
                    guidesUnavailable=true;
                    if(guidePass!=null){guidePass.close();guidePass=null;}
                    RivelaBowlPass.disableAttributes();GLES20.glDisable(GLES20.GL_BLEND);GLES20.glDepthMask(true);
                    for(int drain=0;drain<16&&GLES20.glGetError()!=GLES20.GL_NO_ERROR;drain++){}
                    Log.w("Birdseye","Steering overlay unavailable; keeping camera view",optionalOverlayFailure);
                }
            }
            // Compose the car once, above every ground overlay and with its original alpha silhouette.
            if(hasCarArtwork)carPass.draw(textures[4],carArtworkGeometry,mesh.groundWidth,mesh.groundHeight);
            }
            checkGl();
            threeDActive=drewThreeD; // Publish completed-frame state, never an in-flight false pulse.
            wideActive=!drewThreeD&&useWide;
            freshMask = mask;displayMask = visibleMask;
            renderedFrames++; lastDrawElapsedMs = SystemClock.elapsedRealtime();
        } catch (RuntimeException e) { fail(e); }
        lastDrawCpuUs = (System.nanoTime()-start)/1000;
        recordTiming(System.nanoTime());
    }

    private void initializeCarArtwork() {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[4]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        uploadCarArtwork(requestedCarColor);
    }

    // One prepared PNG is decoded/uploaded per selection (and per recreated GL context).
    private void uploadCarArtwork(BirdseyeCarColor color) {
        uploadedCarColor = color;
        hasCarArtwork = false;
        carArtworkGeometry=null;
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[4]);
        int ready = GLES20.glGetUniformLocation(program,"uCarReady");
        GLES20.glUniform1f(ready,0);
        transparentCarTexture();
        BirdseyeCarArtwork artwork = null;
        try {
            if (artworkSource == null || (artwork = artworkSource.load(color)) == null) return;
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,artwork.bitmap,0);
            checkGl();
            carArtworkGeometry=artwork.geometry;
            GLES20.glUniform1f(ready,1);
            hasCarArtwork = true;
        } catch (RuntimeException e) {
            Log.w("Birdseye","Car texture unavailable; retaining black mask",e);
            transparentCarTexture();
        } finally { if (artwork != null) artwork.bitmap.recycle(); }
    }

    private void transparentCarTexture() {
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,1,1,0,
                GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,ByteBuffer.allocateDirect(4));
    }

    private void fail(RuntimeException e) { error = e.getMessage(); freshMask = 0; displayMask = 0; }
    private static void checkGl() {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) throw new IllegalStateException("GLES error 0x"+Integer.toHexString(error));
    }
    private static int shader(int type, String code) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, code); GLES20.glCompileShader(shader);
        int[] ok = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String error = GLES20.glGetShaderInfoLog(shader); GLES20.glDeleteShader(shader);
            throw new IllegalStateException(error);
        }
        return shader;
    }
    private static int link(String vertex, String fragment) {
        int v = shader(GLES20.GL_VERTEX_SHADER, vertex), f = 0, p = 0;
        try {
            f = shader(GLES20.GL_FRAGMENT_SHADER, fragment); p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p);
            int[] ok = new int[1]; GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(p));
            return p;
        } catch (RuntimeException e) { if (p != 0) GLES20.glDeleteProgram(p); throw e; }
        finally { GLES20.glDeleteShader(v); if (f != 0) GLES20.glDeleteShader(f); }
    }
}
