package com.drivehub.kamera.birdseye;

/** Display-only self-body contour from the MG4 rear-camera snapshot (unmirrored 720x480).
 * This is a provisional visibility mask, not a change to calibration or vehicle dimensions. */
final class BirdseyeRearMask {
    private BirdseyeRearMask() {}
    private static final int[] SHADOW_RGB={38,40,43};

    static final String GLSL =
            // Scale before squaring so the mediump GLES2 path cannot overflow.
            "float rearBodyVisibility(vec2 uv){float x=(clamp(uv.x,0.0,719.0)-350.0)*0.01;"
            +"float edge=414.0-7.4*x*x;return 1.0-smoothstep(edge-3.0,edge+2.0,uv.y);}\n";

    static double visibility(double u,double v) {
        double x=(Math.max(0,Math.min(719,u))-350)*.01,edge=414-7.4*x*x;
        return 1-smooth(edge-3,edge+2,v);
    }

    static int firstShadowRow(int height) { return (int)(height*.65); }

    /** Bake the original small shadow into the selected PNG once, before its usual GL upload.
     * rows contains only the bottom part of the straight-alpha bitmap, starting at firstRow. */
    static void addShadow(int[] rows,int width,int height,int firstRow,BirdseyeCarGeometry geometry) {
        if(width<1 || height<1 || firstRow<0 || firstRow>=height
                || rows.length!=(long)width*(height-firstRow))
            throw new IllegalArgumentException("Invalid rear artwork region");
        double[] outline=new double[width];
        java.util.Arrays.fill(outline,Double.NaN);
        for(int x=0;x<width;x++)for(int y=height-1;y>=firstRow;y--) {
            if((rows[(y-firstRow)*width+x]>>>24)>248) {
                outline[x]=geometry.maskReferenceTopMm+(y+.5)*geometry.maskReferencePixelMm;
                break;
            }
        }
        for(int y=firstRow;y<height;y++) {
            double groundY=geometry.topMm+(y+.5)*geometry.heightMm/height;
            if(groundY<1600)continue;
            for(int x=0;x<width;x++) {
                int index=(y-firstRow)*width+x,original=rows[index];
                if((original>>>24)==255 || !Double.isFinite(outline[x]))continue;
                double groundX=geometry.leftMm+(x+.5)*geometry.widthMm/width;
                double alpha=(1-smooth(outline[x]+135,outline[x]+225,groundY))
                        *(1-smooth(875,925,Math.abs(groundX)));
                if(alpha>0)rows[index]=overShadow(original,alpha);
            }
        }
    }

    private static int overShadow(int original,double shadowAlpha) {
        double foregroundAlpha=(original>>>24)/255.0;
        double underneath=shadowAlpha*(1-foregroundAlpha),alpha=foregroundAlpha+underneath;
        int result=(int)Math.round(alpha*255)<<24;
        for(int channel=0;channel<3;channel++) {
            int shift=16-channel*8,value=(original>>>shift)&255;
            result|=(int)Math.round((value*foregroundAlpha+SHADOW_RGB[channel]*underneath)/alpha)<<shift;
        }
        return result;
    }

    private static double smooth(double a,double b,double x) {
        double t=Math.max(0,Math.min(1,(x-a)/(b-a)));
        return t*t*(3-2*t);
    }
}
