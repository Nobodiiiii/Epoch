package dev.epoch.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Local file transport. Commands and replies are replaced atomically. */
public final class BridgeFiles {
    public static final class Command { public String id, action, path, sessionId, worldId, transactionId; }
    public volatile long windowHandle;
    public volatile boolean clientReady;
    public final Map<String, Object> diagnostics = new ConcurrentHashMap<String, Object>();
    private final Path directory;
    private final String version;
    private String lastCommand = "";
    private String lastId = "", lastPhase = "STARTING", lastDetail = "";
    private long lastRequest;
    private String transactionId = "";
    private final Map<String,String[]> replies = new LinkedHashMap<String,String[]>(64, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String,String[]> entry) { return size() > 64; }
    };
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public BridgeFiles(Path directory, String version) {
        this.directory = directory.toAbsolutePath(); this.version = version;
        try {
            Files.createDirectories(this.directory);
            // The previous process may have stopped after a shutdown/export command.
            // Remember that command without deleting the file: a new host can safely
            // replace it atomically while this client starts and its new ID will run.
            Path previousPath = this.directory.resolve("command.json");
            if (Files.exists(previousPath)) {
                try {
                    Command previous = GSON.fromJson(new String(Files.readAllBytes(previousPath), StandardCharsets.UTF_8), Command.class);
                    if (previous != null && previous.id != null) lastCommand = previous.id;
                } catch (Exception ignored) {
                    diagnostics.put("startupTransportError", ignored.toString());
                }
            }
            status("", "STARTING", "Starting Epoch " + version);
        }
        catch (Exception e) { throw new IllegalStateException("Cannot initialize Epoch control directory", e); }
    }

    public synchronized Command poll() {
        Command command = pendingCommand();
        if (command != null) {
            lastCommand = command.id; transactionId = command.transactionId == null ? "" : command.transactionId;
            System.setProperty("epoch.transactionId", transactionId);
        }
        return command;
    }

    /** Client ticks continue through pause menus, while integrated server ticks stop. */
    public synchronized boolean needsWorldTick() {
        Command command = pendingCommand();
        if (command == null) return false;
        return "export".equals(command.action) || "import".equals(command.action)
            || "freeze".equals(command.action) || "resume".equals(command.action) || "recoverresume".equals(command.action)
            || "leaveworld".equals(command.action) || "shutdown".equals(command.action);
    }

    private Command pendingCommand() {
        if ("NO_WORLD".equals(lastPhase)) return null;
        Path commandPath = directory.resolve("command.json");
        if (!Files.exists(commandPath)) return null;
        try {
            Command command = GSON.fromJson(new String(Files.readAllBytes(commandPath), StandardCharsets.UTF_8), Command.class);
            if (command == null || command.id == null || command.action == null) return null;
            String session = System.getProperty("epoch.sessionId", ""), world = System.getProperty("epoch.worldId", "");
            if (session.isEmpty() || world.isEmpty()) return null;
            if ((!session.isEmpty() && !session.equals(command.sessionId)) || (!world.isEmpty() && !world.equals(command.worldId))) return null;
            String[] completed = replies.get(command.id);
            if (completed != null) {
                if (!command.id.equals(lastId)) {
                    transactionId = command.transactionId == null ? "" : command.transactionId;
                    status(command.id, completed[0], completed[1]);
                }
                return null;
            }
            if (command.id.equals(lastCommand)) return null;
            if (command.transactionId != null && !command.transactionId.isEmpty() && !transactionAllows(command)) return null;
            return command;
        } catch (Exception e) { diagnostics.put("transportError", e.toString()); return null; }
    }

    /** Old transaction commands cannot run again after their bounded reply cache is evicted. */
    private boolean transactionAllows(Command command) throws IOException {
        Path file = Paths.get(System.getProperty("epoch.projectRoot", ".")).resolve(".minecraft/epoch/session/transaction.json");
        if (Files.size(file) > 16384) throw new IOException("Oversized handoff transaction");
        com.google.gson.JsonObject transaction = new com.google.gson.JsonParser().parse(
                new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
        if (!command.transactionId.equals(transaction.get("transactionId").getAsString())) return false;
        String stage = transaction.get("stage").getAsString();
        String instance = System.getProperty("epoch.instanceId", "");
        boolean source = instance.equals(transaction.get("source").getAsString());
        boolean target = instance.equals(transaction.get("target").getAsString());
        if ("export".equals(command.action)) return source && "EXPORTING".equals(stage);
        if ("import".equals(command.action)) return target && "IMPORTING".equals(stage);
        if ("resume".equals(command.action)) return target && "COMMITTING".equals(stage) || source && "ROLLING_BACK".equals(stage);
        if ("recoverresume".equals(command.action)) return target && ("COMMITTING".equals(stage) || "COMMITTED".equals(stage));
        return !"COMMITTED".equals(stage) && !"ABORTED".equals(stage);
    }

    public synchronized void status(String commandId, String phase, String detail) {
        lastId = commandId == null ? "" : commandId;
        lastPhase = phase;
        lastDetail = detail;
        if (!lastId.isEmpty()) replies.put(lastId, new String[]{phase, detail});
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("version", version); data.put("commandId", lastId); data.put("phase", phase); data.put("detail", detail);
        data.put("sessionId", System.getProperty("epoch.sessionId", "")); data.put("worldId", System.getProperty("epoch.worldId", ""));
        data.put("transactionId", transactionId);
        data.put("windowHandle", windowHandle); data.put("clientReady", clientReady);
        data.put("updatedAt", System.currentTimeMillis()); data.put("diagnostics", new LinkedHashMap<String,Object>(diagnostics));
        write(directory.resolve("status.json"), data);
    }

    public synchronized void heartbeat() { status(lastId, lastPhase, lastDetail); }
    public synchronized void worldStopped() {
        replies.clear(); lastCommand = ""; transactionId = ""; diagnostics.clear(); clientReady = false;
        status("", "NO_WORLD", "World session closed");
    }

    public synchronized void requestSwitch() {
        long now = System.currentTimeMillis();
        if (now - lastRequest < 1500) return;
        lastRequest = now;
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("id", UUID.randomUUID().toString()); request.put("version", version);
        request.put("sessionId", System.getProperty("epoch.sessionId", "")); request.put("worldId", System.getProperty("epoch.worldId", ""));
        write(directory.resolve("request.json"), request);
    }

    private static void write(Path path, Object data) {
        Path temp = null;
        try {
            temp = Files.createTempFile(path.getParent(), "bridge-", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { GSON.toJson(data, writer); }
            boolean moved = false;
            for (int attempt = 0; !moved; attempt++) {
                try {
                    try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
                    moved = true;
                } catch (AccessDeniedException sharingViolation) {
                    if (attempt >= 40) throw sharingViolation;
                    try { Thread.sleep(5); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                }
            }
        } catch (IOException e) { throw new IllegalStateException("Epoch IPC write failed: " + path, e); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch(IOException ignored) {} }
    }
}
