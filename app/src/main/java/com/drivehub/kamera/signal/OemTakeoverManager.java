package com.drivehub.kamera.signal;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.birdseye.ExclusiveCameraLease;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.helper.vehiclesensors.SystemPropertiesHelper;
import com.drivehub.kamera.helper.vehiclesensors.VehicleSpeedReader;
import com.drivehub.kamera.settings.UiPrefs;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** All component, journal and ownership operations run on one worker, never the UI thread. */
public final class OemTakeoverManager implements AutoCloseable {
    public static final String OEM_PACKAGE = "com.saicmotor.hmi.aroundview";
    static final ComponentName RECEIVER = new ComponentName(OEM_PACKAGE, OEM_PACKAGE + ".car.CarBroadcastReceiver");
    static final ComponentName DATA_SERVICE = new ComponentName(OEM_PACKAGE, OEM_PACKAGE + ".car.CarManagerServer");
    private final Context context;
    private final ExclusiveCameraLease lease;
    private final IntSupplier gear;
    private final Runnable changed;
    private final SharedPreferences journal;
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1,
            r -> new Thread(r, "OEM-takeover"));
    private volatile OemTakeoverController controller;
    private volatile boolean closed;
    private long lastDiagnostic = -1;
    private final File diagnosticFile;
    private final OemDataServiceConnection dataService;
    private volatile boolean initializationFailed;

    public OemTakeoverManager(Context context, ExclusiveCameraLease lease, IntSupplier gear,
                              BooleanSupplier oemBusy, Runnable changed) {
        this.context = context.getApplicationContext();
        this.lease = lease;
        this.gear = gear;
        this.changed = changed;
        journal = this.context.getSharedPreferences("oem_takeover_journal", Context.MODE_PRIVATE);
        diagnosticFile = new File(this.context.getFilesDir(), "oem-takeover.log");
        dataService = new OemDataServiceConnection(this.context, DATA_SERVICE, this::recordEvent);
        worker.setRemoveOnCancelPolicy(true);
        worker.execute(() -> {
            try {
                AndroidPorts ports = new AndroidPorts(lease);
                controller = new OemTakeoverController(ports);
                // The OEM receiver will no longer supply BOOT_COMPLETED when exclusive mode persists.
                try { ports.startDataService(); }
                catch (RuntimeException error) { log("OEM data-service preflight: " + error.getClass().getSimpleName() + ": " + error.getMessage()); }
            } catch (RuntimeException | LinkageError error) {
                initializationFailed = true;
                log("initialization failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
                SignalService.setTakeoverYield(this.context, gear.getAsInt() == 2);
            }
        });
        worker.scheduleWithFixedDelay(() -> {
            if (closed || controller == null) return;
            try {
                long now = SystemClock.elapsedRealtime();
                OemTakeoverController.Snapshot before = controller.snapshot();
                boolean coexist = UiPrefs.isOemAvmCoexistEnabled(UiPrefs.getPrefs(this.context));
                boolean busy = !coexist && oemBusy.getAsBoolean();
                controller.tick(coexist, gear.getAsInt(), busy, now);
                if (before != controller.snapshot()) changed.run();
                if (now - lastDiagnostic >= 1000) {
                    lastDiagnostic = now;
                    String nativeEvents = CameraProbe.drainCameraEvents();
                    if (!nativeEvents.isEmpty()) log("native events:\n" + nativeEvents);
                    OemTakeoverController.Snapshot s = controller.snapshot();
                    log("sample state=" + s.state + " allowOem=" + coexist + " oemBusy=" + busy + " gear=" + gear.getAsInt()
                            + " rawGear=" + SystemPropertiesHelper.getString("arcsoft.avm.mCurCarGear")
                            + " speedKmh=" + VehicleSpeedReader.readSpeedKmh()
                            + " rawSpeed=" + SystemPropertiesHelper.getString("arcsoft.avm.mCurCarSpeed")
                            + " lifecycle=" + SystemPropertiesHelper.getString("arcsoft.avm.ActivityLifecycle")
                            + " mainVisible=" + com.drivehub.kamera.MainActivity.isMainVisible()
                            + " leaseMask=" + lease.heldMask() + " blocked=" + CameraProbe.isOemCameraAccessBlocked()
                            + " health[L,R,F,rear;fd,consumers,leases,ageMs,errno,frames]="
                            + Arrays.toString(CameraProbe.cameraHealth()));
                }
            } catch (RuntimeException | LinkageError error) {
                log("sample failed: " + error.getClass().getSimpleName());
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
    }

    public OemTakeoverController.Snapshot snapshot() {
        OemTakeoverController current = controller;
        return current == null ? (initializationFailed ? new OemTakeoverController.Snapshot(
                OemTakeoverController.State.ERROR, "initialization_failed", false) : null) : current.snapshot();
    }

    /** Completion runs on the worker; true means the OEM receiver was verified as enabled. */
    public void requestRestore(java.util.function.Consumer<Boolean> onComplete) {
        if (closed) {
            if (onComplete != null) onComplete.accept(false);
            return;
        }
        try {
            worker.execute(() -> {
                boolean restored = false;
                try {
                    if (!closed) {
                        log("explicit OEM restore requested");
                        // A failed startup must not turn the Restore button into a no-op.
                        if (controller == null) {
                            controller = new OemTakeoverController(new AndroidPorts(lease));
                            initializationFailed = false;
                        }
                        restored = controller.restoreOem(gear.getAsInt(), SystemClock.elapsedRealtime());
                        log("explicit OEM restore verified=" + restored);
                        changed.run();
                    }
                } catch (RuntimeException | LinkageError error) {
                    log("explicit OEM restore failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
                }
                if (onComplete != null) onComplete.accept(restored);
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            if (onComplete != null) onComplete.accept(false);
        }
    }

    private final class AndroidPorts implements OemTakeoverController.Ports {
        private final ExclusiveCameraLease lease;
        private final PackageManager packages = context.getPackageManager();
        AndroidPorts(ExclusiveCameraLease lease) { this.lease = lease; }
        @Override public OemTakeoverController.Journal load() {
            OemTakeoverController.Journal j = new OemTakeoverController.Journal();
            j.originalReceiver = journal.getInt("originalReceiver", -1);
            j.ownsReceiver = journal.getBoolean("ownsReceiver", false);
            j.pending = journal.getBoolean("pending", false);
            j.fallback = journal.getBoolean("fallback", false);
            j.fault = journal.getBoolean("fault", false);
            j.oemLaunchAttempted = journal.getBoolean("oemLaunchAttempted", false);
            j.failure = journal.getString("failure", "");
            // v0.8.20 persisted ordinary preflight failures as a permanent fault. A completed
            // rollback is safe to re-check once with the corrected protocol. Never clear an
            // interrupted takeover, owned receiver or the reverse fallback latch this way.
            if (j.retryCompletedLegacyFault(journal.getInt("protocol", 1))) {
                save(j);
                log("migrated completed legacy preflight fault; rechecking once");
            }
            return j;
        }
        @Override public void save(OemTakeoverController.Journal j) {
            if (!journal.edit().putInt("originalReceiver", j.originalReceiver)
                    .putBoolean("ownsReceiver", j.ownsReceiver).putBoolean("pending", j.pending)
                    .putBoolean("fallback", j.fallback).putBoolean("fault", j.fault)
                    .putBoolean("oemLaunchAttempted", j.oemLaunchAttempted)
                    .putString("failure", j.failure).putInt("protocol", 2).commit())
                throw new IllegalStateException("Cannot persist OEM recovery journal");
        }
        @Override public int receiverState() {
            try { packages.getReceiverInfo(RECEIVER, PackageManager.MATCH_DISABLED_COMPONENTS); }
            catch (PackageManager.NameNotFoundException missing) { throw new IllegalStateException("OEM receiver missing", missing); }
            return packages.getComponentEnabledSetting(RECEIVER);
        }
        @Override public boolean receiverAvailable() {
            try { packages.getReceiverInfo(RECEIVER, PackageManager.MATCH_DISABLED_COMPONENTS); return true; }
            catch (PackageManager.NameNotFoundException missing) { return false; }
        }
        @Override public void receiverState(int state) {
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && (closed || UiPrefs.isOemAvmCoexistEnabled(UiPrefs.getPrefs(context))))
                throw new java.util.concurrent.CancellationException("OEM takeover request changed");
            log("receiver setting=" + state + " DONT_KILL_APP");
            packages.setComponentEnabledSetting(RECEIVER, state, PackageManager.DONT_KILL_APP);
        }
        @Override public void startDataService() { dataService.start(); }
        @Override public boolean dataServiceRunning() { return dataService.running(); }
        @Override public void yieldCameras(boolean yield) { SignalService.setTakeoverYield(context, yield); }
        @Override public void releaseCameras() {
            lease.setActive(false);
            CameraProbe.setOemCameraAccessBlocked(true);
            if (!CameraProbe.stopCombinedMp4Record(false, true))
                log("Recording stop reported an encoder/flush failure; checking camera descriptors independently");
            CameraProbe.releaseCombinedCameras();
            long deadline = SystemClock.elapsedRealtime() + 1000;
            int mask;
            do {
                mask = 0;
                long[] health = CameraProbe.cameraHealth();
                for (int i = 0; i < 4; i++) if (health[i * 6] != 0) mask |= 1 << i;
                if (mask == 0) break;
                SystemClock.sleep(10);
            } while (SystemClock.elapsedRealtime() < deadline);
            if (mask != 0) throw new IllegalStateException("Camera descriptors still held, mask=" + mask);
            log("all four camera descriptors released");
        }
        @Override public void acquireCameras() {
            if (closed || UiPrefs.isOemAvmCoexistEnabled(UiPrefs.getPrefs(context)))
                throw new java.util.concurrent.CancellationException("OEM takeover request changed");
            lease.setActive(true);
        }
        @Override public int heldMask() { return lease.heldMask(); }
        @Override public boolean externallyPaused() {
            return UiPrefs.getPrefs(context).getBoolean(UiPrefs.KEY_EXTERNAL_CAMERA_PAUSED, false);
        }
        @Override public long rearFrameAgeMs() { return CameraProbe.cameraHealth()[21]; }
        @Override public void closeOem() {
            context.sendBroadcast(OemAvmControl.closeCameraIntent()); log("requested pre-existing OEM window close");
        }
        @Override public void openOem() {
            Intent open = new Intent().setComponent(new ComponentName(OEM_PACKAGE, OEM_PACKAGE + ".AVMActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(OEM_PACKAGE + ".startAVMActivityMsg", 1);
            context.startActivity(open); log("one-shot OEM fallback launch");
        }
        @Override public void log(String message) { OemTakeoverManager.this.log(message); }
    }

    public void recordEvent(String message) {
        if (closed) return;
        try { worker.execute(() -> log(message)); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { /* Service is closing. */ }
    }

    private void log(String message) {
        String line = "t=" + SystemClock.elapsedRealtime() + " pid=" + android.os.Process.myPid() + " " + message;
        DevRuntimeLog.add("OEM takeover", line);
        try {
            if (diagnosticFile.length() > 128 * 1024) {
                File previous = new File(context.getFilesDir(), "oem-takeover.previous.log");
                if (previous.exists() && !previous.delete()) return;
                if (!diagnosticFile.renameTo(previous)) return;
            }
            try (FileOutputStream output = new FileOutputStream(diagnosticFile, true)) {
                output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException error) { android.util.Log.w("OEM takeover", "Diagnostic log unavailable", error); }
    }

    public static String diagnosticText(Context context) {
        StringBuilder text = new StringBuilder("OEM takeover diagnostics; no camera images\n");
        for (String name : new String[]{"oem-takeover.previous.log", "oem-takeover.log"}) {
            try { text.append(new String(Files.readAllBytes(new File(context.getFilesDir(), name).toPath()), StandardCharsets.UTF_8)); }
            catch (java.io.IOException ignored) { /* No log yet. */ }
        }
        text.append("\nCurrent rear PDC (levels left / mid-left / mid-right / right):\n")
                .append(com.drivehub.kamera.parking.PdcReader.diagnostic()).append('\n');
        return text.toString();
    }

    /** Explicit user export to the selected dashcam storage (USB when configured/available). */
    public static File exportDiagnostic(Context context) throws java.io.IOException {
        if (!com.drivehub.kamera.dashcam.StorageIoGate.begin()) throw new java.io.IOException("Storage is stopped for eject/shutdown");
        try {
            com.drivehub.kamera.dashcam.DashcamStorageManager.Resolution storage =
                    com.drivehub.kamera.dashcam.DashcamStorageManager.resolve(context);
            if (storage.baseDir == null) throw new java.io.IOException("Selected storage unavailable");
            File directory = new File(storage.baseDir, "oem-diagnostics");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create diagnostic directory");
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS",java.util.Locale.US).format(new java.util.Date());
            File file = new File(directory, "oem-" + com.drivehub.kamera.BuildConfig.VERSION_NAME + "-" + stamp + ".log");
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(("App " + com.drivehub.kamera.BuildConfig.VERSION_NAME + "\n" + diagnosticText(context)).getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            return file;
        } finally { com.drivehub.kamera.dashcam.StorageIoGate.end(); }
    }

    @Override public void close() {
        closed = true;
        try { worker.execute(dataService::close); } catch (java.util.concurrent.RejectedExecutionException ignored) { dataService.close(); }
        worker.shutdown();
    }
}
