import dev.epoch.bridge.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class AppearanceTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("epoch-appearance-test-");
        System.setProperty("epoch.projectRoot", root.toString());
        System.setProperty("epoch.instanceId", "source-a");
        Snapshot snapshot = new Snapshot(); snapshot.version = "1.20.1";
        snapshot.sourceInstance = "source-a"; snapshot.sizeX = snapshot.sizeZ = 16;
        snapshot.palette.add("{Name:\"minecraft:cherry_planks\"}"); snapshot.runs = new int[]{0, 65536};
        snapshot.appearanceSources.put("0", "original-b"); snapshot.appearanceSources.put("1", "");
        snapshot.schema = 2; snapshot.extras = new WorldExtras();
        snapshot.extras.player = "{}"; snapshot.extras.weather = new WorldExtras.Weather();
        String[] biomes = new String[16384]; Arrays.fill(biomes, "minecraft:plains");
        snapshot.extras.biomes = WorldExtras.Biomes.of(biomes);
        Path file = root.resolve("roundtrip.gz"); snapshot.write(file);
        System.setProperty("epoch.instanceId", "intermediate-c");
        Snapshot roundtrip = Snapshot.read(file);
        check(roundtrip.appearanceSource(0).equals("original-b"), "preserved block must retain original instance");
        check(roundtrip.appearanceSource(1).isEmpty(), "old unknown must not inherit the intermediate instance");
        check(roundtrip.appearanceSource(2).equals("source-a"), "native block must refer to capturing instance");
        ChunkSnapshot chunk = ChunkSnapshot.split(roundtrip).get(0);
        check(chunk.blocks().appearanceSource(0).equals("original-b"), "chunk split lost source");
        check(chunk.blocks().appearanceSource(1).isEmpty(), "chunk split rewrote missing source");
        check(chunk.blocks().appearanceSource(2).equals("source-a"), "chunk split lost default source");
        roundtrip.appearanceSources.put("65536", "bad");
        try { roundtrip.validate(); throw new AssertionError("out-of-bounds source accepted"); } catch (IOException expected) {}
        check(new Snapshot().sourceInstance.isEmpty(), "old JSON defaults must not impersonate reader");
        check(Appearance.textureFile("../escape", Appearance.key("x")) == null, "unsafe instance accepted");
        check(Appearance.textureFile("source-a", "../../escape") == null, "unsafe texture accepted");

        System.setProperty("epoch.instanceId", "source-a");
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(2,4,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream(); javax.imageio.ImageIO.write(image,"png",png);
        byte[] cropped = Appearance.firstFrame(png.toByteArray());
        check(javax.imageio.ImageIO.read(new ByteArrayInputStream(cropped)).getHeight() == 2, "animated texture first frame");
        offlineModel(root,cropped);
        Appearance.Mesh mesh = new Appearance.Mesh(); Appearance.Face face = new Appearance.Face();
        face.texture = Appearance.texture(cropped); face.ny = 1; mesh.faces.add(face);
        Appearance.validate(mesh);
        face.vertices[0] = Float.NaN;
        try { Appearance.validate(mesh); throw new AssertionError("NaN accepted"); } catch (IOException expected) {}
        face.vertices[0] = 0;
        String state = snapshot.palette.get(0); Appearance.request(Collections.singleton(state));
        int[] baked = {0}; Appearance.tick(value -> { baked[0]++; check(value.equals(state), "request altered state"); return mesh; });
        Appearance.request(Collections.singleton(state)); Appearance.tick(value -> { baked[0]++; return mesh; });
        check(baked[0] == 1, "source repeatedly baked same state");
        long deadline = System.currentTimeMillis() + 5000;
        Appearance.Mesh received;
        do { received = Appearance.get("source-a",state); if(received==null)Thread.sleep(25); } while(received==null && System.currentTimeMillis()<deadline);
        check(received != null && received.faces.get(0).texture.equals(face.texture), "source mesh cache did not roundtrip");
        check(Files.isRegularFile(Appearance.textureFile("source-a",face.texture)), "source texture missing");
        check(Appearance.get("other-instance",state) == null, "same state leaked across instances");
        boolean changed = false; float start = Appearance.glitch(0,.25);
        for(int i=1;i<100;i++) if(Appearance.glitch(i/100.0,.25)!=start)changed=true;
        check(changed,"glitch is static");
        System.out.println("Appearance provenance, chunk split, cache, validation and animation tests passed");
    }
    private static void offlineModel(Path root,byte[] png) throws Exception {
        Path game=root.resolve("profile"), version=game.resolve("profile.json");Files.createDirectories(game);
        Files.write(version,"{\"id\":\"profile\"}".getBytes("UTF-8"));
        try(java.util.zip.ZipOutputStream jar=new java.util.zip.ZipOutputStream(Files.newOutputStream(game.resolve("profile.jar")))) {
            entry(jar,"assets/test/blockstates/slab.json","{\"variants\":{\"half=bottom\":{\"model\":\"test:block/slab\"}}}".getBytes("UTF-8"));
            entry(jar,"assets/test/models/block/slab.json","{\"parent\":\"test:block/base\",\"textures\":{\"all\":\"test:block/surface\"}}".getBytes("UTF-8"));
            entry(jar,"assets/test/models/block/base.json","{\"textures\":{\"side\":\"#all\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,8,16],\"faces\":{\"south\":{\"texture\":\"#side\"},\"up\":{\"texture\":\"#all\"}}}]}".getBytes("UTF-8"));
            // The enabled source resource pack, rather than this deliberately invalid base texture, must win.
            entry(jar,"assets/test/textures/block/surface.png",new byte[]{1,2,3});
        }
        Path texture=game.resolve("resourcepacks/source-pack/assets/test/textures/block/surface.png");Files.createDirectories(texture.getParent());Files.write(texture,png);
        Files.write(game.resolve("options.txt"),"resourcePacks:[\"vanilla\",\"file/source-pack\"]\n".getBytes("UTF-8"));
        com.google.gson.JsonObject catalog=new com.google.gson.JsonObject(),profile=new com.google.gson.JsonObject();
        profile.addProperty("id","offline-source");profile.addProperty("version","1.20.1");profile.addProperty("gameDir",game.toString());profile.addProperty("versionJson",version.toString());
        com.google.gson.JsonArray instances=new com.google.gson.JsonArray();instances.add(profile);catalog.add("instances",instances);
        Path path=root.resolve(".minecraft/epoch/catalog.json");Files.createDirectories(path.getParent());Files.write(path,catalog.toString().getBytes("UTF-8"));
        check(SourceAppearance.uniqueInstance("1.20.1").equals("offline-source"),"unique legacy source lookup");
        Appearance.Mesh mesh=SourceAppearance.load("offline-source","{Name:\"test:slab\",Properties:{half:\"bottom\"}}");
        Appearance.validate(mesh);check(mesh.faces.size()==2,"source model faces/parent inheritance lost");
        for(Appearance.Face face:mesh.faces)for(int i=0;i<4;i++)check(face.vertices[i*5+1]<=.5f,"source slab was replaced with full cube");
        check(!mesh.occludes,"half slab must not hide its neighbor's complete face");
        String state="{Name:\"test:slab\",Properties:{half:\"bottom\"}}";
        Appearance.Mesh cached=null;long deadline=System.currentTimeMillis()+5000;
        while(cached==null&&System.currentTimeMillis()<deadline){cached=Appearance.get("offline-source",state);Thread.sleep(20);}
        check(cached!=null,"offline cache did not load");
        mesh.faces.get(0).color=0x123456;
        Path nativeFile=root.resolve(".minecraft/epoch/appearances/offline-source/"+Appearance.key(state)+".json");
        Files.write(nativeFile,new com.google.gson.Gson().toJson(mesh).getBytes("UTF-8"));
        deadline=System.currentTimeMillis()+5000;
        while(cached.faces.get(0).color!=0x123456&&System.currentTimeMillis()<deadline){Thread.sleep(20);cached=Appearance.get("offline-source",state);}
        check(cached.faces.get(0).color==0x123456,"native source export did not replace offline approximation");
        check(SourceAppearance.load("unrelated","{Name:\"test:slab\"}")==null,"offline lookup ignored instance");
        instances.add(profile);Files.write(path,catalog.toString().getBytes("UTF-8"));
        check(SourceAppearance.uniqueInstance("1.20.1").isEmpty(),"ambiguous legacy source silently selected");
    }
    private static void entry(java.util.zip.ZipOutputStream jar,String name,byte[] data)throws Exception {
        jar.putNextEntry(new java.util.zip.ZipEntry(name));jar.write(data);jar.closeEntry();
    }
}
