package com.drivehub.kamera.birdseye;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ThreeDFailureInstrumentedTest {
    @Test public void unavailableDiagnosticStorageDoesNotBreakTheTwoDFallback() {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context unwritable=new ContextWrapper(base) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode){throw new IllegalStateException("Read-only test store");}
        };
        ThreeDFailure failure=ThreeDFailure.at(ThreeDFailure.Kind.MODEL,new IOException("Cannot load model texture"));
        failure.report(unwritable);
        assertTrue(failure.userMessage(base).contains("3D-MODEL"));
    }

    @Test public void lastFailureSurvivesReopeningAndIncludesCauseVersionAndTime() {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences saved=base.getSharedPreferences("test-3d-diagnostics",Context.MODE_PRIVATE);
        Context isolated=new ContextWrapper(base) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return saved;}
        };
        saved.edit().clear().commit();
        try {
            assertEquals("",ThreeDFailure.lastReport(isolated));
            ThreeDFailure failure=ThreeDFailure.at(ThreeDFailure.Kind.MODEL,
                    new IOException("Cannot load prepared 3D paints",new IOException("Missing test-paint.png")));
            failure.report(isolated);
            Context reopened=new ContextWrapper(base) {
                @Override public SharedPreferences getSharedPreferences(String name,int mode){return saved;}
            };
            String report=ThreeDFailure.lastReport(reopened);
            assertTrue(report,report.contains("3D-MODEL"));
            assertTrue(report,report.contains("Missing test-paint.png"));
            assertTrue(report,report.contains(com.drivehub.kamera.BuildConfig.VERSION_NAME));
            assertTrue(saved.getLong("time",0)>0);
            assertTrue(report,report.contains("Birdseye"));
        } finally { saved.edit().clear().commit(); }
    }

    @Test public void allCodesHaveLocalizedMessagesWithThePersistentDetailsLocation() {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        for(String language:new String[]{"en","de","cs","fr"}) {
            Configuration configuration=new Configuration(base.getResources().getConfiguration());
            configuration.setLocale(Locale.forLanguageTag(language));
            Context localized=base.createConfigurationContext(configuration);
            for(ThreeDFailure.Kind kind:ThreeDFailure.Kind.values()) {
                String message=ThreeDFailure.at(kind,new IllegalStateException()).userMessage(localized);
                assertTrue(message,message.contains(kind.code));
                assertTrue(message,message.contains("2D"));
                assertTrue(message,message.contains("Dev →"));
                assertFalse(message,message.contains("%1$s"));
            }
        }
    }
}
