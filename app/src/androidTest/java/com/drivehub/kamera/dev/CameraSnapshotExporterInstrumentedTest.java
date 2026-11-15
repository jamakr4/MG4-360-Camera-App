package com.drivehub.kamera.dev;
import android.graphics.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.birdseye.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class CameraSnapshotExporterInstrumentedTest {
    private CameraSnapshotCapture.Set frames(){CameraSnapshotCapture.Frame[] frames=new CameraSnapshotCapture.Frame[4];
        for(int c=0;c<4;c++){byte[] raw=new byte[CameraSnapshotCapture.BYTES];for(int i=0;i<raw.length;i+=4){raw[i]=(byte)128;raw[i+1]=16;raw[i+2]=(byte)128;raw[i+3]=(byte)235;}
            frames[c]=new CameraSnapshotCapture.Frame(raw,100000+c*10000,12+c,5,1456);}
        return new CameraSnapshotCapture.Set(frames,90000);}
    @Test public void oneTransactionExportsFourFullPngRawPairsCalibrationAndAccurateMetadata() throws Exception {
        File base=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"snapshot-test-"+System.nanoTime());assertTrue(base.mkdir());
        try {
            byte[] calibration=BirdseyeSynthetic.calibrationBytes();
            File result=CameraSnapshotExporter.write(base,frames(),calibration,"test formats");
            JSONObject manifest=new JSONObject(new String(Files.readAllBytes(new File(result,"manifest.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(30000,manifest.getLong("max_camera_timestamp_skew_us"));assertEquals(4,manifest.getJSONArray("cameras").length());
            assertArrayEquals(calibration,Files.readAllBytes(new File(result,"camera_calibdata.bin").toPath()));
            for(int c=0;c<4;c++){
                String name=CameraSnapshotCapture.NAMES[c];File raw=new File(result,name+".uyvy");assertEquals(CameraSnapshotCapture.BYTES,raw.length());
                Bitmap image=BitmapFactory.decodeFile(new File(result,name+".png").getAbsolutePath());
                assertNotNull(image);assertEquals(720,image.getWidth());assertEquals(480,image.getHeight());
                assertEquals(0xFF000000,image.getPixel(0,0));assertEquals(0xFFFFFFFF,image.getPixel(1,0));image.recycle();
                JSONObject row=manifest.getJSONArray("cameras").getJSONObject(c);assertEquals(CameraSnapshotCapture.VIDEO[c],row.getInt("video_index"));
                assertEquals(1456,row.getInt("source_row_stride_bytes"));assertEquals(1440,row.getInt("raw_row_stride_bytes"));assertEquals(64,row.getString("png_sha256").length());
            }
            assertEquals(1,new File(base,"camera-snapshots").listFiles().length);
        }finally{remove(base);}
    }
    @Test public void absentCalibrationIsOptionalAndInvalidExportDoesNotPublishPartialFiles() throws Exception {
        File base=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"snapshot-test-"+System.nanoTime());assertTrue(base.mkdir());
        try {
            File first=CameraSnapshotExporter.write(base,frames(),null,"");assertTrue(new File(first,"manifest.json").isFile());
            try{CameraSnapshotExporter.write(base,frames(),new byte[1],"");fail();}catch(IllegalArgumentException expected){}
            File[] published=new File(base,"camera-snapshots").listFiles();assertEquals(1,published.length);assertEquals(first,published[0]);
        }finally{remove(base);}
    }
    private void remove(File f){File[] children=f.listFiles();if(children!=null)for(File child:children)remove(child);f.delete();}
}
