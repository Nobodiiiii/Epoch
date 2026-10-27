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
        public String warmupPhase="",warmupError="";
        public boolean warmupAvailable;
        public boolean releaseAvailable;
        public boolean canRelease(){return !current&&releaseAvailable&&warmupPhase.equals("READY");}
        public boolean canWarmup(){return supported&&!current&&warmupAvailable&&(warmupPhase.isEmpty()||warmupPhase.equals("FAILED")||warmupPhase.equals("RELEASED"));}
        public String warmupLabel(){
            if(current)return "当前版本";
            if(!supported)return "不可用";
            if(warmupPhase.equals("READY"))return canRelease()?"释放预热":"已预热";
            if(warmupPhase.equals("RELEASING"))return "释放中…";
            if(warmupPhase.equals("RELEASED"))return "预热";
            if(warmupPhase.equals("FAILED"))return "重试预热";
            return warmupPhase.isEmpty()?"预热":"预热中…";
        }
        public String detail(){
            if(current)return "You are playing this instance";
            if(!supported)return reason==null?"Unavailable":reason;
            if(warmupPhase.equals("FAILED"))return "预热失败："+warmupError;
            if(warmupPhase.equals("READY"))return "已预热，点击版本即可切换";
            if(warmupPhase.equals("RELEASED"))return "已释放预热实例，可再次准备";
            if(warmupPhase.equals("RELEASING"))return "正在释放预热实例";
            if(!warmupPhase.isEmpty())return "正在后台预热，可继续预热其他版本";
            return "点击版本切换，或点击预热在后台准备";
        }
    }
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static Path project,control,seedFile,worldBinding;
    private static String instanceId,sessionId,message="正在检测本目录下的实例…";
    private static boolean attempted;
    private static String worldId = "", worldName = "";
    private static long worldGeneration;
    private static final Set<String> preparedBindings = new HashSet<String>();
    private static final java.util.concurrent.ThreadPoolExecutor links = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<Runnable>(4),
            task -> { Thread thread = new Thread(task, "Epoch save entry preparation"); thread.setDaemon(true); return thread; });
    private static final Map<String,java.util.concurrent.Future<?>> worldTasks = new HashMap<String,java.util.concurrent.Future<?>>();
    private static long refreshedAt;
    private static volatile List<Instance> cached=Collections.emptyList();
    private AutoRuntime(){}

    public static synchronized void prepareSavedWorlds(Path binding) throws IOException {
        String key = binding.toAbsolutePath().normalize().toString();
        java.util.concurrent.Future<?> existing = worldTasks.get(key);
        if (existing != null && !existing.isDone()) return;
        if (!preparedBindings.add(key)) return;
        final long generation = worldGeneration;
        try { worldTasks.put(key, links.submit(() -> {
            try { prepareSavedWorldsNow(binding); }
            catch (IOException error) { synchronized (AutoRuntime.class) { if (generation == worldGeneration) preparedBindings.remove(key); } System.err.println("[Epoch] " + error); }
        })); } catch (java.util.concurrent.RejectedExecutionException full) { preparedBindings.remove(key); throw new IOException("Save entry preparation queue is full", full); }
    }
    private static void prepareSavedWorldsNow(Path binding) throws IOException {
        Path root = findProject(binding);
        Process process = new ProcessBuilder(System.getProperty("epoch.python", "python"),
                root.resolve("scripts/prepare_saved_worlds.py").toString(), binding.toString())
                .directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(root.resolve(".minecraft/epoch/saved-worlds.log").toFile())).start();
        try {
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly(); throw new IOException("Save entry preparation timed out");
            }
            if (process.exitValue() != 0)
                throw new IOException("Saved world committed, but profile links could not be prepared; see saved-worlds.log");
        } catch (InterruptedException error) { process.destroyForcibly(); Thread.currentThread().interrupt(); throw new IOException(error); }
    }

    public static boolean managed(){return Boolean.getBoolean("epoch.managed");}
    public static synchronized void bindWorld(Path binding, String id) throws IOException {
        worldId = id; System.setProperty("epoch.worldId", id);
        worldBinding = binding.toAbsolutePath().normalize();
        try { prepareSavedWorlds(binding); } catch (IOException error) { System.err.println("[Epoch] Save entries could not be scheduled: " + error); }
    }
    public static synchronized void worldStopped() {
        String endedSession = activeSession();
        if (endedSession != null && !endedSession.isEmpty() && control != null) try {
            JsonObject ended = new JsonObject(); ended.addProperty("sessionId", endedSession); ended.addProperty("worldId", worldId);
            write(control.resolve("session-stop-" + endedSession + ".json"), ended);
        } catch (IOException error) { System.err.println("[Epoch] Cannot signal world close: " + error); }
        for (java.util.concurrent.Future<?> task : worldTasks.values()) if (!task.isDone()) task.cancel(true);
        worldTasks.clear(); links.purge(); preparedBindings.clear(); worldGeneration++;
        attempted = false; sessionId = null; seedFile = null; worldBinding = null; worldName = ""; worldId = "";
        cached = Collections.emptyList(); refreshedAt = 0;
        if (!managed()) { System.clearProperty("epoch.sessionId"); System.clearProperty("epoch.worldId"); }
        System.clearProperty("epoch.transactionId");
    }
    /** Normal single-player launches stream by default; managed targets negotiate with their source. */
    public static boolean streamingEnabled(){
        return StreamingMode.enabled();
    }
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
        if (streamingEnabled() && worldBinding == null) return;
        attempted=true;
        try{
            sessionId=UUID.randomUUID().toString();
            AutoRuntime.worldName = worldName;
            if (worldId.isEmpty()) worldId = UUID.randomUUID().toString();
            System.setProperty("epoch.sessionId", sessionId); System.setProperty("epoch.worldId", worldId);
            JsonObject seed=new JsonObject();seed.addProperty("sessionId",sessionId);seed.addProperty("instanceId",instanceId);
            seed.addProperty("version",version);seed.addProperty("gameDir",gameDir.toAbsolutePath().normalize().toString());
            seed.addProperty("controlDir",control.toString());seed.addProperty("worldName",worldName);seed.addProperty("windowHandle",hwnd);
            seed.addProperty("pid",Long.parseLong(ManagementFactory.getRuntimeMXBean().getName().split("@")[0]));
            seed.addProperty("username",username);seed.addProperty("uuid",uuid);seed.addProperty("projectRoot",project.toString());
            seed.addProperty("createdAt",System.currentTimeMillis());seed.addProperty("javaHome",System.getProperty("java.home"));
            seed.addProperty("worldId", worldId);
            seed.addProperty("streaming", streamingEnabled());
            if (worldBinding != null) seed.addProperty("authorityBinding", worldBinding.toString());
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
            JsonObject host=null;
            try{JsonObject latest=read(project.resolve(".minecraft/epoch/host-status.json"));if(sameSession(latest)&&freshStatus(latest))host=latest;}catch(Exception ignored){}
            List<Instance> result=new ArrayList<Instance>();
            for(JsonElement element:catalog.getAsJsonArray("instances")){
                Instance item=GSON.fromJson(element,Instance.class);
                item.current=item.id.equals(System.getProperty("epoch.instanceId",instanceId==null?"":instanceId));
                if(host!=null){
                    String phase=host.get("phase").getAsString();
                    item.warmupAvailable=phase.equals("READY")||phase.equals("PREPARING");
                    if(host.has("warmups")&&host.getAsJsonObject("warmups").has(item.id)){
                        JsonObject warmup=host.getAsJsonObject("warmups").getAsJsonObject(item.id);
                        item.warmupPhase=warmup.get("phase").getAsString();
                        item.releaseAvailable=!host.has("rootInstanceId")||!item.id.equals(host.get("rootInstanceId").getAsString());
                        item.warmupError=warmup.has("error")?warmup.get("error").getAsString():"";
                    }
                    if(host.has("managedProcesses")&&host.getAsJsonObject("managedProcesses").has(item.id)){
                        item.supported=true;item.reason="";
                    }
                    if(!item.current&&host.has("rootInstanceId")&&item.id.equals(host.get("rootInstanceId").getAsString()))item.warmupPhase="READY";
                    if(item.id.matches("[A-Za-z0-9_-][A-Za-z0-9._-]*")&&Files.exists(warmupRequest(item.id))){
                        item.warmupPhase="releaseWarmup".equals(read(warmupRequest(item.id)).get("action").getAsString())?"RELEASING":"QUEUED";item.warmupError="";
                    }
                }
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
                if(phase.equals("READY"))return "点击版本切换，或并行预热多个版本；预热不会切换当前版本。";
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
            if (worldBinding != null) {
                preparedBindings.remove(worldBinding.toString()); prepareSavedWorlds(worldBinding);
            }
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
            JsonObject request=new JsonObject();request.addProperty("id",UUID.randomUUID().toString());request.addProperty("sessionId",activeSession());request.addProperty("worldId",worldId);request.addProperty("version",version);request.addProperty("targetId",targetId);
            write(control.resolve("request.json"),request);message="已选择 "+target.label+"，正在准备…";
        }catch(Exception error){message="无法切换："+error.getMessage();throw new IllegalStateException(message,error);}
    }
    private static Path warmupRequest(String targetId){return project.resolve(".minecraft/epoch/warmup-requests").resolve(activeSession()+"-"+targetId+".json");}
    public static synchronized void warmup(Path gameDir,String version,String targetId){
        try{
            controlDirectory(gameDir,version);catalog();
            JsonObject host=read(project.resolve(".minecraft/epoch/host-status.json"));
            if(!sameSession(host)||!freshStatus(host))throw new IllegalStateException("当前窗口尚未连接或宿主已离线");
            String phase=host.get("phase").getAsString();
            if(!phase.equals("READY")&&!phase.equals("PREPARING"))throw new IllegalStateException("正在交接世界，请稍候");
            Instance target=null;for(Instance item:instances(gameDir))if(item.id.equals(targetId))target=item;
            if(target==null||!target.supported||target.current||!targetId.matches("[A-Za-z0-9_-][A-Za-z0-9._-]*"))throw new IllegalArgumentException("该实例当前不可预热");
            if(!target.canWarmup()&&!target.canRelease())return;
            JsonObject request=new JsonObject();request.addProperty("id",UUID.randomUUID().toString());request.addProperty("action",target.canRelease()?"releaseWarmup":"warmup");request.addProperty("sessionId",activeSession());request.addProperty("worldId",worldId);request.addProperty("targetId",targetId);
            write(warmupRequest(targetId),request);message=(target.canRelease()?"正在释放 ":"正在后台预热 ")+target.label;
        }catch(Exception error){message="无法预热："+error.getMessage();throw new IllegalStateException(message,error);}
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
