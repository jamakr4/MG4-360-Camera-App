package com.drivehub.kamera.camera;

import android.content.SharedPreferences;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.R;
import com.drivehub.kamera.birdseye.BirdseyeCalibration;
import com.drivehub.kamera.birdseye.BirdseyeSynthetic;
import com.drivehub.kamera.settings.UiPrefs;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class IpkRearviewInstrumentedTest {
    private float[] coordinates(){return new RearviewLensMap(BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes())).coordinates();}
    private int[] source(int height) {
        int[] p=new int[720*height];
        for(int y=0;y<height;y++)for(int x=0;x<720;x++)p[y*720+x]=0xff000000|((x/3)<<16)|((height==240?y:y/2)<<8)|37;
        return p;
    }
    @Test public void wideRetainsEverySourcePixelAndMirrorsExactlyOnce() {
        for(int height:new int[]{240,480}) {
            int[] source=source(height),copy=source.clone();
            int[] output=IpkRearviewDebugBridge.render(source,height,null);
            assertNotNull(output);assertEquals(source.length,output.length);assertArrayEquals(copy,source);
            for(int y=0;y<height;y++)for(int x=0;x<720;x++)assertEquals(source[y*720+x],output[y*720+719-x]);
        }
    }
    @Test public void normalMatchesIndependentReferenceSamplingForBothFieldLayouts() {
        float[] uv=coordinates();
        for(int height:new int[]{240,480}) {
            int[] source=source(height),output=IpkRearviewDebugBridge.render(source,height,uv);
            assertNotNull(output);assertEquals(260*173,output.length);
            double error=0;
            for(int y=0;y<173;y++)for(int x=0;x<260;x++) {
                int i=(y*260+x)*2;double u=uv[i],v=height==240?Math.min(239,uv[i+1]/2):uv[i+1];
                int sx=(int)u,sy=(int)v,xx=Math.min(719,sx+1),yy=Math.min(height-1,sy+1);
                double dx=u-sx,dy=v-sy;
                for(int shift:new int[]{0,8,16}) {
                    double a=((source[sy*720+sx]>>>shift)&255)*(1-dx)+((source[sy*720+xx]>>>shift)&255)*dx;
                    double b=((source[yy*720+sx]>>>shift)&255)*(1-dx)+((source[yy*720+xx]>>>shift)&255)*dx;
                    error+=Math.abs(Math.round(a*(1-dy)+b*dy)-((output[y*260+259-x]>>>shift)&255));
                }
            }
            assertTrue("Mean RGB error="+error/(260*173*3),error/(260*173*3)<1.2);
        }
    }
    @Test public void jniRejectsInvalidMapsAndStaleOverlayOwners() {
        int first=1_100_000_000,second=first+1;float[] uv=coordinates();
        try {
            CameraProbe.setIpkRearviewSession(first);assertTrue(CameraProbe.setIpkRearviewMap(first,uv));
            assertFalse(CameraProbe.setIpkRearviewMap(first,new float[1]));
            for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-1,720}) {
                float[] bad=uv.clone();bad[0]=invalid;assertFalse(CameraProbe.setIpkRearviewMap(first,bad));
            }
            CameraProbe.setIpkRearviewSession(second);
            assertFalse(CameraProbe.setIpkRearviewMap(first,uv));assertTrue(CameraProbe.setIpkRearviewMap(second,uv));
            CameraProbe.setIpkRearviewSession(-first);assertTrue(CameraProbe.setIpkRearviewMap(second,null));
            CameraProbe.setIpkRearviewSession(-second);assertFalse(CameraProbe.setIpkRearviewMap(second,uv));
        }finally{CameraProbe.setIpkRearviewSession(-second);CameraProbe.setIpkRearviewSession(-first);}
    }
    @Test public void segmentedViewDefaultsWideAndPersistsWithoutChangingOtherRearviewSettings() {
        SharedPreferences prefs=UiPrefs.getPrefs(InstrumentationRegistry.getInstrumentation().getTargetContext());
        Map<String,?> saved=prefs.getAll();
        String[] keys={UiPrefs.KEY_DIGITAL_REARVIEW_IPK_NORMAL,UiPrefs.KEY_DIGITAL_REARVIEW_DISPLAY_TARGET};
        prefs.edit().remove(keys[0]).putInt(keys[1],0).commit();
        boolean enabled=UiPrefs.isDigitalRearviewEnabled(prefs);int screen=UiPrefs.getDigitalRearviewIpkScreen(prefs);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            openSettings();
            onView(withId(R.id.segRearviewIpkView)).inRoot(isDialog()).check(matches(withEffectiveVisibility(Visibility.GONE)));
            onView(withId(R.id.segRearviewDisplayIpk)).inRoot(isDialog()).perform(scrollTo(),click());
            onView(withId(R.id.segRearviewIpkWide)).inRoot(isDialog()).perform(scrollTo()).check(matches(isSelected()));
            assertFalse(UiPrefs.isDigitalRearviewIpkNormal(prefs));
            onView(withId(R.id.segRearviewIpkNormal)).inRoot(isDialog()).perform(click()).check(matches(isSelected()));
            assertTrue(UiPrefs.isDigitalRearviewIpkNormal(prefs));assertEquals(enabled,UiPrefs.isDigitalRearviewEnabled(prefs));
            assertEquals(screen,UiPrefs.getDigitalRearviewIpkScreen(prefs));
            onView(withId(R.id.btnClose)).inRoot(isDialog()).perform(click());scenario.recreate();openSettings();
            onView(withId(R.id.segRearviewIpkNormal)).inRoot(isDialog()).perform(scrollTo()).check(matches(isSelected()));
            onView(withId(R.id.segRearviewDisplayBoth)).inRoot(isDialog()).perform(scrollTo(),click());
            onView(withId(R.id.segRearviewIpkNormal)).inRoot(isDialog()).perform(scrollTo()).check(matches(isSelected()));
            onView(withId(R.id.segRearviewIpkWide)).inRoot(isDialog()).perform(click());assertFalse(UiPrefs.isDigitalRearviewIpkNormal(prefs));
            onView(withId(R.id.btnClose)).inRoot(isDialog()).perform(click());
        }finally {
            SharedPreferences.Editor edit=prefs.edit();for(String key:keys){Object v=saved.get(key);
                if(v instanceof Boolean)edit.putBoolean(key,(Boolean)v);else if(v instanceof Integer)edit.putInt(key,(Integer)v);else edit.remove(key);}
            edit.commit();
        }
    }
    private static void openSettings() {
        onView(withId(R.id.btnSettings)).perform(click());onView(withId(R.id.tabDigitalRearview)).inRoot(isDialog()).perform(click());
    }
}
