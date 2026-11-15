package com.drivehub.kamera.birdseye;
import java.nio.ByteBuffer;
/** Switches the offline debug source without creating native camera owners. */
final class DebugFrameSource implements BirdseyeRenderer.Frames {
    volatile BirdseyeRenderer.Frames source;
    DebugFrameSource(BirdseyeRenderer.Frames source){this.source=source;}
    @Override public long copy(int camera,ByteBuffer output,long afterUs){return source.copy(camera,output,afterUs);}
    @Override public long clockUs(){return source.clockUs();}
    @Override public boolean allowed(){return source.allowed();}
}
