import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/** Standalone offline geometry experiment; no Android or third-party binary dependency. */
public final class BirdseyePreview {
    private static final String[] NAMES = {"left", "right", "front", "rear"};
    private static final int[] COLORS = {0x52b8de, 0xf2b45f, 0x86ca8b, 0xc494e0};
    private static final int INPUT_W = 720, INPUT_H = 480;
    private static final double MAX_THETA = Math.toRadians(88);

    private static final class Camera {
        double fx, fy, cx, cy;
        double[] distortion, translation, center;
        double[][] rotation;
    }

    private static final class Calibration {
        final Camera[] cameras = new Camera[4];
        double centerX, centerY, header;
    }

    private static final class Projection {
        final double u, v, weight;
        Projection(double u, double v, double weight) {
            this.u = u; this.v = v; this.weight = weight;
        }
    }

    private static double[][] rotation(double[] vector) {
        double angle = Math.hypot(Math.hypot(vector[0], vector[1]), vector[2]);
        if (!Double.isFinite(angle)) throw new IllegalArgumentException("Rotation overflow");
        if (angle < 1e-12) return new double[][]{{1,0,0},{0,1,0},{0,0,1}};
        double x = vector[0]/angle, y = vector[1]/angle, z = vector[2]/angle;
        double c = Math.cos(angle), s = Math.sin(angle), q = 1-c;
        return new double[][]{
                {c+x*x*q, x*y*q-z*s, x*z*q+y*s},
                {x*y*q+z*s, c+y*y*q, y*z*q-x*s},
                {x*z*q-y*s, y*z*q+x*s, c+z*z*q}};
    }

    private static double dot(double[] a, double[] b) {
        return a[0]*b[0] + a[1]*b[1] + a[2]*b[2];
    }

