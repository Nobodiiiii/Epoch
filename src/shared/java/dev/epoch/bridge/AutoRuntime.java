package dev.epoch.bridge;

import com.google.gson.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Discovers the local Epoch runtime and attaches the already-running client. */
public final class AutoRuntime {
    public static final class Instance {
        public String id="",label="",version="",forgeVersion="",gameDir="",reason="";
        public boolean supported,current;
    }
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static Path project,control,seedFile;
    private static String instanceId,sessionId,message="正在检测本目录下的实例…";
    private static boolean attempted;
    private static long refreshedAt;
    private static volatile List<Instance> cached=Collections.emptyList();
    private AutoRuntime(){}

    public static boolean managed(){return Boolean.getBoolean("epoch.managed");}
    private static String activeSession(){return managed()?System.getProperty("epoch.sessionId", ""):sessionId;}
    private static boolean sameSession(JsonObject object){
        String expected=activeSession();
        return expected!=null&&!expected.isEmpty()&&object.has("sessionId")&&expected.equals(object.get("sessionId").getAsString());
    }
    private static JsonObject catalog()throws IOException{
        JsonObject value=read(project.resolve(".minecraft/epoch/catalog.json"));
        if(!sameSession(value))throw new IOException("实例目录属于另一会话；请等待当前窗口连接完成");
        return value;
    }
    private static boolean freshStatus(JsonObject status){
        if(!status.has("heartbeat"))return false;
        long age=System.currentTimeMillis()-status.get("heartbeat").getAsLong();
        return age>=-5000&&age<15000;
    }
    public static Path findProject(Path gameDir){
        String configured=System.getProperty("epoch.projectRoot");
        if(configured!=null&&!configured.isEmpty())return Paths.get(configured).toAbsolutePath().normalize();
        for(Path dir=gameDir.toAbsolutePath().normalize();dir!=null;dir=dir.getParent())
            if(Files.isRegularFile(dir.resolve("scripts/launch_epoch.py"))&&Files.isDirectory(dir.resolve("host")))return dir;
        throw new IllegalStateException("未找到 Epoch 运行组件。请将实例放在 Epoch 目录内。");
    }
    public static String idFor(Path gameDir,String version){
        try{
            String key=gameDir.toAbsolutePath().normalize().toString().replace('\\','/').toLowerCase(Locale.ROOT)+"|"+version;
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder suffix=new StringBuilder();for(int n=0;n<6;n++)suffix.append(String.format("%02x",hash[n]&255));
            return version.replace('.','-')+"-"+suffix;
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    public static synchronized Path controlDirectory(Path gameDir,String version){
        if(control!=null)return control;
        String configured=System.getProperty("epoch.controlDir");
        instanceId=System.getProperty("epoch.instanceId",idFor(gameDir,version));
        try{project=findProject(gameDir);}catch(Exception error){message=error.getMessage();}
        control=configured!=null?Paths.get(configured):project!=null?project.resolve(".minecraft/epoch/control/"+instanceId):gameDir.resolve("epoch/control/"+version);
        control=control.toAbsolutePath().normalize();
        System.setProperty("epoch.controlDir",control.toString());
        System.setProperty("epoch.instanceId",instanceId);
        if(project!=null)System.setProperty("epoch.projectRoot",project.toString());
        return control;
    }
    public static synchronized void tick(Path gameDir,String version,long hwnd,String worldName,String username,String uuid){
        controlDirectory(gameDir,version);
        if(project==null||managed()||attempted||hwnd==0||worldName==null||worldName.isEmpty())return;
        attempted=true;
        try{
            sessionId=UUID.randomUUID().toString();
            JsonObject seed=new JsonObject();seed.addProperty("sessionId",sessionId);seed.addProperty("instanceId",instanceId);
            seed.addProperty("version",version);seed.addProperty("gameDir",gameDir.toAbsolutePath().normalize().toString());
            seed.addProperty("controlDir",control.toString());seed.addProperty("worldName",worldName);seed.addProperty("windowHandle",hwnd);
            seed.addProperty("pid",Long.parseLong(ManagementFactory.getRuntimeMXBean().getName().split("@")[0]));
            seed.addProperty("username",username);seed.addProperty("uuid",uuid);seed.addProperty("projectRoot",project.toString());
            seed.addProperty("createdAt",System.currentTimeMillis());seed.addProperty("javaHome",System.getProperty("java.home"));
            seedFile=control.resolve("seed-"+sessionId+".json");write(seedFile,seed);
            launch("--adopt",seedFile.toString());message="正在检测实例并连接当前窗口…";
        }catch(Exception error){message="Epoch 启动失败："+error.getMessage();}
    }
    private static void launch(String...args)throws IOException{
        List<String> command=new ArrayList<String>();command.add(System.getProperty("epoch.python","python"));
        command.add(project.resolve("scripts/launch_epoch.py").toString());Collections.addAll(command,args);
        Files.createDirectories(control);
        new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(control.resolve("bootstrap.log").toFile())).start();
    }
    private static JsonObject read(Path file)throws IOException{
        return new JsonParser().parse(new String(Files.readAllBytes(file),StandardCharsets.UTF_8)).getAsJsonObject();
    }
    public static List<Instance> instances(Path gameDir){
        try{
            if(project==null)project=findProject(gameDir);
            JsonObject catalog=catalog();
            List<Instance> result=new ArrayList<Instance>();
            for(JsonElement element:catalog.getAsJsonArray("instances")){
                Instance item=GSON.fromJson(element,Instance.class);
                item.current=item.id.equals(System.getProperty("epoch.instanceId",instanceId==null?"":instanceId));
                result.add(item);
            }
            cached=Collections.unmodifiableList(result);
        }catch(Exception ignored){cached=Collections.emptyList();}
        return cached;
    }
    public static String statusText(Path gameDir){
        try{
            if(control!=null){
                JsonObject failure=read(control.resolve("bootstrap-error.json"));
                if(sameSession(failure)&&failure.has("error"))return "Epoch 启动失败："+failure.get("error").getAsString();
            }
        }catch(Exception ignored){}
        try{
            if(project==null)project=findProject(gameDir);
            JsonObject status=read(project.resolve(".minecraft/epoch/host-status.json"));
            if(sameSession(status)){
                if(!freshStatus(status))return "Epoch 连接已中断：宿主未响应，请检查 bootstrap.log。";
                String phase=status.get("phase").getAsString();
                if(status.has("error")&&!status.get("error").getAsString().isEmpty())return "切换失败："+status.get("error").getAsString();
                if(phase.equals("PREPARING"))return "正在准备目标实例，首次启动需要较长时间…";
                if(phase.equals("SWITCHING"))return "正在交接世界…";
                if(phase.equals("READY"))return "选择目标实例；首次使用会自动安装对应 Epoch 模组。";
            }
        }catch(Exception ignored){}
        return message;
    }
    public static synchronized void refresh(Path gameDir){
        try{
            if(project==null)project=findProject(gameDir);
            JsonObject currentCatalog=catalog();
            if(System.currentTimeMillis()-refreshedAt<1000)return;
            refreshedAt=System.currentTimeMillis();
            Path seed=seedFile;
            if(seed==null)seed=Paths.get(currentCatalog.get("seedPath").getAsString());
            if(!sameSession(read(seed)))throw new IOException("启动信息属于另一会话，无法刷新");
            launch("--discover","--seed",seed.toString());message="正在刷新实例列表…";
        }catch(Exception error){message="刷新失败："+error.getMessage();}
    }
    public static synchronized void select(Path gameDir,String version,String targetId){
        try{
            controlDirectory(gameDir,version);
            catalog();
            JsonObject host=read(project.resolve(".minecraft/epoch/host-status.json"));
            if(!sameSession(host)||!freshStatus(host))throw new IllegalStateException("当前窗口尚未连接或宿主已离线");
            if(!"READY".equals(host.get("phase").getAsString()))throw new IllegalStateException("另一切换正在准备，请稍候");
            Instance target=null;for(Instance item:instances(gameDir))if(item.id.equals(targetId))target=item;
            if(target==null||!target.supported||target.current)throw new IllegalArgumentException("该实例当前不可切换");
            JsonObject request=new JsonObject();request.addProperty("id",UUID.randomUUID().toString());request.addProperty("sessionId",activeSession());request.addProperty("version",version);request.addProperty("targetId",targetId);
            write(control.resolve("request.json"),request);message="已选择 "+target.label+"，正在准备…";
        }catch(Exception error){message="无法切换："+error.getMessage();throw new IllegalStateException(message,error);}
    }
    private static void write(Path path,JsonObject value)throws IOException{
        Files.createDirectories(path.getParent());Path temp=Files.createTempFile(path.getParent(),"epoch-auto-",".tmp");
        try{
            Files.write(temp,GSON.toJson(value).getBytes(StandardCharsets.UTF_8));
            for(int attempt=0;;attempt++)try{
                try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}return;
            }catch(AccessDeniedException e){if(attempt>=30)throw e;try{Thread.sleep(10);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException(interrupted);}}
        }finally{Files.deleteIfExists(temp);}
    }
}
