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
 private boolean frozen,announced;
 @SubscribeEvent public void serverTick(TickEvent.ServerTickEvent event){
  if(event.phase!=TickEvent.Phase.END)return;MinecraftServer server=FMLCommonHandler.instance().getMinecraftServerInstance();if(server==null||server.isDedicatedServer())return;
  if(!announced&&!server.getPlayerList().getPlayers().isEmpty()){announced=true;ClientHooks.bridge().status("","READY","Integrated world ready; region x,z [-32,31], y [0,255]");}
  do{BridgeFiles.Command cmd=ClientHooks.bridge().poll();if(cmd!=null)try{handle(server,cmd);}catch(Exception error){if("export".equals(cmd.action)||"import".equals(cmd.action))frozen=true;ClientHooks.bridge().status(cmd.id,"ERROR",error.toString());error.printStackTrace();}
   if(frozen)try{drainTasks(server);server.getNetworkSystem().networkTick();Thread.sleep(10);}catch(Exception error){ClientHooks.bridge().diagnostics.put("frozenLoopError",error.toString());}
  }while(frozen&&server.isServerRunning());
 }
 private static java.lang.reflect.Field tasksField;
 private static void drainTasks(MinecraftServer server)throws Exception{
  if(tasksField==null){for(String name:new String[]{"futureTaskQueue","field_175589_i"})try{tasksField=MinecraftServer.class.getDeclaredField(name);tasksField.setAccessible(true);break;}catch(NoSuchFieldException ignored){}if(tasksField==null)throw new IllegalStateException("Server task queue unavailable");}
  Queue<?> tasks=(Queue<?>)tasksField.get(server);synchronized(tasks){Object task;while((task=tasks.poll())!=null)((Runnable)task).run();}
 }
 private void handle(MinecraftServer server,BridgeFiles.Command cmd)throws Exception{
  switch(cmd.action){
   case "export":frozen=true;double[] camera=ClientHooks.captureFrozen();Snapshot snapshot=exportWorld(server);snapshot.x=camera[0];snapshot.y=camera[1];snapshot.z=camera[2];snapshot.yaw=(float)camera[3];snapshot.pitch=(float)camera[4];snapshot.write(Paths.get(cmd.path));ClientHooks.bridge().status(cmd.id,"EXPORTED","World snapshot committed; simulation frozen");break;
   case "import":frozen=true;ClientHooks.freezeCurrent();importWorld(server,Snapshot.read(Paths.get(cmd.path)));ClientHooks.markImported(cmd.id);break;
   case "freeze":frozen=true;ClientHooks.freezeCurrent();ClientHooks.bridge().status(cmd.id,"FROZEN","Simulation frozen");break;
   case "resume":frozen=false;ClientHooks.resumeAt=System.currentTimeMillis()+400;ClientHooks.bridge().status(cmd.id,"READY","Simulation resumed");break;
   case "catalog":ClientHooks.catalog();ClientHooks.bridge().status(cmd.id,"CATALOG","Instance catalog available in diagnostics");break;
   case "select":ClientHooks.select(cmd.path);ClientHooks.bridge().status(cmd.id,"SELECTED","Target instance requested");break;
   case "usekey":ClientHooks.bridge().status(cmd.id,"KEY_TRIGGERED","Invoking actual Epoch Key item use on client");ClientHooks.useKey(cmd.id);break;
   case "testsetup":testsetup(server);ClientHooks.bridge().status(cmd.id,"READY","Lab prepared");break;
   case "testedit":server.getWorld(0).destroyBlock(new BlockPos(0,64,3),false);server.getWorld(0).setBlockState(new BlockPos(1,64,3),Blocks.DIRT.getDefaultState(),2|16);sendChunks(server.getWorld(0),player(server));probe(server);ClientHooks.bridge().status(cmd.id,frozen?"FROZEN":"READY","Deleted placeholder and replaced stone with dirt");break;
   case "registry":LegacyRegistry.catalog(LegacyRegistry.mods()).write(Paths.get(cmd.path));ClientHooks.bridge().status(cmd.id,"REGISTRY_EXPORTED","Registry catalog written");break;
  case "auditstates":RegistryCatalog.writeJson(Paths.get(cmd.path+".audit-"+Epoch.VERSION+".json"),LegacyRegistry.audit(RegistryCatalog.read(Paths.get(cmd.path)),LegacyRegistry.mods()));ClientHooks.bridge().status(cmd.id,"STATES_AUDITED","State audit written");break;
  case "probe":probe(server);ClientHooks.bridge().status(cmd.id,"PROBED","Probe available in diagnostics");break;
   case "shutdown":frozen=false;Minecraft.getMinecraft().addScheduledTask(()->Minecraft.getMinecraft().shutdown());break;
   default:throw new IllegalArgumentException("Unknown action "+cmd.action);
  }
 }
 private EntityPlayerMP player(MinecraftServer server){if(server.getPlayerList().getPlayers().size()!=1)throw new IllegalStateException("Epoch requires exactly one player");EntityPlayerMP p=server.getPlayerList().getPlayers().get(0);if(p.dimension!=0)throw new IllegalStateException("Epoch supports the Overworld only");if(p.posX< -32||p.posX>=32||p.posZ< -32||p.posZ>=32||p.posY<0||p.posY>=256)throw new IllegalStateException("Outside Epoch shared region [-32,32), y [0,256)");return p;}
 private Snapshot exportWorld(MinecraftServer server){EntityPlayerMP p=player(server);WorldServer world=server.getWorld(0);Snapshot s=new Snapshot();s.version=Epoch.VERSION;s.x=p.posX;s.y=p.posY;s.z=p.posZ;s.yaw=p.rotationYaw;s.pitch=p.rotationPitch;s.gameTime=world.getTotalWorldTime();s.dayTime=world.getWorldTime();s.inventory=LegacyItems.encodeList(p.inventory.writeToNBT(new NBTTagList())).toString();s.selectedSlot=p.inventory.currentItem;
  Map<String,Integer> ids=new HashMap<String,Integer>();Map<IBlockState,String> encoded=new IdentityHashMap<IBlockState,String>();List<Integer> runs=new ArrayList<Integer>();int previous=-1,count=0;
  for(int y=0;y<s.sizeY;y++)for(int z=0;z<s.sizeZ;z++)for(int x=0;x<s.sizeX;x++){
   int index=(y*s.sizeZ+z)*s.sizeX+x;BlockPos pos=new BlockPos(x+s.x0,y+s.y0,z+s.z0);IBlockState state=world.getBlockState(pos);TileEntity be=state.getBlock().hasTileEntity(state)?world.getTileEntity(pos):null;String snbt=encoded.get(state);if(snbt==null){snbt=LegacyStates.encode(state);encoded.put(state,snbt);}String payload=be==null?null:LegacyItems.encodePayload(be.writeToNBT(new NBTTagCompound())).toString();
   if(be instanceof Epoch.UnknownTile&&!((Epoch.UnknownTile)be).originalState.isEmpty()){Epoch.UnknownTile unknown=(Epoch.UnknownTile)be;snbt=unknown.originalState;payload=unknown.originalNbt.isEmpty()?null:unknown.originalNbt;}
   if(payload!=null)s.blockEntities.put(Integer.toString(index),payload);Integer id=ids.get(snbt);if(id==null){id=s.palette.size();ids.put(snbt,id);s.palette.add(snbt);}if(id==previous)count++;else{if(count>0){runs.add(previous);runs.add(count);}previous=id;count=1;}
  }if(count>0){runs.add(previous);runs.add(count);}s.runs=new int[runs.size()];for(int n=0;n<runs.size();n++)s.runs[n]=runs.get(n);return s;
 }
 private void importWorld(MinecraftServer server,Snapshot s)throws Exception{
  if(s.schema!=1||s.x0!=-32||s.z0!=-32||s.y0!=0||s.sizeX!=64||s.sizeZ!=64||s.sizeY!=256)throw new IllegalArgumentException("Unsupported snapshot bounds/schema");
  EntityPlayerMP p=player(server);WorldServer world=server.getWorld(0);IBlockState[] palette=new IBlockState[s.palette.size()];for(int n=0;n<palette.length;n++)palette[n]=LegacyStates.decode(s.palette.get(n));
  Map<Integer,NBTTagCompound> parsed=new HashMap<Integer,NBTTagCompound>();for(Map.Entry<String,String> entry:s.blockEntities.entrySet())parsed.put(Integer.parseInt(entry.getKey()),Snbt.compound(entry.getValue()));
  NBTTagCompound inventoryTag=Snbt.compound("{Inventory:"+(s.inventory==null||s.inventory.isEmpty()?"[]":s.inventory)+"}");if(inventoryTag.getTag("Inventory").getId()!=9)throw new IllegalArgumentException("Inventory must be a list");NBTTagList inventory=LegacyItems.decodePlayerInventory((NBTTagList)inventoryTag.getTag("Inventory"));
  Map<Integer,TileEntity> prepared=new HashMap<Integer,TileEntity>();PayloadDiagnostics payloadReport=new PayloadDiagnostics();Set<Integer> incompatible=new HashSet<Integer>();int planned=0;
  for(int r=0;r<s.runs.length;r+=2){IBlockState state=palette[s.runs[r]];for(int k=0;k<s.runs[r+1];k++,planned++){NBTTagCompound payload=parsed.get(planned);if(payload==null||state==null)continue;try{
   if(!state.getBlock().hasTileEntity(state))throw new IllegalArgumentException("Block cannot hold NBT");TileEntity fresh=state.getBlock().createTileEntity(world,state);ResourceLocation type=fresh==null?null:TileEntity.getKey(fresh.getClass());if(type==null||!type.toString().equals(payload.getString("id")))throw new IllegalArgumentException("Incompatible block entity type");
   // Older containers cannot represent items introduced after this version.
   
   NBTTagCompound copy=payload.copy();BlockPos pos=position(planned);copy.setInteger("x",pos.getX());copy.setInteger("y",pos.getY());copy.setInteger("z",pos.getZ());fresh.readFromNBT(LegacyItems.decodePayload(copy));if(!preserves(copy,LegacyItems.encodePayload(fresh.writeToNBT(new NBTTagCompound()))))throw new IllegalArgumentException("Block entity NBT would lose data");prepared.put(planned,fresh);
  }catch(Exception bad){incompatible.add(planned);payloadReport.reject(planned,s.palette.get(s.runs[r]),bad);}}}
  ClientHooks.freezeAt(s.x,s.y,s.z,s.yaw,s.pitch);ClientHooks.bridge().diagnostics.put("blockEntityCompatibility",payloadReport.snapshot());int index=0,unknowns=0;
  for(int r=0;r<s.runs.length;r+=2){int id=s.runs[r];for(int k=0;k<s.runs[r+1];k++,index++){BlockPos pos=position(index);IBlockState state=palette[id];boolean unknown=state==null||incompatible.contains(index);IBlockState placed=unknown?Epoch.UNKNOWN.getDefaultState():state;String payload=s.blockEntities.get(Integer.toString(index));
   if(world.getBlockState(pos)!=placed){TileEntity previous=world.getTileEntity(pos);if(previous instanceof IInventory)((IInventory)previous).clear();world.setBlockState(pos,placed,2|16);}
   TileEntity be=null;if(placed.getBlock().hasTileEntity(placed)){be=prepared.get(index);if(unknown||be==null)be=placed.getBlock().createTileEntity(world,placed);if(be==null)throw new IllegalStateException("Tile factory failed at "+pos);world.removeTileEntity(pos);world.setTileEntity(pos,be);}
   if(unknown){unknowns++;Epoch.UnknownTile tile=(Epoch.UnknownTile)be;tile.originalState=s.palette.get(id);tile.originalNbt=payload==null?"":payload;tile.sourceVersion=s.version;tile.markDirty();}
   else if(be!=null){if(payload!=null){NBTTagCompound tag=parsed.get(index).copy();tag.setInteger("x",pos.getX());tag.setInteger("y",pos.getY());tag.setInteger("z",pos.getZ());be.readFromNBT(LegacyItems.decodePayload(tag));}else if(be instanceof IInventory)((IInventory)be).clear();be.markDirty();}
  }}
  // onBlockAdded can rotate a chest while its neighbours are still being imported.
  // Once every block exists, restore the requested states without replacing tile entities.
  int finalIndex=0,correctedStates=0;
  for(int r=0;r<s.runs.length;r+=2){IBlockState state=palette[s.runs[r]];for(int k=0;k<s.runs[r+1];k++,finalIndex++){
   IBlockState desired=state==null||incompatible.contains(finalIndex)?Epoch.UNKNOWN.getDefaultState():state;BlockPos pos=position(finalIndex);IBlockState actual=world.getBlockState(pos);
   if(actual!=desired){if(actual.getBlock()!=desired.getBlock())throw new IllegalStateException("A placement callback replaced the imported block at "+pos);world.setBlockState(pos,desired,2|16);correctedStates++;}
  }}
  ClientHooks.bridge().diagnostics.put("correctedPlacementStates",correctedStates);
  world.setWorldTime(s.dayTime);world.setTotalWorldTime(s.gameTime);ClientHooks.freezeAt(s.x,s.y,s.z,s.yaw,s.pitch);p.connection.setPlayerLocation(s.x,s.y,s.z,s.yaw,s.pitch);p.inventory.readFromNBT(inventory);p.inventory.currentItem=s.selectedSlot;p.sendContainerToPlayer(p.inventoryContainer);p.connection.sendPacket(new SPacketHeldItemChange(s.selectedSlot));p.connection.sendPacket(new SPacketTimeUpdate(s.gameTime,s.dayTime,world.getGameRules().getBoolean("doDaylightCycle")));ClientHooks.bridge().diagnostics.put("unknownBlocks",unknowns);sendChunks(world,p);
 }
 /** Extra defaults are harmless; every source field and list element must survive. */
 static boolean preserves(NBTBase source,NBTBase actual){
  if(source==null||actual==null||source.getId()!=actual.getId())return false;
  if(source instanceof NBTTagCompound){NBTTagCompound left=(NBTTagCompound)source,right=(NBTTagCompound)actual;for(String key:left.getKeySet())if(!preserves(left.getTag(key),right.getTag(key)))return false;return true;}
  if(source instanceof NBTTagList){NBTTagList left=(NBTTagList)source,right=(NBTTagList)actual;if(left.tagCount()!=right.tagCount())return false;for(int n=0;n<left.tagCount();n++)if(!preserves(left.get(n),right.get(n)))return false;return true;}
  return source.equals(actual);
 }
 private static BlockPos position(int index){return new BlockPos(index%64-32,index/(64*64),(index/64)%64-32);}
 private static void sendChunks(WorldServer world,EntityPlayerMP p){for(int z=-2;z<2;z++)for(int x=-2;x<2;x++){net.minecraft.world.chunk.Chunk chunk=world.getChunk(x,z);chunk.generateSkylightMap();chunk.markDirty();p.connection.sendPacket(new SPacketChunkData(chunk,65535));}}
 private void testsetup(MinecraftServer server){WorldServer world=server.getWorld(0);for(int z=-12;z<=12;z++)for(int x=-12;x<=12;x++){world.setBlockState(new BlockPos(x,63,z),Blocks.GRASS.getDefaultState(),2);for(int y=64;y<=72;y++)world.setBlockState(new BlockPos(x,y,z),Blocks.AIR.getDefaultState(),2);}world.setBlockState(new BlockPos(0,64,3),Blocks.PLANKS.getDefaultState(),2);world.setBlockState(new BlockPos(1,64,3),Blocks.STONE.getDefaultState(),2);world.setBlockState(new BlockPos(-1,64,3),Blocks.CHEST.getDefaultState(),2);TileEntity be=world.getTileEntity(new BlockPos(-1,64,3));if(be instanceof TileEntityChest){((TileEntityChest)be).setInventorySlotContents(0,new ItemStack(Items.DIAMOND,7).setStackDisplayName("Epoch NBT sentinel"));be.markDirty();}EntityPlayerMP p=server.getPlayerList().getPlayers().get(0);p.connection.setPlayerLocation(0.5,64,-3.5,0,10);p.setGameType(GameType.CREATIVE);p.inventory.setInventorySlotContents(0,new ItemStack(Epoch.KEY));world.setWorldTime(6000);world.getGameRules().setOrCreateGameRule("doDaylightCycle","false");world.getGameRules().setOrCreateGameRule("doWeatherCycle","false");world.getGameRules().setOrCreateGameRule("randomTickSpeed","0");p.sendContainerToPlayer(p.inventoryContainer);}
 private void probe(MinecraftServer server){WorldServer world=server.getWorld(0);for(int x=-1;x<=2;x++){BlockPos pos=new BlockPos(x,64,3);ClientHooks.bridge().diagnostics.put("block_"+x,LegacyStates.encode(world.getBlockState(pos)));TileEntity be=world.getTileEntity(pos);if(be!=null)ClientHooks.bridge().diagnostics.put("nbt_"+x,be.writeToNBT(new NBTTagCompound()).toString());}EntityPlayerMP p=server.getPlayerList().getPlayers().get(0);ClientHooks.bridge().diagnostics.put("camera",Arrays.asList(p.posX,p.posY,p.posZ,p.rotationYaw,p.rotationPitch));long items=0;for(net.minecraft.entity.Entity entity:world.loadedEntityList)if(entity instanceof net.minecraft.entity.item.EntityItem)items++;ClientHooks.bridge().diagnostics.put("itemEntityCount",items);}
}


