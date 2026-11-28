package com.drivehub.kamera.birdseye;

import android.util.Log;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;

/**
 * Persists computed projection meshes across process restarts (issue #152).
 * The key contains the SHA-256 of the calibration that was actually read, so a changed
 * vehicle calibration or mesh parameter is a miss; a corrupt file is a miss and is rewritten.
 */
final class ProjectionDiskCache {
    private static final int MAGIC=0x4d473450, VERSION=1; // "MG4P"
    static final String FILE="projection-mesh.bin";
    private final File file;
    ProjectionDiskCache(File directory) {file=new File(directory,FILE);}

    /** android.util.Log is not available in plain JVM unit tests. */
    static void info(String message) {
        com.drivehub.kamera.dev.DevRuntimeLog.add("Projection cache",message);
        try{Log.i("ProjectionCache",message);}catch(RuntimeException ignored){}
    }
    static void warn(String message) {
        com.drivehub.kamera.dev.DevRuntimeLog.add("Projection cache",message);
        try{Log.w("ProjectionCache",message);}catch(RuntimeException ignored){}
    }

    static final class Entry {
        final BirdseyeMesh mesh,wideMesh;final String wideError;
        Entry(BirdseyeMesh mesh,BirdseyeMesh wideMesh,String wideError) {
            this.mesh=mesh;this.wideMesh=wideMesh;this.wideError=wideError;
        }
    }

    /** Null for a miss: absent, other key, truncated, checksum or content failure. */
    Entry load(String key) {
        if(!file.isFile())return null;
        try {
            byte[] all=Files.readAllBytes(file.toPath());
            if(all.length<8)throw new IOException("Truncated");
            CRC32 crc=new CRC32();crc.update(all,0,all.length-8);
            long stored=0;for(int i=all.length-8;i<all.length;i++)stored=(stored<<8)|(all[i]&255);
            if(crc.getValue()!=stored)throw new IOException("Checksum mismatch");
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(all,0,all.length-8));
            if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("Unknown format");
            if(!key.equals(in.readUTF()))return null;
            BirdseyeMesh mesh=BirdseyeMesh.read(in),wide=in.readBoolean()?BirdseyeMesh.read(in):null;
            String error=in.readBoolean()?in.readUTF():null;
            if(wide==null&&error==null)throw new IOException("Missing wide mesh state");
            return new Entry(mesh,wide,error);
        }catch(IOException|RuntimeException failure) {
            warn("Discarding cached projection: "+failure.getMessage());
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return null;
        }
    }

    /** Atomic replace; a failed write leaves no partial file behind. */
    void store(String key,Entry entry) {
        File temp=new File(file.getParentFile(),FILE+".tmp");
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(2*BirdseyeMesh.VERTEX_FLOATS*4+1024);
            DataOutputStream out=new DataOutputStream(bytes);
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeUTF(key);
            entry.mesh.write(out);
            out.writeBoolean(entry.wideMesh!=null);if(entry.wideMesh!=null)entry.wideMesh.write(out);
            out.writeBoolean(entry.wideError!=null);if(entry.wideError!=null)out.writeUTF(entry.wideError);
            CRC32 crc=new CRC32();byte[] payload=bytes.toByteArray();crc.update(payload);
            out.writeLong(crc.getValue());out.flush();
            File parent=file.getParentFile();
            if(parent!=null&&!parent.isDirectory()&&!parent.mkdirs())throw new IOException("No cache directory");
            Files.write(temp.toPath(),bytes.toByteArray());
            Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }catch(IOException|RuntimeException failure) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            warn("Cannot persist projection: "+failure.getMessage());
        }
    }
}
