package dev.epoch.bridge;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Read-only map metadata. Never loads terrain or takes the simulation's store lock. */
public final class ChunkOrigins {
    public static final String UNKNOWN = "unknown";
    public static final Map<String,Integer> COLORS;
    static {
        Map<String,Integer> colors = new LinkedHashMap<>();
        colors.put("1.7.10", 0xE69F00); colors.put("1.12.2", 0x56B4E9);
        colors.put("1.16.5", 0x009E73); colors.put("1.18.2", 0xCC79A7);
        colors.put("1.20.1", 0xD55E00); colors.put(UNKNOWN, 0x9CA3AF);
        COLORS = Collections.unmodifiableMap(colors);
    }
    public static final class Area {
        public final int x0, z0, x1, z1;
        public final String version;
        Area(int x0, int z0, int x1, int z1, String version) {
            this.x0=x0; this.z0=z0; this.x1=x1; this.z1=z1; this.version=version;
        }
    }
    public static final class View {
        public final List<Area> areas;
        public final int chunks;
        public final boolean limited;
        View(List<Area> areas, int chunks, boolean limited) { this.areas=areas;this.chunks=chunks;this.limited=limited; }
    }
    public static View read(Path bindingPath, int x0, int z0, int x1, int z1) throws IOException {
        WorldBinding binding = WorldBinding.read(bindingPath);
        Path root = Paths.get(binding.authority).resolve("chunks");
        Map<Integer,TreeMap<Integer,String>> rows = new TreeMap<>();
        int count=0; boolean limited=false;
        // A viewport is capped at 512x512 chunks; no unbounded disk walk when zoomed out.
        if ((long)x1-x0 > 511) { int center=(int)(((long)x0+x1)/2);x0=center-255;x1=center+256;limited=true; }
        if ((long)z1-z0 > 511) { int center=(int)(((long)z0+z1)/2);z0=center-255;z1=center+256;limited=true; }
        outer: for(int rx=Math.floorDiv(x0,32);rx<=Math.floorDiv(x1,32);rx++)
            for(int rz=Math.floorDiv(z0,32);rz<=Math.floorDiv(z1,32);rz++) {
                Path directory=root.resolve(Integer.toString(rx)).resolve(Integer.toString(rz));
                if(!Files.isDirectory(directory))continue;
                if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Map read cancelled");
                try(DirectoryStream<Path> files=Files.newDirectoryStream(directory,"*.json")) {
                    for(Path file:files) {
                        String name=file.getFileName().toString();ChunkKey key=ChunkKey.parse(name.substring(0,name.length()-5));
                        if(key.x<x0||key.x>x1||key.z<z0||key.z>z1)continue;
                        if(count>=32768){limited=true;break outer;}
                        if(Files.size(file)>1024)throw new IOException("Oversized chunk origin record");
                        JsonObject value;
                        try(Reader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){value=new JsonParser().parse(reader).getAsJsonObject();}
                        String version=value.has("originVersion")&&!value.get("originVersion").isJsonNull()?value.get("originVersion").getAsString():UNKNOWN;
                        if(!COLORS.containsKey(version))version=UNKNOWN;
                        rows.computeIfAbsent(key.z,k->new TreeMap<>()).put(key.x,version);count++;
                    }
                }
            }
        List<Area> areas=new ArrayList<>();
        // Merge horizontal runs, keeping region borders exact and the renderer bounded.
        for(Map.Entry<Integer,TreeMap<Integer,String>> row:rows.entrySet()) {
            int start=0,last=0;String version=null;
            for(Map.Entry<Integer,String> cell:row.getValue().entrySet()) {
                if(version!=null&&(!version.equals(cell.getValue())||cell.getKey()!=last+1)) {
                    areas.add(new Area(start,row.getKey(),last+1,row.getKey()+1,version));version=null;
                }
                if(version==null){start=cell.getKey();version=cell.getValue();}last=cell.getKey();
            }
            if(version!=null)areas.add(new Area(start,row.getKey(),last+1,row.getKey()+1,version));
        }
        if(areas.size()>4096){areas=new ArrayList<>(areas.subList(0,4096));limited=true;}
        return new View(Collections.unmodifiableList(areas),count,limited);
    }
}
