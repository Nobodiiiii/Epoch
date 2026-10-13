package dev.epoch;

import dev.epoch.bridge.PackedBlocks;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/** Single-player terrain dictionary. The render thread reads immutable publications only. */
final class CompactUnknowns {
    static final String TAG="EpochPackedTerrain";
    static final Block BLOCK=new PackedBlock();
    private static final Map<Chunk,PackedBlocks> DATA=Collections.synchronizedMap(new WeakHashMap<Chunk,PackedBlocks>());
    static final ConcurrentMap<Long,PackedBlocks> VISIBLE=new ConcurrentHashMap<Long,PackedBlocks>();
    static final ConcurrentMap<Long,Long> CLIENT_CHANGES=new ConcurrentHashMap<Long,Long>();
    private static final java.util.concurrent.atomic.AtomicLong CHANGES=new java.util.concurrent.atomic.AtomicLong();
    static void changed(Chunk chunk){
        if(!chunk.worldObj.isRemote||chunk.worldObj.provider.dimensionId!=0)return;
        long revision=CHANGES.incrementAndGet();int x=chunk.xPosition,z=chunk.zPosition;
        for(long key:new long[]{key(x,z),key(x-1,z),key(x+1,z),key(x,z-1),key(x,z+1)})if(VISIBLE.containsKey(key))CLIENT_CHANGES.put(key,revision);
    }
    private static Map<Chunk,Map<Integer,PackedBlocks.Entry>> batch;
    static void begin(){if(batch!=null)throw new IllegalStateException("Nested packed terrain import");batch=new IdentityHashMap<Chunk,Map<Integer,PackedBlocks.Entry>>();}
    static void end(){Map<Chunk,Map<Integer,PackedBlocks.Entry>> finished=batch;batch=null;for(Map.Entry<Chunk,Map<Integer,PackedBlocks.Entry>> entry:finished.entrySet())install(entry.getKey(),new PackedBlocks(entry.getValue()));}
    static int index(int x,int y,int z){return (y<<8)|((z&15)<<4)|(x&15);}
    static long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    static PackedBlocks data(Chunk chunk){PackedBlocks data=DATA.get(chunk);return data==null?PackedBlocks.EMPTY:data;}
    static void install(Chunk chunk,PackedBlocks data){
        DATA.put(chunk,data);if(chunk.worldObj.provider.dimensionId!=0)return;
        if(data.size()==0)VISIBLE.remove(key(chunk.xPosition,chunk.zPosition));
        else VISIBLE.put(key(chunk.xPosition,chunk.zPosition),data);
    }
    static PackedBlocks.Entry get(World world,int x,int y,int z){
        if(world.isRemote&&world.provider.dimensionId!=0)return null;
        PackedBlocks data=world.isRemote?VISIBLE.get(key(x>>4,z>>4)):data(world.getChunkFromChunkCoords(x>>4,z>>4));
        return data==null?null:data.get(index(x,y,z));
    }
    static PackedBlocks.Entry require(World world,int x,int y,int z)throws IOException{
        PackedBlocks.Entry entry=get(world,x,y,z);if(entry==null)throw new IOException("Missing packed terrain at "+x+","+y+","+z);return entry;
    }
    static void put(World world,int x,int y,int z,PackedBlocks.Entry entry){
        if(world.isRemote)return;Chunk chunk=world.getChunkFromChunkCoords(x>>4,z>>4);
        if(batch!=null){Map<Integer,PackedBlocks.Entry> edits=batch.get(chunk);if(edits==null){edits=new TreeMap<Integer,PackedBlocks.Entry>();PackedBlocks old=data(chunk);for(int n=0;n<old.size();n++)edits.put(old.position(n),old.value(n));batch.put(chunk,edits);}if(entry==null)edits.remove(index(x,y,z));else edits.put(index(x,y,z),entry);chunk.setChunkModified();return;}
        install(chunk,data(chunk).with(index(x,y,z),entry));chunk.setChunkModified();
    }
    static void save(Chunk chunk,NBTTagCompound data)throws IOException{
        PackedBlocks packed=data(chunk);if(packed.size()>0)data.setByteArray(TAG,packed.encode());else data.removeTag(TAG);
    }
    static void load(Chunk chunk,NBTTagCompound data)throws IOException{
        PackedBlocks packed=PackedBlocks.decode(data.getByteArray(TAG));
        // Migrate old per-block payloads only for explicitly supported solid, payload-free states.
        NBTTagList old=data.getTagList("TileEntities",10),keep=new NBTTagList();
        Map<Integer,PackedBlocks.Entry> migrated=null;
        for(int n=0;n<old.tagCount();n++){
            NBTTagCompound tile=old.getCompoundTagAt(n);String state=tile.getString("OriginalState");
            if("epoch:unknown_block".equals(tile.getString("id"))&&PackedBlocks.eligible(state,tile.getString("OriginalNbt"))){
                int x=tile.getInteger("x"),y=tile.getInteger("y"),z=tile.getInteger("z");
                if((x>>4)!=chunk.xPosition||(z>>4)!=chunk.zPosition||y<0||y>=256)throw new IOException("Misplaced packed tile");
                if(migrated==null){migrated=new TreeMap<Integer,PackedBlocks.Entry>();for(int i=0;i<packed.size();i++)migrated.put(packed.position(i),packed.value(i));}
                migrated.put(index(x,y,z),new PackedBlocks.Entry(state,tile.getString("SourceVersion"),tile.getString("SourceInstance")));
                net.minecraft.world.chunk.storage.ExtendedBlockStorage section=chunk.getBlockStorageArray()[y>>4];
                if(section==null)throw new IOException("Packed tile has no section");
                section.func_150818_a(x&15,y&15,z&15,BLOCK);section.setExtBlockMetadata(x&15,y&15,z&15,0);
            }else keep.appendTag(tile);
        }
        if(migrated!=null){packed=new PackedBlocks(migrated);data.setTag("TileEntities",keep);chunk.generateSkylightMap();chunk.isLightPopulated=false;chunk.setChunkModified();}
        install(chunk,packed);
    }
    static void unload(Chunk chunk){if(chunk.worldObj.provider.dimensionId!=0)return;long key=key(chunk.xPosition,chunk.zPosition);VISIBLE.remove(key,data(chunk));CLIENT_CHANGES.remove(key);}
    static void clear(){DATA.clear();VISIBLE.clear();CLIENT_CHANGES.clear();batch=null;}
    static ItemStack item(PackedBlocks.Entry entry){
        ItemStack stack=new ItemStack(Epoch.UNKNOWN);
        if(entry!=null){NBTTagCompound data=new NBTTagCompound();data.setString("OriginalState",entry.state);data.setString("OriginalNbt","");data.setString("SourceVersion",entry.version);data.setString("SourceInstance",entry.instance);data.setString("id","epoch:unknown_block");NBTTagCompound tag=new NBTTagCompound();tag.setTag("BlockEntityTag",data);stack.setTagCompound(tag);}return stack;
    }
    private static final class PackedBlock extends Block {
        PackedBlock(){super(Material.rock);setBlockName("epoch.unknown_block");setHardness(1.5f);setResistance(6);setLightOpacity(255);}
        public int getRenderType(){return -1;}
        public int getMobilityFlag(){return 2;}
        public ArrayList<ItemStack> getDrops(World world,int x,int y,int z,int meta,int fortune){return new ArrayList<ItemStack>(Collections.singletonList(item(get(world,x,y,z))));}
        public ItemStack getPickBlock(net.minecraft.util.MovingObjectPosition hit,World world,int x,int y,int z){return item(get(world,x,y,z));}
        public ItemStack getPickBlock(net.minecraft.util.MovingObjectPosition hit,World world,int x,int y,int z,net.minecraft.entity.player.EntityPlayer player){return item(get(world,x,y,z));}
        public void breakBlock(World world,int x,int y,int z,Block old,int meta){put(world,x,y,z,null);super.breakBlock(world,x,y,z,old,meta);}
        public boolean removedByPlayer(World world,net.minecraft.entity.player.EntityPlayer player,int x,int y,int z,boolean harvest){return harvest||super.removedByPlayer(world,player,x,y,z,false);}
        public void harvestBlock(World world,net.minecraft.entity.player.EntityPlayer player,int x,int y,int z,int meta){super.harvestBlock(world,player,x,y,z,meta);world.setBlockToAir(x,y,z);}
    }
}
