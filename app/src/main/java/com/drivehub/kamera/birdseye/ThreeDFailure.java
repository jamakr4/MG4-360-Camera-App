package com.drivehub.kamera.birdseye;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.drivehub.kamera.BuildConfig;
import com.drivehub.kamera.R;
import java.text.DateFormat;
import java.util.Date;

/** A stable support code plus the original cause; created only when 3D fails. */
public final class ThreeDFailure extends RuntimeException {
    public enum Kind {
        PROJECTION("3D-PROJECTION", R.string.surround_3d_error_projection),
        MODEL("3D-MODEL", R.string.surround_3d_error_model),
        MEMORY("3D-MEMORY", R.string.surround_3d_error_memory),
        DEPTH("3D-DEPTH", R.string.surround_3d_error_depth),
        GPU_SETUP("3D-GPU-INIT", R.string.surround_3d_error_gpu_setup),
        GPU_DRAW("3D-GPU-DRAW", R.string.surround_3d_error_gpu_draw);

        public final String code;
        final int reason;
        Kind(String code, int reason) { this.code=code; this.reason=reason; }
    }

    public final Kind kind;
    private static final String PREFS="birdseye_3d_diagnostics";

    private ThreeDFailure(Kind kind, Throwable cause) {
        super(kind.code+": "+details(cause), cause);
        this.kind=kind;
    }

    public static ThreeDFailure at(Kind stage, Throwable cause) {
        if(cause instanceof ThreeDFailure)return (ThreeDFailure)cause;
        Throwable current=cause;
        for(int i=0;i<8&&current!=null;i++,current=current.getCause()) {
            if(current instanceof OutOfMemoryError)return new ThreeDFailure(Kind.MEMORY,cause);
        }
        return new ThreeDFailure(stage,cause);
    }

    static String details(Throwable cause) {
        StringBuilder text=new StringBuilder();
        for(int i=0;i<8&&cause!=null;i++,cause=cause.getCause()) {
            if(text.length()>0)text.append(" → ");
            text.append(cause.getClass().getSimpleName());
            String message=cause.getMessage();
            if(message!=null&&!message.trim().isEmpty())text.append(": ").append(message);
            if(text.length()>1200)return text.substring(0,1200)+"…";
        }
        return text.length()==0?"No exception details":text.toString();
    }

    public String userMessage(Context context) {
        return context.getString(R.string.surround_3d_failed_detail,kind.code,context.getString(kind.reason));
    }

    /** Preserve the last incident across app restarts; never write from a rendering loop. */
    public void report(Context context) {
        Log.e("Birdseye", "3D fallback ["+kind.code+"]", this);
        try {
            context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                    .putString("code",kind.name()).putString("detail",getMessage())
                    .putString("version",BuildConfig.VERSION_NAME+" ("+BuildConfig.VERSION_CODE+")")
                    .putString("graphics",BirdseyeRenderer.timingStatus())
                    .putLong("time",System.currentTimeMillis()).apply();
        } catch(RuntimeException storageFailure) {
            Log.w("Birdseye","Cannot save 3D diagnostic; keeping 2D",storageFailure);
        }
    }

    public static String lastReport(Context context) {
        SharedPreferences saved=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String code=saved.getString("code",null);
        if(code==null)return "";
        Kind kind;
        try { kind=Kind.valueOf(code); } catch(IllegalArgumentException error) { return ""; }
        String when=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM)
                .format(new Date(saved.getLong("time",0)));
        return context.getString(R.string.surround_3d_last_error,when,saved.getString("version",""))
                +"\n"+kind.code+": "+context.getString(kind.reason)
                +"\n"+saved.getString("detail","")+"\n"+saved.getString("graphics","");
    }
}
