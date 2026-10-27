package dev.epoch;

import dev.epoch.bridge.*;
import net.minecraft.block.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.*;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.network.play.server.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.*;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.*;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import java.nio.file.*;
import java.util.*;

public final class Handoff {
 static Handoff instance;
 public Handoff(){instance=this;}
 private volatile boolean frozen,announced;
 static boolean preparing(){return AutoRuntime.managed()&&(instance==null||!instance.announced);}
 void resetWorld(){frozen=announced=false;}
 @SubscribeEvent public void serverTick(TickEvent.ServerTickEvent event){
  if(event.phase!=TickEvent.Phase.END)return;MinecraftServer server=FMLCommonHandler.instance().getMinecraftServerInstance();if(server==null||server.isDedicatedServer())return;
  if(!server.getPlayerList().getPlayers().isEmpty())try{
   Path saved=StreamingWorld.restart(server.getWorld(0));
   if(saved!=null){frozen=true;StreamingWorld.verifyImport(server.getWorld(0),saved);importWorld(server,StreamingWorld.readSnapshot(server.getWorld(0), saved));StreamingWorld.resume(server.getWorld(0));StreamingWorld.restarted(server.getWorld(0));frozen=false;ClientHooks.resumeAt=System.currentTimeMillis()+400;ClientHooks.bridge().diagnostics.put("restartRestored",true);}
  }catch(Exception error){frozen=true;ClientHooks.bridge().status("","ERROR",error.toString());}
  if(!announced&&!server.getPlayerList().getPlayers().isEmpty()){announced=true;ClientHooks.bridge().status("","READY","Integrated world ready; region x,z [-32,31], y [0,255]");}
  try{if(!frozen)StreamingWorld.tick(server.getWorld(0));}catch(Exception error){frozen=true;ClientHooks.freezeCurrent();ClientHooks.bridge().status("","ERROR",error.toString());}
  do{try{StreamingWorld.serviceRequests(server.getWorld(0));}catch(Exception error){frozen=true;ClientHooks.bridge().status("","ERROR",error.toString());}BridgeFiles.Command cmd=ClientHooks.bridge().poll();if(cmd!=null)try{handle(server,cmd);}catch(Exception error){if("export".equals(cmd.action)||"import".equals(cmd.action))frozen=true;ClientHooks.bridge().status(cmd.id,"ERROR",error.toString());error.printStackTrace();}
   if(frozen)try{drainTasks(server);server.getNetworkSystem().networkTick();Thread.sleep(10);}catch(Exception error){ClientHooks.bridge().diagnostics.put("frozenLoopError",error.toString());}
  }while(frozen&&server.isServerRunning());
 }
 private static java.lang.reflect.Field tasksField;
 static void drainTasks(MinecraftServer server)throws Exception{
  if(tasksField==null){for(String name:new String[]{"futureTaskQueue","field_175589_i"})try{tasksField=MinecraftServer.class.getDeclaredField(name);tasksField.setAccessible(true);break;}catch(NoSuchFieldException ignored){}if(tasksField==null)throw new IllegalStateException("Server task queue unavailable");}
  Queue<?> tasks=(Queue<?>)tasksField.get(server);synchronized(tasks){Object task;while((task=tasks.poll())!=null)((Runnable)task).run();}
 }
 private void handle(MinecraftServer server,BridgeFiles.Command cmd)throws Exception{
  switch(cmd.action){
   case "export":frozen=true;double[] camera=ClientHooks.captureFrozen();ClientHooks.bridge().diagnostics.put("exportInputWaitMillis",HandoffFence.drain(server,player(server).connection));StreamingWorld.flush(server.getWorld(0));Snapshot snapshot=exportWorld(server);snapshot.x=camera[0];snapshot.y=camera[1];snapshot.z=camera[2];snapshot.yaw=(float)camera[3];snapshot.pitch=(float)camera[4];StreamingWorld.exported(server.getWorld(0),snapshot,Paths.get(cmd.path));ClientHooks.bridge().status(cmd.id,"EXPORTED","World snapshot committed; simulation frozen");break;
   case "import":frozen=true;ClientHooks.freezeCurrent();StreamingWorld.verifyImport(server.getWorld(0),Paths.get(cmd.path));importWorld(server,StreamingWorld.readSnapshot(server.getWorld(0), Paths.get(cmd.path)));ClientHooks.markImported(cmd.id);break;
   case "freeze":frozen=true;ClientHooks.freezeCurrent();ClientHooks.bridge().status(cmd.id,"FROZEN","Simulation frozen");break;
   case "recoverresume":
        case "resume":StreamingWorld.resume(server.getWorld(0));frozen=false;ClientHooks.resumeAt=System.currentTimeMillis()+400;ClientHooks.bridge().status(cmd.id,"READY","Simulation resumed");break;
   case "catalog":ClientHooks.catalog();ClientHooks.bridge().status(cmd.id,"CATALOG","Instance catalog available in diagnostics");break;
   case "select":ClientHooks.select(cmd.path);ClientHooks.bridge().status(cmd.id,"SELECTED","Target instance requested");break;
   case "usekey":ClientHooks.bridge().status(cmd.id,"KEY_TRIGGERED","Invoking actual Epoch Key item use on client");ClientHooks.useKey(cmd.id);break;
   case "reloadrules":
          CompatibilityRules.reload(CompatibilityRules.directory());
          ClientHooks.bridge().status(cmd.id, "RULES_RELOADED", "Compatibility rules published");
          break;
        case "registry":LegacyRegistry.catalog(LegacyRegistry.mods()).write(Paths.get(cmd.path));ClientHooks.bridge().status(cmd.id,"REGISTRY_EXPORTED","Registry catalog written");break;
  case "auditstates":RegistryCatalog.writeJson(Paths.get(cmd.path+".audit-"+Epoch.VERSION+".json"),LegacyRegistry.audit(RegistryCatalog.read(Paths.get(cmd.path)),LegacyRegistry.mods()));ClientHooks.bridge().status(cmd.id,"STATES_AUDITED","State audit written");break;
  case "leaveworld":frozen=false;Minecraft.getMinecraft().addScheduledTask(() -> { Minecraft.getMinecraft().loadWorld(null); Minecraft.getMinecraft().displayGuiScreen(new net.minecraft.client.gui.GuiMainMenu()); });ClientHooks.bridge().status(cmd.id,"WORLD_LEFT","World session exit requested");break;case "shutdown":StreamingWorld.flush(server.getWorld(0));frozen=false;Minecraft.getMinecraft().addScheduledTask(()->Minecraft.getMinecraft().shutdown());break;
   default:throw new IllegalArgumentException("Unknown action "+cmd.action);
  }
 }
 private EntityPlayerMP player(MinecraftServer server){if(server.getPlayerList().getPlayers().size()!=1)throw new IllegalStateException("Epoch requires exactly one player");EntityPlayerMP p=server.getPlayerList().getPlayers().get(0);if(p.dimension!=0)throw new IllegalStateException("Epoch supports the Overworld only");int limit=StreamingWorld.enabled(server.getWorld(0))?30000000:32;if(p.posX< -limit||p.posX>=limit||p.posZ< -limit||p.posZ>=limit||p.posY<0||p.posY>=256)throw new IllegalStateException("Outside Epoch shared region [-32,32), y [0,256)");return p;}
 Snapshot exportWorld(MinecraftServer server) throws Exception { return exportWorld(server, player(server)); }
 Snapshot exportWorld(MinecraftServer server, EntityPlayerMP p) throws Exception {WorldServer world=server.getWorld(0);Snapshot s=new Snapshot();s.version=Epoch.VERSION;s.sourceInstance = dev.epoch.bridge.Appearance.instance();s.x=p.posX;s.y=p.posY;s.z=p.posZ;s.yaw=p.rotationYaw;s.pitch=p.rotationPitch;s.gameTime=world.getTotalWorldTime();s.dayTime=world.getWorldTime();s.inventory=LegacyItems.encodeList(p.inventory.writeToNBT(new NBTTagList())).toString();s.selectedSlot=p.inventory.currentItem;StreamingWorld.configureSnapshot(world,s,p.posX,p.posZ);EnvironmentTransfer.capture(server,world,p,s);
    if (StreamingWorld.reuseTerrain(world, s)) return s;
  Map<String,Integer> ids=new HashMap<String,Integer>();Map<IBlockState,String> encoded=new IdentityHashMap<IBlockState,String>();List<Integer> runs=new ArrayList<Integer>();int previous=-1,count=0;
  for(int y=0;y<s.sizeY;y++)for(int z=0;z<s.sizeZ;z++)for(int x=0;x<s.sizeX;x++){
   int index=(y*s.sizeZ+z)*s.sizeX+x;BlockPos pos=new BlockPos(x+s.x0,y+s.y0,z+s.z0);IBlockState state=world.getBlockState(pos);TileEntity be=state.getBlock().hasTileEntity(state)?world.getTileEntity(pos):null;String snbt=encoded.get(state);if(snbt==null){snbt=LegacyStates.encode(state);encoded.put(state,snbt);}String payload=be==null?null:LegacyItems.encodePayload(be.writeToNBT(new NBTTagCompound())).toString();
   if(be instanceof Epoch.UnknownTile&&!((Epoch.UnknownTile)be).originalState.isEmpty()){Epoch.UnknownTile unknown=(Epoch.UnknownTile)be;snbt=unknown.originalState;s.appearanceSources.put(Integer.toString(index), unknown.sourceInstance);payload=unknown.originalNbt.isEmpty()?null:unknown.originalNbt;}
   if(payload!=null)s.blockEntities.put(Integer.toString(index),payload);Integer id=ids.get(snbt);if(id==null){id=s.palette.size();ids.put(snbt,id);s.palette.add(snbt);}if(id==previous)count++;else{if(count>0){runs.add(previous);runs.add(count);}previous=id;count=1;}
  }if(count>0){runs.add(previous);runs.add(count);}s.runs=new int[runs.size()];for(int n=0;n<runs.size();n++)s.runs[n]=runs.get(n);LegacyContext.capture(s,world); dev.epoch.bridge.Appearance.request(s.palette); return s;
 }
 private void importWorld(MinecraftServer server,Snapshot s)throws Exception{
  s.validateColumns(); BlockPayloads.project(s, Epoch.VERSION); BlockContext.repairPending(s);
  EntityPlayerMP p=player(server);WorldServer world=server.getWorld(0);EnvironmentTransfer.Prepared environment=EnvironmentTransfer.prepare(server,world,p,s);IBlockState[] palette=new IBlockState[s.palette.size()];for(int n=0;n<palette.length;n++)palette[n]=LegacyStates.decode(s.palette.get(n));
  Map<Integer,NBTTagCompound> parsed=new HashMap<Integer,NBTTagCompound>();for(Map.Entry<String,String> entry:s.blockEntities.entrySet())parsed.put(Integer.parseInt(entry.getKey()),Snbt.compound(entry.getValue()));
  NBTTagCompound inventoryTag=Snbt.compound("{Inventory:"+(s.inventory==null||s.inventory.isEmpty()?"[]":s.inventory)+"}");if(inventoryTag.getTag("Inventory").getId()!=9)throw new IllegalArgumentException("Inventory must be a list");NBTTagList inventory=LegacyItems.decodePlayerInventory((NBTTagList)inventoryTag.getTag("Inventory"));
  Map<Integer,TileEntity> prepared=new HashMap<Integer,TileEntity>();PayloadDiagnostics payloadReport=new PayloadDiagnostics();Set<Integer> incompatible=new HashSet<Integer>();int planned=0;
  for(int r=0;r<s.runs.length;r+=2){IBlockState state=palette[s.runs[r]];for(int k=0;k<s.runs[r+1];k++,planned++){NBTTagCompound payload=parsed.get(planned);if(payload==null||state==null)continue;try{
   if(!state.getBlock().hasTileEntity(state))throw new IllegalArgumentException("Block cannot hold NBT");TileEntity fresh=state.getBlock().createTileEntity(world,state);ResourceLocation type=fresh==null?null:TileEntity.getKey(fresh.getClass());if(type==null||!type.toString().equals(payload.getString("id")))throw new IllegalArgumentException("Incompatible block entity type");
   // Older containers cannot represent items introduced after this version.
   
   NBTTagCompound copy=payload.copy();BlockPos pos=position(s,planned);copy.setInteger("x",pos.getX());copy.setInteger("y",pos.getY());copy.setInteger("z",pos.getZ());fresh.readFromNBT(LegacyItems.decodePayload(copy));if(!preserves(copy,LegacyItems.encodePayload(fresh.writeToNBT(new NBTTagCompound()))))throw new IllegalArgumentException("Block entity NBT would lose data");prepared.put(planned,fresh);
  }catch(Exception bad){incompatible.add(planned);payloadReport.reject(planned,s.palette.get(s.runs[r]),bad);}}}
  ClientHooks.freezeAt(s.x,s.y,s.z,s.yaw,s.pitch);ClientHooks.bridge().diagnostics.put("blockEntityCompatibility",payloadReport.snapshot());int index=0,unknowns=0;
  for(int r=0;r<s.runs.length;r+=2){int id=s.runs[r];for(int k=0;k<s.runs[r+1];k++,index++){BlockPos pos=position(s,index);IBlockState state=palette[id];boolean unknown=state==null||incompatible.contains(index);IBlockState placed=unknown?Epoch.UNKNOWN.getDefaultState():state;String payload=s.blockEntities.get(Integer.toString(index));
   if(world.getBlockState(pos)!=placed){TileEntity previous=world.getTileEntity(pos);if(previous instanceof IInventory)((IInventory)previous).clear();world.setBlockState(pos,placed,2|16);}
   TileEntity be=null;if(placed.getBlock().hasTileEntity(placed)){be=prepared.get(index);if(unknown||be==null)be=placed.getBlock().createTileEntity(world,placed);if(be==null)throw new IllegalStateException("Tile factory failed at "+pos);world.removeTileEntity(pos);world.setTileEntity(pos,be);}
   if(unknown){unknowns++;Epoch.UnknownTile tile=(Epoch.UnknownTile)be;tile.originalState=s.originalState(index);tile.originalNbt=s.originalPayload(index);tile.sourceVersion=s.version;tile.sourceInstance = s.appearanceSource(index);tile.markDirty();}
   else if(be!=null){if(payload!=null){NBTTagCompound tag=parsed.get(index).copy();tag.setInteger("x",pos.getX());tag.setInteger("y",pos.getY());tag.setInteger("z",pos.getZ());be.readFromNBT(LegacyItems.decodePayload(tag));}else if(be instanceof IInventory)((IInventory)be).clear();be.markDirty();}
  }}
  // onBlockAdded can rotate a chest while its neighbours are still being imported.
  // Once every block exists, restore the requested states without replacing tile entities.
  int finalIndex=0,correctedStates=0;
  for(int r=0;r<s.runs.length;r+=2){IBlockState state=palette[s.runs[r]];for(int k=0;k<s.runs[r+1];k++,finalIndex++){
   IBlockState desired=state==null||incompatible.contains(finalIndex)?Epoch.UNKNOWN.getDefaultState():state;BlockPos pos=position(s,finalIndex);IBlockState actual=world.getBlockState(pos);
   if(actual!=desired){if(actual.getBlock()!=desired.getBlock())throw new IllegalStateException("A placement callback replaced the imported block at "+pos);world.setBlockState(pos,desired,2|16);correctedStates++;}
  }}
  ClientHooks.bridge().diagnostics.put("correctedPlacementStates",correctedStates);
  world.setWorldTime(s.dayTime);world.setTotalWorldTime(s.gameTime);ClientHooks.freezeAt(s.x,s.y,s.z,s.yaw,s.pitch);p.connection.setPlayerLocation(s.x,s.y,s.z,s.yaw,s.pitch);p.inventory.readFromNBT(inventory);p.inventory.currentItem=s.selectedSlot;p.sendContainerToPlayer(p.inventoryContainer);p.connection.sendPacket(new SPacketHeldItemChange(s.selectedSlot));p.connection.sendPacket(new SPacketTimeUpdate(s.gameTime,s.dayTime,world.getGameRules().getBoolean("doDaylightCycle")));ClientHooks.bridge().diagnostics.put("unknownBlocks",unknowns);EnvironmentTransfer.apply(server,world,p,s,environment);sendChunks(world,p,s);
 }
 /** Extra defaults are harmless; every source field and list element must survive. */
 static boolean preserves(NBTBase source,NBTBase actual){
  if(source==null||actual==null||source.getId()!=actual.getId())return false;
  if(source instanceof NBTTagCompound){NBTTagCompound left=(NBTTagCompound)source,right=(NBTTagCompound)actual;for(String key:left.getKeySet())if(!preserves(left.getTag(key),right.getTag(key)))return false;return true;}
  if(source instanceof NBTTagList){NBTTagList left=(NBTTagList)source,right=(NBTTagList)actual;if(left.tagCount()!=right.tagCount())return false;for(int n=0;n<left.tagCount();n++)if(!preserves(left.get(n),right.get(n)))return false;return true;}
  return source.equals(actual);
 }
 private static BlockPos position(Snapshot s,int index){return new BlockPos(s.blockX(index),s.blockY(index),s.blockZ(index));}
 private static void sendChunks(WorldServer world,EntityPlayerMP p){sendChunks(world,p,new Snapshot());}
 private static void sendChunks(WorldServer world,EntityPlayerMP p,Snapshot s){for(int z=s.minChunkZ();z<=s.maxChunkZ();z++)for(int x=s.minChunkX();x<=s.maxChunkX();x++){net.minecraft.world.chunk.Chunk chunk=world.getChunk(x,z);chunk.generateSkylightMap();chunk.markDirty();p.connection.sendPacket(new SPacketChunkData(chunk,65535));}}

}


