package dev.epoch;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.*;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.*;
import net.minecraft.world.World;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;

@Mod(modid="epoch",name="Epoch",version="0.3.0",acceptedMinecraftVersions="[1.12.2]",clientSideOnly=true)
public final class Epoch {
 public static final String VERSION="1.12.2";
 public static final UnknownBlock UNKNOWN=(UnknownBlock)new UnknownBlock().setRegistryName("epoch","unknown_block").setTranslationKey("epoch.unknown_block");
 public static final Item KEY=new Item(){
  private long last,count;
  public ActionResult<ItemStack> onItemRightClick(World world,EntityPlayer player,EnumHand hand){
   if(world.isRemote&&System.currentTimeMillis()-last>1500){last=System.currentTimeMillis();ClientHooks.bridge().diagnostics.put("phaseKeyUseCount",++count);ClientHooks.bridge().diagnostics.put("lastPhaseKeyUseAt",last);ClientHooks.request();}
   return new ActionResult<ItemStack>(EnumActionResult.SUCCESS,player.getHeldItem(hand));
  }
 }.setRegistryName("epoch","phase_key").setTranslationKey("epoch.phase_key").setMaxStackSize(1).setCreativeTab(CreativeTabs.TOOLS);
 @Mod.EventHandler public void preInit(FMLPreInitializationEvent event){MinecraftForge.EVENT_BUS.register(this);MinecraftForge.EVENT_BUS.register(new Handoff());MinecraftForge.EVENT_BUS.register(new ClientHooks());GameRegistry.registerTileEntity(UnknownTile.class,new ResourceLocation("epoch","unknown_block"));}
 @SubscribeEvent public void blocks(RegistryEvent.Register<Block> event){event.getRegistry().register(UNKNOWN);}
 @SubscribeEvent public void items(RegistryEvent.Register<Item> event){event.getRegistry().register(KEY);event.getRegistry().register(new ItemBlock(UNKNOWN).setRegistryName(UNKNOWN.getRegistryName()));}
 @SubscribeEvent public void models(net.minecraftforge.client.event.ModelRegistryEvent event){ModelLoader.setCustomModelResourceLocation(KEY,0,new ModelResourceLocation("epoch:phase_key","inventory"));ModelLoader.setCustomModelResourceLocation(Item.getItemFromBlock(UNKNOWN),0,new ModelResourceLocation("epoch:unknown_block","inventory"));}
 @Mod.EventHandler public void init(FMLInitializationEvent event){net.minecraftforge.client.ClientCommandHandler.instance.registerCommand(new net.minecraft.command.CommandBase(){public String getName(){return "epoch";}public String getUsage(net.minecraft.command.ICommandSender sender){return "/epoch versions";}public int getRequiredPermissionLevel(){return 0;}public void execute(net.minecraft.server.MinecraftServer server,net.minecraft.command.ICommandSender sender,String[] args){ClientHooks.request();}});}
 public static class UnknownBlock extends Block {
  UnknownBlock(){super(Material.ROCK);setHardness(1.5f);setResistance(6f);setCreativeTab(CreativeTabs.BUILDING_BLOCKS);}
  public boolean hasTileEntity(IBlockState state){return true;}
  public TileEntity createTileEntity(World world,IBlockState state){return new UnknownTile();}
  private ItemStack preserved(net.minecraft.world.IBlockAccess world,net.minecraft.util.math.BlockPos pos){ItemStack stack=new ItemStack(this);TileEntity tile=world.getTileEntity(pos);if(tile instanceof UnknownTile){NBTTagCompound data=((UnknownTile)tile).writeToNBT(new NBTTagCompound());data.removeTag("x");data.removeTag("y");data.removeTag("z");NBTTagCompound tag=new NBTTagCompound();tag.setTag("BlockEntityTag",data);stack.setTagCompound(tag);}return stack;}
  public void getDrops(net.minecraft.util.NonNullList<ItemStack> drops,net.minecraft.world.IBlockAccess world,net.minecraft.util.math.BlockPos pos,IBlockState state,int fortune){drops.add(preserved(world,pos));}
  public ItemStack getPickBlock(IBlockState state,net.minecraft.util.math.RayTraceResult target,World world,net.minecraft.util.math.BlockPos pos,EntityPlayer player){return preserved(world,pos);}
  public boolean removedByPlayer(IBlockState state,World world,net.minecraft.util.math.BlockPos pos,EntityPlayer player,boolean willHarvest){return willHarvest||super.removedByPlayer(state,world,pos,player,willHarvest);}
  public void harvestBlock(World world,EntityPlayer player,net.minecraft.util.math.BlockPos pos,IBlockState state,TileEntity tile,ItemStack tool){super.harvestBlock(world,player,pos,state,tile,tool);world.setBlockToAir(pos);}
  public void onBlockPlacedBy(World world,net.minecraft.util.math.BlockPos pos,IBlockState state,net.minecraft.entity.EntityLivingBase placer,ItemStack stack){super.onBlockPlacedBy(world,pos,state,placer,stack);TileEntity tile=world.getTileEntity(pos);if(tile instanceof UnknownTile&&stack.hasTagCompound()&&stack.getTagCompound().hasKey("BlockEntityTag",10)){NBTTagCompound data=stack.getTagCompound().getCompoundTag("BlockEntityTag");UnknownTile unknown=(UnknownTile)tile;unknown.originalState=data.getString("OriginalState");unknown.originalNbt=data.getString("OriginalNbt");unknown.sourceVersion=data.getString("SourceVersion");unknown.markDirty();world.notifyBlockUpdate(pos,state,state,3);}}
 }
 public static class UnknownTile extends TileEntity {
  public String originalState="",originalNbt="",sourceVersion="";
  public NBTTagCompound writeToNBT(NBTTagCompound tag){super.writeToNBT(tag);tag.setString("OriginalState",originalState);tag.setString("OriginalNbt",originalNbt);tag.setString("SourceVersion",sourceVersion);return tag;}
  public void readFromNBT(NBTTagCompound tag){super.readFromNBT(tag);originalState=tag.getString("OriginalState");originalNbt=tag.getString("OriginalNbt");sourceVersion=tag.getString("SourceVersion");}
  public NBTTagCompound getUpdateTag(){return writeToNBT(new NBTTagCompound());}
 }
}

