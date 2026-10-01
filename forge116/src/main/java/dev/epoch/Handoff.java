package dev.epoch;
import dev.epoch.bridge.*;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.block.*;
import net.minecraft.tileentity.*;
import net.minecraft.nbt.*;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.World;
import net.minecraft.item.*;
import net.minecraft.state.Property;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.nio.file.*;
import java.util.*;
public class Handoff {
private boolean frozen,announced;
 @SubscribeEvent public void commands(net.minecraftforge.event.RegisterCommandsEvent e){e.getDispatcher().register(net.minecraft.command.Commands.literal("epoch").then(net.minecraft.command.Commands.literal("versions").executes(context->{ClientHooks.request();return 1;})));}

 @SubscribeEvent public void serverTick(TickEvent.ServerTickEvent e){
  if(e.phase!=TickEvent.Phase.END)return;
  MinecraftServer server=net.minecraftforge.fml.server.ServerLifecycleHooks.getCurrentServer();
  if(server==null || server.isDedicatedServer())return;
  if(!announced && !server.getPlayerList().getPlayers().isEmpty()){announced=true;ClientHooks.bridge().status("","READY","Integrated world ready; region x,z [-32,31], y [0,255]");}
  do {
   BridgeFiles.Command cmd=ClientHooks.bridge().poll();
   if(cmd!=null)try{handle(server,cmd);}catch(Exception error){if("export".equals(cmd.action)||"import".equals(cmd.action))frozen=true;ClientHooks.bridge().status(cmd.id,"ERROR",error.toString());error.printStackTrace();}
   if(frozen)try{while(server.pollTask()){}while(server.overworld().getChunkSource().pollTask()){}server.getConnection().tick();Thread.sleep(10);}catch(Exception error){ClientHooks.bridge().diagnostics.put("frozenLoopError",error.toString());}
  }while(frozen && server.isRunning());
 }
 void handle(MinecraftServer server,BridgeFiles.Command cmd)throws Exception{try{switch(cmd.action){
  case "export": frozen=true;double[] camera=ClientHooks.captureFrozen();Snapshot snapshot=exportWorld(server);snapshot.x=camera[0];snapshot.y=camera[1];snapshot.z=camera[2];snapshot.yaw=(float)camera[3];snapshot.pitch=(float)camera[4];snapshot.write(Paths.get(cmd.path));ClientHooks.bridge().status(cmd.id,"EXPORTED","World snapshot committed; simulation frozen");break;
  case "import": frozen=true;ClientHooks.freezeCurrent();importWorld(server,Snapshot.read(Paths.get(cmd.path)));ClientHooks.markImported(cmd.id);break;
  case "freeze":frozen=true;ClientHooks.freezeCurrent();ClientHooks.bridge().status(cmd.id,"FROZEN","Simulation frozen");break;
  case "resume":frozen=false;ClientHooks.resumeAt=System.currentTimeMillis()+400;ClientHooks.bridge().status(cmd.id,"READY","Simulation resumed");break;
  case "catalog":ClientHooks.catalog();ClientHooks.bridge().status(cmd.id,"CATALOG","Instance catalog available in diagnostics");break;
  case "select":ClientHooks.select(cmd.path);ClientHooks.bridge().status(cmd.id,"SELECTED","Target instance requested");break;
  case "usekey":ClientHooks.bridge().status(cmd.id,"KEY_TRIGGERED","Invoking actual Epoch Key item use on client");ClientHooks.useKey(cmd.id);break;
  case "testedit":testedit(server);ClientHooks.bridge().status(cmd.id,frozen?"FROZEN":"READY","Deleted placeholder (0,64,3) without drop; stone (1,64,3) replaced with dirt");break;
  case "testsetup":testsetup(server);ClientHooks.bridge().status(cmd.id,"READY","Lab prepared");break;
  case "probe":probe(server);ClientHooks.bridge().status(cmd.id,"PROBED","Probe available in diagnostics");break;
  case "shutdown":frozen=false;Minecraft.getInstance().execute(()->Minecraft.getInstance().stop());break;
  default:throw new IllegalArgumentException("Unknown action "+cmd.action);
 }}catch(Exception ex){ClientHooks.bridge().status(cmd.id,"ERROR",ex.toString());throw ex;}}
 ServerPlayerEntity player(MinecraftServer server){if(server.getPlayerList().getPlayers().size()!=1)throw new IllegalStateException("Epoch v0.1 requires exactly one player");ServerPlayerEntity p=server.getPlayerList().getPlayers().get(0);if(p.level.dimension()!=World.OVERWORLD)throw new IllegalStateException("Epoch v0.1 supports the Overworld only");if(p.getX()< -32||p.getX()>=32||p.getZ()< -32||p.getZ()>=32||p.getY()<0||p.getY()>=256)throw new IllegalStateException("Outside Epoch v0.1 shared region [-32,32), y [0,256)");return p;}
 Snapshot exportWorld(MinecraftServer server){ServerPlayerEntity p=player(server);ServerWorld level=server.overworld();Snapshot s=new Snapshot();s.version=Epoch.VERSION;s.x=p.getX();s.y=p.getY();s.z=p.getZ();s.yaw=p.yRot;s.pitch=p.xRot;s.gameTime=level.getGameTime();s.dayTime=level.getDayTime();s.inventory=p.inventory.save(new ListNBT()).toString();s.selectedSlot=p.inventory.selected;
  Map<String,Integer> ids=new HashMap<>();Map<BlockState,String> encodedStates=new IdentityHashMap<>();List<Integer> runs=new ArrayList<>();int previous=-1,count=0;
  for(int y=0;y<s.sizeY;y++)for(int z=0;z<s.sizeZ;z++)for(int x=0;x<s.sizeX;x++){
   int index=(y*s.sizeZ+z)*s.sizeX+x;BlockPos pos=new BlockPos(x+s.x0,y+s.y0,z+s.z0);BlockState state=level.getBlockState(pos);TileEntity be=state.hasTileEntity()?level.getBlockEntity(pos):null;String snbt=encodedStates.get(state);if(snbt==null){snbt=NBTUtil.writeBlockState(state).toString();encodedStates.put(state,snbt);}String beNbt=be==null?null:be.save(new CompoundNBT()).toString();
   if(be instanceof Epoch.UnknownTile && !((Epoch.UnknownTile)be).originalState.isEmpty()){Epoch.UnknownTile unknown=(Epoch.UnknownTile)be;snbt=unknown.originalState;beNbt=unknown.originalNbt.isEmpty()?null:unknown.originalNbt;}
   if(beNbt!=null)s.blockEntities.put(Integer.toString(index),beNbt);Integer id=ids.get(snbt);if(id==null){id=s.palette.size();ids.put(snbt,id);s.palette.add(snbt);}if(id==previous)count++;else {if(count>0){runs.add(previous);runs.add(count);}previous=id;count=1;}
  }if(count>0){runs.add(previous);runs.add(count);}s.runs=runs.stream().mapToInt(Integer::intValue).toArray();return s;
 }
 @SuppressWarnings({"rawtypes","unchecked"}) BlockState decode(String text)throws Exception{CompoundNBT tag=JsonToNBT.parseTag(text);if(tag.contains("EpochLegacyVersion"))return null;ResourceLocation id=new ResourceLocation(tag.getString("Name"));if(!ForgeRegistries.BLOCKS.containsKey(id))return null;BlockState state=ForgeRegistries.BLOCKS.getValue(id).defaultBlockState();CompoundNBT properties=tag.getCompound("Properties");for(String key:properties.getAllKeys()){Property prop=state.getBlock().getStateDefinition().getProperty(key);if(prop==null)return null;Optional value=prop.getValue(properties.getString(key));if(!value.isPresent())return null;state=state.setValue(prop,(Comparable)value.get());}return state;}
 void importWorld(MinecraftServer server,Snapshot s)throws Exception{if(s.schema!=1||s.x0!=-32||s.z0!=-32||s.y0!=0||s.sizeX!=64||s.sizeZ!=64||s.sizeY!=256)throw new IllegalArgumentException("Unsupported snapshot bounds/schema");ServerPlayerEntity p=player(server);ServerWorld level=server.overworld();BlockState[] palette=new BlockState[s.palette.size()];for(int n=0;n<palette.length;n++)palette[n]=decode(s.palette.get(n));Map<Integer,CompoundNBT> parsedNbt=new HashMap<>();
  for(Map.Entry<String,String> entry:s.blockEntities.entrySet())parsedNbt.put(Integer.parseInt(entry.getKey()),JsonToNBT.parseTag(entry.getValue()));
  ListNBT inventory=new ListNBT();
  if(s.inventory!=null&&!s.inventory.isEmpty()){INBT parsedInventory=new JsonToNBT(new com.mojang.brigadier.StringReader(s.inventory)).readValue();if(!(parsedInventory instanceof ListNBT))throw new IllegalArgumentException("Inventory must be an NBT list");inventory=(ListNBT)parsedInventory;}
  validateInventory(inventory);
  Map<Integer,TileEntity> preparedEntities=new HashMap<>();
  Set<Integer> incompatibleEntities=new HashSet<>();
  int plannedIndex=0;
  for(int r=0;r<s.runs.length;r+=2){
   BlockState state=palette[s.runs[r]];
   for(int k=0;k<s.runs[r+1];k++,plannedIndex++){
    CompoundNBT payload=parsedNbt.get(plannedIndex);
    if(payload==null||state==null)continue;
    BlockPos pos=new BlockPos(plannedIndex%64-32,plannedIndex/(64*64),(plannedIndex/64)%64-32);
    try{
     if(!state.hasTileEntity())throw new IllegalArgumentException("Block cannot hold NBT");
     TileEntity fresh=state.createTileEntity(level);
     if(fresh==null||!ForgeRegistries.TILE_ENTITIES.getKey(fresh.getType()).toString().equals(payload.getString("id")))throw new IllegalArgumentException("Incompatible block entity type");
     CompoundNBT copy=payload.copy();copy.putInt("x",pos.getX());copy.putInt("y",pos.getY());copy.putInt("z",pos.getZ());fresh.load(state,copy.copy());if(!preservesNbt(copy,fresh.save(new CompoundNBT())))throw new IllegalArgumentException("Block entity would lose NBT");preparedEntities.put(plannedIndex,fresh);
    }catch(Exception incompatible){incompatibleEntities.add(plannedIndex);}
   }
  }
  ClientHooks.freezeAt(s.x,s.y,s.z,s.yaw,s.pitch);
  int index=0,unknowns=0;for(int r=0;r<s.runs.length;r+=2){int id=s.runs[r];if(id<0||id>=palette.length||s.runs[r+1]<1)throw new IllegalArgumentException("Invalid runs");for(int k=0;k<s.runs[r+1];k++,index++){if(index>=64*64*256)throw new IllegalArgumentException("Snapshot overflow");BlockPos pos=new BlockPos(index%64-32,index/(64*64),(index/64)%64-32);String payload=s.blockEntities.get(Integer.toString(index));BlockState state=palette[id];boolean unknown=state==null||incompatibleEntities.contains(index);BlockState placed=unknown?Epoch.UNKNOWN.get().defaultBlockState():state;if(level.getBlockState(pos)!=placed){
   // Replacement invokes the old block's onRemove, which otherwise spills stale contents.
   TileEntity previous=level.getBlockEntity(pos);
   if(previous instanceof net.minecraft.inventory.IClearable)((net.minecraft.inventory.IClearable)previous).clearContent();
   level.setBlock(pos,placed,2|16);
  }
  TileEntity be=null;
  if(placed.hasTileEntity()){
   // Fresh instances prevent omitted NBT fields from retaining target-world values.
   be=preparedEntities.get(index);if(unknown||be==null)be=placed.createTileEntity(level);
   if(be==null)throw new IllegalStateException("Block entity factory failed at "+pos);
   level.removeBlockEntity(pos);level.setBlockEntity(pos,be);
  }
  if(unknown){unknowns++;if(be instanceof Epoch.UnknownTile){Epoch.UnknownTile u=(Epoch.UnknownTile)be;u.originalState=s.palette.get(id);u.originalNbt=payload==null?"":payload;u.sourceVersion=s.version;u.setChanged();}}else if(be!=null && payload!=null){CompoundNBT tag=parsedNbt.get(index).copy();tag.putInt("x",pos.getX());tag.putInt("y",pos.getY());tag.putInt("z",pos.getZ());be.load(placed,tag);be.setChanged();}else if(be instanceof net.minecraft.inventory.IClearable)((net.minecraft.inventory.IClearable)be).clearContent();}}
  if(index!=64*64*256)throw new IllegalArgumentException("Snapshot incomplete");level.setDayTime(s.dayTime);((net.minecraft.world.storage.IServerWorldInfo)level.getLevelData()).setGameTime(s.gameTime);ClientHooks.freezeAt(s.x,s.y,s.z,s.yaw,s.pitch);p.teleportTo(level,s.x,s.y,s.z,s.yaw,s.pitch);p.inventory.load(inventory);p.inventory.selected=s.selectedSlot;p.inventoryMenu.broadcastChanges();p.connection.send(new net.minecraft.network.play.server.SHeldItemChangePacket(s.selectedSlot));p.connection.send(new net.minecraft.network.play.server.SUpdateTimePacket(s.gameTime,s.dayTime,level.getGameRules().getBoolean(net.minecraft.world.GameRules.RULE_DAYLIGHT)));ClientHooks.bridge().diagnostics.put("unknownBlocks",unknowns);
  net.minecraft.world.server.ServerWorldLightManager lights=level.getChunkSource().getLightEngine();
  java.util.List<java.util.concurrent.CompletableFuture<?>> lighting=new java.util.ArrayList<>();
  for(int cz=-2;cz<2;cz++)for(int cx=-2;cx<2;cx++)lighting.add(lights.lightChunk(level.getChunk(cx,cz),true));
  long deadline=System.currentTimeMillis()+30000;
  while(lighting.stream().anyMatch(f->!f.isDone())){lights.tryScheduleUpdate();while(level.getChunkSource().pollTask()){}if(System.currentTimeMillis()>deadline)throw new IllegalStateException("Lighting did not finish");Thread.sleep(1);}
  for(java.util.concurrent.CompletableFuture<?> future:lighting)future.join();
  for(int cz=-2;cz<2;cz++)for(int cx=-2;cx<2;cx++){net.minecraft.world.chunk.Chunk chunk=level.getChunk(cx,cz);p.connection.send(new net.minecraft.network.play.server.SChunkDataPacket(chunk,65535));p.connection.send(new net.minecraft.network.play.server.SUpdateLightPacket(chunk.getPos(),lights,true));}

 }
  static void validateInventory(ListNBT inventory) {
    InventorySlots slots = new InventorySlots(true);
    for (INBT element : inventory) {
      if (!(element instanceof CompoundNBT))
        throw new IllegalArgumentException("Inventory entries must be compounds");
      CompoundNBT expected = ((CompoundNBT) element).copy();
      if (!expected.contains("Slot", 1))
        throw new IllegalArgumentException("Player inventory Slot must be a byte");
      slots.claim(expected.getByte("Slot"));
      expected.remove("Slot"); // Inventory handles the location independently of ItemStack NBT.
      ItemStack decoded = ItemStack.of(expected.copy());
      CompoundNBT restored = decoded.save(new CompoundNBT());
      if (!preservesNbt(expected, restored))
        throw new IllegalArgumentException(
            "Inventory item cannot be preserved in " + Epoch.VERSION + ": " + expected.getString("id"));
    }
  }

