package dev.epoch;

import dev.epoch.bridge.*;
import net.minecraft.block.*;
import net.minecraft.block.material.Material;
import net.minecraft.item.*;
import net.minecraft.tileentity.*;
import net.minecraft.nbt.*;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.*;
import net.minecraftforge.fml.RegistryObject;

@Mod("epoch")
public class Epoch {
 public static final String VERSION="1.16.5";
 public static final DeferredRegister<Block> BLOCKS=DeferredRegister.create(ForgeRegistries.BLOCKS,"epoch");
 public static final DeferredRegister<Item> ITEMS=DeferredRegister.create(ForgeRegistries.ITEMS,"epoch");
 public static final DeferredRegister<TileEntityType<?>> TILES=DeferredRegister.create(ForgeRegistries.TILE_ENTITIES,"epoch");
 public static final RegistryObject<Block> UNKNOWN=BLOCKS.register("unknown_block",UnknownBlock::new);
 public static final RegistryObject<TileEntityType<UnknownTile>> UNKNOWN_TILE=TILES.register("unknown_block",()->TileEntityType.Builder.of(UnknownTile::new,UNKNOWN.get()).build(null));
 public static final RegistryObject<Item> UNKNOWN_ITEM=ITEMS.register("unknown_block",()->new BlockItem(UNKNOWN.get(),new Item.Properties().tab(ItemGroup.TAB_MISC)));
 public static final RegistryObject<Item> KEY=ITEMS.register("phase_key",()->new Item(new Item.Properties().stacksTo(1).tab(ItemGroup.TAB_MISC)) {
  private long last; private long useCount;
  public ActionResult<ItemStack> use(World world,PlayerEntity player,Hand hand) {
   if(world.isClientSide && System.currentTimeMillis()-last>1500) { last=System.currentTimeMillis();ClientHooks.bridge().diagnostics.put("phaseKeyUseCount",++useCount);ClientHooks.bridge().diagnostics.put("lastPhaseKeyUseAt",last); ClientHooks.request(); }
   return ActionResult.success(player.getItemInHand(hand));
  }
 });
 public Epoch() { BLOCKS.register(FMLJavaModLoadingContext.get().getModEventBus()); ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus()); TILES.register(FMLJavaModLoadingContext.get().getModEventBus()); MinecraftForge.EVENT_BUS.register(new Handoff()); }
 public static class UnknownBlock extends Block {
  public UnknownBlock() { super(AbstractBlock.Properties.of(Material.STONE).strength(1.5f,6f)); }
  public boolean hasTileEntity(BlockState state) { return true; }
  public TileEntity createTileEntity(BlockState state,net.minecraft.world.IBlockReader world) { return new UnknownTile(); }
 }
 public static class UnknownTile extends TileEntity {
  public String originalState="",originalNbt="",sourceVersion="";
  public UnknownTile() { super(UNKNOWN_TILE.get()); }
  public CompoundNBT getUpdateTag() { return save(new CompoundNBT()); }
  public CompoundNBT save(CompoundNBT tag) { super.save(tag); tag.putString("OriginalState",originalState);tag.putString("OriginalNbt",originalNbt);tag.putString("SourceVersion",sourceVersion);return tag; }
  public void load(BlockState state,CompoundNBT tag) { super.load(state,tag);originalState=tag.getString("OriginalState");originalNbt=tag.getString("OriginalNbt");sourceVersion=tag.getString("SourceVersion"); }
 }
}

