package com.drivehub.kamera.dev;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import androidx.appcompat.app.AlertDialog;
import com.drivehub.kamera.BuildConfig;
import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.R;
import com.drivehub.kamera.birdseye.*;
import com.drivehub.kamera.dashcam.DashcamStorageManager;
import com.drivehub.kamera.dashcam.StorageIoGate;
import com.drivehub.kamera.ui.AppSystemBars;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit one-shot Dev export. Camera taps are released before PNG conversion or storage IO. */
public final class CameraSnapshotExporter {
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    private static final java.util.concurrent.ExecutorService IO=Executors.newSingleThreadExecutor(r->new Thread(r,"Camera-snapshot-export"));
    private CameraSnapshotExporter(){}
    public static void bind(Activity activity,Button button) {
        button.setEnabled(!BUSY.get());
        button.setText(BUSY.get()?R.string.settings_dev_snapshot_busy:R.string.settings_dev_snapshot_button);
        Runnable refresh=new Runnable(){@Override public void run(){
            button.setEnabled(!BUSY.get());
            button.setText(BUSY.get()?R.string.settings_dev_snapshot_busy:R.string.settings_dev_snapshot_button);
            if(BUSY.get()&&button.isAttachedToWindow())button.postDelayed(this,200);
        }};
        button.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            @Override public void onViewAttachedToWindow(View view){refresh.run();}
            @Override public void onViewDetachedFromWindow(View view){button.removeCallbacks(refresh);}
        });
        refresh.run();
        button.setOnClickListener(view->{
            if(!BUSY.compareAndSet(false,true))return;
            Context app=activity.getApplicationContext();refresh.run();
            IO.execute(()->{
                File exported=null;String failure=null;
                try {
                    CameraSnapshotCapture.Set images=CameraSnapshotCapture.capture();
                    if(!StorageIoGate.begin())throw new IOException("Storage is stopped for USB eject/shutdown");
                    try {
                        DashcamStorageManager.Resolution storage=DashcamStorageManager.resolve(app);
                        if(storage.baseDir==null)throw new IOException("Selected recording storage is unavailable");
                        byte[] calibration=null;
                        try{calibration=readCalibration();}catch(Exception unavailable){DevRuntimeLog.add("Camera snapshots","Calibration not included: "+unavailable.getClass().getSimpleName());}
                        exported=write(storage.baseDir,images,calibration,CameraProbe.describeCameraFormats());
                    }finally{StorageIoGate.end();}
                }catch(Exception|LinkageError|OutOfMemoryError error){failure=error.getClass().getSimpleName()+": "+error.getMessage();}
                finally{BUSY.set(false);}
                File result=exported;String error=failure;
                DevRuntimeLog.add("Camera snapshots",result!=null?"Saved four cameras: "+result:"Failed: "+error);
                new Handler(Looper.getMainLooper()).post(()->{
                    button.removeCallbacks(refresh);refresh.run();
                    if(activity.isFinishing()||activity.isDestroyed())return;
                    AlertDialog.Builder dialog=new AlertDialog.Builder(activity).setTitle(R.string.settings_dev_snapshot_title);
                    if(result!=null){
                        dialog.setMessage(activity.getString(R.string.settings_dev_snapshot_success,result.getAbsolutePath()))
                                .setNegativeButton(android.R.string.ok,null)
                                .setPositiveButton(R.string.settings_dev_snapshot_copy,(d,w)->{
                                    ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
                                    if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText("Camera snapshot folder",result.getAbsolutePath()));
                                });
                    }else dialog.setMessage(activity.getString(R.string.settings_dev_snapshot_failed,error)).setPositiveButton(android.R.string.ok,null);
                    AppSystemBars.showDialog(dialog.create());
                });
            });
        });
    }
    private static byte[] readCalibration() throws Exception {
        try(InputStream in=new FileInputStream(BirdseyeCalibration.VEHICLE_PATH)) {
            byte[] bytes=new byte[521];int count=0,n;
            while(count<bytes.length&&(n=in.read(bytes,count,bytes.length-count))>0)count+=n;
            byte[] exact=Arrays.copyOf(bytes,count);BirdseyeCalibration.parse(exact);return exact;
        }
    }
    /** Only publish a completed directory after all four PNG/raw pairs and metadata have closed. */
    static File write(File base,CameraSnapshotCapture.Set capture,byte[] calibration,String driverFormats) throws Exception {
        File root=new File(base,"camera-snapshots");if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Cannot create "+root);
        String name=new SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.ROOT).format(new Date())+"-"+UUID.randomUUID().toString().substring(0,8);
        File staging=new File(root,"."+name+".partial"),target=new File(root,name);
        if(!staging.mkdir())throw new IOException("Cannot create snapshot staging directory");
        boolean complete=false;
        try {
            JSONObject report=new JSONObject();report.put("schema_version",1);report.put("app_version",BuildConfig.VERSION_NAME);
            report.put("created_at_epoch_ms",System.currentTimeMillis());report.put("requested_monotonic_us",capture.requestedUs);
            report.put("max_camera_timestamp_skew_us",capture.skewUs);
            report.put("timestamp_meaning","native dequeue monotonic time; not exposure synchronization");
            report.put("orientation","original unmirrored cameras; no crop, UI overlays or lens correction");
            report.put("png_normalization","spatial field weave into 720x480; moving objects may show interlace combing; raw UYVY retained");
            report.put("driver_formats",driverFormats);JSONArray cameras=new JSONArray();
            for(int i=0;i<4;i++) {
                CameraSnapshotCapture.Frame frame=capture.frames[i];String camera=CameraSnapshotCapture.NAMES[i];
                int[] pixels=SnapshotPixels.argb(frame.raw,720,480,frame.field);
                File png=new File(staging,camera+".png"),raw=new File(staging,camera+".uyvy");
                Bitmap bitmap=Bitmap.createBitmap(pixels,720,480,Bitmap.Config.ARGB_8888);
                try(FileOutputStream out=new FileOutputStream(png)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("PNG failed: "+camera);out.getFD().sync();}
                finally{bitmap.recycle();}
                bytes(raw,frame.raw);
                JSONObject row=new JSONObject();row.put("camera",camera);row.put("video_index",CameraSnapshotCapture.VIDEO[i]);
                row.put("png",png.getName());row.put("raw",raw.getName());row.put("raw_format","UYVY422");
                row.put("width",720);row.put("height",480);row.put("raw_row_stride_bytes",1440);row.put("source_row_stride_bytes",frame.sourceStride);
                row.put("v4l2_field",frame.field);row.put("field_layout",SnapshotPixels.layout(frame.field));
                row.put("dequeued_monotonic_us",frame.timeUs);row.put("sequence",frame.sequence);
                row.put("raw_sha256",hash(frame.raw));row.put("png_sha256",hashFile(png));cameras.put(row);
            }
            report.put("cameras",cameras);
            if(calibration!=null){BirdseyeCalibration valid=BirdseyeCalibration.parse(calibration);bytes(new File(staging,"camera_calibdata.bin"),calibration);report.put("calibration_file","camera_calibdata.bin");report.put("calibration_sha256",valid.sha256);}
            else report.put("calibration_file",JSONObject.NULL);
            bytes(new File(staging,"manifest.json"),(report.toString(2)+"\n").getBytes(StandardCharsets.UTF_8));
            if(!staging.renameTo(target))throw new IOException("Cannot publish snapshot directory");complete=true;return target;
        }finally{if(!complete){File[] files=staging.listFiles();if(files!=null)for(File file:files)file.delete();staging.delete();}}
    }
    private static void bytes(File file,byte[] data)throws IOException{try(FileOutputStream out=new FileOutputStream(file)){out.write(data);out.getFD().sync();}}
    private static String hash(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String hashFile(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))>0)digest.update(buffer,0,n);}return hex(digest.digest());}
    private static String hex(byte[] digest){StringBuilder s=new StringBuilder();for(byte b:digest)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
}
