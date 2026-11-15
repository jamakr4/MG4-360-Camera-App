package com.drivehub.kamera.birdseye;

import com.drivehub.kamera.CameraProbe;
import java.io.IOException;
import java.nio.ByteBuffer;

/** One bounded, calibration-independent snapshot transaction through existing camera sessions. */
public final class CameraSnapshotCapture {
    public static final String[] NAMES={"left","right","front","rear"};
    public static final int[] VIDEO={16,14,15,17};
    public static final int WIDTH=720,HEIGHT=480,BYTES=WIDTH*HEIGHT*2;
    public static final long MAX_SKEW_US=100000,MAX_AGE_US=500000;
    public static final class Frame {
        public final byte[] raw;public final long timeUs,sequence;public final int field,sourceStride;
        public Frame(byte[] raw,long timeUs,long sequence,int field,int sourceStride){
            if(raw.length!=BYTES||timeUs<=0||sourceStride<WIDTH*2)throw new IllegalArgumentException("Invalid snapshot frame");
            this.raw=raw;this.timeUs=timeUs;this.sequence=sequence;this.field=field;this.sourceStride=sourceStride;
        }
    }
    public static final class Set {
        public final Frame[] frames;public final long requestedUs,skewUs;
        public Set(Frame[] frames,long requestedUs){
            if(frames.length!=4)throw new IllegalArgumentException("Four frames required");
            this.frames=frames.clone();this.requestedUs=requestedUs;long min=Long.MAX_VALUE,max=0;
            for(Frame frame:frames){if(frame==null||frame.timeUs<requestedUs)throw new IllegalArgumentException("Frame predates request");min=Math.min(min,frame.timeUs);max=Math.max(max,frame.timeUs);}
            skewUs=max-min;if(skewUs>MAX_SKEW_US)throw new IllegalArgumentException("Camera frames too far apart");
        }
    }
    public static Set capture() throws IOException,InterruptedException {
        int owner=BirdseyeOwnerIds.next();long requested=BirdseyeNative.clockUs();
        ByteBuffer[] raw=new ByteBuffer[4];long[][] metadata=new long[4][];long[] after=new long[4];
        try {
            for(int i=0;i<4;i++) {
                raw[i]=ByteBuffer.allocateDirect(BYTES);
                if(!BirdseyeNative.attachSnapshot(owner,i))throw new IOException("Cannot attach "+NAMES[i]+" camera; OEM ownership or camera unavailable");
            }
            while(BirdseyeNative.clockUs()-requested<5000000) {
                if(CameraProbe.isOemCameraAccessBlocked())throw new IOException("Capture stopped for OEM camera ownership");
                for(int i=0;i<4;i++) {
                    long[] info=BirdseyeNative.copySnapshot(owner,i,raw[i],after[i]);
                    if(info!=null){metadata[i]=info;after[i]=info[0];}
                }
                long now=BirdseyeNative.clockUs(),min=Long.MAX_VALUE,max=0;boolean ready=true;
                for(long[] info:metadata) {
                    if(info==null||info.length!=6||info[0]<requested||now<info[0]||now-info[0]>MAX_AGE_US){ready=false;break;}
                    if(info[4]!=WIDTH||info[5]!=HEIGHT)throw new IOException("Unsupported camera dimensions");
                    min=Math.min(min,info[0]);max=Math.max(max,info[0]);
                }
                if(ready&&max-min<=MAX_SKEW_US) {
                    Frame[] frames=new Frame[4];
                    for(int i=0;i<4;i++){byte[] bytes=new byte[BYTES];raw[i].position(0);raw[i].get(bytes);
                        frames[i]=new Frame(bytes,metadata[i][0],metadata[i][1],(int)metadata[i][2],(int)metadata[i][3]);}
                    return new Set(frames,requested);
                }
                Thread.sleep(20);
            }
            StringBuilder missing=new StringBuilder();for(int i=0;i<4;i++)if(metadata[i]==null)missing.append(NAMES[i]).append(' ');
            throw new IOException("No fresh four-camera set within 5 s (missing: "+missing+"; max skew 100 ms)");
        }finally{BirdseyeNative.detach(owner);}
    }
    private CameraSnapshotCapture(){}
}
