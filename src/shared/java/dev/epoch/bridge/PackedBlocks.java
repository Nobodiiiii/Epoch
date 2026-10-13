package dev.epoch.bridge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable position/palette storage for ordinary solid unknown terrain. No per-block NBT objects. */
public final class PackedBlocks {
    public static final PackedBlocks EMPTY = new PackedBlocks(new TreeMap<Integer,Entry>());
    private static final Set<String> SOLIDS = new HashSet<String>(Arrays.asList(
        "andesite", "diorite", "granite", "polished_andesite", "polished_diorite", "polished_granite",
        "deepslate", "tuff", "calcite", "dripstone_block", "amethyst_block", "budding_amethyst",
        "copper_ore", "deepslate_copper_ore", "deepslate_iron_ore", "deepslate_gold_ore",
        "deepslate_coal_ore", "deepslate_redstone_ore", "deepslate_lapis_ore", "deepslate_diamond_ore",
        "deepslate_emerald_ore", "basalt", "smooth_basalt", "polished_basalt", "blackstone", "magma_block"));
    private static final Map<String,Boolean> ELIGIBLE = new LinkedHashMap<String,Boolean>(128,.75f,true) {
        protected boolean removeEldestEntry(Map.Entry<String,Boolean> entry){return size()>1024;}
    };
    public static synchronized boolean eligible(String state, String payload) {
        if (payload != null && !payload.isEmpty()) return false;
        Boolean known=ELIGIBLE.get(state);if(known!=null)return known;
        try { String name = SnbtFields.parse(state).get("Name"); boolean result=name.startsWith("minecraft:") && SOLIDS.contains(name.substring(10));ELIGIBLE.put(state,result);return result; }
        catch (Exception invalid) { return false; }
    }
    public static final class Entry {
        public final String state, version, instance;
        public Entry(String state, String version, String instance) {
            if (state == null || version == null || instance == null) throw new IllegalArgumentException("Missing packed block identity");
            this.state=state; this.version=version; this.instance=instance;
        }
        public boolean equals(Object other) {
            if (!(other instanceof Entry)) return false; Entry e=(Entry)other;
            return state.equals(e.state) && version.equals(e.version) && instance.equals(e.instance);
        }
        public int hashCode() { return Objects.hash(state,version,instance); }
    }
    private final int[] positions;
    private final Entry[] values;
    private int sections;
    public PackedBlocks(Map<Integer,Entry> blocks) {
        TreeMap<Integer,Entry> ordered = new TreeMap<Integer,Entry>(blocks);
        positions=new int[ordered.size()]; values=new Entry[ordered.size()];
        Map<Entry,Entry> palette=new HashMap<Entry,Entry>(); int n=0;
        for (Map.Entry<Integer,Entry> block : ordered.entrySet()) {
            if (block.getKey()<0 || block.getKey()>=65536 || block.getValue()==null) throw new IllegalArgumentException("Invalid packed block position");
            Entry value=block.getValue(), shared=palette.get(value);
            if(shared==null){shared=value;palette.put(value,value);}
            positions[n]=block.getKey();values[n++]=shared;
            sections |= 1 << (block.getKey() >> 12);
        }
    }
    public int size(){return positions.length;}
    public int sections(){return sections;}
    public int position(int n){return positions[n];}
    public Entry value(int n){return values[n];}
    public int first(int index){int n=Arrays.binarySearch(positions,index);return n<0?-n-1:n;}
    public Entry get(int index){int n=Arrays.binarySearch(positions,index);return n<0?null:values[n];}
    public PackedBlocks with(int index,Entry value){
        Map<Integer,Entry> map=new TreeMap<Integer,Entry>();
        for(int n=0;n<positions.length;n++)if(positions[n]!=index)map.put(positions[n],values[n]);
        if(value!=null)map.put(index,value);return new PackedBlocks(map);
    }
    public byte[] encode() throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        Map<Entry,Integer> palette=new LinkedHashMap<Entry,Integer>();
        for(Entry entry:values)if(!palette.containsKey(entry))palette.put(entry,palette.size());
        out.writeInt(1);out.writeInt(palette.size());
        for(Entry entry:palette.keySet()){string(out,entry.state);string(out,entry.version);string(out,entry.instance);}
        out.writeInt(positions.length);
        for(int n=0;n<positions.length;n++){out.writeShort(positions[n]);out.writeShort(palette.get(values[n]));}
        out.flush();return bytes.toByteArray();
    }
    public static PackedBlocks decode(byte[] bytes) throws IOException {
        if(bytes.length==0)return EMPTY;
        if(bytes.length>8*1024*1024)throw new IOException("Oversized packed terrain");
        DataInputStream input=new DataInputStream(new ByteArrayInputStream(bytes));
        if(input.readInt()!=1)throw new IOException("Unsupported packed terrain schema");
        int size=input.readInt();if(size<0||size>65536)throw new IOException("Invalid packed palette");
        Entry[] palette=new Entry[size];
        for(int n=0;n<size;n++){
            palette[n]=new Entry(string(input),string(input),string(input));
            if(!eligible(palette[n].state,""))throw new IOException("Unsupported packed terrain state");
        }
        int count=input.readInt();if(count<0||count>65536)throw new IOException("Invalid packed terrain count");
        Map<Integer,Entry> map=new TreeMap<Integer,Entry>();int previous=-1;
        for(int n=0;n<count;n++){
            int position=input.readUnsignedShort(),id=input.readUnsignedShort();
            if(position<=previous||id>=size)throw new IOException("Invalid packed terrain index");
            map.put(position,palette[id]);previous=position;
        }
        if(input.available()!=0)throw new IOException("Trailing packed terrain data");
        return new PackedBlocks(map);
    }
    private static void string(DataOutputStream out,String value)throws IOException{
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);if(bytes.length>65536)throw new IOException("Oversized packed identity");out.writeInt(bytes.length);out.write(bytes);
    }
    private static String string(DataInputStream input)throws IOException{
        int length=input.readInt();if(length<0||length>65536||length>input.available())throw new IOException("Invalid packed identity");byte[] bytes=new byte[length];input.readFully(bytes);return new String(bytes,StandardCharsets.UTF_8);
    }
}
