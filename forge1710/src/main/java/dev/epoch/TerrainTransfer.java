package dev.epoch;

import dev.epoch.bridge.*;
import java.io.IOException;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/** Terrain conversion for 1.7's numeric block IDs and metadata. Caller owns the server thread. */
final class TerrainTransfer {
    private static final Map<Integer,String> STATE_NAMES = new HashMap<>();
    private static synchronized String stateName(Block block, int metadata) throws IOException {
        int key = (Block.getIdFromBlock(block) << 4) | metadata;
        String name = STATE_NAMES.get(key);
        if (name == null) {
            name = LegacyStates.encode(block, metadata);
            if (STATE_NAMES.size() >= 4096) STATE_NAMES.clear();
            STATE_NAMES.put(key, name);
        }
        return name;
    }

    static final class Prepared {
        final ChunkSnapshot source;
        final LegacyStates.State[] cells;
        final Map<ChunkPosition,TileEntity> entities;
        final String[] appliedBiomes;
        final Map<String,BiomeGenBase> biomes;
        Prepared(ChunkSnapshot source, LegacyStates.State[] cells, Map<ChunkPosition,TileEntity> entities,
                String[] appliedBiomes, Map<String,BiomeGenBase> biomes) {
            this.source=source;this.cells=cells;this.entities=entities;this.appliedBiomes=appliedBiomes;this.biomes=biomes;
        }
        WorldExtras.Carry residual() {
            WorldExtras.Carry carry=new WorldExtras.Carry();carry.bounds(source.blocks());
            carry.original=source.biomes;carry.applied=WorldExtras.Biomes.of(appliedBiomes);return carry;
        }
    }