  static boolean preservesNbt(INBT expected, INBT actual) {
    if (actual == null) return false;
    if (expected instanceof CompoundNBT) {
      if (!(actual instanceof CompoundNBT)) return false;
      CompoundNBT wanted = (CompoundNBT) expected, got = (CompoundNBT) actual;
      for (String key : wanted.getAllKeys())
        if (!preservesNbt(wanted.get(key), got.get(key))) return false;
      return true;
    }
    if (expected instanceof ListNBT) {
      if (!(actual instanceof ListNBT)) return false;
      ListNBT wanted = (ListNBT) expected, got = (ListNBT) actual;
      if (wanted.size() != got.size()) return false;
      for (int index = 0; index < wanted.size(); index++)
        if (!preservesNbt(wanted.get(index), got.get(index))) return false;
      return true;
    }
    if (expected instanceof NumberNBT && actual instanceof NumberNBT) {
      try {
        return new java.math.BigDecimal(((NumberNBT) expected).getAsNumber().toString())
            .compareTo(new java.math.BigDecimal(((NumberNBT) actual).getAsNumber().toString())) == 0;
      } catch (NumberFormatException invalid) {
        return expected.equals(actual);
      }
    }
    return expected.equals(actual);
  }


 void testedit(MinecraftServer server){
  ServerWorld level=server.overworld();
  level.destroyBlock(new BlockPos(0,64,3),false);
  level.setBlock(new BlockPos(1,64,3),Blocks.DIRT.defaultBlockState(),2|16);
  ServerPlayerEntity player=player(server);
  // Flush changed chunks immediately, including when the server is held between ticks.
  player.connection.send(new net.minecraft.network.play.server.SChunkDataPacket(level.getChunk(0,0),65535));
  probe(server);
 }
 void testsetup(MinecraftServer server){ServerWorld level=server.overworld();for(int z=-12;z<=12;z++)for(int x=-12;x<=12;x++){level.setBlock(new BlockPos(x,63,z),Blocks.GRASS_BLOCK.defaultBlockState(),2);for(int y=64;y<=72;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);}level.setBlock(new BlockPos(0,64,3),Blocks.OAK_PLANKS.defaultBlockState(),2);level.setBlock(new BlockPos(1,64,3),Blocks.STONE.defaultBlockState(),2);level.setBlock(new BlockPos(-1,64,3),Blocks.CHEST.defaultBlockState(),2);if(level.getBlockEntity(new BlockPos(-1,64,3)) instanceof ChestTileEntity){ChestTileEntity chest=(ChestTileEntity)level.getBlockEntity(new BlockPos(-1,64,3));ItemStack sentinel=new ItemStack(Items.DIAMOND,7);sentinel.setHoverName(new StringTextComponent("Epoch NBT sentinel"));chest.setItem(0,sentinel);chest.setChanged();}ServerPlayerEntity p=server.getPlayerList().getPlayers().get(0);p.teleportTo(level,0.5,64,-3.5,0,10);p.setGameMode(net.minecraft.world.GameType.CREATIVE);p.inventory.setItem(0,new ItemStack(Epoch.KEY.get()));level.setDayTime(6000);level.getGameRules().getRule(net.minecraft.world.GameRules.RULE_DAYLIGHT).set(false,server);level.getGameRules().getRule(net.minecraft.world.GameRules.RULE_WEATHER_CYCLE).set(false,server);level.getGameRules().getRule(net.minecraft.world.GameRules.RULE_RANDOMTICKING).tryDeserialize("0");}
 void probe(MinecraftServer server){ServerWorld level=server.overworld();for(int x=-1;x<=2;x++){BlockPos pos=new BlockPos(x,64,3);ClientHooks.bridge().diagnostics.put("block_"+x,NBTUtil.writeBlockState(level.getBlockState(pos)).toString());TileEntity be=level.getBlockEntity(pos);if(be!=null)ClientHooks.bridge().diagnostics.put("nbt_"+x,be.save(new CompoundNBT()).toString());}ServerPlayerEntity p=server.getPlayerList().getPlayers().get(0);ClientHooks.bridge().diagnostics.put("camera",Arrays.asList(p.getX(),p.getY(),p.getZ(),p.yRot,p.xRot));long items=0;for(net.minecraft.entity.Entity entity:level.getAllEntities())if(entity instanceof net.minecraft.entity.item.ItemEntity)items++;ClientHooks.bridge().diagnostics.put("itemEntityCount",items);}
}

