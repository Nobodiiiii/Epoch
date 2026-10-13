package dev.epoch.bridge;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Offline fallback for ordinary JSON models in the exact source profile. No mod code is loaded. */
public final class SourceAppearance implements AutoCloseable {
    private final List<Path> packs = new ArrayList<Path>();
    private final Map<Path, ZipFile> archives = new HashMap<Path, ZipFile>();
    private final Map<String, JsonObject> models = new HashMap<String, JsonObject>();
    private final Map<String, String> textures = new HashMap<String, String>();
    private final String instance;
    private boolean opaque = true;
    private SourceAppearance(String instance, Path game, Path version) throws IOException {
        this.instance = instance;
        // Later enabled packs win, matching Minecraft's selected pack order.
        Path options = game.resolve("options.txt");
        if (Files.isRegularFile(options)) for (String line : Files.readAllLines(options, StandardCharsets.UTF_8)) {
            if (!line.startsWith("resourcePacks:")) continue;
            try {
                JsonArray selected = new JsonParser().parse(line.substring(14)).getAsJsonArray();
                for (int n=selected.size()-1;n>=0;n--) {
                    String name=selected.get(n).getAsString(); if(name.startsWith("file/"))name=name.substring(5);
                    Path base=game.resolve("resourcepacks").toAbsolutePath().normalize(), path=base.resolve(name).normalize();
                    if(path.startsWith(base)&&Files.exists(path))packs.add(path);
                }
            } catch (RuntimeException malformed) { throw new IOException("Invalid source resource pack list", malformed); }
        }
        Path mods = game.resolve("mods");
        if (Files.isDirectory(mods)) try (DirectoryStream<Path> files=Files.newDirectoryStream(mods,"*.jar")) {
            List<Path> sorted=new ArrayList<Path>();for(Path file:files)sorted.add(file);Collections.sort(sorted);packs.addAll(sorted);
        }
        JsonObject profile=jsonFile(version);
        String jar=profile.has("jar")?profile.get("jar").getAsString():profile.get("id").getAsString();
        if (!jar.matches("[A-Za-z0-9_. -]+")) throw new IOException("Invalid client jar id");
        Path local=version.getParent().resolve(jar+".jar");
        if(Files.isRegularFile(local))packs.add(local);
        else {
            Path sibling=version.getParent().getParent().resolve(jar).resolve(jar+".jar");
            if(Files.isRegularFile(sibling))packs.add(sibling);
        }
    }
    private static JsonObject jsonFile(Path path) throws IOException {
        try(Reader reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)){return new JsonParser().parse(reader).getAsJsonObject();}
    }
    private static JsonArray catalog() throws IOException {
        String root=System.getProperty("epoch.projectRoot");if(root==null)throw new IOException("No project root");
        return jsonFile(Paths.get(root,".minecraft","epoch","catalog.json")).getAsJsonArray("instances");
    }
    public static String uniqueInstance(String version) {
        try { String match="";
            for(JsonElement item:catalog()){JsonObject profile=item.getAsJsonObject();
                if(version.equals(profile.get("version").getAsString())){if(!match.isEmpty())return "";match=profile.get("id").getAsString();}}
            return match;
        } catch(Exception ignored){return "";}
    }
    public static Appearance.Mesh load(String instance,String state) throws IOException {
        try {
            for(JsonElement item:catalog()) {
                JsonObject profile=item.getAsJsonObject();
                if(!instance.equals(profile.get("id").getAsString()))continue;
                try(SourceAppearance source=new SourceAppearance(instance,Paths.get(profile.get("gameDir").getAsString()),Paths.get(profile.get("versionJson").getAsString()))) {
                    return source.block(state);
                }
            }
        } catch (RuntimeException invalid) { throw new IOException("Unsupported source model",invalid); }
        return null;
    }
    private byte[] resource(String path) throws IOException {
        if(!path.matches("assets/[a-z0-9_.-]+/[a-zA-Z0-9_./-]+")||path.contains(".."))throw new IOException("Invalid asset path");
        for(Path pack:packs) {
            if(Files.isDirectory(pack)) {Path file=pack.resolve(path);if(Files.isRegularFile(file))return Appearance.bytes(Files.newInputStream(file));}
            else {
                ZipFile zip=archives.get(pack);if(zip==null){zip=new ZipFile(pack.toFile());archives.put(pack,zip);}
                ZipEntry entry=zip.getEntry(path);if(entry!=null)return Appearance.bytes(zip.getInputStream(entry));
            }
        }
        throw new FileNotFoundException(path);
    }
    private static String id(String value) throws IOException {
        String result=value.indexOf(':')<0?"minecraft:"+value:value;
        if(!result.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||result.contains(".."))throw new IOException("Invalid resource id");return result;
    }
    private JsonObject json(String kind,String name) throws IOException {
        String[] parts=id(name).split(":",2);
        return new JsonParser().parse(new String(resource("assets/"+parts[0]+"/"+kind+"/"+parts[1]+".json"),StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private JsonObject model(String name,Set<String> chain) throws IOException {
        name=id(name);JsonObject cached=models.get(name);if(cached!=null)return cached;
        if(chain.size()>32||!chain.add(name))throw new IOException("Model parent cycle");
        JsonObject current=json("models",name),out=new JsonObject();
        if(current.has("loader"))throw new IOException("Custom model loader needs native source");
        if(current.has("parent")) {
            String parent=current.get("parent").getAsString();
            if(parent.contains("builtin/"))throw new IOException("Builtin model needs native source");
            JsonObject base=model(parent,chain);for(Map.Entry<String,JsonElement> e:base.entrySet())out.add(e.getKey(),e.getValue());
        }
        JsonObject merged=new JsonObject();if(out.has("textures"))for(Map.Entry<String,JsonElement> e:out.getAsJsonObject("textures").entrySet())merged.add(e.getKey(),e.getValue());
        if(current.has("textures"))for(Map.Entry<String,JsonElement> e:current.getAsJsonObject("textures").entrySet())merged.add(e.getKey(),e.getValue());
        for(Map.Entry<String,JsonElement> e:current.entrySet())if(!"textures".equals(e.getKey()))out.add(e.getKey(),e.getValue());
        out.add("textures",merged);chain.remove(name);models.put(name,out);return out;
    }
    private Appearance.Mesh block(String state) throws IOException {
        Matcher name=Pattern.compile("(?:\\\"Name\\\"|Name)\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(state);
        if(!name.find())return null;
        Map<String,String> properties=new HashMap<String,String>();
        Matcher group=Pattern.compile("(?:\\\"Properties\\\"|Properties)\\s*:\\s*\\{([^}]*)\\}").matcher(state);
        if(group.find()) {
            Matcher pair=Pattern.compile("[\\\"]?([a-zA-Z0-9_]+)[\\\"]?\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(group.group(1));
            while(pair.find())properties.put(pair.group(1),pair.group(2));
        }
        JsonObject block=json("blockstates",name.group(1));Appearance.Mesh mesh=new Appearance.Mesh();
        if(block.has("variants"))for(Map.Entry<String,JsonElement> variant:block.getAsJsonObject("variants").entrySet()) {
            boolean matches=true;
            if(!variant.getKey().isEmpty())for(String entry:variant.getKey().split(",")){String[] pair=entry.split("=",2);if(pair.length!=2||!pair[1].equals(properties.get(pair[0])))matches=false;}
            if(matches){apply(mesh,variant.getValue());break;}
        }
        if(block.has("multipart"))for(JsonElement element:block.getAsJsonArray("multipart")) {
            JsonObject part=element.getAsJsonObject();if(!part.has("when")||matches(part.getAsJsonObject("when"),properties))apply(mesh,part.get("apply"));
        }
        int covered=0;
        for(Appearance.Face face:mesh.faces) {
            int side=Appearance.boundary(face),corners=0;if(side<0)continue;
            for(int i=0;i<4;i++) {
                int corner=0;boolean valid=true;
                for(int axis=0;axis<3;axis++){float coordinate=face.vertices[i*5+axis];if(coordinate==1)corner|=1<<axis;else if(coordinate!=0)valid=false;}
                if(valid)corners|=1<<corner;
            }
            if(Integer.bitCount(corners)==4)covered|=1<<side;
        }
        mesh.occludes=opaque&&covered==63;
        return mesh.faces.isEmpty()?null:mesh;
    }
    private static boolean matches(JsonObject condition,Map<String,String> properties) {
        for(Map.Entry<String,JsonElement> entry:condition.entrySet()) {
            if("OR".equals(entry.getKey())) {boolean any=false;for(JsonElement child:entry.getValue().getAsJsonArray())any|=matches(child.getAsJsonObject(),properties);if(!any)return false;}
            else if("AND".equals(entry.getKey())){for(JsonElement child:entry.getValue().getAsJsonArray())if(!matches(child.getAsJsonObject(),properties))return false;}
            else if(!Arrays.asList(entry.getValue().getAsString().split("\\|")).contains(properties.get(entry.getKey())))return false;
        }return true;
    }
    private void apply(Appearance.Mesh mesh,JsonElement value) throws IOException {
        if(value.isJsonArray()){JsonArray choices=value.getAsJsonArray();if(choices.size()==0)return;value=choices.get(0);}
        JsonObject variant=value.getAsJsonObject();int rx=variant.has("x")?variant.get("x").getAsInt():0,ry=variant.has("y")?variant.get("y").getAsInt():0;
        if(variant.has("uvlock")&&variant.get("uvlock").getAsBoolean()&&(rx!=0||ry!=0))throw new IOException("UV-locked rotation needs native source");
        JsonObject model=model(variant.get("model").getAsString(),new HashSet<String>());
        if(!model.has("elements"))throw new IOException("Model has no portable elements");
        for(JsonElement item:model.getAsJsonArray("elements")) {
            JsonObject element=item.getAsJsonObject();float[] from=vector(element.getAsJsonArray("from")),to=vector(element.getAsJsonArray("to"));
            for(Map.Entry<String,JsonElement> entry:element.getAsJsonObject("faces").entrySet()) {
                JsonObject definition=entry.getValue().getAsJsonObject();Appearance.Face face=face(entry.getKey(),from,to);
                String texture=definition.get("texture").getAsString();Set<String> visited=new HashSet<String>();
                while(texture.startsWith("#")){if(!visited.add(texture)||visited.size()>32)throw new IOException("Texture alias cycle");JsonElement ref=model.getAsJsonObject("textures").get(texture.substring(1));if(ref==null)throw new IOException("Missing texture alias");texture=ref.getAsString();}
                texture=id(texture);String hash=textures.get(texture);
                if(hash==null){String[] parts=texture.split(":",2);byte[] png=Appearance.firstFrame(resource("assets/"+parts[0]+"/textures/"+parts[1]+".png"));
                    if(opaque){java.awt.image.BufferedImage pixels=javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
                        for(int y=0;y<pixels.getHeight()&&opaque;y++)for(int x=0;x<pixels.getWidth();x++)if((pixels.getRGB(x,y)>>>24)!=255){opaque=false;break;}}
                    hash=Appearance.texture(instance,png);textures.put(texture,hash);}face.texture=hash;
                float[] uv=definition.has("uv")?vector(definition.getAsJsonArray("uv")):defaultUv(entry.getKey(),from,to);
                int turn=definition.has("rotation")?definition.get("rotation").getAsInt()/90:0;
                float[][] coords={{uv[0],uv[3]},{uv[2],uv[3]},{uv[2],uv[1]},{uv[0],uv[1]}};
                for(int i=0;i<4;i++){face.vertices[i*5+3]=coords[(i+turn)%4][0]/16;face.vertices[i*5+4]=coords[(i+turn)%4][1]/16;}
                if(element.has("rotation")) {
                    JsonObject rotation=element.getAsJsonObject("rotation");float[] origin=vector(rotation.getAsJsonArray("origin"));
                    float angle=rotation.get("angle").getAsFloat();String axis=rotation.get("axis").getAsString();
                    transform(face,axis,angle,origin,rotation.has("rescale")&&rotation.get("rescale").getAsBoolean());
                }
                transform(face,"x",-rx,new float[]{8,8,8},false);transform(face,"y",-ry,new float[]{8,8,8},false);
                mesh.faces.add(face);if(mesh.faces.size()>4096)throw new IOException("Oversized model");
            }
        }
    }
    private static float[] vector(JsonArray values){float[] out=new float[values.size()];for(int n=0;n<out.length;n++)out[n]=values.get(n).getAsFloat();return out;}
    private static float[] defaultUv(String side,float[] a,float[] b) {
        switch(side){case "down":return new float[]{a[0],16-b[2],b[0],16-a[2]};case "up":return new float[]{a[0],a[2],b[0],b[2]};case "north":return new float[]{16-b[0],16-b[1],16-a[0],16-a[1]};case "south":return new float[]{a[0],16-b[1],b[0],16-a[1]};case "west":return new float[]{a[2],16-b[1],b[2],16-a[1]};default:return new float[]{16-b[2],16-b[1],16-a[2],16-a[1]};}
    }
    private static Appearance.Face face(String side,float[] a,float[] b) throws IOException {
        float x=a[0]/16,y=a[1]/16,z=a[2]/16,X=b[0]/16,Y=b[1]/16,Z=b[2]/16;float[] points;Appearance.Face f=new Appearance.Face();
        switch(side){case "west":points=new float[]{x,y,z,x,y,Z,x,Y,Z,x,Y,z};f.nx=-1;break;case "east":points=new float[]{X,y,Z,X,y,z,X,Y,z,X,Y,Z};f.nx=1;break;case "down":points=new float[]{x,y,Z,x,y,z,X,y,z,X,y,Z};f.ny=-1;break;case "up":points=new float[]{x,Y,z,x,Y,Z,X,Y,Z,X,Y,z};f.ny=1;break;case "north":points=new float[]{X,y,z,x,y,z,x,Y,z,X,Y,z};f.nz=-1;break;case "south":points=new float[]{x,y,Z,X,y,Z,X,Y,Z,x,Y,Z};f.nz=1;break;default:throw new IOException("Unknown model face");}
        for(int i=0;i<4;i++)System.arraycopy(points,i*3,f.vertices,i*5,3);return f;
    }
    private static void transform(Appearance.Face face,String axis,float degrees,float[] origin,boolean rescale) throws IOException {
        int fixed="xyz".indexOf(axis);if(fixed<0)throw new IOException("Invalid rotation axis");if(degrees==0)return;
        int u=(fixed+1)%3,v=(fixed+2)%3;double radians=Math.toRadians(degrees),c=Math.cos(radians),s=Math.sin(radians),scale=rescale?1/Math.abs(c):1;
        for(int i=0;i<4;i++){float[] p=face.vertices;double a=p[i*5+u]-origin[u]/16,b=p[i*5+v]-origin[v]/16;p[i*5+u]=(float)((a*c-b*s)*scale+origin[u]/16);p[i*5+v]=(float)((a*s+b*c)*scale+origin[v]/16);}
        float[] n={face.nx,face.ny,face.nz};double a=n[u],b=n[v];n[u]=(float)(a*c-b*s);n[v]=(float)(a*s+b*c);face.nx=n[0];face.ny=n[1];face.nz=n[2];
    }
    @Override public void close() throws IOException {for(ZipFile zip:archives.values())zip.close();}
}
