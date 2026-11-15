package com.drivehub.kamera.birdseye;

import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AssetCacheTest {
    @Test public void successfulValueIsReusedAndIdentityChangesReplaceIt() throws Exception {
        SingleAssetCache<Object> cache=new SingleAssetCache<>();Object first=new Object(),second=new Object();
        assertSame(first,cache.get("model+materials-v1",()->first));
        assertSame(first,cache.get("model+materials-v1",()->{throw new AssertionError("Reloaded");}));
        assertSame(second,cache.get("model+materials-v2",()->second));
        assertNotSame(first,cache.get("model+materials-v1",Object::new));
    }
    @Test public void failedNewKeyNeverReturnsOldDataAndCanRetry() throws Exception {
        SingleAssetCache<Object> cache=new SingleAssetCache<>();Object old=cache.get("old",Object::new);
        try{cache.get("new",()->{throw new IOException("bad model");});fail();}catch(IOException expected){}
        assertSame(old,cache.get("old",()->{throw new AssertionError("Lost valid old key");}));
        assertNotSame(old,cache.get("new",Object::new));
    }
    @Test public void simultaneousRequestsShareOneLoad() throws Exception {
        SingleAssetCache<Object> cache=new SingleAssetCache<>();AtomicInteger loads=new AtomicInteger();
        ExecutorService pool=Executors.newFixedThreadPool(8);CountDownLatch start=new CountDownLatch(1);
        try {
            java.util.List<Future<Object>> results=new java.util.ArrayList<>();
            for(int i=0;i<8;i++)results.add(pool.submit(()->{start.await();return cache.get("same",()->{loads.incrementAndGet();return new Object();});}));
            start.countDown();Object value=results.get(0).get(3,TimeUnit.SECONDS);
            for(Future<Object> future:results)assertSame(value,future.get(3,TimeUnit.SECONDS));assertEquals(1,loads.get());
        }finally{pool.shutdownNow();}
    }
    @Test public void currentCalibrationHashReusesProjectionAndChangedHashInvalidatesIt() throws Exception {
        byte[] bytes=BirdseyeSynthetic.calibrationBytes();
        ProjectionAssets first=ProjectionAssets.get(BirdseyeCalibration.parse(bytes));first.scene.prepareBowl();
        ProjectionAssets same=ProjectionAssets.get(BirdseyeCalibration.parse(bytes.clone()));
        assertSame(first,same);assertSame(first.mesh,same.mesh);assertSame(first.scene.bowl(),same.scene.bowl());
        byte[] changed=bytes.clone();java.nio.ByteBuffer.wrap(changed).order(java.nio.ByteOrder.LITTLE_ENDIAN).putDouble(43);
        ProjectionAssets next=ProjectionAssets.get(BirdseyeCalibration.parse(changed));assertNotSame(first,next);assertNotSame(first.scene,next.scene);
        try{ProjectionAssets.get(BirdseyeCalibration.parse(new byte[520]));fail();}catch(IllegalArgumentException expected){}
        assertSame(next,ProjectionAssets.get(BirdseyeCalibration.parse(changed)));
    }
}
