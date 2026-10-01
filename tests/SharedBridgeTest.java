import dev.epoch.bridge.*;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;

/** Standalone integration tests: exercise real gzip and atomic IPC files. */
public final class SharedBridgeTest {
    private static int checks;
    private interface Throwing { void run() throws Exception; }
    private static void check(boolean ok,String why){ checks++;if(!ok)throw new AssertionError(why); }
    private static void rejects(Throwing action,String why)throws Exception {checks++;try{action.run();}catch(IOException expected){return;}throw new AssertionError("Accepted invalid snapshot: "+why);}
    private static Snapshot sample(){Snapshot s=new Snapshot();s.version="1.20.1";s.sizeX=2;s.sizeY=2;s.sizeZ=2;s.palette.add("{Name:\"minecraft:air\"}");s.palette.add("{Name:\"minecraft:chiseled_bookshelf\",Properties:{facing:\"north\",slot_0_occupied:\"true\"}}");s.runs=new int[]{0,3,1,1,0,4};s.x=.125;s.y=65.25;s.z=-3.5;s.yaw=179.99f;s.pitch=-32.5f;s.gameTime=9223372036854775700L;s.dayTime=6000;s.selectedSlot=7;s.inventory="[{Slot:0b,id:\"epoch:phase_key\",Count:1b}]";s.blockEntities.put("3","{id:\"minecraft:chiseled_bookshelf\",x:0,y:64,z:3,EpochLong:9223372036854775807L,EpochInts:[I;1,-2,2147483647],EpochLongs:[L;1L,-9223372036854775808L],CustomName:'{\"text\":\"未知方块・Epoch 🌱\"}',Items:[{Slot:0b,id:\"minecraft:written_book\",Count:1b,tag:{pages:['{\"text\":\"跨版本\\n保留\"}']}}]}");return s;}
    private static JsonObject json(Path file)throws Exception{try(Reader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){return new JsonParser().parse(reader).getAsJsonObject();}}
    private static void put(Path file,String text)throws Exception{Files.write(file,text.getBytes(StandardCharsets.UTF_8));}
    public static void main(String[] args)throws Exception{
      Path root=Files.createTempDirectory("epoch-shared-tests-");
      try {
        Snapshot original=sample();Path file=root.resolve("snapshot.json.gz");original.write(file);byte[] bytes=Files.readAllBytes(file);check((bytes[0]&255)==31&&(bytes[1]&255)==139,"Not gzip");Snapshot restored=Snapshot.read(file);
        check(restored.blockEntities.equals(original.blockEntities),"Raw SNBT payload changed");check(restored.palette.equals(original.palette),"Palette changed");check(Arrays.equals(restored.runs,original.runs),"Runs changed");check(restored.inventory.equals(original.inventory),"Inventory changed");check(restored.gameTime==original.gameTime,"Long value narrowed");check(restored.x==original.x&&restored.yaw==original.yaw&&restored.pitch==original.pitch,"Camera changed");
        Snapshot bad=sample();bad.runs=new int[]{2,8};final Snapshot badPalette=bad;rejects(()->badPalette.write(file),"out-of-range palette id");check(Snapshot.read(file).blockEntities.equals(original.blockEntities),"Rejected write replaced last valid snapshot");
        bad=sample();bad.runs=new int[]{0,9};final Snapshot overflow=bad;rejects(overflow::validate,"volume overflow");bad=sample();bad.runs=new int[]{0,7};final Snapshot incomplete=bad;rejects(incomplete::validate,"incomplete volume");bad=sample();bad.runs=new int[]{0,8,1};final Snapshot odd=bad;rejects(odd::validate,"odd runs");bad=sample();bad.runs=new int[]{0,0,1,8};final Snapshot zero=bad;rejects(zero::validate,"zero run");bad=sample();bad.sizeY=257;final Snapshot tall=bad;rejects(tall::validate,"height >256");bad=sample();bad.y0=-1;final Snapshot low=bad;rejects(low::validate,"negative y");bad=sample();bad.sizeX=129;final Snapshot wide=bad;rejects(wide::validate,"oversized x");bad=sample();bad.blockEntities.put("8","{}");final Snapshot invalidIndex=bad;rejects(invalidIndex::validate,"BE beyond volume");bad=sample();bad.x=Double.NaN;final Snapshot nan=bad;rejects(nan::validate,"NaN camera");
        Path broken=root.resolve("broken.gz");try(OutputStream out=new GZIPOutputStream(Files.newOutputStream(broken))){out.write("{\"schema\":1,\"runs\":[".getBytes(StandardCharsets.UTF_8));}rejects(()->Snapshot.read(broken),"malformed JSON");
        Path ipc=root.resolve("ipc");BridgeFiles bridge=new BridgeFiles(ipc,"1.16.5");bridge.status("import-42","IMPORTED","等待首帧");bridge.clientReady=true;bridge.windowHandle=4294967399L;bridge.diagnostics.put("unknownBlocks",2);bridge.heartbeat();JsonObject status=json(ipc.resolve("status.json"));check(status.get("commandId").getAsString().equals("import-42"),"Heartbeat lost command id");check(status.get("phase").getAsString().equals("IMPORTED"),"Heartbeat lost phase");check(status.get("detail").getAsString().equals("等待首帧"),"Heartbeat lost detail");check(status.get("clientReady").getAsBoolean(),"Heartbeat stale readiness");check(status.get("windowHandle").getAsLong()==4294967399L,"HWND narrowed");
        check(bridge.poll()==null,"Unexpected command");Path command=ipc.resolve("command.json");put(command,"{\"id\":\"c1\",\"action\":\"export\",\"path\":\"世界.gz\"}");BridgeFiles.Command received=bridge.poll();check(received!=null&&received.id.equals("c1")&&received.path.equals("世界.gz"),"Command corrupted");check(bridge.poll()==null,"Duplicate command replayed");put(command,"{\"id\":\"c1\",\"action\":\"resume\"}");check(bridge.poll()==null,"Same id accepted after payload change");put(command,"{\"id\":\"c2\",\"action\":\"resume\"}");check(bridge.poll()!=null,"Next command skipped");put(command,"{");check(bridge.poll()==null,"Malformed command accepted");check(bridge.diagnostics.containsKey("transportError"),"Malformed command not diagnosed");put(command,"{\"id\":\"c3\",\"action\":\"probe\"}");check(bridge.poll()!=null,"Recovery after malformed command failed");
        Path restartIpc=root.resolve("restart-ipc");Files.createDirectories(restartIpc);Path restartCommand=restartIpc.resolve("command.json");
        put(restartCommand,"{\"id\":\"previous-shutdown\",\"action\":\"shutdown\"}");
        BridgeFiles restarted=new BridgeFiles(restartIpc,"1.16.5");
        check(!restarted.needsWorldTick(),"Previous process shutdown requested an unpause after restart");
        check(restarted.poll()==null,"Previous process shutdown replayed after restart");
        check(Files.exists(restartCommand),"Startup deleted command file and could race with new host command");
        Path replacement=restartIpc.resolve("next-command.tmp");put(replacement,"{\"id\":\"new-session-probe\",\"action\":\"probe\"}");
        Files.move(replacement,restartCommand,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        BridgeFiles.Command afterRestart=restarted.poll();check(afterRestart!=null&&afterRestart.id.equals("new-session-probe"),"New host command skipped after restart");
        check(restarted.poll()==null,"New host command replayed twice");
        // The client may peek repeatedly while paused. Only the server poll consumes it.
        Path peekIpc=root.resolve("peek-ipc");BridgeFiles peeking=new BridgeFiles(peekIpc,"1.12.2");Path peekCommand=peekIpc.resolve("command.json");
        check(!peeking.needsWorldTick(),"Missing command requested a world tick");
        for(String action:new String[]{"export","import","freeze","resume","shutdown","probe","select"}){
          String id="peek-"+action;boolean needsTick=!action.equals("probe")&&!action.equals("select");
          put(peekCommand,"{\"id\":\""+id+"\",\"action\":\""+action+"\",\"path\":\"target-instance\"}");
          byte[] beforePeek=Files.readAllBytes(peekCommand);
          check(peeking.needsWorldTick()==needsTick,"Wrong world-tick decision for "+action);
          check(peeking.needsWorldTick()==needsTick,"Peek consumed "+action);
          check(Arrays.equals(beforePeek,Files.readAllBytes(peekCommand)),"Peek modified command file for "+action);
          BridgeFiles.Command pending=peeking.poll();
          check(pending!=null&&pending.id.equals(id)&&pending.action.equals(action)&&pending.path.equals("target-instance"),"Peek lost command payload for "+action);
          check(!peeking.needsWorldTick(),"Consumed command requested another world tick: "+action);
          check(peeking.poll()==null,"Command consumed more than once: "+action);
        }
        put(peekCommand,"{\"id\":\"peek-select\",\"action\":\"export\"}");
        check(!peeking.needsWorldTick()&&peeking.poll()==null,"Reused command id woke or replayed after action changed");
        put(peekCommand,"{");check(!peeking.needsWorldTick()&&peeking.poll()==null,"Malformed command woke the world or was consumed");
        put(peekCommand,"{\"id\":\"peek-recovered\",\"action\":\"export\"}");
        check(peeking.needsWorldTick()&&peeking.poll()!=null&&!peeking.needsWorldTick(),"Peek did not recover from malformed command");
        bridge.requestSwitch();String requestId=json(ipc.resolve("request.json")).get("id").getAsString();bridge.requestSwitch();check(json(ipc.resolve("request.json")).get("id").getAsString().equals(requestId),"Switch request debounce failed");
        AtomicBoolean writing=new AtomicBoolean(true);AtomicReference<Throwable> failure=new AtomicReference<>();Thread writer=new Thread(()->{try{for(int n=0;n<100;n++){bridge.clientReady=(n&1)==0;bridge.heartbeat();}}catch(Throwable t){failure.set(t);}finally{writing.set(false);}});writer.start();int reads=0;do{JsonObject live=json(ipc.resolve("status.json"));check(live.get("commandId").getAsString().equals("import-42")&&live.get("phase").getAsString().equals("IMPORTED"),"Concurrent heartbeat exposed partial/stale status");reads++;}while(writing.get());writer.join();if(failure.get()!=null)failure.get().printStackTrace();check(failure.get()==null,"Atomic writer failed: "+failure.get());check(reads>0,"Concurrent status reader did not run");try(java.util.stream.Stream<Path> paths=Files.list(ipc)){check(paths.noneMatch(p->p.getFileName().toString().endsWith(".tmp")),"Orphan atomic temp files");}
        System.out.println("PASS: "+checks+" assertions; gzip/NBT, rejection boundaries, IPC heartbeat/duplicates, "+reads+" atomic read snapshots.");
      }finally{try(java.util.stream.Stream<Path> paths=Files.walk(root)){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(path);}}
    }
}

