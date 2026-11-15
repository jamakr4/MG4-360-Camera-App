package com.drivehub.kamera.birdseye;

import android.view.View;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MainCameraLayoutInstrumentedTest {
    private void assertPanels(MainCameraLayout root) {
        MainCameraGeometry g=new MainCameraGeometry(root.getWidth()-root.getPaddingLeft()-root.getPaddingRight(),
                root.getHeight()-root.getPaddingTop()-root.getPaddingBottom());
        int[] ids={R.id.sideCameraPanel,R.id.mainCameraPanel,R.id.birdseyeView};
        for(int i=0;i<3;i++) {
            View child=root.findViewById(ids[i]);int[] p=g.panels[i];
            assertEquals(p[0]+root.getPaddingLeft(),child.getLeft());
            assertEquals(p[1]+root.getPaddingTop(),child.getTop());
            assertEquals(p[2]-p[0],child.getWidth());assertEquals(p[3]-p[1],child.getHeight());
        }
    }
    @Test public void actualPanelsAndAllCentralControlsStayInsideCenterAcrossInsetsAndLifecycle() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(a->{
                MainCameraLayout root=a.findViewById(R.id.main);assertPanels(root);
                View center=a.findViewById(R.id.mainCameraPanel);
                for(int id:new int[]{R.id.surfaceView,R.id.mainPreviewOemPaused,R.id.tvStatus,
                        R.id.recordingStatusPill,R.id.bg_tishi,R.id.warningBanner,R.id.btnTriggerEventSave,R.id.btnRecordTestClip})
                    assertSame(center,a.findViewById(id).getParent());
                // Same real ViewGroup policy at physical reference size, including asymmetric insets.
                MainCameraLayout isolated=new MainCameraLayout(a,null);
                for(int id:new int[]{R.id.sideCameraPanel,R.id.mainCameraPanel,R.id.birdseyeView}) {
                    View v=new View(a);v.setId(id);isolated.addView(v);
                }
                for(int[] size:new int[][]{{1920,640},{960,800},{2000,700}}) {
                    isolated.setPadding(size[0]==2000?32:0,size[0]==2000?20:0,size[0]==2000?48:0,size[0]==2000?40:0);
                    isolated.measure(View.MeasureSpec.makeMeasureSpec(size[0],View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(size[1],View.MeasureSpec.EXACTLY));
                    isolated.layout(0,0,size[0],size[1]);assertPanels(isolated);
                }
            });
            for(int i=0;i<3;i++){scenario.moveToState(Lifecycle.State.CREATED);scenario.moveToState(Lifecycle.State.RESUMED);}
            scenario.recreate();InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(a->assertPanels(a.findViewById(R.id.main)));
        }
    }
    @Test public void nativeBirdseyeRespectsOemGateAndRejectsInvalidOwnersAndBuffers() {
        boolean previous=CameraProbe.isOemCameraAccessBlocked();
        try {
            CameraProbe.setOemCameraAccessBlocked(true);
            for(int camera=0;camera<4;camera++) assertFalse(BirdseyeNative.attach(99999,camera));
            assertEquals(0,BirdseyeNative.copy(99999,0,ByteBuffer.allocateDirect(720*240*2),0));
            assertFalse(BirdseyeNative.attach(0,0));assertFalse(BirdseyeNative.attach(1,4));
            BirdseyeNative.detach(99999);
        } finally {CameraProbe.setOemCameraAccessBlocked(previous);}
    }
    @Test public void externalPauseBlocksBirdseyeSnapshotsAndExclusiveLeases() {
        boolean external=com.drivehub.kamera.settings.UiPrefs.getPrefs(
                InstrumentationRegistry.getInstrumentation().getTargetContext())
                .getBoolean(com.drivehub.kamera.settings.UiPrefs.KEY_EXTERNAL_CAMERA_PAUSED,false);
        int owner=654321;
        try {
            CameraProbe.setExternalCameraAccessBlocked(true);
            CameraProbe.releaseCameraPreviewsForHandoff();
            for(int camera=0;camera<4;camera++) {
                assertFalse(BirdseyeNative.attach(owner,camera));
                assertFalse(BirdseyeNative.attachSnapshot(owner,camera));
                assertFalse(BirdseyeNative.hold(owner,camera));
                assertEquals(0,BirdseyeNative.copy(owner,camera,ByteBuffer.allocateDirect(720*240*2),0));
            }
            assertArrayEquals(new int[]{0,0,1},CameraProbe.cameraOwnershipSnapshot());
        } finally {
            BirdseyeNative.detach(owner);BirdseyeNative.releaseHold(owner);
            CameraProbe.setExternalCameraAccessBlocked(external);
        }
    }
    @Test public void actionButtonsShareOneRowAndPdcStaysAboveThemWhenTestRecordingIsToggled() {
        android.content.SharedPreferences prefs=com.drivehub.kamera.settings.UiPrefs.getPrefs(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        boolean hadValue=prefs.contains("testRecordEnabled"),saved=prefs.getBoolean("testRecordEnabled",true);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->{
                MainCameraLayout root=a.findViewById(R.id.main);
                View center=a.findViewById(R.id.mainCameraPanel),dashcam=a.findViewById(R.id.btnTriggerEventSave),
                        test=a.findViewById(R.id.btnRecordTestClip);
                TextView pdc=a.findViewById(R.id.pdcStatus);
                TextView wheels=a.findViewById(R.id.modeWheels);
                View sides=a.findViewById(R.id.sideCameraPanel);
                for(boolean enabled:new boolean[]{true,false,true,false}) {
                    prefs.edit().putBoolean("testRecordEnabled",enabled).commit();a.refreshTestRecordButtonState();
                    // The live status must stay above both action buttons.
                    pdc.setText("PDC  ●   ●   ●   ●   80 cm");
                    pdc.setVisibility(View.VISIBLE);
                    for(int[] size:new int[][]{{1920,640},{1280,480},{960,640}}) {
                        root.measure(View.MeasureSpec.makeMeasureSpec(size[0],View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(size[1],View.MeasureSpec.EXACTLY));
                        root.layout(0,0,size[0],size[1]);
                        assertEquals(sides.getTop()+wheels.getTop(),center.getTop()+dashcam.getTop(),1);
                        assertEquals(wheels.getHeight(),dashcam.getHeight(),1);
                        assertEquals(wheels.getTextSize(),((TextView)dashcam).getTextSize(),.05f);
                        assertEquals(wheels.getTypeface(),((TextView)dashcam).getTypeface());
                        for(View button:new View[]{dashcam,test}) {
                            if(button.getVisibility()==View.GONE)continue;
                            TextView label=(TextView)button;
                            assertEquals(1,label.getLineCount());
                            assertTrue(label.getPaint().measureText(label.getText().toString())
                                    <=label.getWidth()-label.getPaddingLeft()-label.getPaddingRight());
                        }
                        assertTrue(dashcam.getWidth()>0);assertTrue(dashcam.getLeft()>=0);
                        assertTrue(dashcam.getRight()<=center.getWidth());
                        assertTrue(pdc.getTop()>=0);assertTrue(pdc.getBottom()<dashcam.getTop());
                        if(enabled) {
                            assertEquals(View.VISIBLE,test.getVisibility());assertTrue(test.getWidth()>0);
                            assertEquals(dashcam.getTop(),test.getTop());assertEquals(dashcam.getBottom(),test.getBottom());
                            assertTrue(dashcam.getRight()<test.getLeft());assertTrue(test.getRight()<=center.getWidth());
                            assertEquals(center.getWidth()/2.0,(dashcam.getLeft()+test.getRight())/2.0,1);
                        } else {
                            assertEquals(View.GONE,test.getVisibility());
                            assertEquals(center.getWidth()/2.0,(dashcam.getLeft()+dashcam.getRight())/2.0,1);
                            assertTrue(dashcam.getWidth()>=Math.round(300*center.getHeight()/640f));
                        }
                    }
                }
            });
        } finally {
            android.content.SharedPreferences.Editor edit=prefs.edit();
            if(hadValue)edit.putBoolean("testRecordEnabled",saved);else edit.remove("testRecordEnabled");edit.commit();
        }
    }
}
