import com.drivehub.kamera.birdseye.BirdseyeCalibration;
import com.drivehub.kamera.birdseye.SideGroundPatch;
import com.drivehub.kamera.birdseye.SideLensPatch;
import com.drivehub.kamera.birdseye.WheelSillPatch;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;

/** Local offline comparison; compile with the two Android-independent geometry classes. */
public final class CurbsidePreview {
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("Usage: CurbsidePreview <snapshot-folder> <new-output-folder>");
        Path source=Path.of(args[0]),output=Path.of(args[1]);
        if(Files.exists(output))throw new IllegalArgumentException("Output folder already exists");
        BirdseyeCalibration calibration=BirdseyeCalibration.load(source.resolve("camera_calibdata.bin"));
        Files.createDirectories(output);
        for(int camera=0;camera<2;camera++){
            String name=camera==0?"left":"right";BufferedImage input=ImageIO.read(source.resolve(name+".png").toFile());
            if(input==null||input.getWidth()!=720||input.getHeight()!=480)throw new IllegalArgumentException("Expected full 720x480 PNG");
            for(int region=0;region<3;region++){
                int width=254,height=region==0?456:224;
                SideGroundPatch.Map map=new SideGroundPatch.Map(calibration,camera,SideGroundPatch.defaults(calibration,camera,region),width,height);
                int[] rendered=map.render(input.getRGB(0,0,720,480,null,0,720));
                BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,width,height,rendered,0,width);
                ImageIO.write(image,"png",output.resolve(name+"-"+new String[]{"side","front-wheel-ground","rear-wheel-ground"}[region]+".png").toFile());
                System.out.println(name+" region "+region+": "+map.covered+"/"+(width*height)+" covered ground pixels");
                if(region!=0){
                    WheelSillPatch.Map advanced=new WheelSillPatch.Map(calibration,camera,region==1,width,height);
                    BufferedImage aligned=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
                    aligned.setRGB(0,0,width,height,advanced.render(input.getRGB(0,0,720,480,null,0,720)),0,width);
                    ImageIO.write(aligned,"png",output.resolve(name+(region==1?"-front-advanced.png":"-rear-advanced.png")).toFile());
                    System.out.println(name+" region "+region+": "+advanced.covered+"/"+(width*height)+" covered advanced pixels");
                }
                int lensHeight=region==0?456:224;
                double[] corners=region==0?SideLensPatch.sideDefaults(calibration,camera):SideLensPatch.defaults(calibration,camera,region==1);
                SideLensPatch.Map lens=new SideLensPatch.Map(calibration,camera,corners,254,lensHeight,region==0);
                BufferedImage corrected=new BufferedImage(254,lensHeight,BufferedImage.TYPE_INT_RGB);
                corrected.setRGB(0,0,254,lensHeight,lens.render(input.getRGB(0,0,720,480,null,0,720)),0,254);
                ImageIO.write(corrected,"png",output.resolve(name+new String[]{"-side-lens.png","-front-lens.png","-rear-lens.png"}[region]).toFile());
            }
        }
    }
}
