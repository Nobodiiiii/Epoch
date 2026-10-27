import com.google.gson.*;
import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.win32.W32APIOptions;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/** Stable Java top-level HWND; only the child GLFW surface changes. */
public class EpochHost {
    interface Win extends com.sun.jna.win32.StdCallLibrary {
        Win API=Native.load("user32",Win.class,W32APIOptions.DEFAULT_OPTIONS);
        HWND SetParent(HWND child,HWND parent); HWND SetFocus(HWND child);
        boolean SetProcessDPIAware();
        boolean AttachThreadInput(int from,int to,boolean attach);
        int GetWindowThreadProcessId(HWND window,com.sun.jna.ptr.IntByReference processId);
        HWND GetForegroundWindow(); HWND GetAncestor(HWND window,int flags);
        boolean IsWindow(HWND window); boolean IsWindowVisible(HWND window);
        boolean IsWindowEnabled(HWND window); boolean IsIconic(HWND window);
        boolean GetGUIThreadInfo(int thread,WinUser.GUITHREADINFO info);
        boolean PeekMessage(WinUser.MSG message,HWND window,int min,int max,int remove);
    }
    /** Route input only inside the already-foreground game. Never activate a background root. */
    static final class FocusRouter {
        static boolean same(HWND a,HWND b){return Objects.equals(a,b);}
        static int pendingThread(Win api,HWND root,HWND target,long owner,Collection<HWND> surfaces){
            if(root==null||target==null||!api.IsWindow(root)||!api.IsWindow(target)
                    ||!same(api.GetForegroundWindow(),root)||api.IsIconic(root)
                    ||!api.IsWindowVisible(root)||!api.IsWindowVisible(target)
                    ||!api.IsWindowEnabled(root)||!api.IsWindowEnabled(target)
                    ||!same(api.GetAncestor(target,WinUser.GA_ROOT),root))return 0;
            com.sun.jna.ptr.IntByReference pid=new com.sun.jna.ptr.IntByReference();
            int thread=api.GetWindowThreadProcessId(target,pid);
            if(thread==0||Integer.toUnsignedLong(pid.getValue())!=owner)return 0;
            WinUser.GUITHREADINFO info=new WinUser.GUITHREADINFO();
            // GetFocus on the coordinator thread cannot observe the game's input queue.
            // Leave native menus, title-bar drags and unrelated controls/dialogs alone.
            if(!api.GetGUIThreadInfo(thread,info)||!same(info.hwndActive,root)
                    ||(info.flags&0x1e)!=0||info.hwndMenuOwner!=null||info.hwndMoveSize!=null
                    ||info.hwndCapture!=null||same(info.hwndFocus,target))return 0;
            if(info.hwndFocus!=null&&!same(info.hwndFocus,root)&&!surfaces.contains(info.hwndFocus))return 0;
            return thread;
        }
        static boolean restore(Win api,int ours,HWND root,HWND target,long owner,Collection<HWND> surfaces){
            int thread=pendingThread(api,root,target,owner,surfaces);
            if(thread==0)return false;
            // A headless coordinator must have a message queue before attaching input.
            api.PeekMessage(new WinUser.MSG(),null,0,0,0);
            boolean linked=ours!=thread;
            if(linked&&!api.AttachThreadInput(ours,thread,true))return false;
            try{
                // Alt-Tab, hide, disable or destruction may race the queue attachment.
                if(pendingThread(api,root,target,owner,surfaces)!=thread)return false;
                api.SetFocus(target);
                WinUser.GUITHREADINFO info=new WinUser.GUITHREADINFO();
                return api.GetGUIThreadInfo(thread,info)&&same(info.hwndFocus,target);
            }finally{if(linked)api.AttachThreadInput(ours,thread,false);}
        }
    }
    final Gson gson=new GsonBuilder().setPrettyPrinting().create();
    final Path root,epoch; final Map<String,Process> processes=new LinkedHashMap<>();
    final Map<String,HWND> windows=new ConcurrentHashMap<>();
    final JFrame frame;
    final Canvas canvas=new Canvas(){ public void paint(Graphics g){
        if(held!=null)g.drawImage(held,0,0,getWidth(),getHeight(),null);
        else {g.setColor(new Color(20,24,29));g.fillRect(0,0,getWidth(),getHeight());g.setColor(Color.WHITE);g.drawString("Epoch · Preparing both worlds…",40,60);}
    }};
    volatile BufferedImage held; volatile boolean closing; HWND host,viewport;
    volatile String active="1.20.1",phase="STARTING";
    String lastRequest="",lastHostCommand="";
    String transactionId="";
    long switchMillis,exportMillis,importRenderMillis,revealResumeMillis; String error="";
    EpochHost(Path root){this(root,true);}
    EpochHost(Path root,boolean legacy){
        this.root=root;epoch=root.resolve(".minecraft/epoch");
        frame=legacy?new JFrame("Epoch — Preparing worlds"):null;
        if(!legacy)return;
        Path session=epoch.resolve("session/session.json");
        if(Files.isRegularFile(session))try{
            JsonObject state=read(session);String version=state.get("activeVersion").getAsString();
            if(!version.equals("1.20.1")&&!version.equals("1.16.5"))throw new IllegalArgumentException("Unsupported active version: "+version);
            active=version;
        }catch(Exception e){throw new IllegalStateException("Cannot restore authoritative Epoch session: "+session,e);}
    }
    void saveSession()throws Exception{
        JsonObject state=new JsonObject();state.addProperty("schema",1);state.addProperty("activeVersion",active);state.addProperty("updatedAt",java.time.Instant.now().toString());
        write(epoch.resolve("session/session.json"),state);
    }
    Path control(String v){return epoch.resolve("control/"+v);}
    JsonObject read(Path p)throws Exception{
        // Close the file before JSON parsing so the writer can replace it on Windows.
        byte[] bytes=Files.readAllBytes(p);return JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
    }
    void write(Path p,JsonObject data)throws Exception{
        Files.createDirectories(p.getParent()); Path temp=p.resolveSibling(p.getFileName()+".host.tmp");
        try(java.nio.channels.FileChannel output=java.nio.channels.FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){
            java.nio.ByteBuffer bytes=java.nio.charset.StandardCharsets.UTF_8.encode(gson.toJson(data));
            while(bytes.hasRemaining())output.write(bytes);output.force(true);
        }
        for(int attempt=0;;attempt++){
            try{
                try{Files.move(temp,p,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,p,StandardCopyOption.REPLACE_EXISTING);}
                return;
            }catch(FileSystemException busy){
                if(attempt>=15)throw busy;
                Thread.sleep(15);
            }
        }
    }
    void log(String message){try{Files.writeString(epoch.resolve("host.log"),java.time.Instant.now()+" "+message+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception ignored){}}
    void status()throws Exception{
        JsonObject j=new JsonObject();j.addProperty("phase",phase);j.addProperty("activeVersion",active);j.addProperty("hostHwnd",host==null?0:Pointer.nativeValue(host.getPointer()));j.addProperty("switchMillis",switchMillis);j.addProperty("error",error);
        JsonObject timings=new JsonObject();timings.addProperty("export",exportMillis);timings.addProperty("importAndRender",importRenderMillis);timings.addProperty("revealAndResume",revealResumeMillis);j.add("phaseMillis",timings);
        JsonObject handles=new JsonObject();windows.forEach((v,h)->handles.addProperty(v,Pointer.nativeValue(h.getPointer())));j.add("children",handles);write(epoch.resolve("host-status.json"),j);
    }
    void createWindow()throws Exception{
        Win.API.SetProcessDPIAware();
        SwingUtilities.invokeAndWait(()->{
            canvas.setPreferredSize(new Dimension(1280,720));frame.add(canvas);frame.pack();frame.setLocationRelativeTo(null);frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){closing=true;}});
            canvas.addComponentListener(new ComponentAdapter(){public void componentResized(ComponentEvent e){resize();}});
            frame.addWindowFocusListener(new WindowAdapter(){public void windowGainedFocus(WindowEvent e){if(!phase.equals("SWITCHING"))focus();}});
            frame.setVisible(true);
        });
        host=new HWND(Native.getComponentPointer(frame));viewport=new HWND(Native.getComponentPointer(canvas));
    }
    void resize(){for(HWND h:windows.values())User32.INSTANCE.MoveWindow(h,0,0,canvas.getWidth(),canvas.getHeight(),true);}
    void focus(){
        if(!SwingUtilities.isEventDispatchThread()){
            try{SwingUtilities.invokeAndWait(this::focus);}catch(Exception e){log("Focus transfer failed: "+e);}return;
        }
        HWND h=windows.get(active);if(h==null)return;
        int ours=Kernel32.INSTANCE.GetCurrentThreadId(),target=Win.API.GetWindowThreadProcessId(h,null);
        // GLFW belongs to another process; ordinary SetFocus alone cannot
        // reliably transfer keyboard focus across independent input queues.
        boolean linked=ours!=target && Win.API.AttachThreadInput(ours,target,true);
        try{Win.API.SetFocus(h);}finally{if(linked)Win.API.AttachThreadInput(ours,target,false);}
    }
    void attach(String v,HWND h){
        int style=User32.INSTANCE.GetWindowLong(h,-16); style=(style&~(0x80000000|0x00C00000|0x00040000|0x00080000|0x00020000|0x00010000))|0x40000000;
        User32.INSTANCE.SetWindowLong(h,-16,style);Win.API.SetParent(h,viewport);windows.put(v,h);resize();User32.INSTANCE.ShowWindow(h,0);log("Embedded "+v+" HWND "+Pointer.nativeValue(h.getPointer()));
    }
    void launch(String v)throws Exception{
        Path c=control(v);Files.deleteIfExists(c.resolve("status.json"));Files.deleteIfExists(c.resolve("command.json"));Files.deleteIfExists(c.resolve("request.json"));
        JsonObject launch=read(c.resolve("launch.json"));List<String> argv=new ArrayList<>();launch.getAsJsonArray("argv").forEach(x->argv.add(x.getAsString()));
        ProcessBuilder builder=new ProcessBuilder(argv).directory(Path.of(launch.get("cwd").getAsString()).toFile()).redirectErrorStream(true).redirectOutput(c.resolve("client-console.log").toFile());
        if(this instanceof Adopted)builder.environment().put("EPOCH_HIDDEN_START","1");
        Process process=builder.start();processes.put(v,process);log("Started "+v+" PID "+process.pid());
    }
    boolean alive(String v){Process p=processes.get(v);return p!=null&&p.isAlive();}
    void poll()throws Exception{}
    JsonObject waitFor(String v,String id,Set<String> phases,long timeout)throws Exception{
        long end=System.currentTimeMillis()+timeout;
        while(!closing&&System.currentTimeMillis()<end){
            poll();
            try{
                JsonObject hostCommand=read(epoch.resolve("host-command.json"));
                if(hostCommand.has("action") && hostCommand.get("action").getAsString().equals("shutdown")){
                    closing=true;throw new CancellationException("Host shutdown requested");
                }
            }catch(NoSuchFileException|AccessDeniedException|JsonParseException ignored){}
            if(!alive(v))throw new IllegalStateException(v+" exited");
            try{
                JsonObject s=read(control(v).resolve("status.json"));
                if((sessionIdentity().isEmpty()||s.has("sessionId")&&sessionIdentity().equals(s.get("sessionId").getAsString()))
                        &&(worldIdentity().isEmpty()||s.has("worldId")&&worldIdentity().equals(s.get("worldId").getAsString()))
                        &&(id==null||s.has("commandId")&&id.equals(s.get("commandId").getAsString()))){
                    String state=s.get("phase").getAsString();
                    if(state.equals("ERROR"))throw new IllegalStateException(v+": "+s);
                    boolean needsRenderedClient=id==null || state.equals("IMPORTED");
                    if(phases.contains(state) && (!needsRenderedClient || s.has("clientReady") && s.get("clientReady").getAsBoolean()))return s;
                }
            }catch(NoSuchFileException|AccessDeniedException|JsonParseException ignored){}
            Thread.sleep(100);
        }
        if(closing)throw new CancellationException("Host closing");
        throw new TimeoutException(v+" waiting for "+phases);
    }
    String command(String v,String action,Path path)throws Exception{
        return command(v,action,path,transactionId);
    }
    String command(String v,String action,Path path,String boundary)throws Exception{
        String id=boundary.isEmpty()?UUID.randomUUID().toString():boundary+"-"+v+"-"+action;
        JsonObject j=new JsonObject();j.addProperty("id",id);j.addProperty("action",action);j.addProperty("transactionId",boundary);
        if(this instanceof Adopted){Adopted adopted=(Adopted)this;j.addProperty("sessionId",adopted.sessionId);j.addProperty("worldId",adopted.worldId);}
        if(path!=null)j.addProperty("path",path.toString());write(control(v).resolve("command.json"),j);log(v+" "+action+" "+id);return id;
    }
    void action(String v,String action,String expected)throws Exception{waitFor(v,command(v,action,null),Set.of(expected),20000);}
    void validateStreamingPair(String source,String target)throws Exception{
        JsonObject a=read(control(source).resolve("status.json")),b=read(control(target).resolve("status.json"));
        JsonObject ad=a.has("diagnostics")?a.getAsJsonObject("diagnostics"):new JsonObject();
        JsonObject bd=b.has("diagnostics")?b.getAsJsonObject("diagnostics"):new JsonObject();
        boolean streamA=ad.has("streamingProtocol")&&ad.get("streamingProtocol").getAsInt()==5;
        boolean streamB=bd.has("streamingProtocol")&&bd.get("streamingProtocol").getAsInt()==5;
        if(streamA!=streamB)throw new IllegalStateException("Both adapters must support the active streaming authority");
        if(streamA&&(!ad.has("streamingWorldId")||!bd.has("streamingWorldId")||!ad.get("streamingWorldId").equals(bd.get("streamingWorldId"))))
            throw new IllegalStateException("Instances are attached to different world authorities");
    }
    void freezeFrame(String source)throws Exception{
        Path capture=control(source).resolve("frozen.png");
        if(!Files.isRegularFile(capture))throw new IllegalStateException("Source did not publish its frozen framebuffer: "+capture);
        held=ImageIO.read(capture.toFile());
        if(held==null || held.getWidth()<1 || held.getHeight()<1)throw new IllegalStateException("Invalid source frozen framebuffer");
        Files.createDirectories(epoch.resolve("evidence"));ImageIO.write(held,"png",epoch.resolve("evidence/before-switch.png").toFile());
        HWND h=windows.get(active);User32.INSTANCE.ShowWindow(h,0);canvas.repaint();
    }
    void reveal(){for(var entry:windows.entrySet())if(!entry.getKey().equals(active))User32.INSTANCE.ShowWindow(entry.getValue(),0);HWND h=windows.get(active);resize();User32.INSTANCE.ShowWindow(h,5);held=null;focus();SwingUtilities.invokeLater(()->frame.setTitle("Epoch — "+active));}
    interface Preparation { void run() throws Exception; }
    String sessionIdentity(){return "";}
    String worldIdentity(){return "";}
    void stopStandby(String id)throws Exception{
        Process process=processes.get(id);if(process==null)return;
        command(id,"shutdown",null);
        if(!process.waitFor(12,TimeUnit.SECONDS)){process.destroy();if(!process.waitFor(3,TimeUnit.SECONDS))process.destroyForcibly();}
        if(!process.waitFor(3,TimeUnit.SECONDS))throw new IllegalStateException("Standby did not exit: "+id);
        windows.remove(id);
    }
    void switchVersion()throws Exception{
        performHandoff(active.equals("1.20.1")?"1.16.5":"1.20.1",()->{});
    }
    /** One state machine for adopted and coordinator-owned windows; the lease decides recovery. */
    void performHandoff(String target,Preparation preparation)throws Exception{
        if(!phase.equals("READY"))throw new IllegalStateException("A handoff or recovery is already in progress");
        if(target.equals(active))return;
        String source=active;boolean frozen=false,committed=false,resumeRequested=false;
        Path authority=null,snapshot=epoch.resolve("session/"+UUID.randomUUID()+".json.gz");
        long expectedEpoch=-1;
        transactionId=UUID.randomUUID().toString();
        JsonObject transaction=new JsonObject();transaction.addProperty("schema",1);
        transaction.addProperty("transactionId",transactionId);transaction.addProperty("sessionId",sessionIdentity());
        transaction.addProperty("worldId",worldIdentity());transaction.addProperty("source",source);transaction.addProperty("target",target);
        transaction.addProperty("snapshot",snapshot.toString());transaction.addProperty("stage","PREPARING");
        phase="PREPARING";error="";
        try{
            write(epoch.resolve("session/transaction.json"),transaction);status();preparation.run();
            validateStreamingPair(source,target);
            JsonObject diagnostics=read(control(source).resolve("status.json")).getAsJsonObject("diagnostics");
            if(diagnostics!=null&&diagnostics.has("streamingBinding")){
                JsonObject binding=read(Path.of(diagnostics.get("streamingBinding").getAsString()));
                if(!worldIdentity().isEmpty()&&!worldIdentity().equals(binding.get("worldId").getAsString()))
                    throw new IllegalStateException("World session changed before handoff");
                authority=Path.of(binding.get("authority").getAsString()).resolve("world.json");
                JsonObject owner=read(authority);
                if(!source.equals(owner.get("owner").getAsString()))throw new IllegalStateException("Source no longer owns authority");
                expectedEpoch=owner.get("epoch").getAsLong();transaction.addProperty("sourceEpoch",expectedEpoch);
                transaction.addProperty("authority",authority.toString());
            }
            phase="SWITCHING";status();long started=System.nanoTime();exportMillis=importRenderMillis=revealResumeMillis=0;
            transaction.addProperty("stage","EXPORTING");write(epoch.resolve("session/transaction.json"),transaction);
            Files.createDirectories(snapshot.getParent());Files.deleteIfExists(control(source).resolve("frozen.png"));frozen=true;
            waitFor(source,command(source,"export",snapshot),Set.of("EXPORTED"),30000);freezeFrame(source);
            exportMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);long importing=System.nanoTime();
            transaction.addProperty("stage","IMPORTING");write(epoch.resolve("session/transaction.json"),transaction);
            waitFor(target,command(target,"import",snapshot),Set.of("IMPORTED"),30000);
            importRenderMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-importing);long revealing=System.nanoTime();
            transaction.addProperty("stage","COMMITTING");write(epoch.resolve("session/transaction.json"),transaction);
            resumeRequested=true;action(target,"resume","READY");committed=true;
            active=target;reveal();saveSession();
            transaction.addProperty("stage","COMMITTED");transaction.addProperty("owner",target);write(epoch.resolve("session/transaction.json"),transaction);
            revealResumeMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-revealing);switchMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
            phase="READY";log("Handoff committed "+source+" -> "+target+" in "+switchMillis+" ms");
        }catch(Exception failure){
            error=failure.toString();log("Handoff failed: "+error);
            if(authority!=null&&expectedEpoch>=0){
                JsonObject owner;
                try{owner=read(authority);}catch(Exception unreadable){phase="RECOVERY_REQUIRED";error+="; durable ownership is unavailable: "+unreadable;return;}
                String holder=owner.get("owner").getAsString();long epochValue=owner.get("epoch").getAsLong();
                committed=target.equals(holder)&&epochValue==expectedEpoch+1&&owner.has("transactionId")&&transactionId.equals(owner.get("transactionId").getAsString());
                if(!committed&&(!source.equals(holder)||epochValue!=expectedEpoch)){phase="RECOVERY_REQUIRED";error+="; durable owner changed unexpectedly";return;}
            }else if(resumeRequested&&!committed){
                phase="RECOVERY_REQUIRED";error+="; snapshot-only resume outcome requires recovery";return;
            }
            if(committed){
                active=target;transaction.addProperty("stage","COMMITTED");transaction.addProperty("owner",target);
                try{action(target,"recoverresume","READY");reveal();saveSession();write(epoch.resolve("session/transaction.json"),transaction);phase="READY";}
                catch(Exception recovery){phase="RECOVERY_REQUIRED";error+="; committed target requires recovery: "+recovery;}
                return;
            }
            if(closing||!alive(source)){phase="RECOVERY_REQUIRED";return;}
            if(frozen){
                if(alive(target))try{action(target,"freeze","FROZEN");}catch(Exception unknown){
                    try{stopStandby(target);}catch(Exception unstoppable){phase="RECOVERY_REQUIRED";error+="; cannot establish a frozen standby: "+unstoppable;return;}
                }
                // A delayed resume may commit while the host is waiting for the freeze reply.
                // Re-read ownership only after the target is quiescent, before touching the source.
                if(authority!=null){
                    JsonObject owner;
                    try{owner=read(authority);}catch(Exception unavailable){phase="RECOVERY_REQUIRED";error+="; cannot determine ownership after target freeze: "+unavailable;return;}
                    long epochValue=owner.get("epoch").getAsLong();String holder=owner.get("owner").getAsString();
                    if(target.equals(holder)&&epochValue==expectedEpoch+1&&owner.has("transactionId")&&transactionId.equals(owner.get("transactionId").getAsString())){
                        active=target;transaction.addProperty("stage","COMMITTED");transaction.addProperty("owner",target);
                        write(epoch.resolve("session/transaction.json"),transaction);
                        try{action(target,"recoverresume","READY");reveal();saveSession();phase="READY";}
                        catch(Exception recovery){phase="RECOVERY_REQUIRED";error+="; committed target requires recovery: "+recovery;}
                        return;
                    }
                    if(!source.equals(holder)||epochValue!=expectedEpoch){phase="RECOVERY_REQUIRED";error+="; ownership changed during rollback preparation";return;}
                }
                // Only an unchanged source lease can use the pre-commit rollback path.
                transaction.addProperty("stage","ROLLING_BACK");write(epoch.resolve("session/transaction.json"),transaction);
                action(source,"resume","READY");active=source;reveal();saveSession();
            }
            transaction.addProperty("stage","ABORTED");write(epoch.resolve("session/transaction.json"),transaction);phase="READY";
        }finally{
            String stage=transaction.get("stage").getAsString();
            if(phase.equals("READY")&&(stage.equals("COMMITTED")||stage.equals("ABORTED"))){
                Files.deleteIfExists(snapshot.resolveSibling(snapshot.getFileName()+".authority.json"));Files.deleteIfExists(snapshot);
                if(authority!=null){
                    String pin=UUID.nameUUIDFromBytes(snapshot.toAbsolutePath().normalize().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                    Files.deleteIfExists(authority.getParent().resolve("pins").resolve(pin+".json"));
                }
            }
            transactionId="";status();
        }
    }
    void run()throws Exception{
        Files.createDirectories(epoch);Files.deleteIfExists(epoch.resolve("host-command.json"));createWindow();status();for(String v:List.of("1.20.1","1.16.5"))launch(v);
        for(String v:processes.keySet()){
            JsonObject s=waitFor(v,null,Set.of("READY"),240000);long handle=s.get("windowHandle").getAsLong();if(handle==0)throw new IllegalStateException("Missing native window for "+v);attach(v,new HWND(new Pointer(handle)));action(v,"freeze","FROZEN");
        }
        reveal();action(active,"resume","READY");saveSession();phase="READY";status();
        while(!closing){
            try{JsonObject j=read(epoch.resolve("host-command.json"));String id=j.get("id").getAsString();if(!id.equals(lastHostCommand)){lastHostCommand=id;String a=j.get("action").getAsString();if(a.equals("switch"))switchVersion();else if(a.equals("shutdown"))closing=true;}}catch(NoSuchFileException|AccessDeniedException|JsonParseException ignored){}
            if(closing)break;
            try{Path request=control(active).resolve("request.json");JsonObject j=read(request);String id=j.get("id").getAsString();if(!id.equals(lastRequest)){lastRequest=id;Files.deleteIfExists(request);switchVersion();}}catch(NoSuchFileException|AccessDeniedException|JsonParseException ignored){}
            for(var entry:processes.entrySet())if(!entry.getValue().isAlive()){
                if(entry.getKey().equals(active))throw new IllegalStateException(entry.getKey()+" exited unexpectedly");
                if(!phase.equals("DEGRADED")){phase="DEGRADED";error=entry.getKey()+" standby exited; active world remains available";status();log(error);}
            }
            Thread.sleep(100);
        }
    }
    void shutdown(){
        log("Host shutdown");for(var e:processes.entrySet())try{command(e.getKey(),"shutdown",null);}catch(Exception ignored){}
        for(var entry:processes.entrySet())try{
            Process p=entry.getValue();if(!p.waitFor(12,TimeUnit.SECONDS)){
                log(entry.getKey()+" did not shut down gracefully; terminating owned process");p.destroy();
                if(!p.waitFor(3,TimeUnit.SECONDS))p.destroyForcibly();
            }
        }catch(Exception e){log("Shutdown failure: "+e);}
        SwingUtilities.invokeLater(frame::dispose);
    }
    /** Adopts a user's already running Minecraft HWND. Only owned targets become children. */
    /** Cooperative jobs: slow helpers/clients run in parallel; window and IPC work stays on the host thread. */
    static final class Warmups {
        interface Driver {
            Process prepare(String id)throws Exception;
            void launch(String id)throws Exception;
            boolean alive(String id);
            JsonObject state(String id)throws Exception;
            String freeze(String id,JsonObject ready)throws Exception;
            void abort(String id);
            default boolean protectedInstance(String id){return false;}
        }
        static final class Job {
            final String id;String phase="PREPARING",error="",commandId;long deadline,readyAt;Process helper;
            Job(String id){this.id=id;}
            boolean pending(){return !phase.equals("READY")&&!phase.equals("FAILED")&&!phase.equals("RELEASED");}
        }
        final Driver driver;final Map<String,Job> jobs=new LinkedHashMap<>();
        final int maxInstances=Math.max(1,Math.min(4,Integer.getInteger("epoch.warmup.maxInstances",2)));
        final long idleMillis=Math.max(1000,Long.getLong("epoch.warmup.idleMillis",180000L));
        Warmups(Driver driver){this.driver=driver;}
        Job request(String id,long now){
            Job prior=jobs.get(id);
            if(prior!=null&&(prior.pending()||prior.phase.equals("READY")&&driver.alive(id)))return prior;
            if(prior!=null&&driver.alive(id)){prior.error="Waiting for the released client to exit";return prior;}
            long occupied=jobs.values().stream().filter(job->job.pending()||job.phase.equals("READY")||driver.alive(job.id)).count();
            if(occupied>=maxInstances){
                Job idle=jobs.values().stream().filter(job->job.phase.equals("READY")&&!driver.protectedInstance(job.id))
                    .min(Comparator.comparingLong(job->job.readyAt)).orElse(null);
                if(idle!=null)release(idle.id);
                if(idle==null||driver.alive(idle.id)) {Job denied=new Job(id);denied.phase="FAILED";denied.error="Prewarm resource budget is full; retry after another preparation finishes";jobs.put(id,denied);return denied;}
            }
            Job job=new Job(id);jobs.put(id,job);
            try{job.helper=driver.prepare(id);job.deadline=now+120000;}catch(Exception e){fail(job,e);}
            return job;
        }
        void fail(Job job,Exception failure){
            if(job.helper!=null&&job.helper.isAlive())job.helper.destroyForcibly();
            driver.abort(job.id);job.phase="FAILED";job.error=failure.toString();
        }
        void tick(long now){
            for(Job job:jobs.values())try{
                if(job.phase.equals("FAILED")||job.phase.equals("RELEASED"))continue;
                if(job.phase.equals("READY")){
                    if(!driver.alive(job.id))fail(job,new IllegalStateException("Prewarmed instance exited"));
                    else if(driver.protectedInstance(job.id))job.readyAt=now;
                    else if(now-job.readyAt>=idleMillis)release(job.id);
                    continue;
                }
                if(now>=job.deadline)throw new TimeoutException(job.id+" during "+job.phase);
                if(job.phase.equals("PREPARING")){
                    if(job.helper.isAlive())continue;
                    if(job.helper.exitValue()!=0)throw new IllegalStateException("Target preparation failed; see prepare-"+job.id+".log");
                    driver.launch(job.id);job.phase="STARTING";job.deadline=now+240000;
                    continue;
                }
                if(!driver.alive(job.id))throw new IllegalStateException(job.id+" exited");
                JsonObject state=driver.state(job.id);if(state==null)continue;
                String phase=state.get("phase").getAsString();
                if(job.phase.equals("STARTING")){
                    if(phase.equals("ERROR"))throw new IllegalStateException(job.id+": "+state);
                    if(phase.equals("READY")&&state.has("clientReady")&&state.get("clientReady").getAsBoolean()){
                        job.commandId=driver.freeze(job.id,state);job.phase="FREEZING";job.deadline=now+20000;
                    }
                }else if(state.has("commandId")&&job.commandId.equals(state.get("commandId").getAsString())){
                    if(phase.equals("ERROR"))throw new IllegalStateException(job.id+": "+state);
                    if(phase.equals("FROZEN")){job.phase="READY";job.readyAt=now;}
                }
            }catch(Exception e){fail(job,e);}
        }
        JsonObject status(){
            JsonObject result=new JsonObject();jobs.forEach((id,job)->{
                JsonObject item=new JsonObject();item.addProperty("phase",job.phase);item.addProperty("error",job.error);result.add(id,item);
            });return result;
        }
        void cancelPending(){
            for(Job job:jobs.values())if(job.pending())fail(job,new CancellationException("Host closing"));
        }
        void release(String id){
            Job job=jobs.get(id);if(job==null||driver.protectedInstance(id))return;
            if(job.helper!=null&&job.helper.isAlive())job.helper.destroyForcibly();
            driver.abort(id);job.phase="RELEASED";job.error="";
        }
    }
    static final class Adopted extends EpochHost {
        final Path seedPath,sourceControl;
        final JsonObject seed;
        final String sourceId,sessionId,worldId;
        boolean worldClosed;
        boolean sourceWorldClosed;
        final long sourcePid;
        final ProcessHandle sourceProcess;
        final Map<String,String> versions=new HashMap<>();
        String pendingTarget="";
        long lastHeartbeat;
        long focusTransfers;
        int width=-1,height=-1;
        int rootStyle;
        boolean clippingInstalled;
        boolean acceptingWarmups=true;
        final Warmups warmups=new Warmups(new Warmups.Driver(){
            public Process prepare(String id)throws Exception{return startPreparation(id);}
            public void launch(String id)throws Exception{windows.remove(id);Adopted.this.launch(id);}
            public boolean alive(String id){return Adopted.this.alive(id);}
            public JsonObject state(String id)throws Exception{
                try{return read(control(id).resolve("status.json"));}
                catch(NoSuchFileException|AccessDeniedException|JsonParseException ignored){return null;}
            }
            public String freeze(String id,JsonObject ready)throws Exception{
                attach(id,new HWND(new Pointer(ready.get("windowHandle").getAsLong())));
                return command(id,"freeze",null,"");
            }
            public void abort(String id){
                if(id.equals(sourceId)||id.equals(active))return;
                Process process=processes.get(id);if(process!=null&&process.isAlive())process.destroyForcibly();
                windows.remove(id);
            }
            public boolean protectedInstance(String id){return id.equals(sourceId)||id.equals(active)||id.equals(pendingTarget);}
        });
        Adopted(Path root,Path seedPath)throws Exception{
            super(root,false);this.seedPath=seedPath.toAbsolutePath();seed=read(this.seedPath);
            sourceId=seed.get("instanceId").getAsString();sessionId=seed.get("sessionId").getAsString();
            worldId=seed.has("worldId")?seed.get("worldId").getAsString():"";
            sourceControl=Path.of(seed.get("controlDir").getAsString());sourcePid=seed.get("pid").getAsLong();
            sourceProcess=ProcessHandle.of(sourcePid).filter(ProcessHandle::isAlive).orElseThrow(()->new IllegalStateException("Adopted source process is not alive"));
            if(System.currentTimeMillis()-Files.getLastModifiedTime(seedPath).toMillis()>300000)throw new IllegalStateException("Expired adoption seed");
            if(seed.has("createdAt")){
                long age=System.currentTimeMillis()-seed.get("createdAt").getAsLong();
                if(age< -30000||age>300000)throw new IllegalStateException("Expired or future-dated adoption session");
            }
            host=new HWND(new Pointer(seed.get("windowHandle").getAsLong()));viewport=host;
            com.sun.jna.ptr.IntByReference owner=new com.sun.jna.ptr.IntByReference();
            if(Pointer.nativeValue(host.getPointer())==0 || !User32.INSTANCE.IsWindow(host) || Win.API.GetWindowThreadProcessId(host,owner)==0 || Integer.toUnsignedLong(owner.getValue())!=sourcePid)throw new IllegalStateException("Source HWND ownership does not match seed PID");
            if((User32.INSTANCE.GetWindowLong(host,-16)&0x40000000)!=0)throw new IllegalStateException("Source must be an original top-level window, not an existing child");
            JsonObject catalog=read(epoch.resolve("catalog.json"));
            if(!sessionId.equals(catalog.get("sessionId").getAsString()) || !sourceId.equals(catalog.get("sourceId").getAsString()))throw new IllegalStateException("Adoption seed does not match current discovery session");
            active=sourceId;versions.put(sourceId,seed.get("version").getAsString());windows.put(sourceId,host);
        }
        @Override Path control(String id){return id.equals(sourceId)?sourceControl:epoch.resolve("control").resolve(id);}
        @Override boolean alive(String id){return id.equals(sourceId)?sourceProcess.isAlive():super.alive(id);}
        @Override void poll()throws Exception{
            if(!sourceProcess.isAlive())throw new CancellationException("Original Minecraft process closed");
            if(Files.exists(sourceControl.resolve("session-stop-"+sessionId+".json"))){sourceWorldClosed=worldClosed=true;closing=true;throw new CancellationException("Original world session closed");}
            if(!active.equals(sourceId)&&Files.exists(control(active).resolve("session-stop-"+sessionId+".json"))){worldClosed=true;closing=true;throw new CancellationException("Active world session closed");}
            if(!closing&&acceptingWarmups){readWarmupRequests();warmups.tick(System.currentTimeMillis());}
            resize();
            // SetParent retains the original top-level HWND, which Windows can refocus
            // after Alt-Tab/click/restore. Unlike the Swing host it has no focus listener.
            if(!closing&&!active.equals(sourceId)&&(phase.equals("READY")||phase.equals("PREPARING")))focus();
            if(System.currentTimeMillis()-lastHeartbeat>=1000){status();lastHeartbeat=System.currentTimeMillis();}
        }
        @Override void status()throws Exception{
            JsonObject j=new JsonObject();j.addProperty("phase",phase);j.addProperty("adoptedRoot",true);
            j.addProperty("sessionId",sessionId);j.addProperty("worldId",worldId);j.addProperty("rootInstanceId",sourceId);j.addProperty("activeInstanceId",active);
            j.addProperty("activeVersion",versions.getOrDefault(active,active));j.addProperty("sourcePID",sourcePid);
            j.addProperty("hostHwnd",host==null?0:Pointer.nativeValue(host.getPointer()));j.addProperty("targetId",pendingTarget);
            j.addProperty("heartbeat",System.currentTimeMillis());j.addProperty("heartbeatTimestamp",java.time.Instant.now().toString());
            j.addProperty("switchMillis",switchMillis);j.addProperty("error",error);
            j.addProperty("focusTransfers",focusTransfers);
            JsonObject timings=new JsonObject();timings.addProperty("export",exportMillis);timings.addProperty("importAndRender",importRenderMillis);timings.addProperty("revealAndResume",revealResumeMillis);j.add("phaseMillis",timings);
            JsonObject children=new JsonObject();windows.forEach((id,h)->{if(!id.equals(sourceId))children.addProperty(id,Pointer.nativeValue(h.getPointer()));});j.add("children",children);
            JsonObject owned=new JsonObject();processes.forEach((id,p)->{if(p.isAlive())owned.addProperty(id,p.pid());});j.add("managedProcesses",owned);
            j.add("warmups",warmups.status());
            write(epoch.resolve("host-status.json"),j);
        }
        @Override void resize(){
            if(host==null)return;RECT rect=new RECT();if(!User32.INSTANCE.GetClientRect(host,rect))return;
            int w=rect.right-rect.left,h=rect.bottom-rect.top;if(w<=0||h<=0||w==width&&h==height)return;
            width=w;height=h;
            windows.forEach((id,window)->{if(!id.equals(sourceId))User32.INSTANCE.MoveWindow(window,0,0,w,h,true);});
        }
        @Override void focus(){
            HWND window=windows.get(active);if(window==null)return;
            Process child=processes.get(active);
            if(!active.equals(sourceId)&&(child==null||!child.isAlive()))return;
            long owner=active.equals(sourceId)?sourcePid:child.pid();
            if(FocusRouter.restore(Win.API,Kernel32.INSTANCE.GetCurrentThreadId(),host,window,owner,windows.values())){
                focusTransfers++;
                log("Focus routed to "+active+" HWND "+Pointer.nativeValue(window.getPointer()));
            }
        }
        @Override void attach(String id,HWND window){
            if(id.equals(sourceId))throw new IllegalArgumentException("Original top-level must never be reparented");
            Process p=processes.get(id);com.sun.jna.ptr.IntByReference owner=new com.sun.jna.ptr.IntByReference();
            if(p==null||!User32.INSTANCE.IsWindow(window)||Win.API.GetWindowThreadProcessId(window,owner)==0||Integer.toUnsignedLong(owner.getValue())!=p.pid())throw new IllegalStateException("Target window owner mismatch");
            User32.INSTANCE.ShowWindow(window,0);
            int style=User32.INSTANCE.GetWindowLong(window,-16);
            style=(style&~(0x80000000|0x00C00000|0x00040000|0x00080000|0x00020000|0x00010000))|0x40000000;
            User32.INSTANCE.SetWindowLong(window,-16,style);Win.API.SetParent(window,host);
            HWND parent=User32.INSTANCE.GetParent(window);
            if(parent==null||Pointer.nativeValue(parent.getPointer())!=Pointer.nativeValue(host.getPointer()))throw new IllegalStateException("Target could not be attached to original HWND");
            windows.put(id,window);width=-1;resize();log("Adopted target "+id+" HWND "+Pointer.nativeValue(window.getPointer()));
        }
        @Override void freezeFrame(String id)throws Exception{
            // Source mod draws its captured frame itself. Never hide the source
            // surface until the target's imported frame has passed readiness.
            Path file=control(id).resolve("frozen.png");BufferedImage capture=ImageIO.read(file.toFile());
            if(capture==null||capture.getWidth()<1)throw new IllegalStateException("Missing frozen source frame");
            Files.createDirectories(epoch.resolve("evidence"));ImageIO.write(capture,"png",epoch.resolve("evidence/before-switch.png").toFile());
        }
        @Override void reveal(){
            resize();HWND next=windows.get(active);
            if(!active.equals(sourceId))User32.INSTANCE.ShowWindow(next,8);
            windows.forEach((id,window)->{if(!id.equals(sourceId)&&!id.equals(active))User32.INSTANCE.ShowWindow(window,0);});
            focus();
        }
        @Override void saveSession()throws Exception{
            JsonObject j=new JsonObject();j.addProperty("schema",2);j.addProperty("sessionId",sessionId);j.addProperty("sourceInstanceId",sourceId);
            j.addProperty("worldId",worldId);j.addProperty("activeInstanceId",active);j.addProperty("activeVersion",versions.get(active));j.addProperty("updatedAt",java.time.Instant.now().toString());
            write(epoch.resolve("session/session.json"),j);
        }
        JsonObject targetEntry(String id)throws Exception{
            if(!id.matches("[A-Za-z0-9_-][A-Za-z0-9._-]*"))throw new IllegalArgumentException("Invalid instance ID");
            JsonObject catalog=read(epoch.resolve("catalog.json"));
            if(!sessionId.equals(catalog.get("sessionId").getAsString()))throw new IllegalStateException("Discovery session changed");
            for(JsonElement entry:catalog.getAsJsonArray("instances")){
                JsonObject e=entry.getAsJsonObject();if(id.equals(e.get("id").getAsString())){
                    if(!e.get("supported").getAsBoolean()&&!alive(id))throw new IllegalArgumentException("Unsupported target: "+e);
                    versions.put(id,e.get("version").getAsString());return e;
                }
            }
            throw new IllegalArgumentException("Unknown instance ID: "+id);
        }
        Process startPreparation(String id)throws Exception{
            targetEntry(id);
            if(id.equals(sourceId))throw new IllegalStateException("Original source unavailable");
            String python=seed.has("pythonExecutable")?seed.get("pythonExecutable").getAsString():"python";
            Path output=epoch.resolve("prepare-"+id+".log");
            return new ProcessBuilder(python,root.resolve("scripts/launch_epoch.py").toString(),"--prepare-target",id,"--seed",seedPath.toString()).directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        }
        void warmup(String id)throws Exception{
            targetEntry(id);
            if(id.equals(active)||id.equals(sourceId))return;
            warmups.request(id,System.currentTimeMillis());status();
        }
        void readWarmupRequests()throws Exception{
            Path directory=epoch.resolve("warmup-requests");if(!Files.isDirectory(directory))return;
            try(DirectoryStream<Path> files=Files.newDirectoryStream(directory,"*.json")){
                for(Path path:files){
                    try{
                        JsonObject request=read(path);Files.deleteIfExists(path);
                        if(!request.has("sessionId")||!sessionId.equals(request.get("sessionId").getAsString()))continue;
                        if("releaseWarmup".equals(request.get("action").getAsString())){warmups.release(request.get("targetId").getAsString());continue;}
                        if(!"warmup".equals(request.get("action").getAsString()))continue;
                        warmup(request.get("targetId").getAsString());
                    }catch(NoSuchFileException|AccessDeniedException ignored){}
                    catch(Exception failure){Files.deleteIfExists(path);log("Warmup request rejected: "+failure);}
                }
            }
        }
        void prepare(String id)throws Exception{
            targetEntry(id);
            Warmups.Job job=warmups.jobs.get(id);
            // An attached client is reusable only after its initial freeze was acknowledged.
            if(alive(id)&&windows.containsKey(id)&&(job==null||job.phase.equals("READY")))return;
            job=warmups.request(id,System.currentTimeMillis());
            while(!closing&&job.pending()){
                poll();
                try{
                    JsonObject request=read(epoch.resolve("host-command.json"));
                    if("shutdown".equals(request.get("action").getAsString()))closing=true;
                }catch(NoSuchFileException|AccessDeniedException|JsonParseException ignored){}
                if(job.pending())Thread.sleep(100);
            }
            if(closing)throw new CancellationException("Host closing");
            if(!job.phase.equals("READY"))throw new IllegalStateException(job.error);
        }
        @Override String sessionIdentity(){return sessionId;}
        @Override String worldIdentity(){return worldId;}
        @Override void stopStandby(String id)throws Exception{
            if(id.equals(sourceId))throw new IllegalStateException("Original root state is uncertain; preserve the committed recovery checkpoint");
            stopOwned(id);if(alive(id))throw new IllegalStateException("Owned standby did not stop");
        }
        void switchTo(String target)throws Exception{
            if(target==null||target.isBlank())throw new IllegalArgumentException("Select a target instance");
            pendingTarget=target;
            try{performHandoff(target,()->prepare(target));}finally{pendingTarget="";status();}
        }
        void handle(JsonObject j)throws Exception{
            String action=j.get("action").getAsString();
            if(action.equals("shutdown")){closing=true;return;}
            if(action.equals("warmup")){
                if(j.has("sessionId")&&sessionId.equals(j.get("sessionId").getAsString())&&j.has("targetId"))warmup(j.get("targetId").getAsString());
                return;
            }
            if(action.equals("releaseWarmup")){
                if(j.has("sessionId")&&sessionId.equals(j.get("sessionId").getAsString())&&j.has("targetId"))warmups.release(j.get("targetId").getAsString());
                return;
            }
            if(action.equals("switch")){
                if(!j.has("sessionId")||!sessionId.equals(j.get("sessionId").getAsString()))return;
                if(j.has("worldId")&&!worldId.equals(j.get("worldId").getAsString()))return;
                if(!j.has("targetId")){error="Select an instance: targetId is required";status();return;}
                switchTo(j.get("targetId").getAsString());
            }
        }
        @Override void run()throws Exception{
            Files.createDirectories(epoch);Files.deleteIfExists(epoch.resolve("host-command.json"));Files.deleteIfExists(control(sourceId).resolve("request.json"));
            rootStyle=User32.INSTANCE.GetWindowLong(host,-16);User32.INSTANCE.SetWindowLong(host,-16,rootStyle|0x02000000);clippingInstalled=true;
            phase="READY";saveSession();status();log("Adopted original HWND "+Pointer.nativeValue(host.getPointer())+" PID "+sourcePid+" instance "+sourceId);
            while(!closing){
                poll();
                try{JsonObject j=read(epoch.resolve("host-command.json"));String id=j.get("id").getAsString();if(!id.equals(lastHostCommand)){lastHostCommand=id;handle(j);}}catch(NoSuchFileException|AccessDeniedException|JsonParseException ignored){}
                if(closing)break;
                try{Path request=control(active).resolve("request.json");JsonObject j=read(request);String id=j.get("id").getAsString();if(!id.equals(lastRequest)){lastRequest=id;Files.deleteIfExists(request);j.addProperty("action","switch");handle(j);}}catch(NoSuchFileException|AccessDeniedException|JsonParseException ignored){}
                if(!alive(active))throw new IllegalStateException("Active instance exited: "+active);
                Thread.sleep(100);
            }
        }
        void stopOwned(String id){
            Process p=processes.get(id);if(p==null||!p.isAlive())return;
            try{command(id,"shutdown",null);if(!p.waitFor(12,TimeUnit.SECONDS)){p.destroy();if(!p.waitFor(3,TimeUnit.SECONDS))p.destroyForcibly();}}catch(Exception e){log("Target shutdown: "+e);}
            HWND h=windows.remove(id);if(h!=null&&User32.INSTANCE.IsWindow(h))User32.INSTANCE.ShowWindow(h,0);
        }
        @Override void shutdown(){
            log("Adopted coordinator shutdown; original process is unowned");
            acceptingWarmups=false;
            warmups.cancelPending();
            if(worldClosed&&!sourceWorldClosed&&sourceProcess.isAlive())try{
                transactionId="";command(sourceId,"leaveworld",null);
            }catch(Exception leaveFailure){log("Original world needs manual exit: "+leaveFailure);}
            // Do not kill or reparent a user-launched root, including error paths.
            // Before an explicit helper shutdown, bring the latest world back to
            // the root if possible so leaving the helper does not lose a handoff.
            if(sourceProcess.isAlive()&&!worldClosed)try{
                closing=false;
                Files.deleteIfExists(epoch.resolve("host-command.json"));
                if(!active.equals(sourceId)&&alive(active)){
                    // A return is a fresh reverse transaction from the current authoritative state.
                    if(phase.equals("READY"))performHandoff(sourceId,()->{});
                    else log("Preserving recovery checkpoint; uncertain handoff cannot directly resume the old root");
                }else if(phase.equals("READY"))action(sourceId,"resume","READY");
            }catch(Exception e){log("Unable to restore original root during shutdown: "+e);}finally{closing=true;}
            for(String id:new ArrayList<>(processes.keySet()))stopOwned(id);
            if(clippingInstalled&&User32.INSTANCE.IsWindow(host)){
                int style=User32.INSTANCE.GetWindowLong(host,-16);
                User32.INSTANCE.SetWindowLong(host,-16,(style&~0x02000000)|(rootStyle&0x02000000));
            }
            phase="STOPPED";try{status();}catch(Exception ignored){}
        }
    }
    public static void main(String[]args)throws Exception{
        EpochHost app=args.length>=3&&args[1].equals("--adopt")?new Adopted(Path.of(args[0]),Path.of(args[2])):new EpochHost(Path.of(args[0]));Files.createDirectories(app.epoch);
        try(java.nio.channels.FileChannel channel=java.nio.channels.FileChannel.open(app.epoch.resolve("host.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)){
            java.nio.channels.FileLock lock=channel.tryLock();if(lock==null){if(app.frame!=null)app.frame.dispose();throw new IllegalStateException("Epoch host already running");}
            try(lock){try{app.run();}catch(CancellationException e){app.phase="STOPPING";app.log(e.getMessage());app.status();}catch(Exception e){app.error=e.toString();app.phase="ERROR";app.log(app.error);app.status();}finally{app.shutdown();}}
        }
    }
}