    private static Calibration parse(byte[] bytes) {
        if (bytes.length != 520) throw new IllegalArgumentException("Expected 520 calibration bytes, got " + bytes.length);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        double[] values = new double[65];
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.getDouble();
            if (!Double.isFinite(values[i])) throw new IllegalArgumentException("Non-finite calibration field " + i);
        }
        Calibration result = new Calibration();
        result.header = values[0];
        for (int i = 0; i < 4; i++) {
            int start = 1 + i*16;
            Camera camera = new Camera();
            camera.fx = values[start]; camera.fy = values[start+1];
            camera.cx = values[start+2]; camera.cy = values[start+3];
            if (camera.fx <= 0 || camera.fy <= 0 || camera.cx < 0 || camera.cx >= INPUT_W
                    || camera.cy < 0 || camera.cy >= INPUT_H)
                throw new IllegalArgumentException("Implausible intrinsics for " + NAMES[i]);
            camera.distortion = Arrays.copyOfRange(values, start+6, start+10);
            camera.rotation = rotation(Arrays.copyOfRange(values, start+10, start+13));
            camera.translation = Arrays.copyOfRange(values, start+13, start+16);
            camera.center = new double[3];
            for (int axis = 0; axis < 3; axis++)
                for (int row = 0; row < 3; row++)
                    camera.center[axis] -= camera.rotation[row][axis]*camera.translation[row];
            for (double coordinate : camera.center)
                if (!Double.isFinite(coordinate)) throw new IllegalArgumentException("Invalid camera position for " + NAMES[i]);
            // The inverse ray calculation below assumes this lens polynomial is monotonic.
            double previous = 0;
            for (int sample = 1; sample <= 180; sample++) {
                double current = distortedRadius(camera, MAX_THETA*sample/180);
                if (!Double.isFinite(current) || current <= previous) throw new IllegalArgumentException("Non-monotonic lens polynomial for " + NAMES[i]);
                previous = current;
            }
            result.cameras[i] = camera;
        }
        result.centerX = result.cameras[0].center[0]/2 + result.cameras[1].center[0]/2;
        result.centerY = result.cameras[2].center[1]/2 + result.cameras[3].center[1]/2;
        return result;
    }

    private static double distortedRadius(Camera camera, double theta) {
        double t2 = theta*theta;
        double[] k = camera.distortion;
        return theta*(1+t2*(k[0]+t2*(k[1]+t2*(k[2]+t2*k[3]))));
    }

    private static Projection project(Camera camera, double x, double y) {
        double[] world = {x, y, 0};
        double[] ray = new double[3];
        for (int axis = 0; axis < 3; axis++) ray[axis] = dot(camera.rotation[axis], world)+camera.translation[axis];
        double radial = Math.hypot(ray[0], ray[1]);
        double theta = Math.atan2(radial, ray[2]);
        if (theta >= MAX_THETA) return null;
        double scale = radial < 1e-12 ? 0 : distortedRadius(camera, theta)/radial;
        double u = camera.cx+camera.fx*ray[0]*scale;
        double v = camera.cy+camera.fy*ray[1]*scale;
        double border = Math.min(Math.min(u, INPUT_W-1-u), Math.min(v, INPUT_H-1-v));
        if (border <= 0) return null;
        // Independent prototype weighting: prefer central lens rays and taper image borders.
        double weight = Math.pow(Math.cos(theta), 4)*Math.min(1, border/16);
        return new Projection(u, v, weight);
    }

    private static double[] groundPoint(Camera camera, double u, double v) {
        double nx = (u-camera.cx)/camera.fx, ny = (v-camera.cy)/camera.fy;
        double radius = Math.hypot(nx, ny);
        if (radius >= distortedRadius(camera, MAX_THETA)) return null;
        double lo = 0, hi = MAX_THETA;
        for (int step = 0; step < 45; step++) {
            double mid = (lo+hi)/2;
            if (distortedRadius(camera, mid) < radius) lo = mid; else hi = mid;
        }
        double theta = (lo+hi)/2;
        double scale = radius < 1e-12 ? 0 : Math.sin(theta)/radius;
        double[] local = {nx*scale, ny*scale, Math.cos(theta)}, direction = new double[3];
        for (int axis = 0; axis < 3; axis++)
            for (int row = 0; row < 3; row++) direction[axis] += camera.rotation[row][axis]*local[row];
        if (Math.abs(direction[2]) < 1e-12) return null;
        double distance = -camera.center[2]/direction[2];
        if (distance <= 0) return null;
        return new double[]{camera.center[0]+distance*direction[0], camera.center[1]+distance*direction[1]};
    }

    private static int groundColor(Calibration calibration, double worldX, double worldY) {
        double x = worldX-calibration.centerX, y = worldY-calibration.centerY;
        if (Math.hypot(x,y) > 12000) return 0x192029;
        int checker = (int)Math.floor(x/500)+(int)Math.floor(y/500);
        int color = (checker & 1) == 0 ? 0x526170 : 0x303d4c;
        if (Math.abs(x-Math.rint(x/1000)*1000) < 16 || Math.abs(y-Math.rint(y/1000)*1000) < 16)
            color = 0xd7e1e8;
        if (Math.hypot(x+1500,y+2800) < 180) color = 0xf2b45f;
        if (Math.hypot(x-1500,y-2800) < 180) color = 0x52b8de;
        return color;
    }

    private static BufferedImage syntheticCamera(Calibration calibration, Camera camera) {
        BufferedImage image = new BufferedImage(INPUT_W, INPUT_H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < INPUT_H; y++) for (int x = 0; x < INPUT_W; x++) {
            double[] point = groundPoint(camera, x, y);
            image.setRGB(x,y,point == null ? 0x192029 : groundColor(calibration,point[0],point[1]));
        }
        return image;
    }

    private static int sample(BufferedImage image, double u, double v) {
        int x = (int)u, y = (int)v;
        double dx = u-x, dy = v-y;
        int result = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            double a = ((image.getRGB(x,y) >> shift)&255)*(1-dx) + ((image.getRGB(x+1,y) >> shift)&255)*dx;
            double b = ((image.getRGB(x,y+1) >> shift)&255)*(1-dx) + ((image.getRGB(x+1,y+1) >> shift)&255)*dx;
            result |= (int)Math.round(a*(1-dy)+b*dy) << shift;
        }
        return result;
    }

    private static void annotate(Path path, BufferedImage image, String title, String footer,
                                 int carX, int carY, int carW, int carH) throws IOException {
        BufferedImage canvas = new BufferedImage(image.getWidth(), image.getHeight()+88, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x111923)); g.fillRect(0,0,canvas.getWidth(),canvas.getHeight());
        g.drawImage(image,0,56,null);
        g.setColor(new Color(0xe4edf5)); g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,16));
        g.drawString(title,16,24);
        g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,12));
        g.setColor(new Color(0xa8b8c8)); g.drawString("2D-Bodenprojektion · 4 Kameras · VORNE ↑",16,44);
        g.setColor(new Color(0x121c27)); g.fillRoundRect(carX,carY+56,carW,carH,Math.min(48,carW/2),48);
        g.setColor(new Color(0xa8b8c8)); g.setStroke(new BasicStroke(2));
        g.drawRoundRect(carX+5,carY+61,carW-10,carH-10,Math.min(40,carW/2),40);
        g.drawLine(carX+carW/4,carY+56+carH/5,carX+3*carW/4,carY+56+carH/5);
        g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,18));
        g.drawString("MG4",carX+carW/2-20,carY+56+carH/2);
        g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,11));
        g.drawString(footer,12,canvas.getHeight()-12);
        g.dispose();
        if (!ImageIO.write(canvas,"png",path.toFile())) throw new IOException("PNG writer unavailable");
    }

    private static void selfTest(Calibration calibration) {
        Camera analytic = new Camera();
        analytic.fx = analytic.fy = 200; analytic.cx = 360; analytic.cy = 240;
        analytic.distortion = new double[4]; analytic.rotation = rotation(new double[3]);
        analytic.translation = new double[]{0,0,1000}; analytic.center = new double[]{0,0,-1000};
        Projection center = project(analytic,0,0), fortyFive = project(analytic,1000,0);
        require(Math.abs(center.u-360)<1e-9 && Math.abs(center.v-240)<1e-9,"optical center");
        require(Math.abs(fortyFive.u-(360+50*Math.PI))<1e-9,"analytic equidistant 45-degree ray");
        int rays = 0;
        double maxError = 0;
        for (Camera camera : calibration.cameras) {
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
                require(Math.abs(dot(camera.rotation[i],camera.rotation[j])-(i==j?1:0))<1e-9,"orthonormal rotation");
            for (int v = 16; v < INPUT_H-16; v += 29) for (int u = 16; u < INPUT_W-16; u += 31) {
                double[] world = groundPoint(camera,u,v);
                if (world == null) continue;
                Projection projection = project(camera,world[0],world[1]);
                require(projection != null,"visible inverse ray");
                double error = Math.hypot(projection.u-u,projection.v-v);
                maxError = Math.max(maxError,error); rays++;
                require(error < 1e-6,"ray/project round trip");
            }
        }
        require(rays > 100,"sufficient ground rays");
        System.out.printf(Locale.ROOT,"Geometry checks passed: %d rays; max round-trip error %.3g pixels.%n",rays,maxError);
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new IllegalStateException("Geometry check failed: " + name);
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x",value&255));
        return result.toString();
    }

    public static void main(String[] args) throws Exception {
        Map<String,String> options = new HashMap<>();
        boolean synthetic = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--synthetic")) synthetic = true;
            else {
                if (!args[i].startsWith("--") || i+1 >= args.length) throw new IllegalArgumentException("Expected --option value");
                options.put(args[i],args[++i]);
            }
        }
        for (String key : options.keySet()) if (!Arrays.asList("--calibration","--output","--width","--height",
                "--margin-mm","--car-width-mm","--car-length-mm","--left","--right","--front","--rear").contains(key))
            throw new IllegalArgumentException("Unknown option " + key);
        if (!options.containsKey("--calibration") || !options.containsKey("--output"))
            throw new IllegalArgumentException("Use --calibration FILE --output DIR [--synthetic | --left FILE --right FILE --front FILE --rear FILE]");
        int width = Integer.parseInt(options.getOrDefault("--width","540"));
        int height = Integer.parseInt(options.getOrDefault("--height","800"));
        double margin = Double.parseDouble(options.getOrDefault("--margin-mm","1500"));
        double carWidth = Double.parseDouble(options.getOrDefault("--car-width-mm","1836"));
        double carLength = Double.parseDouble(options.getOrDefault("--car-length-mm","4287"));
        if (width < 320 || height < 320 || width > 2048 || height > 2048 || !Double.isFinite(margin)
                || !Double.isFinite(carWidth) || !Double.isFinite(carLength) || margin < 0 || margin > 20000
                || carWidth < 500 || carWidth > 5000 || carLength < 1000 || carLength > 10000)
            throw new IllegalArgumentException("Invalid output dimensions, margin or vehicle dimensions");
        Path input = Path.of(options.get("--calibration")), output = Path.of(options.get("--output"));
        byte[] bytes = Files.readAllBytes(input);
        Calibration calibration = parse(bytes);
        selfTest(calibration);
        BufferedImage[] images = new BufferedImage[4];
        for (int i = 0; i < 4; i++) {
            if (synthetic && options.containsKey("--"+NAMES[i])) throw new IllegalArgumentException("Do not combine synthetic and real inputs");
            if (!synthetic) {
                if (!options.containsKey("--"+NAMES[i])) throw new IllegalArgumentException("Missing camera --"+NAMES[i]);
                images[i] = ImageIO.read(Path.of(options.get("--"+NAMES[i])).toFile());
                if (images[i] == null || images[i].getWidth()!=INPUT_W || images[i].getHeight()!=INPUT_H)
                    throw new IllegalArgumentException("Expected an unmirrored 720x480 image for " + NAMES[i]);
            }
        }
        double mmPerPixel = (carLength+margin*2)/height;
        double viewWidth = width*mmPerPixel;
        if (viewWidth <= carWidth) throw new IllegalArgumentException("View is too narrow for the car; increase width or margin");
        Files.createDirectories(output);
        for (String name : Arrays.asList("topview.png","coverage.png","report.txt","left.png","right.png","front.png","rear.png"))
            if (Files.exists(output.resolve(name))) throw new IOException("Generated output already exists; choose another directory: " + name);
        if (synthetic) for (int i = 0; i < 4; i++) {
            images[i] = syntheticCamera(calibration,calibration.cameras[i]);
            ImageIO.write(images[i],"png",output.resolve(NAMES[i]+".png").toFile());
        }
        BufferedImage bird = new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        BufferedImage coverage = new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        long covered = 0, outside = 0;
        double absoluteError = 0;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            double localX = (x+.5-width/2.0)*mmPerPixel;
            double localY = (y+.5-height/2.0)*mmPerPixel;
            if (Math.abs(localX)<carWidth/2 && Math.abs(localY)<carLength/2) continue;
            outside++;
            double r = 0, g = 0, b = 0, weight = 0, dominant = 0;
            int owner = -1;
            for (int i = 0; i < 4; i++) {
                Projection p = project(calibration.cameras[i],calibration.centerX+localX,calibration.centerY+localY);
                if (p == null || p.weight < 1e-8) continue;
                int rgb = sample(images[i],p.u,p.v);
                r += ((rgb>>16)&255)*p.weight; g += ((rgb>>8)&255)*p.weight; b += (rgb&255)*p.weight;
                weight += p.weight;
                if (p.weight > dominant) { dominant = p.weight; owner = i; }
            }
            int rgb = 0x793650;
            if (weight > 0) {
                rgb = ((int)Math.round(r/weight)<<16) | ((int)Math.round(g/weight)<<8) | (int)Math.round(b/weight);
                covered++;
                if (synthetic) {
                    int expected = groundColor(calibration,calibration.centerX+localX,calibration.centerY+localY);
                    for (int shift = 0; shift <= 16; shift += 8)
                        absoluteError += Math.abs(((rgb>>shift)&255)-((expected>>shift)&255));
                }
            }
            bird.setRGB(x,y,rgb);
            coverage.setRGB(x,y,owner < 0 ? 0x793650 : COLORS[owner]);
        }
        int carX = (int)Math.round(width/2.0-carWidth/mmPerPixel/2), carY = (int)Math.round(height/2.0-carLength/mmPerPixel/2);
        int carW = (int)Math.round(carWidth/mmPerPixel), carH = (int)Math.round(carLength/mmPerPixel);
        annotate(output.resolve("topview.png"),bird,synthetic ? "Synthetischer Test · keine Fahrzeugaufnahme" : "Offline-Topview · Geometrie ungeprüft",
                String.format(Locale.ROOT,"Raster: 1 m · %.2f mm/Pixel · violett = ohne Bildabdeckung",mmPerPixel),carX,carY,carW,carH);
        annotate(output.resolve("coverage.png"),coverage,"Kalibrierungsbasierte Kamerabdeckung",
                "Links: blau · Rechts: orange · Vorne: grün · Hinten: lila",carX,carY,carW,carH);
        StringBuilder report = new StringBuilder();
        report.append("Calibration: ").append(input.toAbsolutePath()).append('\n');
        report.append("SHA-256: ").append(sha256(bytes)).append('\n');
        report.append("Input mode: ").append(synthetic ? "SYNTHETIC; mathematical consistency only" : "FOUR PROVIDED INPUT IMAGES; provenance and calibration match not established").append('\n');
        report.append("Header (uninterpreted): ").append(calibration.header).append('\n');
        report.append("Ground-plane coordinates are treated as millimetres; reference image: 720x480.\n");
        for (int i = 0; i < 4; i++) report.append(NAMES[i]).append(" camera center: ").append(Arrays.toString(calibration.cameras[i].center)).append('\n');
        report.append(String.format(Locale.ROOT,"Output: %dx%d; %.3f mm/pixel; geometric coverage outside car mask: %.3f%%%n",width,height,mmPerPixel,100.0*covered/outside));
        if (synthetic) report.append(String.format(Locale.ROOT,"Synthetic reconstruction mean absolute RGB error: %.3f / 255%n",absoluteError/Math.max(1,covered*3)));
        report.append("No scene reconstruction under the vehicle. No brightness matching, depth reconstruction or camera synchronization.\n");
        report.append("Synthetic round trips cannot validate vehicle calibration or real camera orientation.\n");
        Files.writeString(output.resolve("report.txt"),report.toString());
        System.out.print(report);
    }
}
