package com.drivehub.kamera.birdseye;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class ProjectionDiskCacheTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();

    private static ProjectionDiskCache.Entry computed() {
        RivelaScene scene=new RivelaScene(BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes()),RivelaScene.GUIDANCE_FLAT_MM);
        return new ProjectionDiskCache.Entry(new BirdseyeMesh(scene.calibration),new BirdseyeMesh(scene),null);
    }
    private static void assertSameMesh(BirdseyeMesh expected,BirdseyeMesh actual) {
        assertEquals(expected.coveredVertices,actual.coveredVertices);
        assertEquals(expected.groundWidth,actual.groundWidth,0);assertEquals(expected.groundHeight,actual.groundHeight,0);
        assertEquals(0,expected.vertices.duplicate().compareTo(actual.vertices.duplicate()));
        assertEquals(0,expected.indices.duplicate().compareTo(actual.indices.duplicate()));
        assertEquals(0,actual.vertices.position());assertEquals(0,actual.indices.position());
    }

    @Test public void roundTripReproducesTheComputedMeshesExactly() {
        ProjectionDiskCache cache=new ProjectionDiskCache(folder.getRoot());ProjectionDiskCache.Entry entry=computed();
        cache.store("k1",entry);
        ProjectionDiskCache.Entry loaded=cache.load("k1");
        assertNotNull(loaded);assertSameMesh(entry.mesh,loaded.mesh);assertSameMesh(entry.wideMesh,loaded.wideMesh);assertNull(loaded.wideError);
    }
    @Test public void wideFailureIsKeptAsError() {
        ProjectionDiskCache cache=new ProjectionDiskCache(folder.getRoot());ProjectionDiskCache.Entry entry=computed();
        cache.store("k",new ProjectionDiskCache.Entry(entry.mesh,null,"3D lens range is invalid"));
        ProjectionDiskCache.Entry loaded=cache.load("k");
        assertNull(loaded.wideMesh);assertEquals("3D lens range is invalid",loaded.wideError);
    }
    @Test public void otherKeyIsAMissAndKeepsTheFile() {
        ProjectionDiskCache cache=new ProjectionDiskCache(folder.getRoot());cache.store("old-sha",computed());
        assertNull(cache.load("new-sha"));
        assertTrue(new File(folder.getRoot(),ProjectionDiskCache.FILE).isFile());
    }
    @Test public void corruptOrTruncatedFileIsDiscarded() throws IOException {
        ProjectionDiskCache cache=new ProjectionDiskCache(folder.getRoot());cache.store("k",computed());
        File file=new File(folder.getRoot(),ProjectionDiskCache.FILE);
        try(RandomAccessFile raf=new RandomAccessFile(file,"rw")){raf.seek(raf.length()/2);int b=raf.read();raf.seek(raf.length()/2);raf.write(b^1);}
        assertNull(cache.load("k"));assertFalse(file.exists());
        cache.store("k",computed());
        byte[] all=Files.readAllBytes(file.toPath());Files.write(file.toPath(),java.util.Arrays.copyOf(all,all.length/3));
        assertNull(cache.load("k"));assertFalse(file.exists());
    }
    @Test public void projectionAssetsUsesDiskEntryForTheSameCalibration() throws Exception {
        BirdseyeCalibration calibration=BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes());
        ProjectionDiskCache cache=new ProjectionDiskCache(folder.getRoot());ProjectionDiskCache.Entry entry=computed();
        cache.store(ProjectionAssets.key(calibration),entry);
        assertNotNull(cache.load(ProjectionAssets.key(calibration)));
        byte[] changed=BirdseyeSynthetic.calibrationBytes();
        java.nio.ByteBuffer.wrap(changed).order(java.nio.ByteOrder.LITTLE_ENDIAN).putDouble(43);
        assertNull("A changed calibration must never reuse the old mesh",
                cache.load(ProjectionAssets.key(BirdseyeCalibration.parse(changed))));
    }
}
