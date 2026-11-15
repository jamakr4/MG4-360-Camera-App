package com.drivehub.kamera.birdseye;

/** Spatial field normalization and limited-range BT.601 UYVY conversion; never mirror camera inputs. */
public final class SnapshotPixels {
    private SnapshotPixels(){}
    public static String layout(int field) {
        switch(field){case 0:case 1:return "stacked top-first (observed MG4 interpretation of ANY/NONE)";
            case 5:return "stacked top-first";case 6:return "stacked bottom-first";
            case 4:case 8:case 9:return "woven interlaced";default:throw new IllegalArgumentException("Unsupported V4L2 field layout: "+field);}
    }
    static int sourceRow(int y,int height,int field) {
        layout(field);
        if(field==4||field==8||field==9)return y;
        int top=field==6?height/2:0,bottom=field==6?0:height/2;
        return (y%2==0?top:bottom)+y/2;
    }
    public static int[] argb(byte[] raw,int width,int height,int field) {
        if(width<2||width>720||height<2||height>480||width%2!=0||height%2!=0||raw.length!=width*height*2)
            throw new IllegalArgumentException("Invalid bounded packed frame");
        layout(field);int[] pixels=new int[width*height];
        for(int y=0;y<height;y++) {
            int row=sourceRow(y,height,field)*width*2;
            for(int x=0;x<width;x+=2) {
                int i=row+x*2,u=(raw[i]&255)-128,v=(raw[i+2]&255)-128;
                pixels[y*width+x]=rgb((raw[i+1]&255)-16,u,v);
                pixels[y*width+x+1]=rgb((raw[i+3]&255)-16,u,v);
            }
        }
        return pixels;
    }
    private static int clamp(int c){return Math.max(0,Math.min(255,c));}
    private static int rgb(int y,int u,int v){return 0xFF000000|clamp((298*y+409*v+128)>>8)<<16
            |clamp((298*y-100*u-208*v+128)>>8)<<8|clamp((298*y+516*u+128)>>8);}
}
