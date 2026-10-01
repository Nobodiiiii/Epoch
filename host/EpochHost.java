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
        Files.writeString(temp,gson.toJson(data));
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
                if(id==null||s.has("commandId")&&id.equals(s.get("commandId").getAsString())){
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
        String id=UUID.randomUUID().toString();JsonObject j=new JsonObject();j.addProperty("id",id);j.addProperty("action",action);if(path!=null)j.addProperty("path",path.toString());write(control(v).resolve("command.json"),j);log(v+" "+action+" "+id);return id;
    }
    void action(String v,String action,String expected)throws Exception{waitFor(v,command(v,action,null),Set.of(expected),20000);}
    void freezeFrame(String source)throws Exception{
        Path capture=control(source).resolve("frozen.png");
        if(!Files.isRegularFile(capture))throw new IllegalStateException("Source did not publish its frozen framebuffer: "+capture);
        held=ImageIO.read(capture.toFile());
        if(held==null || held.getWidth()<1 || held.getHeight()<1)throw new IllegalStateException("Invalid source frozen framebuffer");
        Files.createDirectories(epoch.resolve("evidence"));ImageIO.write(held,"png",epoch.resolve("evidence/before-switch.png").toFile());
        HWND h=windows.get(active);User32.INSTANCE.ShowWindow(h,0);canvas.repaint();
    }
    void reveal(){for(var entry:windows.entrySet())if(!entry.getKey().equals(active))User32.INSTANCE.ShowWindow(entry.getValue(),0);HWND h=windows.get(active);resize();User32.INSTANCE.ShowWindow(h,5);held=null;focus();SwingUtilities.invokeLater(()->frame.setTitle("Epoch — "+active));}
    void switchVersion()throws Exception{
        String source=active,target=source.equals("1.20.1")?"1.16.5":"1.20.1";long start=System.nanoTime();exportMillis=importRenderMillis=revealResumeMillis=0;phase="SWITCHING";error="";status();
        try{
            Path snapshot=epoch.resolve("session/snapshot.json.gz");Files.createDirectories(snapshot.getParent());
            // Source remains visible while its own render thread captures the
            // framebuffer and freezes the camera. Never capture desktop pixels.
            Files.deleteIfExists(control(source).resolve("frozen.png"));
            waitFor(source,command(source,"export",snapshot),Set.of("EXPORTED"),30000);
            exportMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
            freezeFrame(source);
            long importStarted=System.nanoTime();
            waitFor(target,command(target,"import",snapshot),Set.of("IMPORTED"),30000);
            importRenderMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-importStarted);
            // Target is still frozen but has rendered the imported scene.
            // Focus the visible child before resume so it can grab the mouse.
            long revealStarted=System.nanoTime();active=target;reveal();action(target,"resume","READY");saveSession();revealResumeMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-revealStarted);switchMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);phase="READY";status();log("Switch committed "+source+" -> "+target+" in "+switchMillis+" ms (export="+exportMillis+", import/render="+importRenderMillis+", reveal/resume="+revealResumeMillis+")");
        }catch(Exception failure){
            if(closing)throw new CancellationException("Host closing during switch");
            error=failure.toString();log("Switch failed: "+error);
            // Do not let a failed target freeze prevent the source from recovering.
            // If its state cannot be established, terminate only our target process
            // before resuming the source, so two simulations never run together.
            try{action(target,"freeze","FROZEN");}catch(Exception targetFailure){
                log("Target state uncertain; stopping target before rollback: "+targetFailure);
                Process targetProcess=processes.get(target);targetProcess.destroy();
                if(!targetProcess.waitFor(3,TimeUnit.SECONDS)){targetProcess.destroyForcibly();targetProcess.waitFor(3,TimeUnit.SECONDS);}
                if(targetProcess.isAlive()){phase="ERROR";error+="; cannot stop target for safe rollback";status();return;}
            }
            try{active=source;reveal();action(source,"resume","READY");saveSession();phase=processes.get(target).isAlive()?"READY":"DEGRADED";}catch(Exception rollback){phase="ERROR";error+="; rollback: "+rollback;}
            status();
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
    static final class Adopted extends EpochHost {
        final Path seedPath,sourceControl;
        final JsonObject seed;
        final String sourceId,sessionId;
        final long sourcePid;
        final ProcessHandle sourceProcess;
        final Map<String,String> versions=new HashMap<>();
        String pendingTarget="";
        long lastHeartbeat;
        int width=-1,height=-1;
        int rootStyle;
        boolean clippingInstalled;
        Adopted(Path root,Path seedPath)throws Exception{
            super(root,false);this.seedPath=seedPath.toAbsolutePath();seed=read(this.seedPath);
            sourceId=seed.get("instanceId").getAsString();sessionId=seed.get("sessionId").getAsString();
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
            resize();
            if(System.currentTimeMillis()-lastHeartbeat>=1000){status();lastHeartbeat=System.currentTimeMillis();}
        }
        @Override void status()throws Exception{
            JsonObject j=new JsonObject();j.addProperty("phase",phase);j.addProperty("adoptedRoot",true);
            j.addProperty("sessionId",sessionId);j.addProperty("rootInstanceId",sourceId);j.addProperty("activeInstanceId",active);
            j.addProperty("activeVersion",versions.getOrDefault(active,active));j.addProperty("sourcePID",sourcePid);
            j.addProperty("hostHwnd",host==null?0:Pointer.nativeValue(host.getPointer()));j.addProperty("targetId",pendingTarget);
            j.addProperty("heartbeat",System.currentTimeMillis());j.addProperty("heartbeatTimestamp",java.time.Instant.now().toString());
            j.addProperty("switchMillis",switchMillis);j.addProperty("error",error);
            JsonObject timings=new JsonObject();timings.addProperty("export",exportMillis);timings.addProperty("importAndRender",importRenderMillis);timings.addProperty("revealAndResume",revealResumeMillis);j.add("phaseMillis",timings);
            JsonObject children=new JsonObject();windows.forEach((id,h)->{if(!id.equals(sourceId))children.addProperty(id,Pointer.nativeValue(h.getPointer()));});j.add("children",children);
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
            int ours=Kernel32.INSTANCE.GetCurrentThreadId(),target=Win.API.GetWindowThreadProcessId(window,null);
            boolean linked=ours!=target&&Win.API.AttachThreadInput(ours,target,true);
            try{Win.API.SetFocus(window);}finally{if(linked)Win.API.AttachThreadInput(ours,target,false);}
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
            j.addProperty("activeInstanceId",active);j.addProperty("activeVersion",versions.get(active));j.addProperty("updatedAt",java.time.Instant.now().toString());
            write(epoch.resolve("session/session.json"),j);
        }
        JsonObject targetEntry(String id)throws Exception{
            if(!id.matches("[A-Za-z0-9_-][A-Za-z0-9._-]*"))throw new IllegalArgumentException("Invalid instance ID");
            JsonObject catalog=read(epoch.resolve("catalog.json"));
            if(!sessionId.equals(catalog.get("sessionId").getAsString()))throw new IllegalStateException("Discovery session changed");
            for(JsonElement entry:catalog.getAsJsonArray("instances")){
                JsonObject e=entry.getAsJsonObject();if(id.equals(e.get("id").getAsString())){
                    if(!e.get("supported").getAsBoolean())throw new IllegalArgumentException("Unsupported target: "+e);
                    versions.put(id,e.get("version").getAsString());return e;
                }
            }
            throw new IllegalArgumentException("Unknown instance ID: "+id);
        }
        void prepare(String id)throws Exception{
            targetEntry(id);if(alive(id)&&windows.containsKey(id))return;
            if(id.equals(sourceId))throw new IllegalStateException("Original source unavailable");
            String python=seed.has("pythonExecutable")?seed.get("pythonExecutable").getAsString():"python";
            Path output=epoch.resolve("prepare-"+id+".log");
            Process helper=new ProcessBuilder(python,root.resolve("scripts/launch_epoch.py").toString(),"--prepare-target",id,"--seed",seedPath.toString()).directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try{
                long end=System.currentTimeMillis()+120000;
                while(helper.isAlive()&&System.currentTimeMillis()<end){poll();Thread.sleep(100);}
                if(helper.isAlive())throw new TimeoutException("Target preparation timed out; see "+output);
                if(helper.exitValue()!=0)throw new IllegalStateException("Target preparation failed; see "+output);
            }finally{if(helper.isAlive())helper.destroy();}
            windows.remove(id);launch(id);
            JsonObject ready=waitFor(id,null,Set.of("READY"),240000);
            attach(id,new HWND(new Pointer(ready.get("windowHandle").getAsLong())));
            action(id,"freeze","FROZEN");
        }
        void switchTo(String target)throws Exception{
            if(target==null||target.isBlank())throw new IllegalArgumentException("Adopted switch requires targetId");
            if(target.equals(active))return;
            String source=active;boolean frozen=false;pendingTarget=target;phase="PREPARING";error="";status();
            try{
                prepare(target);phase="SWITCHING";status();long started=System.nanoTime();exportMillis=importRenderMillis=revealResumeMillis=0;
                Path snapshot=epoch.resolve("session/snapshot.json.gz");Files.createDirectories(snapshot.getParent());
                Files.deleteIfExists(control(source).resolve("frozen.png"));frozen=true;
                waitFor(source,command(source,"export",snapshot),Set.of("EXPORTED"),30000);freezeFrame(source);
                exportMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);long imported=System.nanoTime();
                waitFor(target,command(target,"import",snapshot),Set.of("IMPORTED"),30000);
                importRenderMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-imported);long revealed=System.nanoTime();
                active=target;reveal();action(target,"resume","READY");saveSession();
                revealResumeMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-revealed);switchMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
                phase="READY";log("Adopted switch committed "+source+" -> "+target+" in "+switchMillis+" ms");
            }catch(Exception failure){
                error=failure.toString();log("Adopted switch failed: "+error);
                if(!sourceProcess.isAlive())throw failure;
                if(frozen){
                    if(alive(target))try{action(target,"freeze","FROZEN");}catch(Exception freezeFailure){
                        if(target.equals(sourceId)){phase="ERROR";error+="; original root state uncertain: "+freezeFailure;status();return;}
                        stopOwned(target);if(alive(target)){phase="ERROR";status();return;}
                    }
                    active=source;reveal();action(source,"resume","READY");
                }else if(!target.equals(sourceId))stopOwned(target);
                phase="READY";
            }finally{pendingTarget="";status();}
        }
        void handle(JsonObject j)throws Exception{
            String action=j.get("action").getAsString();
            if(action.equals("shutdown")){closing=true;return;}
            if(action.equals("switch")){
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
            // Do not kill or reparent a user-launched root, including error paths.
            // Before an explicit helper shutdown, bring the latest world back to
            // the root if possible so leaving the helper does not lose a handoff.
            if(sourceProcess.isAlive())try{
                closing=false;
                Files.deleteIfExists(epoch.resolve("host-command.json"));
                if(!active.equals(sourceId)&&alive(active)){
                    Path snapshot=epoch.resolve("session/snapshot.json.gz");
                    waitFor(active,command(active,"export",snapshot),Set.of("EXPORTED"),30000);
                    waitFor(sourceId,command(sourceId,"import",snapshot),Set.of("IMPORTED"),30000);
                    active=sourceId;reveal();saveSession();
                }
                action(sourceId,"resume","READY");
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
