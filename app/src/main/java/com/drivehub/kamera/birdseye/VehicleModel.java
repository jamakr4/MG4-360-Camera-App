package com.drivehub.kamera.birdseye;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.*;
import java.nio.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;
/** Bounded binary runtime loader and eight prepared paint palettes. IO worker only. */
public final class VehicleModel {
    public static final String DIRECTORY="mg4/model3d/";
    static final String BINARY="car.meshbin";
    private static final String[] PART_NAMES={"body","front_left","front_right","rear_left","rear_right"};
    public final FloatBuffer vertices;
    public final int vertexCount;
    public final Bitmap atlas;
    public final List<Part> parts;
    private final Map<BirdseyeCarColor,Paint> paints;
    final float[] paintRect;
    public static final class Paint {
        public final Bitmap atlas;
        public final float metallic,roughness;
        Paint(Bitmap atlas,float metallic,float roughness) {
            this.atlas=atlas;this.metallic=metallic;this.roughness=roughness;
        }
    }
    public Paint paint(BirdseyeCarColor color) {
        return paints.get(color==null?BirdseyeCarColor.PICCADILLY_BLUE:color);
    }
    public static final class Part {
        public final String name;
        public final int firstVertex,vertexCount;
        public final float pivotX,pivotY,pivotZ;
        public final boolean steers;
        private Part(String name,int first,int count,float[] pivot) {
            this.name=name;firstVertex=first;vertexCount=count;pivotX=pivot[0];pivotY=pivot[1];pivotZ=pivot[2];
            steers=name.equals("front_left")||name.equals("front_right");
        }
    }
    static final class Parsed {
        final FloatBuffer vertices;final List<Part> parts;
        Parsed(FloatBuffer vertices,List<Part> parts){this.vertices=vertices;this.parts=Collections.unmodifiableList(parts);}
    }
    private VehicleModel(Parsed parsed,Map<BirdseyeCarColor,Paint> paints,float[] rect) {
        vertices=parsed.vertices;vertexCount=vertices.limit()/8;parts=parsed.parts;
        this.paints=Collections.unmodifiableMap(paints);paintRect=rect;
        atlas=paint(BirdseyeCarColor.PICCADILLY_BLUE).atlas;
    }
    public static VehicleModel load(AssetManager assets) throws IOException {
        Parsed parsed;
        String source;
        try {
            JSONObject metadata=new JSONObject(metadataBytes(assets,"model.json"));
            JSONObject runtime=metadata.getJSONObject("runtime_mesh");
            if(!BINARY.equals(runtime.getString("file"))||runtime.getInt("format_version")!=1)
                throw new IOException("Unsupported runtime mesh");
            source=metadata.getJSONObject("sha256").getString("car.obj");
            if(!source.equals(runtime.getString("source_sha256")))throw new IOException("Model source hash mismatch");
            try(InputStream in=assets.open(DIRECTORY+BINARY)) {parsed=readBinary(in,runtime.getString("sha256"),source);}
            if(runtime.getInt("vertex_stride_floats")!=8||runtime.getInt("vertices")!=parsed.vertices.limit()/8
                    ||runtime.getInt("bytes")!=236+parsed.vertices.limit()*4||metadata.getInt("triangles")!=parsed.vertices.limit()/24)
                throw new IOException("Binary mesh metadata mismatch");
        } catch(JSONException e) {throw new IOException("Invalid runtime model metadata",e);}
        return loadPaints(assets,parsed,source);
    }
    /** Small APK metadata identifies the immutable model/material pair without reading the mesh again. */
    static String assetKey(AssetManager assets) throws IOException {
        MessageDigest digest=sha256();
        digest.update(metadataBytes(assets,"model.json").getBytes(StandardCharsets.UTF_8));
        digest.update((byte)0);
        digest.update(metadataBytes(assets,"paint-variants.json").getBytes(StandardCharsets.UTF_8));
        return hex(digest.digest());
    }
    private static String metadataBytes(AssetManager assets,String file) throws IOException {
        try(InputStream in=assets.open(DIRECTORY+file)) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] chunk=new byte[1024];int n;
            while((n=in.read(chunk))!=-1) {
                if(bytes.size()+n>16384)throw new IOException("Model metadata exceeds size limit");
                bytes.write(chunk,0,n);
            }
            return bytes.toString("UTF-8");
        }
    }
    static Parsed readBinary(InputStream input,String expectedHash,String expectedSource) throws IOException {
        if(!hash(expectedHash)||!hash(expectedSource))throw new IOException("Invalid mesh hash");
        MessageDigest digest=sha256();
        DataInputStream in=new DataInputStream(new DigestInputStream(new BufferedInputStream(input,65536),digest));
        byte[] header=new byte[56];in.readFully(header);ByteBuffer h=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic=new byte[8];h.get(magic);
        if(!"MG4MSH01".equals(new String(magic,StandardCharsets.US_ASCII))||h.getInt()!=1||h.getInt()!=8)
            throw new IOException("Unsupported binary mesh header");
        int count=h.getInt(),groups=h.getInt();byte[] source=new byte[32];h.get(source);
        if(count<=0||count>300000||count%3!=0||groups!=5||!hex(source).equals(expectedSource))
            throw new IOException("Invalid binary mesh counts/source");
        ArrayList<Part> parts=new ArrayList<>(5);int next=0;
        for(int group=0;group<5;group++) {
            byte[] record=new byte[36];in.readFully(record);ByteBuffer r=ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN);
            byte[] name=new byte[16];r.get(name);String expected=PART_NAMES[group];
            for(int i=0;i<16;i++)if(name[i]!=(i<expected.length()?(byte)expected.charAt(i):0))
                throw new IOException("Invalid binary mesh group");
            int first=r.getInt(),length=r.getInt();float[] pivot={r.getFloat(),r.getFloat(),r.getFloat()};
            if(first!=next||length<=0||length%3!=0||length>count-next)throw new IOException("Invalid binary group range");
            for(float value:pivot)if(!Float.isFinite(value)||Math.abs(value)>10000)throw new IOException("Invalid binary pivot");
            if(group==0&&(pivot[0]!=0||pivot[1]!=0||pivot[2]!=0))throw new IOException("Body pivot must be zero");
            parts.add(new Part(expected,first,length,pivot));next+=length;
        }
        if(next!=count)throw new IOException("Incomplete binary mesh groups");
        ByteBuffer data=ByteBuffer.allocateDirect(count*32).order(ByteOrder.LITTLE_ENDIAN);
        byte[] chunk=new byte[65536];
        while(data.hasRemaining()) {int n=in.read(chunk,0,Math.min(chunk.length,data.remaining()));if(n<0)throw new EOFException("Truncated binary mesh");data.put(chunk,0,n);}
        if(in.read()!=-1)throw new IOException("Trailing binary mesh data");
        if(!hex(digest.digest()).equals(expectedHash))throw new IOException("Binary mesh checksum mismatch");
        data.flip();FloatBuffer vertices=data.asFloatBuffer().asReadOnlyBuffer();
        for(int i=0;i<vertices.limit();i+=8) {
            for(int axis=0;axis<8;axis++)if(!Float.isFinite(vertices.get(i+axis)))throw new IOException("Non-finite binary vertex");
            for(int axis=0;axis<3;axis++)if(Math.abs(vertices.get(i+axis))>10000)throw new IOException("Binary vertex outside bounds");
            for(int axis=6;axis<8;axis++)if(vertices.get(i+axis)<0||vertices.get(i+axis)>1)throw new IOException("Binary UV outside atlas");
        }
        Parsed parsed=new Parsed(vertices,parts);validateGeometry(parsed);return parsed;
    }
    private static boolean hash(String value) {return value!=null&&value.matches("[0-9a-f]{64}");}
    private static MessageDigest sha256() {
        try{return MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private static String hex(byte[] bytes) {
        char[] text=new char[bytes.length*2],digits="0123456789abcdef".toCharArray();
        for(int i=0;i<bytes.length;i++){text[i*2]=digits[(bytes[i]&255)>>>4];text[i*2+1]=digits[bytes[i]&15];}
        return new String(text);
    }
    private static void validateGeometry(Parsed parsed) throws IOException {
        FloatBuffer data=parsed.vertices;
        float[] lo={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY},hi={Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        for(int i=0;i<data.limit();i+=8)for(int axis=0;axis<3;axis++){float value=data.get(i+axis);lo[axis]=Math.min(lo[axis],value);hi[axis]=Math.max(hi[axis],value);}
        if(hi[2]>1||lo[2]<-2500||hi[1]-lo[1]<4000||hi[1]-lo[1]>4500||hi[0]-lo[0]<1500||hi[0]-lo[0]>2800)
            throw new IOException("Invalid model footprint or world orientation");
        if(parsed.parts.size()!=5)throw new IOException("Model needs body and four wheel groups");
        for(Part part:parsed.parts)if(!part.name.equals("body")) {
            if((part.name.endsWith("left")?part.pivotX>=0:part.pivotX<=0)
                    ||(part.name.startsWith("front")?part.pivotY>=0:part.pivotY<=0)||part.pivotZ>=0)
                throw new IOException("Invalid wheel pivot orientation");
            for(int i=part.firstVertex*8;i<(part.firstVertex+part.vertexCount)*8;i+=8) {
                double dx=data.get(i)-part.pivotX,dy=data.get(i+1)-part.pivotY,dz=data.get(i+2)-part.pivotZ;
                if(dx*dx+dy*dy+dz*dz>650*650)throw new IOException("Wheel geometry outside pivot bounds");
            }
        }
    }
    private static VehicleModel loadPaints(AssetManager assets,Parsed parsed,String source) throws IOException {
        Map<BirdseyeCarColor,Paint> paints=new EnumMap<>(BirdseyeCarColor.class);
        try {
            JSONObject metadata=new JSONObject(metadataBytes(assets,"paint-variants.json"));
            if(!source.equals(metadata.getString("mesh_sha256")))throw new IOException("Paints belong to a different model");
            JSONArray rect=metadata.getJSONArray("paint_rect_uv");
            if(rect.length()!=4)throw new IOException("Invalid paint UV rectangle");
            float[] bounds=new float[4];
            for(int i=0;i<4;i++)bounds[i]=unit(rect.getDouble(i));
            if(bounds[0]>=bounds[2]||bounds[1]>=bounds[3])throw new IOException("Empty paint UV rectangle");
            JSONObject variants=metadata.getJSONObject("variants");
            for(BirdseyeCarColor color:BirdseyeCarColor.values()) {
                JSONObject info=variants.getJSONObject(color.id);
                float metallic=unit(info.getDouble("metallic")),roughness=unit(info.getDouble("roughness"));
                String file=DIRECTORY+"paints/"+color.id+".png";
                BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;
                try(InputStream in=assets.open(file)) { BitmapFactory.decodeStream(in,null,o); }
                if(o.outWidth!=1024||o.outHeight!=1024)throw new IOException("Invalid model atlas dimensions: "+color.id);
                // Every face samples the center of a solid 64px palette cell. Decode at
                // 256px without changing those samples; all eight palettes use only 2 MiB.
                o.inJustDecodeBounds=false;o.inScaled=false;o.inSampleSize=4;o.inPreferredConfig=Bitmap.Config.ARGB_8888;
                Bitmap image;
                try(InputStream in=assets.open(file)) { image=BitmapFactory.decodeStream(in,null,o); }
                if(image==null)throw new IOException("Cannot decode model paint: "+color.id);
                if(image.getWidth()!=256||image.getHeight()!=256) {image.recycle();throw new IOException("Invalid decoded paint size");}
                paints.put(color,new Paint(image,metallic,roughness));
            }
            return new VehicleModel(parsed,paints,bounds);
        } catch(IOException|JSONException e) {
            for(Paint paint:paints.values())paint.atlas.recycle();
            throw new IOException("Cannot load prepared 3D paints",e);
        }
    }
    private static float unit(double value) throws IOException {
        if(!Double.isFinite(value)||value<0||value>1)throw new IOException("Invalid material parameter");
        return (float)value;
    }
    public static FloatBuffer parse(Reader source) throws IOException {
        return parseParts(source).vertices;
    }
    /** Offline/test reference only. App startup exclusively uses the prepared binary. */
    static Parsed parseParts(Reader source) throws IOException {
        ArrayList<float[]> positions=new ArrayList<>(),normals=new ArrayList<>(),uvs=new ArrayList<>();
        ArrayList<Part> parts=new ArrayList<>();Map<String,float[]> pivots=new HashMap<>();HashSet<String> seen=new HashSet<>();
        String group="body";int first=0;
        FloatBuffer out=ByteBuffer.allocateDirect(100000*3*8*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        BufferedReader reader=new BufferedReader(source);String line;int chars=0,faces=0;
        while((line=reader.readLine())!=null) {
            chars+=line.length();if(chars>24*1024*1024||line.length()>4096)throw new IOException("Model exceeds size limit");
            String[] t=line.trim().split("\\s+");
            try {
                if(t[0].equals("#")&&t.length>1&&t[1].equals("pivot")) {
                    if(t.length!=6||!groupName(t[2])||t[2].equals("body")||pivots.containsKey(t[2]))throw new IOException("Invalid wheel pivot");
                    float[] p=vector(new String[]{"pivot",t[3],t[4],t[5]},3);
                    for(float v:p)if(Math.abs(v)>10000)throw new IOException("Pivot outside vehicle bounds");
                    pivots.put(t[2],p);
                }
                else if(t[0].equals("g")) {
                    if(t.length!=2||!groupName(t[1]))throw new IOException("Unsupported model group");
                    if(out.position()/8>first)addPart(parts,seen,pivots,group,first,out.position()/8-first);
                    group=t[1];first=out.position()/8;
                }
                else if(t[0].equals("v")) { positions.add(vector(t,3));if(positions.size()>300000)throw new IOException("Too many positions"); }
                else if(t[0].equals("vn")) { normals.add(vector(t,3));if(normals.size()>300000)throw new IOException("Too many normals"); }
                else if(t[0].equals("vt")) { uvs.add(vector(t,2));if(uvs.size()>300000)throw new IOException("Too many UVs"); }
                else if(t[0].equals("f")) {
                    if(t.length!=4||++faces>100000)throw new IOException("Model must contain at most 100000 triangles");
                    for(int i=1;i<=3;i++) {
                        String[] ids=t[i].split("/");if(ids.length!=3)throw new IOException("Missing triangle attributes");
                        float[] p=positions.get(Integer.parseInt(ids[0])-1),uv=uvs.get(Integer.parseInt(ids[1])-1),n=normals.get(Integer.parseInt(ids[2])-1);
                        for(float v:p)if(Math.abs(v)>10000)throw new IOException("Model outside vehicle bounds");
                        out.put(p).put(n).put(uv);
                    }
                }
            } catch(IndexOutOfBoundsException|NumberFormatException e) { throw new IOException("Invalid OBJ indices",e); }
        }
        if(faces==0)throw new IOException("Empty model");
        addPart(parts,seen,pivots,group,first,out.position()/8-first);
        out.flip();return new Parsed(out.asReadOnlyBuffer(),parts);
    }
    private static boolean groupName(String n){return n.equals("body")||n.equals("front_left")||n.equals("front_right")||n.equals("rear_left")||n.equals("rear_right");}
    private static void addPart(List<Part> parts,HashSet<String> seen,Map<String,float[]> pivots,String name,int first,int count) throws IOException {
        if(count<=0||!seen.add(name)||parts.size()>=5)throw new IOException("Invalid model part range");
        float[] p=name.equals("body")?new float[3]:pivots.get(name);
        if(p==null)throw new IOException("Missing wheel pivot");
        parts.add(new Part(name,first,count,p));
    }
    private static float[] vector(String[] t,int count) throws IOException {
        if(t.length!=count+1)throw new IOException("Invalid OBJ attribute");
        float[] v=new float[count];for(int i=0;i<count;i++) {v[i]=Float.parseFloat(t[i+1]);if(!Float.isFinite(v[i]))throw new IOException("Non-finite OBJ attribute");}return v;
    }
}
