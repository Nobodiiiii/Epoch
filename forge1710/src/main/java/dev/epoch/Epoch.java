package dev.epoch;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

@Mod(modid="epoch",name="Epoch",version="0.3.0",acceptedMinecraftVersions="[1.7.10]",acceptableRemoteVersions="*")
public final class Epoch {
 public static final String VERSION="1.7.10";
 public static final Block UNKNOWN=new UnknownBlock();
 public static final Item UNAVAILABLE_ITEM=new Item().setUnlocalizedName("epoch.unavailable_item").setTextureName("epoch:unknown_block");
 public static final Item KEY=new Item(){
  private long last,useCount;
  @Override public ItemStack onItemRightClick(ItemStack stack,World world,EntityPlayer player){
   if(world.isRemote&&System.currentTimeMillis()-last>1000){last=System.currentTimeMillis();ClientHooks.bridge().diagnostics.put("phaseKeyUseCount",++useCount);ClientHooks.request();}
   return stack;
  }
 }.setUnlocalizedName("epoch.phase_key").setTextureName("epoch:phase_key").setMaxStackSize(1).setCreativeTab(CreativeTabs.tabTools);
 @Mod.EventHandler public void preInit(FMLPreInitializationEvent event){
  HandoffFence.register();
  DestinationLoader.register(this);
  GameRegistry.registerBlock(CompactUnknowns.BLOCK,"packed_unknown");
  GameRegistry.registerBlock(UNKNOWN,"unknown_block");GameRegistry.registerTileEntity(UnknownTile.class,"epoch:unknown_block");GameRegistry.registerItem(KEY,"phase_key");GameRegistry.registerItem(UNAVAILABLE_ITEM,"unavailable_item");net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new StreamingWorld());
 }
 @Mod.EventHandler public void init(FMLInitializationEvent event){
  GameRegistry.addShapelessRecipe(new ItemStack(KEY),Items.clock,Items.ender_pearl);
  if(event.getSide().isClient()){AppearanceClient.init();FMLCommonHandler.instance().bus().register(new ClientHooks());FMLCommonHandler.instance().bus().register(new Handoff());}
 }
 @Mod.EventHandler public void starting(cpw.mods.fml.common.event.FMLServerStartingEvent event){event.registerServerCommand(new net.minecraft.command.CommandBase(){
  public String getCommandName(){return "epoch";}
  public String getCommandUsage(net.minecraft.command.ICommandSender sender){return "/epoch versions | travel x y z | travel cancel";}
  public int getRequiredPermissionLevel(){return 0;}
  public void processCommand(net.minecraft.command.ICommandSender sender,String[] args)throws net.minecraft.command.CommandException{
   if(args.length==0||"versions".equals(args[0])){ClientHooks.request();return;}
   if(!"travel".equals(args[0])||!sender.canCommandSenderUseCommand(2,"epoch"))throw new net.minecraft.command.CommandException("commands.generic.permission");
   net.minecraft.entity.player.EntityPlayerMP player=getCommandSenderAsPlayer(sender);
   if(args.length==2&&"cancel".equals(args[1])){StreamingWorld.cancelTravel(player);return;}
   if(args.length!=4)throw new net.minecraft.command.WrongUsageException(getCommandUsage(sender));
   try{StreamingWorld.travel(player,func_110666_a(sender,player.posX,args[1]),func_110666_a(sender,player.posY,args[2]),func_110666_a(sender,player.posZ,args[3]));}
   catch(Exception error){throw new net.minecraft.command.CommandException(error.getMessage());}
  }
 });}

 @Mod.EventHandler public void stopping(cpw.mods.fml.common.event.FMLServerStoppingEvent event)throws Exception{StreamingWorld.stopping();}
 @Mod.EventHandler public void stopped(cpw.mods.fml.common.event.FMLServerStoppedEvent event){StreamingWorld.stopped();}
 public static final class UnknownBlock extends BlockContainer {
  public UnknownBlock(){super(Material.rock);setBlockName("epoch.unknown_block");setBlockTextureName("epoch:unknown_block");setHardness(1.5f);setResistance(6);setCreativeTab(CreativeTabs.tabBlock);}
  @Override public int getRenderType(){return -1;}
  @Override public boolean isOpaqueCube(){return false;}
  @Override public boolean renderAsNormalBlock(){return false;}
  @Override public TileEntity createNewTileEntity(World world,int meta){return new UnknownTile();}
  private ItemStack copiedStack(World world,int x,int y,int z){ItemStack stack=new ItemStack(this);TileEntity tile=world.getTileEntity(x,y,z);if(tile instanceof UnknownTile)((UnknownTile)tile).copyToItem(stack);return stack;}
  @Override public java.util.ArrayList<ItemStack> getDrops(World world,int x,int y,int z,int meta,int fortune){java.util.ArrayList<ItemStack> drops=new java.util.ArrayList<ItemStack>();drops.add(copiedStack(world,x,y,z));return drops;}
  @Override public ItemStack getPickBlock(net.minecraft.util.MovingObjectPosition target,World world,int x,int y,int z,EntityPlayer player){return copiedStack(world,x,y,z);}
  @Override public ItemStack getPickBlock(net.minecraft.util.MovingObjectPosition target,World world,int x,int y,int z){return copiedStack(world,x,y,z);}
  @Override public boolean removedByPlayer(World world,EntityPlayer player,int x,int y,int z,boolean willHarvest){return willHarvest||super.removedByPlayer(world,player,x,y,z,false);}
  @Override public void harvestBlock(World world,EntityPlayer player,int x,int y,int z,int meta){super.harvestBlock(world,player,x,y,z,meta);world.setBlockToAir(x,y,z);}
  @Override public void onBlockPlacedBy(World world,int x,int y,int z,net.minecraft.entity.EntityLivingBase placer,ItemStack stack){TileEntity tile=world.getTileEntity(x,y,z);if(tile instanceof UnknownTile){((UnknownTile)tile).readFromItem(stack);tile.markDirty();world.markBlockForUpdate(x,y,z);}}
 }
 public static final class UnknownTile extends TileEntity {
  public String originalState="",originalNbt="",sourceVersion="",sourceInstance="";
  // Payload-only tiles must stay out of 1.7's tick list: unloading dense
  // transferred terrain otherwise triggers quadratic ArrayList.removeAll work.
  @Override public boolean canUpdate(){return false;}
  @Override public void writeToNBT(NBTTagCompound tag){super.writeToNBT(tag);tag.setString("OriginalState",originalState);tag.setString("OriginalNbt",originalNbt);tag.setString("SourceVersion",sourceVersion);tag.setString("SourceInstance",sourceInstance);}
  @Override public void readFromNBT(NBTTagCompound tag){super.readFromNBT(tag);originalState=tag.getString("OriginalState");originalNbt=tag.getString("OriginalNbt");sourceVersion=tag.getString("SourceVersion");sourceInstance=tag.getString("SourceInstance");}
  void copyToItem(ItemStack stack){NBTTagCompound payload=new NBTTagCompound();payload.setString("OriginalState",originalState);payload.setString("OriginalNbt",originalNbt);payload.setString("SourceVersion",sourceVersion);payload.setString("SourceInstance",sourceInstance);payload.setString("id","epoch:unknown_block");if(!stack.hasTagCompound())stack.setTagCompound(new NBTTagCompound());stack.getTagCompound().setTag("BlockEntityTag",payload);}
  void readFromItem(ItemStack stack){if(!stack.hasTagCompound()||!stack.getTagCompound().hasKey("BlockEntityTag",10))return;NBTTagCompound payload=stack.getTagCompound().getCompoundTag("BlockEntityTag");originalState=payload.getString("OriginalState");originalNbt=payload.getString("OriginalNbt");sourceVersion=payload.getString("SourceVersion");sourceInstance=payload.getString("SourceInstance");}
  @Override public net.minecraft.network.Packet getDescriptionPacket(){NBTTagCompound tag=new NBTTagCompound();writeToNBT(tag);return new net.minecraft.network.play.server.S35PacketUpdateTileEntity(xCoord,yCoord,zCoord,1,tag);}
  @Override public void onDataPacket(net.minecraft.network.NetworkManager manager,net.minecraft.network.play.server.S35PacketUpdateTileEntity packet){readFromNBT(packet.func_148857_g());}
 }
}
