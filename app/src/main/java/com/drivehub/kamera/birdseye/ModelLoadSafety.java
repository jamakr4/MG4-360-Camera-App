package com.drivehub.kamera.birdseye;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.Log;
import java.io.*;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Boot recovery for automatic and visible-view model loads. Never changes vehicle files. */
public final class ModelLoadSafety {
    public static final long STABLE_MS=60000;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService WRITES=Executors.newSingleThreadExecutor(r->new Thread(r,"Model-startup-ledger"));
    private static ModelLoadSafety instance;
    private final StartupLoadGuard guard;
    private StartupLoadGuard.State ticket;
    private boolean acknowledgementQueued,storageFault;
    private ModelLoadSafety(Context context) {
        File base=new File(context.createDeviceProtectedStorageContext().getFilesDir(),"birdseye-startup-guard.bin");
        AtomicFile atomic=new AtomicFile(base);
        guard=new StartupLoadGuard(new StartupLoadGuard.Store(){
            public StartupLoadGuard.State read() throws IOException {
                if(!base.exists()&&!new File(base+".bak").exists()&&!new File(base+".new").exists())return new StartupLoadGuard.State(0,0,true);
                try(InputStream in=atomic.openRead()) {
                    ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] buffer=new byte[26];int n;
                    while((n=in.read(buffer))!=-1){data.write(buffer,0,n);if(data.size()>25)throw new IOException("Startup ledger too large");}
                    return StartupLoadGuard.decode(data.toByteArray());
                }
            }
            public void write(StartupLoadGuard.State state) throws IOException {
                byte[] bytes=StartupLoadGuard.encode(state);FileOutputStream out=null;
                try {
                    out=atomic.startWrite();out.write(bytes);out.getFD().sync();atomic.finishWrite(out);out=null;
                    if(!Arrays.equals(StartupLoadGuard.encode(read()),bytes))throw new IOException("Startup ledger read-back failed");
                }catch(IOException|RuntimeException error){if(out!=null)atomic.failWrite(out);throw error;}
            }
        });
    }
    private static synchronized ModelLoadSafety get(Context context) {
        if(instance==null)instance=new ModelLoadSafety(context.getApplicationContext());return instance;
    }
    /** Asset worker only. A failed/corrupt ledger fails closed, before decoding any model. */
    static boolean enter(Context context,boolean automatic) {
        ModelLoadSafety safety=get(context);
        synchronized(safety) {
            if(safety.storageFault)return false;
            try {
                StartupLoadGuard.State state=safety.guard.state();
                if(automatic&&!state.automatic)return false;
                if(safety.ticket!=null&&safety.ticket.serial==state.serial)return true;
                safety.ticket=safety.guard.begin(automatic);return safety.ticket!=null;
            }catch(IOException|RuntimeException error){safety.storageFault=true;Log.e("ModelLoadSafety","3D disabled: startup ledger unavailable",error);return false;}
        }
    }
    static void loaded(Context context) {
        ModelLoadSafety safety=get(context);
        synchronized(safety) {
            if(safety.ticket==null||safety.acknowledgementQueued||safety.storageFault)return;
            safety.acknowledgementQueued=true;StartupLoadGuard.State expected=safety.ticket;
            // Main-loop responsiveness is part of the stability acknowledgement.
            MAIN.postDelayed(()->WRITES.execute(()->{
                synchronized(safety) {
                    if(safety.ticket!=expected)return;
                    try{safety.guard.stable(expected.serial);Log.i("ModelLoadSafety","Model startup confirmed stable");}
                    catch(IOException|RuntimeException error){safety.storageFault=true;Log.e("ModelLoadSafety","Cannot confirm model startup",error);}
                }
            }),STABLE_MS);
        }
    }
    public static boolean blocked(Context context) {
        ModelLoadSafety safety=get(context);
        synchronized(safety) {
            if(safety.storageFault)return true;
            try {
                StartupLoadGuard.State state=safety.guard.state();
                return state.attempts>=StartupLoadGuard.LIMIT&&(safety.ticket==null||safety.ticket.serial!=state.serial);
            }catch(IOException|RuntimeException error){safety.storageFault=true;return true;}
        }
    }
    public static boolean automaticEnabled(Context context) {
        ModelLoadSafety safety=get(context);synchronized(safety){try{return safety.guard.state().automatic;}catch(Exception error){return false;}}
    }
    public static void automatic(Context context,boolean enabled) throws IOException {
        ModelLoadSafety safety=get(context);synchronized(safety){try{safety.guard.automatic(enabled);}catch(IOException|RuntimeException error){safety.storageFault=true;throw error;}}
    }
    public static void reset(Context context) throws IOException {
        ModelLoadSafety safety=get(context);synchronized(safety){try{safety.guard.reset();safety.ticket=null;safety.acknowledgementQueued=false;safety.storageFault=false;}catch(IOException|RuntimeException error){safety.storageFault=true;throw error;}}
    }
    public static String status(Context context) {
        ModelLoadSafety safety=get(context);synchronized(safety){
            try {StartupLoadGuard.State s=safety.guard.state();return "unconfirmed="+s.attempts+"/5; automatic="+s.automatic
                    +"; safe_mode="+blocked(context)+"; stability_window=60s";}
            catch(Exception error){return "3D safe mode: startup ledger unreadable";}
        }
    }
}
