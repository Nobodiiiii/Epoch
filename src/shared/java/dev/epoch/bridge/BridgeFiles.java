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
    public static final class Command { public String id, action, path; }
    public volatile long windowHandle;
    public volatile boolean clientReady;
    public final Map<String, Object> diagnostics = new ConcurrentHashMap<String, Object>();
    private final Path directory;
    private final String version;
    private String lastCommand = "";
    private String lastId = "", lastPhase = "STARTING", lastDetail = "";
    private long lastRequest;
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
        if (command != null) lastCommand = command.id;
        return command;
    }

    /** Client ticks continue through pause menus, while integrated server ticks stop. */
    public synchronized boolean needsWorldTick() {
        Command command = pendingCommand();
        if (command == null) return false;
        return "export".equals(command.action) || "import".equals(command.action)
            || "freeze".equals(command.action) || "resume".equals(command.action)
            || "shutdown".equals(command.action);
    }

    private Command pendingCommand() {
        Path commandPath = directory.resolve("command.json");
        if (!Files.exists(commandPath)) return null;
        try {
            Command command = GSON.fromJson(new String(Files.readAllBytes(commandPath), StandardCharsets.UTF_8), Command.class);
            if (command == null || command.id == null || command.action == null || command.id.equals(lastCommand)) return null;
            return command;
        } catch (Exception e) { diagnostics.put("transportError", e.toString()); return null; }
    }

    public synchronized void status(String commandId, String phase, String detail) {
        lastId = commandId == null ? "" : commandId;
        lastPhase = phase;
        lastDetail = detail;
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("version", version); data.put("commandId", lastId); data.put("phase", phase); data.put("detail", detail);
        data.put("windowHandle", windowHandle); data.put("clientReady", clientReady);
        data.put("updatedAt", System.currentTimeMillis()); data.put("diagnostics", new LinkedHashMap<String,Object>(diagnostics));
        write(directory.resolve("status.json"), data);
    }

    public synchronized void heartbeat() { status(lastId, lastPhase, lastDetail); }

    public synchronized void requestSwitch() {
        long now = System.currentTimeMillis();
        if (now - lastRequest < 1500) return;
        lastRequest = now;
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("id", UUID.randomUUID().toString()); request.put("version", version);
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
