package dev.epoch.bridge;

import java.io.IOException;
import java.util.*;

/** Pure, side-effect-free reconstruction of properties formerly supplied by the world. */
public final class BlockContext {
    private BlockContext() {}
    public interface Lookup {
        /** Null means unavailable, never air. Implementations must not generate chunks. */
        String state(int x,int y,int z) throws IOException;
        boolean solid(int x,int y,int z) throws IOException;
        default String instrument(int x,int y,int z) throws IOException { return null; }
        default String name(int x,int y,int z) throws IOException {String s=state(x,y,z);return s==null?null:SnbtFields.parse(s).get("Name");}
    }
    private static final String[] DIR={"north","east","south","west"};
    private static final int[] DX={0,1,0,-1},DZ={-1,0,1,0};
    private static final int[][] STEPS={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private static Lookup memo(final Lookup delegate) {
        return new Lookup(){
            final Map<String,String> names=new HashMap<String,String>();
            public String state(int x,int y,int z)throws IOException{return delegate.state(x,y,z);}
            public boolean solid(int x,int y,int z)throws IOException{return delegate.solid(x,y,z);}
            public String instrument(int x,int y,int z)throws IOException{return delegate.instrument(x,y,z);}
            public String name(int x,int y,int z)throws IOException{String raw=state(x,y,z);if(raw==null)return null;String name=names.get(raw);if(name==null){name=SnbtFields.parse(raw).get("Name");if(names.size()>=4096)names.clear();names.put(raw,name);}return name;}
        };
    }
    private static long position(int x,int y,int z){return ((x&0x3ffffffL)<<35)|((z&0x3ffffffL)<<9)|(y&511L);}
    private static SnbtFields at(Lookup world,int x,int y,int z) throws IOException {
        String value=world.state(x,y,z); if(value==null)throw new MissingContext();
        return SnbtFields.parse(BlockSemantics.normalize(value));
    }
    private static final class MissingContext extends IOException { private static final long serialVersionUID=1L; }
    /** Native adapters use this when a loaded placeholder cannot supply real geometry. */
    public static IOException unavailable() { return new MissingContext(); }
    private static boolean stairs(String name) { return name.startsWith("minecraft:")&&name.endsWith("_stairs"); }
    private static int facing(String value) { for(int n=0;n<4;n++)if(DIR[n].equals(value))return n;return -1; }
    private static String property(SnbtFields s,String key) throws IOException { return s.compound("Properties").get(key); }
    private static boolean sameStair(Lookup w,int x,int y,int z,String direction,String half) throws IOException {
        SnbtFields s=at(w,x,y,z);return stairs(s.get("Name"))&&direction.equals(property(s,"facing"))&&half.equals(property(s,"half"));
    }
    public static String resolve(String text,int x,int y,int z,Lookup world) throws IOException {
        String normalized=BlockSemantics.normalize(text);SnbtFields s=SnbtFields.parse(normalized);
        if(s.get("EpochLegacyVersion").equals("epoch-context-v1")){s.remove("EpochLegacyVersion");s.remove("EpochContextRequired");}
        if(s.has("EpochLegacyVersion"))return normalized;
        String name=s.get("Name");if(!name.startsWith("minecraft:"))return normalized;
        SnbtFields p=s.compound("Properties");String b=name.substring(10);
        try {
            if(name.equals("minecraft:note_block")&&!p.has("instrument")) {
                String instrument=world.instrument(x,y-1,z);if(instrument==null)throw new MissingContext();p.put("instrument",instrument);
            } else if(BlockSemantics.leaves(name)&&p.has("decayable")&&p.has("check_decay")) {
                if(!Arrays.asList("true","false").contains(p.get("decayable"))||!Arrays.asList("true","false").contains(p.get("check_decay")))return normalized;
                p.put("persistent",Boolean.toString(p.get("decayable").equals("false")));
                p.put("distance",Integer.toString(distance(world,x,y,z)));
                p.remove("decayable");p.remove("check_decay");
            } else if(stairs(name)) {
                int f=facing(p.get("facing"));if(f<0)return normalized;String half=p.get("half"),shape="straight";
                SnbtFields front=at(world,x+DX[f],y,z+DZ[f]);int ff=facing(property(front,"facing"));
                if(stairs(front.get("Name"))&&half.equals(property(front,"half"))&&ff>=0&&(f&1)!=(ff&1)
                        &&!sameStair(world,x-DX[ff],y,z-DZ[ff],DIR[f],half)) shape=ff==((f+3)%4)?"outer_left":"outer_right";
                else {
                    SnbtFields back=at(world,x-DX[f],y,z-DZ[f]);int bf=facing(property(back,"facing"));
                    if(stairs(back.get("Name"))&&half.equals(property(back,"half"))&&bf>=0&&(f&1)!=(bf&1)
                            &&!sameStair(world,x+DX[bf],y,z+DZ[bf],DIR[f],half))shape=bf==((f+3)%4)?"inner_left":"inner_right";
                } p.put("shape",shape);
            } else if(b.endsWith("_door")&&!b.endsWith("_trapdoor")) {
                boolean upper=p.get("half").equals("upper");SnbtFields other=at(world,x,y+(upper?-1:1),z);
                if(!name.equals(other.get("Name"))||!property(other,"half").equals(upper?"lower":"upper"))throw new MissingContext();
                SnbtFields op=other.compound("Properties");
                for(String key:upper?new String[]{"facing","open"}:new String[]{"hinge","powered"})p.put(key,op.get(key));
            } else if(Arrays.asList("sunflower","lilac","tall_grass","large_fern","rose_bush","peony").contains(b)&&p.get("half").equals("upper")) {
                SnbtFields below=at(world,x,y-1,z);String base=below.get("Name");
                if(base.startsWith("minecraft:")&&Arrays.asList("sunflower","lilac","tall_grass","large_fern","rose_bush","peony").contains(base.substring(10))&&property(below,"half").equals("lower"))s.put("Name",base);
                else throw new MissingContext();
            } else if(Arrays.asList("grass_block","podzol","mycelium").contains(b)) {
                String above=at(world,x,y+1,z).get("Name");p.put("snowy",Boolean.toString(above.equals("minecraft:snow")||above.equals("minecraft:snow_block")));
            } else if(b.equals("chest")||b.equals("trapped_chest")) {
                int f=facing(p.get("facing"));if(f<0)return normalized;String type="single";int partners=0;
                for(int d:new int[]{(f+1)%4,(f+3)%4}) {
                    SnbtFields other=at(world,x+DX[d],y,z+DZ[d]);
                    if(name.equals(other.get("Name"))&&p.get("facing").equals(property(other,"facing"))) { partners++;type=d==(f+1)%4?"left":"right"; }
                }
                if(partners>1)throw new MissingContext();p.put("type",type);
            } else if(b.equals("cobblestone_wall")||b.equals("mossy_cobblestone_wall")) {
                SnbtFields above=at(world,x,y+1,z);boolean covered=world.solid(x,y+1,z);
                if(!covered&&!Arrays.asList("minecraft:air","minecraft:cave_air","minecraft:void_air").contains(above.get("Name")))throw new MissingContext();
                boolean[] connected=new boolean[4];
                for(int d=0;d<4;d++) {
                    SnbtFields other=at(world,x+DX[d],y,z+DZ[d]);String n=other.get("Name");
                    boolean gate=BlockSemantics.wood(n,"_fence_gate")&&facing(property(other,"facing"))>=0&&(facing(property(other,"facing"))&1)!=(d&1);
                    connected[d]=n.equals("minecraft:cobblestone_wall")||n.equals("minecraft:mossy_cobblestone_wall")||gate||world.solid(x+DX[d],y,z+DZ[d]);
                    p.put(DIR[d],connected[d]?(covered?"tall":"low"):"none");
                }
                // Vanilla WallBlock.shouldRaisePost, restricted to the verified air/full-cover cases above.
                boolean post=(!connected[0]&&!connected[1]&&!connected[2]&&!connected[3])||connected[0]!=connected[2]||connected[1]!=connected[3];
                p.put("up",Boolean.toString(post));
            } else if(b.endsWith("_fence")||b.endsWith("_glass_pane")||b.equals("glass_pane")||b.equals("iron_bars")) {
                for(int d=0;d<4;d++) {
                    SnbtFields other=at(world,x+DX[d],y,z+DZ[d]);String n=other.get("Name"),nb=n.startsWith("minecraft:")?n.substring(10):"";
                    boolean connected;
                    if(b.endsWith("_fence")) connected=name.equals(n)||(b.equals("nether_brick_fence")?false:BlockSemantics.wood(n,"_fence"))
                            ||(nb.endsWith("_fence_gate")&&facing(property(other,"facing"))>=0&&(facing(property(other,"facing"))&1)!=(d&1));
                    else connected=nb.equals("glass_pane")||nb.endsWith("_stained_glass_pane")||nb.equals("iron_bars");
                    // Native support queries are provided by the source adapter; unknown neighbors were rejected above.
                    connected|=world.solid(x+DX[d],y,z+DZ[d]);p.put(DIR[d],Boolean.toString(connected));
                }
            } else if(b.endsWith("_fence_gate")) {
                int f=facing(p.get("facing"));if(f>=0) {int d=(f+1)%4;
                    p.put("in_wall",Boolean.toString(at(world,x+DX[d],y,z+DZ[d]).get("Name").endsWith("_wall")||at(world,x-DX[d],y,z-DZ[d]).get("Name").endsWith("_wall")));}
            }
        } catch(MissingContext unavailable) {
            SnbtFields pending=SnbtFields.parse(normalized);pending.put("EpochLegacyVersion","epoch-context-v1");pending.put("EpochContextRequired","neighbors");return pending.toString();
        }
        if(!p.empty())s.raw("Properties",p.toString());return s.toString();
    }
    private static int distance(Lookup world,int x,int y,int z) throws IOException {
        ArrayDeque<int[]> queue=new ArrayDeque<int[]>();Set<Long> seen=new HashSet<Long>();
        queue.add(new int[]{x,y,z,0});seen.add(position(x,y,z));int missingDepth=7;
        while(!queue.isEmpty()) {
            int[] c=queue.remove();if(c[3]>=6)continue;
            for(int[] step:STEPS) {
                int nx=c[0]+step[0],ny=c[1]+step[1],nz=c[2]+step[2];long key=position(nx,ny,nz);
                if(!seen.add(key))continue;String name=world.name(nx,ny,nz);if(name==null){missingDepth=Math.min(missingDepth,c[3]+1);continue;}
                if(BlockSemantics.wood(name,"_log")||BlockSemantics.wood(name,"_wood")||BlockSemantics.wood(name,"_bark")) {
                    // Missing nearer branches could contain a closer log. Retry once the neighborhood is known.
                    if(missingDepth<c[3]+1)throw new MissingContext();return c[3]+1;
                }
                if(BlockSemantics.leaves(name))queue.add(new int[]{nx,ny,nz,c[3]+1});
            }
        }
        if(missingDepth<7)throw new MissingContext();return 7;
    }
    /** Source capture uses native, already-loaded neighbors, including across chunk borders. */
    public static void capture(Snapshot snapshot,Lookup world) throws IOException {
        world=memo(world);
        List<String> palette=new ArrayList<String>();Map<String,Integer> ids=new HashMap<String,Integer>();List<Integer> runs=new ArrayList<Integer>();
        Map<String,String> normalized=new HashMap<String,String>();Map<String,Boolean> contextual=new HashMap<String,Boolean>();
        int index=0,previous=-1,count=0;
        for(int r=0;r<snapshot.runs.length;r+=2) {
            String original=snapshot.palette.get(snapshot.runs[r]);
            if(!normalized.containsKey(original)) {
                String value=BlockSemantics.normalize(original);normalized.put(original,value);SnbtFields s=SnbtFields.parse(value);String b=s.get("Name");
                contextual.put(original,(!s.has("EpochLegacyVersion")||s.get("EpochLegacyVersion").equals("epoch-context-v1"))&&(BlockSemantics.leaves(b)||stairs(b)||b.endsWith("_door")||b.endsWith("_fence")||b.endsWith("_fence_gate")||b.endsWith("_glass_pane")||b.equals("minecraft:cobblestone_wall")||b.equals("minecraft:mossy_cobblestone_wall")
                    ||Arrays.asList("minecraft:note_block","minecraft:glass_pane","minecraft:iron_bars","minecraft:grass_block","minecraft:podzol","minecraft:mycelium","minecraft:chest","minecraft:trapped_chest","minecraft:sunflower","minecraft:lilac","minecraft:tall_grass","minecraft:large_fern","minecraft:rose_bush","minecraft:peony").contains(b)));
            }
            for(int n=0;n<snapshot.runs[r+1];n++,index++) {
                String state=contextual.get(original)?resolve(original,snapshot.blockX(index),snapshot.blockY(index),snapshot.blockZ(index),world):normalized.get(original);
                Integer id=ids.get(state);if(id==null){id=palette.size();palette.add(state);ids.put(state,id);}
                if(previous==id)count++;else {if(count>0){runs.add(previous);runs.add(count);}previous=id;count=1;}
            }
        }
        if(count>0){runs.add(previous);runs.add(count);}snapshot.palette=palette;snapshot.runs=new int[runs.size()];for(int n=0;n<runs.size();n++)snapshot.runs[n]=runs.get(n);
    }
    /** Repairs historical leaf payloads using only authority present in this import, never target terrain. */
    public static void repairLeaves(final Snapshot snapshot) throws IOException {
        repair(snapshot,false);
    }
    public static void repairPending(final Snapshot snapshot) throws IOException { repair(snapshot,true); }
    private static void repair(final Snapshot snapshot,boolean pendingOnly) throws IOException {
        boolean needed=false;
        final Set<String> eligible=new HashSet<String>();
        for(String state:snapshot.palette)if((!pendingOnly&&state.contains("check_decay"))||state.contains("EpochContextRequired")){needed=true;eligible.add(state);}
        if(!needed)return;
        final int[] cells=new int[snapshot.volume()];int cursor=0;
        for(int r=0;r<snapshot.runs.length;r+=2){Arrays.fill(cells,cursor,cursor+snapshot.runs[r+1],snapshot.runs[r]);cursor+=snapshot.runs[r+1];}
        final List<String> original=snapshot.palette;
        Lookup lookup=memo(new Lookup(){
            public String state(int x,int y,int z) {
                if(y<0||y>=256)return "{Name:\"minecraft:air\"}";
                x-=snapshot.x0;y-=snapshot.y0;z-=snapshot.z0;
                if(x<0||x>=snapshot.sizeX||y<0||y>=snapshot.sizeY||z<0||z>=snapshot.sizeZ)return null;
                return original.get(cells[(y*snapshot.sizeZ+z)*snapshot.sizeX+x]);
            }
            public boolean solid(int x,int y,int z)throws IOException{throw new MissingContext();}
        });
        Map<String,Integer> ids=new HashMap<String,Integer>();List<String> palette=new ArrayList<String>();List<Integer> runs=new ArrayList<Integer>();int previous=-1,count=0;
        for(int index=0;index<cells.length;index++) {
            String state=original.get(cells[index]);
            if(eligible.contains(state))state=resolve(state,snapshot.blockX(index),snapshot.blockY(index),snapshot.blockZ(index),lookup);
            Integer id=ids.get(state);if(id==null){id=palette.size();palette.add(state);ids.put(state,id);}
            if(id==previous)count++;else{if(count>0){runs.add(previous);runs.add(count);}previous=id;count=1;}
        }
        runs.add(previous);runs.add(count);snapshot.palette=palette;snapshot.runs=new int[runs.size()];for(int n=0;n<runs.size();n++)snapshot.runs[n]=runs.get(n);
    }
    public static ChunkSnapshot repairLeaves(ChunkSnapshot original) throws IOException {
        return repairChunk(original,false);
    }
    public static ChunkSnapshot repairPending(ChunkSnapshot original) throws IOException {return repairChunk(original,true);}
    private static ChunkSnapshot repairChunk(ChunkSnapshot original,boolean pendingOnly)throws IOException {
        Snapshot snapshot=original.blocks();repair(snapshot,pendingOnly);
        if(snapshot.palette==original.palette)return original;
        ChunkSnapshot out=new ChunkSnapshot();out.schema=original.schema;out.chunkX=original.chunkX;out.chunkZ=original.chunkZ;out.version=original.version;
        out.preservation=original.preservation;out.sourceInstance=original.sourceInstance;out.appearanceSources=original.appearanceSources;out.biomes=original.biomes;
        out.palette=snapshot.palette;out.runs=snapshot.runs;out.blockEntities=original.blockEntities;return out;
    }
}
