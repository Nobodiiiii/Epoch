import dev.epoch.bridge.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import javax.imageio.ImageIO;

/** Deterministic work/allocation checks; does not claim to measure GPU FPS. */
public final class AppearancePerformanceTest {
    private static void check(boolean result,String message){if(!result)throw new AssertionError(message);}
    private static byte[] png(int... pixels)throws IOException {
        BufferedImage image=new BufferedImage(2,pixels.length/2,BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0,0,2,pixels.length/2,pixels,0,2);
        ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();
    }
    public static void main(String[] args)throws Exception {
        int[] source={0x00334455,0xff345678,0xff987654,0x00000000};
        AppearanceAnimation leaves=new AppearanceAnimation(png(source));
        check(!leaves.translucent,"leaf holes incorrectly require translucent sorting");
        check(new AppearanceAnimation(png(0xff123456,0xff123456,0xff123456,0xff123456)).translucent==false,"opaque material classified as translucent");
        check(new AppearanceAnimation(png(0x80123456,0,0xff112233,0)).translucent,"genuine partial alpha must stay translucent");
        int[] result=new int[4],first=null;int updates=0;boolean changed=false;
        for(int frame=0;frame<24;frame++) {
            // A forest of 10,000 leaf blocks referencing this material on six faces.
            for(int reference=0;reference<60000;reference++)if(leaves.update(result,frame,false))updates++;
            for(int n=0;n<4;n++)check((result[n]>>>24)==(source[n]>>>24),"animation filled a leaf hole");
            if(first==null)first=result.clone();else if(!Arrays.equals(first,result))changed=true;
        }
        check(updates==24,"animation work scaled with the number of blocks/faces");
        check(changed,"shared texture scanlines stopped animating");
        AppearanceAnimation rgba=new AppearanceAnimation(png(source)),abgr=new AppearanceAnimation(png(source));
        int[] a=new int[4],b=new int[4];rgba.update(a,5,false);abgr.update(b,5,true);
        for(int n=0;n<4;n++)check(b[n]==((a[n]&0xff00ff00)|((a[n]&255)<<16)|((a[n]>>>16)&255)),"native image channel order");

        Path root=Files.createTempDirectory("epoch-appearance-hot-cache-");
        System.setProperty("epoch.projectRoot",root.toString());Appearance.reload();
        String instance="source",state="{Name:\"minecraft:cherry_leaves\"}";
        Appearance.Mesh mesh=new Appearance.Mesh();Appearance.Face face=new Appearance.Face();face.texture=Appearance.key("texture");mesh.faces.add(face);
        Path file=root.resolve(".minecraft/epoch/appearances/source/"+Appearance.key(state)+".json");Files.createDirectories(file.getParent());
        Files.write(file,new com.google.gson.Gson().toJson(mesh).getBytes("UTF-8"));
        long deadline=System.currentTimeMillis()+5000;Appearance.Mesh loaded=null;
        while(loaded==null&&System.currentTimeMillis()<deadline){loaded=Appearance.get(instance,state);Thread.sleep(10);}
        check(loaded!=null,"mesh never loaded");
        for(int n=0;n<200000;n++)Appearance.get(instance,state);
        java.lang.management.ThreadMXBean bean=ManagementFactory.getThreadMXBean();
        long allocated=-1;
        if(bean instanceof com.sun.management.ThreadMXBean){
            com.sun.management.ThreadMXBean allocation=(com.sun.management.ThreadMXBean)bean;
            if(allocation.isThreadAllocatedMemorySupported()){
                allocation.setThreadAllocatedMemoryEnabled(true);
                long thread=Thread.currentThread().getId(),before=allocation.getThreadAllocatedBytes(thread);
                for(int n=0;n<200000;n++)if(Appearance.get(instance,state)!=loaded)throw new AssertionError("cache changed stable mesh");
                allocated=allocation.getThreadAllocatedBytes(thread)-before;
                check(allocated<1000000,"hot cache allocated paths/regex/keys per lookup: "+allocated);
            }
        }
        System.out.println("PASS: 1,440,000 leaf-face references, "+updates+" animation updates; alpha preserved; 200,000 hot mesh lookups allocated "+allocated+" bytes");
    }
}
