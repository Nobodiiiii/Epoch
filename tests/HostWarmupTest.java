import com.google.gson.*;
import java.io.*;
import java.util.*;

/** No windows or game processes: drive preparation through controllable process/IPC boundaries. */
public class HostWarmupTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static final class Helper extends Process {
        boolean running=true,killed;int code;
        public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
        public InputStream getInputStream(){return new ByteArrayInputStream(new byte[0]);}
        public InputStream getErrorStream(){return getInputStream();}
        public int waitFor(){throw new AssertionError("Warmups must never block waiting for a helper");}
        public int exitValue(){if(running)throw new IllegalThreadStateException();return code;}
        public boolean isAlive(){return running;}
        public void destroy(){running=false;killed=true;}
        public Process destroyForcibly(){destroy();return this;}
    }
    static final class Driver implements EpochHost.Warmups.Driver {
        final Map<String,Helper> helpers=new HashMap<>();
        final Map<String,JsonObject> states=new HashMap<>();
        final Set<String> live=new HashSet<>();
        final List<String> events=new ArrayList<>();
        public Process prepare(String id){events.add("prepare:"+id);Helper helper=new Helper();helpers.put(id,helper);return helper;}
        public void launch(String id){events.add("launch:"+id);live.add(id);states.remove(id);}
        public boolean alive(String id){return live.contains(id);}
        public JsonObject state(String id){return states.get(id);}
        public String freeze(String id,JsonObject ready){events.add("freeze:"+id);return "freeze-"+id;}
        public void abort(String id){events.add("abort:"+id);live.remove(id);}
        void state(String id,String phase,boolean rendered,String command){
            JsonObject state=new JsonObject();state.addProperty("phase",phase);state.addProperty("clientReady",rendered);
            if(command!=null)state.addProperty("commandId",command);states.put(id,state);
        }
        void finishHelper(String id,int code){Helper helper=helpers.get(id);helper.running=false;helper.code=code;}
    }
    public static void main(String[]args){
        Driver driver=new Driver();EpochHost.Warmups warmups=new EpochHost.Warmups(driver);
        EpochHost.Warmups.Job a=warmups.request("a",0),b=warmups.request("b",0);
        check(driver.helpers.get("a").isAlive()&&driver.helpers.get("b").isAlive(),"two preparations must overlap");
        check(warmups.request("a",1)==a&&driver.events.size()==2,"duplicate pending requests must reuse their job");
        driver.finishHelper("a",0);warmups.tick(2);
        check(driver.live.contains("a")&&b.phase.equals("PREPARING"),"one slow helper must not block another launch");
        driver.finishHelper("b",0);warmups.tick(3);
        check(driver.live.size()==2,"both clients must start before either becomes ready");
        driver.state("a","READY",false,null);warmups.tick(4);
        check(a.phase.equals("STARTING"),"world ready without rendered client is insufficient");
        driver.state("a","READY",true,null);driver.state("b","ERROR",false,null);warmups.tick(5);
        check(a.phase.equals("FREEZING")&&b.phase.equals("FAILED"),"one client failure must not stop the other warmup");
        driver.state("a","FROZEN",true,"old-command");warmups.tick(6);
        check(a.pending(),"stale freeze acknowledgements cannot report ready");
        driver.state("a","FROZEN",true,"freeze-a");warmups.tick(7);
        check(a.phase.equals("READY"),"ready means hidden client freeze acknowledged");
        int count=driver.events.size();check(warmups.request("a",8)==a&&driver.events.size()==count,"switch to a warm client reuses preparation");
        b=warmups.request("b",8);check(b.pending(),"failed preparation can be retried");
        driver.finishHelper("b",0);warmups.tick(9);driver.state("b","READY",true,null);warmups.tick(10);
        driver.state("b","FROZEN",true,"freeze-b");warmups.tick(11);check(b.phase.equals("READY"),"retry can complete");
        EpochHost.Warmups.Job timeout=warmups.request("timeout",12);warmups.tick(120012);
        check(timeout.phase.equals("FAILED")&&driver.helpers.get("timeout").killed,"timeout kills only its helper");
        check(a.phase.equals("READY")&&b.phase.equals("READY"),"timeout preserves other ready clients");
        EpochHost.Warmups.Job pending=warmups.request("pending",120013);warmups.cancelPending();
        check(pending.phase.equals("FAILED")&&driver.helpers.get("pending").killed,"shutdown cancels in-flight helpers");
        check(driver.live.contains("a")&&driver.live.contains("b"),"cancelling preparations preserves ready clients for shutdown handback");
        driver.live.remove("a");warmups.tick(120014);
        check(a.phase.equals("FAILED"),"a dead warm client must no longer be reported ready");
        check(driver.events.stream().noneMatch(event->event.contains("source")),"prewarming must never command the source");
        check(warmups.status().getAsJsonObject("b").get("phase").getAsString().equals("READY"),"per-target status is published");
        System.out.println("Host parallel warmup, readiness, deduplication, retry, timeout and cancellation tests passed");
    }
}
