package com.drivehub.kamera.birdseye;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;
public class StartupLoadGuardTest {
    static final class Memory implements StartupLoadGuard.Store {
        StartupLoadGuard.State state=new StartupLoadGuard.State(0,0,true);boolean fail;
        public StartupLoadGuard.State read() throws IOException {if(fail)throw new IOException("storage");return state;}
        public void write(StartupLoadGuard.State next) throws IOException {if(fail)throw new IOException("storage");state=next;}
    }
    @Test public void fiveUnconfirmedProcessesBlockSixthAcrossNewGuardInstances() throws Exception {
        Memory store=new Memory();
        for(int i=1;i<=5;i++){StartupLoadGuard.State ticket=new StartupLoadGuard(store).begin(true);assertNotNull(ticket);assertEquals(i,ticket.attempts);}
        assertNull(new StartupLoadGuard(store).begin(true));assertNull(new StartupLoadGuard(store).begin(false));
    }
    @Test public void stableAcknowledgementClearsStreakButStaleAcknowledgementCannotClearNewAttempt() throws Exception {
        Memory store=new Memory();StartupLoadGuard g=new StartupLoadGuard(store);
        long first=g.begin(true).serial,second=g.begin(true).serial;g.stable(first);assertEquals(2,store.state.attempts);
        g.stable(second);assertEquals(0,store.state.attempts);assertNotNull(g.begin(true));
    }
    @Test public void automaticDisablePreservesManualUseButNeverBypassesLatchedProtection() throws Exception {
        Memory store=new Memory();StartupLoadGuard g=new StartupLoadGuard(store);g.automatic(false);assertNull(g.begin(true));assertNotNull(g.begin(false));
        for(int i=0;i<4;i++)g.begin(false);g.automatic(true);assertNull(g.begin(false));g.reset();assertNotNull(g.begin(true));
    }
    @Test public void persistenceFailureDoesNotGrantAnAttempt() throws Exception {
        Memory store=new Memory();store.fail=true;
        try{new StartupLoadGuard(store).begin(true);fail();}catch(IOException expected){}
        assertEquals(0,store.state.attempts);
    }
    @Test public void recordSurvivesSerializationAndCorruptionIsRejected() throws Exception {
        byte[] bytes=StartupLoadGuard.encode(new StartupLoadGuard.State(5,17,false));
        StartupLoadGuard.State state=StartupLoadGuard.decode(bytes);assertEquals(5,state.attempts);assertEquals(17,state.serial);assertFalse(state.automatic);
        for(int i=0;i<bytes.length;i++){byte[] bad=bytes.clone();bad[i]^=1;try{StartupLoadGuard.decode(bad);fail();}catch(IOException expected){}}
        try{StartupLoadGuard.decode(new byte[0]);fail();}catch(IOException expected){}
    }
}
