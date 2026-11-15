package com.drivehub.kamera.birdseye;

import org.junit.Test;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import static org.junit.Assert.*;

public class VehicleBinaryTest {
    private static final String SOURCE="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static String sha(byte[] data) throws Exception {
        StringBuilder result=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))result.append(String.format("%02x",b&255));return result.toString();
    }
    private static byte[] fixture() {
        ByteBuffer b=ByteBuffer.allocate(56+5*36+15*32).order(ByteOrder.LITTLE_ENDIAN);
        b.put("MG4MSH01".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(1).putInt(8).putInt(15).putInt(5);
        for(int i=0;i<64;i+=2)b.put((byte)Integer.parseInt(SOURCE.substring(i,i+2),16));
        String[] names={"body","front_left","front_right","rear_left","rear_right"};
        float[][] pivots={{0,0,0},{-777,-1271,-351},{777,-1271,-351},{-777,1458,-376},{777,1458,-376}};
        for(int i=0;i<5;i++) {
            byte[] name=new byte[16],text=names[i].getBytes(java.nio.charset.StandardCharsets.US_ASCII);System.arraycopy(text,0,name,0,text.length);
            b.put(name).putInt(i*3).putInt(3);for(float p:pivots[i])b.putFloat(p);
        }
        for(int group=0;group<5;group++)for(int corner=0;corner<3;corner++) {
            float[] p=group==0?new float[][]{{-918,-2143.5f,-1500},{918,2143.5f,0},{-918,2143.5f,0}}[corner]
                    :new float[]{pivots[group][0]+(corner==0?-50:corner==1?50:0),pivots[group][1]+(corner==2?50:0),pivots[group][2]};
            for(float v:p)b.putFloat(v);b.putFloat(0).putFloat(0).putFloat(-1).putFloat(.1f).putFloat(.2f);
        }
        return b.array();
    }
    private static VehicleModel.Parsed read(byte[] bytes) throws Exception {
        return VehicleModel.readBinary(new ByteArrayInputStream(bytes),sha(bytes),SOURCE);
    }
    private static void reject(byte[] bytes) throws Exception {
        try{read(bytes);fail("Invalid binary accepted");}catch(IOException expected){}
    }
    @Test public void binaryPreservesOrderedGroupsPivotsAndReadOnlyVertexLayout() throws Exception {
        VehicleModel.Parsed parsed=read(fixture());assertEquals(120,parsed.vertices.remaining());assertTrue(parsed.vertices.isReadOnly());
        assertEquals(5,parsed.parts.size());assertEquals("front_right",parsed.parts.get(2).name);
        assertEquals(777,parsed.parts.get(2).pivotX,0);assertEquals(-1271,parsed.parts.get(2).pivotY,0);
        assertEquals(6,parsed.parts.get(2).firstVertex);assertTrue(parsed.parts.get(2).steers);assertFalse(parsed.parts.get(4).steers);
    }
    @Test public void binaryRejectsMalformedHeaderCountsGroupsAndTruncation() throws Exception {
        byte[] original=fixture();
        for(int offset:new int[]{0,8,12,20,24,56,56+16,56+20,56+36+16}) {
            byte[] bad=original.clone();bad[offset]^=0x7f;reject(bad);
        }
        for(int count:new int[]{0,-1,300003,Integer.MAX_VALUE,14}) {
            byte[] bad=original.clone();ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(16,count);reject(bad);
        }
        for(int length:new int[]{1,55,100,original.length-1})reject(java.util.Arrays.copyOf(original,length));
        reject(java.util.Arrays.copyOf(original,original.length+1));
    }
    @Test public void binaryRejectsCorruptionAndWrongExpectedIdentity() throws Exception {
        byte[] original=fixture(),bad=original.clone();bad[bad.length-10]^=1;
        try{VehicleModel.readBinary(new ByteArrayInputStream(bad),sha(original),SOURCE);fail();}catch(IOException expected){}
        try{VehicleModel.readBinary(new ByteArrayInputStream(original),sha(original),"f".repeat(64));fail();}catch(IOException expected){}
        for(String invalid:new String[]{"",null,"zz".repeat(32)})
            try{VehicleModel.readBinary(new ByteArrayInputStream(original),invalid,SOURCE);fail();}catch(IOException expected){}
    }
    @Test public void binaryRejectsNonFiniteGeometryInvalidAtlasAndWheelBounds() throws Exception {
        for(float value:new float[]{Float.NaN,Float.POSITIVE_INFINITY,11000}) {
            byte[] bad=fixture();ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putFloat(236,value);reject(bad);
        }
        byte[] uv=fixture();ByteBuffer.wrap(uv).order(ByteOrder.LITTLE_ENDIAN).putFloat(236+24,1.01f);reject(uv);
        byte[] pivot=fixture();ByteBuffer.wrap(pivot).order(ByteOrder.LITTLE_ENDIAN).putFloat(56+36+24,777);reject(pivot);
        byte[] body=fixture();ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).putFloat(56+24,1);reject(body);
        byte[] wheel=fixture();ByteBuffer.wrap(wheel).order(ByteOrder.LITTLE_ENDIAN).putFloat(236+3*32,-1600);reject(wheel);
    }
    @Test public void preparedLocalBinaryMatchesEveryObjFloatAndWheelPivot() throws Exception {
        Path directory=Paths.get("src/main/assets/mg4/model3d");
        org.junit.Assume.assumeTrue("Local model inputs required for full equivalence",Files.isRegularFile(directory.resolve("car.obj"))&&Files.isRegularFile(directory.resolve("car.meshbin")));
        byte[] binary=Files.readAllBytes(directory.resolve("car.meshbin")),obj=Files.readAllBytes(directory.resolve("car.obj"));
        VehicleModel.Parsed expected=VehicleModel.parseParts(new InputStreamReader(new ByteArrayInputStream(obj),java.nio.charset.StandardCharsets.UTF_8));
        VehicleModel.Parsed actual=VehicleModel.readBinary(new ByteArrayInputStream(binary),sha(binary),sha(obj));
        assertEquals(expected.vertices.limit(),actual.vertices.limit());
        for(int i=0;i<actual.vertices.limit();i++)assertEquals(Float.floatToIntBits(expected.vertices.get(i)),Float.floatToIntBits(actual.vertices.get(i)));
        for(int i=0;i<5;i++) {
            VehicleModel.Part a=expected.parts.get(i),b=actual.parts.get(i);
            assertEquals(a.name,b.name);assertEquals(a.firstVertex,b.firstVertex);assertEquals(a.vertexCount,b.vertexCount);
            assertEquals(a.pivotX,b.pivotX,0);assertEquals(a.pivotY,b.pivotY,0);assertEquals(a.pivotZ,b.pivotZ,0);
        }
    }
}
