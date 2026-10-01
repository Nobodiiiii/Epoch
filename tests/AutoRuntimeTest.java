import dev.epoch.bridge.AutoRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class AutoRuntimeTest {
    static void write(Path file,String text)throws Exception{Files.createDirectories(file.getParent());Files.write(file,text.getBytes(StandardCharsets.UTF_8));}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[]args)throws Exception{
        Path root=Files.createTempDirectory("epoch-runtime-test-");
        Path game=root.resolve(".minecraft/versions/source");
        System.setProperty("epoch.projectRoot",root.toString());
        System.setProperty("epoch.managed","true");
        System.setProperty("epoch.sessionId","session-a");
        System.setProperty("epoch.instanceId","source");
        Path control=AutoRuntime.controlDirectory(game,"1.20.1");
        Path catalog=root.resolve(".minecraft/epoch/catalog.json");
        Path status=root.resolve(".minecraft/epoch/host-status.json");
        String entries="\"instances\":[{\"id\":\"source\",\"supported\":true},{\"id\":\"target\",\"supported\":true}]";
        write(catalog,"{\"sessionId\":\"session-a\","+entries+"}");
        check(AutoRuntime.instances(game).size()==2,"current session catalog must load");
        check(AutoRuntime.instances(game).get(0).current,"source is marked current");
        write(status,"{\"sessionId\":\"session-a\",\"phase\":\"READY\",\"heartbeat\":"+System.currentTimeMillis()+"}");
        AutoRuntime.select(game,"1.20.1","target");
        check(new String(Files.readAllBytes(control.resolve("request.json")),StandardCharsets.UTF_8).contains("session-a"),"request carries session");
        Files.delete(control.resolve("request.json"));
        write(status,"{\"sessionId\":\"session-a\",\"phase\":\"READY\",\"heartbeat\":1}");
        check(AutoRuntime.statusText(game).contains("中断"),"expired heartbeat cannot report ready");
        try{AutoRuntime.select(game,"1.20.1","target");throw new AssertionError("stale host accepted");}catch(IllegalStateException expected){}
        check(!Files.exists(control.resolve("request.json")),"stale host creates no request");
        write(catalog,"{\"sessionId\":\"session-b\","+entries+"}");
        check(AutoRuntime.instances(game).isEmpty(),"different session clears cached entries");
        write(control.resolve("bootstrap-error.json"),"{\"sessionId\":\"session-a\",\"error\":\"test failure\"}");
        check(AutoRuntime.statusText(game).contains("test failure"),"matching async startup failure is visible");
        write(control.resolve("bootstrap-error.json"),"{\"sessionId\":\"session-b\",\"error\":\"old failure\"}");
        check(!AutoRuntime.statusText(game).contains("old failure"),"old startup failure is ignored");
        System.out.println("AutoRuntime session and heartbeat tests passed");
    }
}
