package com.drivehub.kamera.birdseye;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Private debug replay, read only from app files. Never reads camera devices or APK assets. */
public final class PrivateCameraCapture implements BirdseyeRenderer.Frames, AutoCloseable {
    public static final String EXTRA_SET="camera_capture_set";
    private static final long REPLAY_CLOCK_US=1_000_000;
    public final String id;
    public final BirdseyeCalibration calibration;
    private final byte[][] fields=new byte[4][];
    private final Bitmap[] images=new Bitmap[4];
    public static File root(Context context){return new File(context.getFilesDir(),"camera-snapshots");}
    public static String[] available(Context context){
        File[] dirs=root(context).listFiles(f->f.isDirectory()&&validId(f.getName())&&new File(f,"manifest.json").isFile());
        if(dirs==null)return new String[0];
        String[] ids=new String[dirs.length];for(int i=0;i<dirs.length;i++)ids[i]=dirs[i].getName();
        Arrays.sort(ids,Collections.reverseOrder());return ids;
    }
    private static boolean validId(String id){return id!=null&&id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}");}
    public static PrivateCameraCapture load(Context context,String id)throws Exception{
        if(!validId(id))throw new IOException("Invalid capture identifier");
        File root=root(context).getCanonicalFile(),dir=new File(root,id).getCanonicalFile();
        if(!root.equals(dir.getParentFile()))throw new IOException("Capture outside private directory");
        return new PrivateCameraCapture(dir);
    }
    private PrivateCameraCapture(File dir)throws Exception{
        id=dir.getName();boolean complete=false;
        try{
            JSONObject manifest=new JSONObject(new String(read(dir,"manifest.json",65536),StandardCharsets.UTF_8));
            if(manifest.getInt("schema_version")!=1)throw new IOException("Unsupported snapshot schema");
            if(!"camera_calibdata.bin".equals(manifest.optString("calibration_file")))throw new IOException("Vehicle calibration is required for replay");
            byte[] calibrationBytes=read(dir,"camera_calibdata.bin",520);
            checkHash(calibrationBytes,manifest.getString("calibration_sha256"));calibration=BirdseyeCalibration.parse(calibrationBytes);
            JSONArray cameras=manifest.getJSONArray("cameras");if(cameras.length()!=4)throw new IOException("Four cameras required");
            for(int camera=0;camera<4;camera++){
                String name=CameraSnapshotCapture.NAMES[camera];JSONObject row=cameras.getJSONObject(camera);
                if(!name.equals(row.getString("camera"))||!name.concat(".png").equals(row.getString("png"))
                        ||!name.concat(".uyvy").equals(row.getString("raw"))||row.getInt("width")!=720||row.getInt("height")!=480
                        ||row.getInt("raw_row_stride_bytes")!=1440||!"UYVY422".equals(row.getString("raw_format")))
                    throw new IOException("Unexpected camera mapping/format");
                byte[] raw=read(dir,name+".uyvy",CameraSnapshotCapture.BYTES);
                if(raw.length!=CameraSnapshotCapture.BYTES)throw new IOException("Incomplete raw frame");
                checkHash(raw,row.getString("raw_sha256"));int field=row.getInt("v4l2_field");SnapshotPixels.layout(field);
                fields[camera]=new byte[720*240*2];
                for(int y=0;y<240;y++)System.arraycopy(raw,SnapshotPixels.sourceRow(y*2,480,field)*1440,fields[camera],y*1440,1440);
                byte[] png=read(dir,name+".png",4*1024*1024);checkHash(png,row.getString("png_sha256"));
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(png,0,png.length,bounds);
                if(bounds.outWidth!=720||bounds.outHeight!=480)throw new IOException("Expected 720x480 PNG");
                images[camera]=BitmapFactory.decodeByteArray(png,0,png.length);
                if(images[camera]==null)throw new IOException("PNG decoding failed");
            }
            complete=true;
        }finally{if(!complete)close();}
    }
    private static byte[] read(File dir,String name,int maximum)throws IOException{
        File file=new File(dir,name).getCanonicalFile();
        if(!dir.getCanonicalFile().equals(file.getParentFile())||!file.isFile()||file.length()>maximum)throw new IOException("Invalid bounded capture file: "+name);
        try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[16384];int n;
            while((n=in.read(buffer))!=-1){if(out.size()+n>maximum)throw new IOException("Capture grew beyond bound");out.write(buffer,0,n);}return out.toByteArray();
        }
    }
    private static void checkHash(byte[] bytes,String expected)throws Exception{
        StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(Locale.ROOT,"%02x",b&255));
        if(!hash.toString().equals(expected))throw new IOException("Snapshot checksum mismatch");
    }
    public Bitmap image(int camera){return images[camera];}
    public int[] pixels(int camera){int[] rgb=new int[720*480];images[camera].getPixels(rgb,0,720,0,0,720,480);return rgb;}
    // Static replay time deliberately freezes freshness; this is labelled a recorded still, never live.
    @Override public long copy(int camera,ByteBuffer output,long afterUs){
        if(afterUs>=REPLAY_CLOCK_US)return 0;output.position(0);output.put(fields[camera]);output.position(0);return REPLAY_CLOCK_US;
    }
    @Override public long clockUs(){return REPLAY_CLOCK_US;}
    @Override public boolean allowed(){return true;}
    @Override public void close(){for(Bitmap image:images)if(image!=null&&!image.isRecycled())image.recycle();}
}