    static ChunkSnapshot capture(WorldServer world, Chunk chunk, WorldExtras.Carry residual) throws Exception {
        ChunkSnapshot result=new ChunkSnapshot();result.version=Epoch.VERSION;result.chunkX=chunk.xPosition;result.chunkZ=chunk.zPosition;
        Map<Integer,String> states=new HashMap<>();Map<String,Integer> palette=new HashMap<>();List<Integer> runs=new ArrayList<>();
        int previous=-1,count=0;
        for(int index=0;index<ChunkSnapshot.VOLUME;index++){
            int y=index>>8,z=(index>>4)&15,x=index&15;
            // Missing storage is ordinary air; retain all stored block IDs and metadata.
            if((index&4095)==0 && chunk.getBlockStorageArray()[y>>4]==null){
                String air=stateName(net.minecraft.init.Blocks.air,0);
                Integer id=palette.get(air);if(id==null){id=palette.size();palette.put(air,id);result.palette.add(air);}
                if(id==previous)count+=4096;else{if(count>0){runs.add(previous);runs.add(count);}previous=id;count=4096;}
                index+=4095;continue;
            }
            Block block=chunk.getBlock(x,y,z);int meta=chunk.getBlockMetadata(x,y,z);
            int key=(Block.getIdFromBlock(block)<<4)|meta;String state=states.get(key);
            if(state==null){state=stateName(block,meta);states.put(key,state);}
            TileEntity tile=block.hasTileEntity(meta)?chunk.func_150806_e(x,y,z):null;String payload=null;
            if(tile instanceof Epoch.UnknownTile&&!((Epoch.UnknownTile)tile).originalState.isEmpty()){
                Epoch.UnknownTile unknown=(Epoch.UnknownTile)tile;
                state=unknown.originalState;payload=unknown.originalNbt.isEmpty()?null:unknown.originalNbt;}
            else if(tile!=null){NBTTagCompound tag=new NBTTagCompound();tile.writeToNBT(tag);LegacyNbt.Tag canonical=LegacyNbt.from(tag);Handoff.convertTile(canonical,true);payload=LegacyNbt.encode(canonical);}
            if(payload!=null)result.blockEntities.put(Integer.toString(index),payload);
            Integer id=palette.get(state);if(id==null){id=palette.size();palette.put(state,id);result.palette.add(state);}
            if(id==previous)count++;else{if(count>0){runs.add(previous);runs.add(count);}previous=id;count=1;}
        }
        runs.add(previous);runs.add(count);result.runs=new int[runs.size()];for(int n=0;n<runs.size();n++)result.runs[n]=runs.get(n);
        String[] biomes=new String[16384];
        for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            String biome=EnvironmentTransfer.biomeName(chunk.getBiomeGenForWorldCoords(x,z,world.getWorldChunkManager()));
            for(int y=0;y<64;y++)biomes[(y*16+z)*16+x]=biome;
        }
        result.biomes=WorldExtras.Biomes.of(residual==null?biomes:residual.restore(result.blocks(),biomes));result.validate();return result;
    }

    static Prepared prepare(WorldServer world, ChunkSnapshot source) throws Exception {
        source.validate();Snapshot bounds=source.blocks();LegacyStates.State[] palette=new LegacyStates.State[source.palette.size()];
        for(int n=0;n<palette.length;n++)palette[n]=LegacyStates.decode(source.palette.get(n));
        LegacyStates.State[] cells=new LegacyStates.State[ChunkSnapshot.VOLUME];Map<ChunkPosition,TileEntity> entities=new HashMap<>();int index=0;
        for(int r=0;r<source.runs.length;r+=2)for(int n=0;n<source.runs[r+1];n++,index++){
            int id=source.runs[r],x=bounds.blockX(index),y=bounds.blockY(index),z=bounds.blockZ(index);
            LegacyStates.State state=palette[id];String raw=source.blockEntities.get(Integer.toString(index));TileEntity tile=null;boolean unknown=state==null;
            if(!unknown)try{
                if(state.block.hasTileEntity(state.meta)){tile=state.block.createTileEntity(world,state.meta);if(tile==null)throw new IOException("Missing tile factory");}
                if(raw!=null){
                    if(tile==null)throw new IOException("Payload on non-container block");
                    LegacyNbt.Tag expected=LegacyNbt.parse(raw);
                    LegacyNbt.map(expected).put("x",new LegacyNbt.Tag(3,x));LegacyNbt.map(expected).put("y",new LegacyNbt.Tag(3,y));LegacyNbt.map(expected).put("z",new LegacyNbt.Tag(3,z));
                    LegacyNbt.Tag nativeValue=LegacyNbt.parse(LegacyNbt.encode(expected));Handoff.convertTile(nativeValue,false);
                    NBTTagCompound data=LegacyNbt.nativeTag(nativeValue),identity=new NBTTagCompound();tile.writeToNBT(identity);
                    if(!identity.getString("id").equals(data.getString("id")))throw new IOException("Tile type mismatch");
                    tile.readFromNBT(data);NBTTagCompound serialized=new NBTTagCompound();tile.writeToNBT(serialized);
                    LegacyNbt.Tag roundtrip=LegacyNbt.from(serialized);Handoff.convertTile(roundtrip,true);
                    if(!LegacyNbt.preserves(expected,roundtrip))throw new IOException("Tile payload would lose data");
                }
            }catch(Exception incompatible){unknown=true;}
            if(unknown){state=new LegacyStates.State(Epoch.UNKNOWN,0);Epoch.UnknownTile placeholder=new Epoch.UnknownTile();
                placeholder.originalState=source.palette.get(id);placeholder.originalNbt=raw==null?"":raw;placeholder.sourceVersion=source.version;tile=placeholder;}
            cells[index]=state;
            if(tile!=null){tile.xCoord=x;tile.yCoord=y;tile.zCoord=z;entities.put(new ChunkPosition(x,y,z),tile);}
        }
        Map<String,BiomeGenBase> biomes=EnvironmentTransfer.registry();
        String[] applied=BiomeCompatibility.project(source.biomes.expand(16384),16,64,16,false,biomes.keySet());
        return new Prepared(source,cells,entities,applied,biomes);
    }

    static void apply(WorldServer world, Chunk chunk, Prepared prepared) throws Exception {
        if(chunk.xPosition!=prepared.source.chunkX||chunk.zPosition!=prepared.source.chunkZ)throw new IOException("Prepared chunk coordinates differ");
        for(Object value:new ArrayList<Object>(chunk.chunkTileEntityMap.values())){
            TileEntity tile=(TileEntity)value;chunk.removeTileEntity(tile.xCoord&15,tile.yCoord,tile.zCoord&15);tile.invalidate();
        }
        chunk.chunkTileEntityMap.clear();
        for(int y=0;y<256;y++){
            ExtendedBlockStorage section=chunk.getBlockStorageArray()[y>>4];
            if(section==null){section=new ExtendedBlockStorage(y&~15,!world.provider.hasNoSky);chunk.getBlockStorageArray()[y>>4]=section;}
            for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                LegacyStates.State state=prepared.cells[(y*16+z)*16+x];section.func_150818_a(x,y&15,z,state.block);section.setExtBlockMetadata(x,y&15,z,state.meta);
            }
        }
        for(TileEntity tile:prepared.entities.values())chunk.addTileEntity(tile);
        byte[] nativeBiomes=chunk.getBiomeArray();for(int n=0;n<256;n++)nativeBiomes[n]=(byte)prepared.biomes.get(prepared.appliedBiomes[n]).biomeID;
        chunk.generateSkylightMap();chunk.isLightPopulated=false;chunk.setChunkModified();
    }
}
