package dev.epoch;

import dev.epoch.bridge.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.*;
import org.lwjgl.glfw.GLFWNativeWin32;

@Mod("epoch")
public class Epoch {
  public static final String VERSION = "1.20.1";
  static final DeferredRegister<Block> BLOCKS =
      DeferredRegister.create(ForgeRegistries.BLOCKS, "epoch");
  static final DeferredRegister<Item> ITEMS =
      DeferredRegister.create(ForgeRegistries.ITEMS, "epoch");
  static final DeferredRegister<BlockEntityType<?>> ENTITIES =
      DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, "epoch");
  public static final RegistryObject<Block> UNKNOWN =
      BLOCKS.register("unknown_block", UnknownBlock::new);
  public static final RegistryObject<BlockEntityType<UnknownEntity>> UNKNOWN_ENTITY =
      ENTITIES.register(
          "unknown_block",
          () -> BlockEntityType.Builder.of(UnknownEntity::new, UNKNOWN.get()).build(null));
  public static final RegistryObject<Item> UNKNOWN_ITEM =
      ITEMS.register("unknown_block", () -> new BlockItem(UNKNOWN.get(), new Item.Properties()));
  public static final RegistryObject<Item> KEY =
      ITEMS.register(
          "phase_key",
          () ->
              new Item(new Item.Properties().stacksTo(1)) {
                private long last;
                private int uses;

                @Override
                public InteractionResultHolder<ItemStack> use(
                    Level level, Player player, InteractionHand hand) {
                  if (level.isClientSide && System.currentTimeMillis() - last > 1500) {
                    last = System.currentTimeMillis();
                    bridge().diagnostics.put("phaseKeyUseCount", ++uses);
                    bridge().diagnostics.put("lastPhaseKeyUseAt", last);
                    if (Minecraft.getInstance().hasSingleplayerServer()) {
                      try {
                        Minecraft.getInstance().setScreen(new VersionListScreen());
                      } catch (Exception e) {
                        player.displayClientMessage(
                            Component.literal("Epoch: " + e.getMessage()), false);
                      }
                    } else
                      player.displayClientMessage(
                          Component.literal("Epoch supports single-player only."), false);
                  }
                  return InteractionResultHolder.sidedSuccess(
                      player.getItemInHand(hand), level.isClientSide);
                }
              });
  private static BridgeFiles files;
  private boolean frozen, announced, starting;
  private volatile int renderTicks;
  private long heartbeatAt;
  private volatile long grabAfter;
  private volatile java.util.concurrent.CompletableFuture<double[]> frameCapture;
  private volatile double[] heldPose;
  private Boolean previousNoGravity;

  /** Transparent input barrier; world rendering and chunk delivery continue behind it. */
  static final class FreezeScreen extends net.minecraft.client.gui.screens.Screen {
    FreezeScreen() {
      super(Component.literal("Epoch handoff"));
    }

    @Override
    public boolean isPauseScreen() {
      return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
      return false;
    }

    @Override
    public void render(
        net.minecraft.client.gui.GuiGraphics graphics, int x, int y, float partial) {}
  }

  void holdClient(double[] pose) {
    Minecraft mc = Minecraft.getInstance();
    heldPose = pose.clone();
    if (mc.player != null && previousNoGravity == null) previousNoGravity = mc.player.isNoGravity();
    grabAfter = 0;
    net.minecraft.client.KeyMapping.releaseAll();
    mc.mouseHandler.releaseMouse();
    if (!(mc.screen instanceof FreezeScreen)) mc.setScreen(new FreezeScreen());
    enforceClientPose();
  }

  void enforceClientPose() {
    double[] pose = heldPose;
    Minecraft mc = Minecraft.getInstance();
    if (pose == null || mc.player == null) return;
    net.minecraft.client.KeyMapping.releaseAll();
    mc.player.input.forwardImpulse = mc.player.input.leftImpulse = 0;
    mc.player.input.jumping = mc.player.input.shiftKeyDown = false;
    mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
    mc.player.setNoGravity(true);
    mc.player.setPos(pose[0], pose[1], pose[2]);
    mc.player.xo = mc.player.xOld = pose[0];
    mc.player.yo = mc.player.yOld = pose[1];
    mc.player.zo = mc.player.zOld = pose[2];
    mc.player.setYRot((float) pose[3]);
    mc.player.yRotO = (float) pose[3];
    mc.player.setXRot((float) pose[4]);
    mc.player.xRotO = (float) pose[4];
  }

  void requestClientHold(double[] pose) throws Exception {
    java.util.concurrent.CompletableFuture<Void> applied =
        new java.util.concurrent.CompletableFuture<>();
    Minecraft.getInstance()
        .execute(
            () -> {
              try {
                holdClient(pose);
                applied.complete(null);
              } catch (Exception error) {
                applied.completeExceptionally(error);
              }
            });
    applied.get(10, java.util.concurrent.TimeUnit.SECONDS);
  }

  static BridgeFiles bridge() {
    if (files == null)
      files =
          new BridgeFiles(
              AutoRuntime.controlDirectory(Minecraft.getInstance().gameDirectory.toPath(), VERSION),
              VERSION);
    return files;
  }

  public Epoch() {
    IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
    BLOCKS.register(bus);
    ITEMS.register(bus);
    ENTITIES.register(bus);
    bus.addListener(this::creativeTabContents);
    MinecraftForge.EVENT_BUS.addListener(this::serverTick);
    MinecraftForge.EVENT_BUS.addListener(this::clientTick);
    MinecraftForge.EVENT_BUS.addListener(this::renderTick);
    MinecraftForge.EVENT_BUS.addListener(this::commands);
  }

  void creativeTabContents(BuildCreativeModeTabContentsEvent event) {
    if (event.getTabKey().equals(CreativeModeTabs.TOOLS_AND_UTILITIES)) {
      event.accept(KEY);
    }
  }

  void renderTick(TickEvent.RenderTickEvent event) {
    if (event.phase == TickEvent.Phase.START) {
      enforceClientPose();
      return;
    }
    if (event.phase != TickEvent.Phase.END) return;
    Minecraft mc = Minecraft.getInstance();
    bridge().windowHandle = GLFWNativeWin32.glfwGetWin32Window(mc.getWindow().getWindow());
    java.util.concurrent.CompletableFuture<double[]> capture = frameCapture;
    if (capture != null) {
      frameCapture = null;
      try (com.mojang.blaze3d.platform.NativeImage frame =
          net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
        Path directory = AutoRuntime.controlDirectory(mc.gameDirectory.toPath(), VERSION);
        Files.createDirectories(directory);
        frame.writeToFile(directory.resolve("frozen.png"));
        net.minecraft.client.Camera camera = mc.gameRenderer.getMainCamera();
        double[] pose =
            new double[] {
              camera.getPosition().x,
              camera.getPosition().y - mc.player.getEyeHeight(),
              camera.getPosition().z,
              camera.getYRot(),
              camera.getXRot()
            };
        if (Math.abs(pose[1] - Math.rint(pose[1])) < 1.0e-6) pose[1] = Math.rint(pose[1]);
        holdClient(pose);
        capture.complete(pose);
      } catch (Exception error) {
        capture.completeExceptionally(error);
      }
    }
    synchronized (bridge()) {
      bridge().clientReady =
        mc.level != null
            && mc.player != null
            && (mc.screen == null || mc.screen instanceof FreezeScreen)
            && ++renderTicks > 20
            && mc.levelRenderer.hasRenderedAllChunks();
    }
  }

  void clientTick(TickEvent.ClientTickEvent event) {
    enforceClientPose();
    if (event.phase != TickEvent.Phase.END) return;
    Minecraft mc = Minecraft.getInstance();
    if (mc.isPaused() && bridge().needsWorldTick()) mc.setScreen(null);
    if (AutoRuntime.managed() && mc.getWindow().isFullscreen()) mc.getWindow().toggleFullScreen();
    mc.options.pauseOnLostFocus = false;
    mc.options.bobView().set(false);
    mc.options.fov().set(70);
    if (System.currentTimeMillis() - heartbeatAt > 250) {
      heartbeatAt = System.currentTimeMillis();
      if (mc.player != null && mc.getSingleplayerServer() != null) {
        MinecraftServer server = mc.getSingleplayerServer();
        AutoRuntime.tick(mc.gameDirectory.toPath(), VERSION, bridge().windowHandle,
            server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .toAbsolutePath().normalize().getFileName().toString(),
            mc.player.getGameProfile().getName(), mc.player.getGameProfile().getId().toString());
      }
      bridge().diagnostics.put("screenVersionList", mc.screen instanceof VersionListScreen);
      bridge().heartbeat();
      bridge().diagnostics.put("clientFrozen", heldPose != null);
      if (mc.player != null) {
        bridge()
            .diagnostics
            .put(
                "clientCamera",
                Arrays.asList(
                    mc.player.getX(),
                    mc.player.getY(),
                    mc.player.getZ(),
                    mc.player.getYRot(),
                    mc.player.getXRot()));
        bridge().diagnostics.put("clientSelectedSlot", mc.player.getInventory().selected);
      }
    }
    if (grabAfter > 0 && System.currentTimeMillis() > grabAfter) {
      grabAfter = 0;
      heldPose = null;
      if (mc.player != null && previousNoGravity != null) mc.player.setNoGravity(previousNoGravity);
      previousNoGravity = null;
      net.minecraft.client.KeyMapping.releaseAll();
      mc.setScreen(null);
      mc.mouseHandler.grabMouse();
    }
    if (!starting && Boolean.getBoolean("epoch.autoStart") && mc.screen instanceof TitleScreen) {
      starting = true;
      mc.createWorldOpenFlows()
          .loadLevel(mc.screen, System.getProperty("epoch.world", "Epoch Lab"));
    }
  }

  void commands(RegisterCommandsEvent e) {
    e.getDispatcher()
        .register(
            net.minecraft.commands.Commands.literal("epoch")
                .then(net.minecraft.commands.Commands.literal("versions").executes(c -> {
                  Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new VersionListScreen()));
                  return 1;
                }))
                .then(
                    net.minecraft.commands.Commands.literal("testsetup")
                        .executes(
                            c -> {
                              testsetup(c.getSource().getServer());
                              return 1;
                            }))
                .then(
                    net.minecraft.commands.Commands.literal("probe")
                        .executes(
                            c -> {
                              probe(c.getSource().getServer());
                              return 1;
                            })));
  }

  void serverTick(TickEvent.ServerTickEvent e) {
    if (e.phase != TickEvent.Phase.END) return;
    MinecraftServer server = e.getServer();
    if (server.isDedicatedServer()) return;
    try {
      if (!announced && !server.getPlayerList().getPlayers().isEmpty()) {
        announced = true;
        bridge().status("", "READY", "Integrated world ready; v0.1 region x,z [-32,31], y [0,255]");
        server.getPlayerList().getPlayers().get(0).sendSystemMessage(Component.literal(
            "Epoch: use the Epoch Key or /epoch versions to choose a local instance. First startup may take time."));
      }
      do {
        BridgeFiles.Command cmd = bridge().poll();
        if (cmd != null) handle(server, cmd);
        if (frozen) {
          server.getConnection().tick();
          server.pollTask();
          server.overworld().getChunkSource().pollTask();
          double[] pose = heldPose;
          if (pose != null && !server.getPlayerList().getPlayers().isEmpty()) {
            ServerPlayer active = server.getPlayerList().getPlayers().get(0);
            active.setPos(pose[0], pose[1], pose[2]);
            active.setYRot((float) pose[3]);
            active.setXRot((float) pose[4]);
            active.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
          }
          Thread.sleep(10);
        }
      } while (frozen && server.isRunning());
    } catch (Exception ex) {
      frozen = true;
      try {
        bridge().diagnostics.put("serverLoopError", ex.toString());
        bridge().heartbeat();
      } catch (Exception ignored) {
      }
      ex.printStackTrace();
    }
  }

  void handle(MinecraftServer server, BridgeFiles.Command cmd) throws Exception {
    try {
      switch (cmd.action) {
        case "catalog":
          java.util.List<AutoRuntime.Instance> catalog = AutoRuntime.instances(Minecraft.getInstance().gameDirectory.toPath());
          bridge().diagnostics.put("instances", catalog);
          bridge().diagnostics.put("instanceCount", catalog.size());
          bridge().diagnostics.put("screenVersionList", Minecraft.getInstance().screen instanceof VersionListScreen);
          bridge().status(cmd.id, "CATALOG", "Instance catalog available in diagnostics");
          break;
        case "select":
          AutoRuntime.select(Minecraft.getInstance().gameDirectory.toPath(), VERSION, cmd.path);
          Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(null));
          bridge().status(cmd.id, "SELECTED", "Target selected; waiting for preparation");
          break;
        case "export":
          frozen = true;
          java.util.concurrent.CompletableFuture<double[]> capture =
              new java.util.concurrent.CompletableFuture<>();
          frameCapture = capture;
          double[] camera = capture.get(15, java.util.concurrent.TimeUnit.SECONDS);
          Snapshot snapshot = exportWorld(server);
          snapshot.x = camera[0];
          snapshot.y = camera[1];
          snapshot.z = camera[2];
          snapshot.yaw = (float) camera[3];
          snapshot.pitch = (float) camera[4];
          snapshot.write(Paths.get(cmd.path));
          bridge().status(cmd.id, "EXPORTED", "World snapshot committed; simulation frozen");
          break;
        case "import":
          frozen = true;
          bridge().clientReady = false;
          importWorld(server, Snapshot.read(Paths.get(cmd.path)));
          synchronized (bridge()) {
            renderTicks = 0;
            bridge().clientReady = false;
            bridge().status(cmd.id, "IMPORTED", "Snapshot applied; simulation frozen");
          }
          break;
        case "freeze":
          frozen = true;
          ServerPlayer standby = player(server);
          requestClientHold(
              new double[] {
                standby.getX(), standby.getY(), standby.getZ(), standby.getYRot(), standby.getXRot()
              });
          bridge().status(cmd.id, "FROZEN", "Simulation frozen");
          break;
        case "resume":
          frozen = false;
          grabAfter = System.currentTimeMillis() + 400;
          bridge().status(cmd.id, "READY", "Simulation resumed");
          break;
        case "testsetup":
          testsetup(server);
          bridge().status(cmd.id, "READY", "Lab prepared");
          break;
        case "probe":
          probe(server);
          bridge().status(cmd.id, "PROBED", "Probe available in diagnostics");
          break;
        case "usekey":
          if (frozen) throw new IllegalStateException("Cannot use the key while the client is frozen");
          if (!player(server).getMainHandItem().is(KEY.get()))
            throw new IllegalStateException("Select the Epoch Key in the main hand first");
          bridge().status(cmd.id, "KEY_TRIGGERED", "Queued the registered item use handler");
          Minecraft.getInstance().execute(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.level != null)
              KEY.get().use(mc.level, mc.player, InteractionHand.MAIN_HAND);
          });
          break;
        case "shutdown":
          frozen = false;
          Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
          break;
        default:
          throw new IllegalArgumentException("Unknown action " + cmd.action);
      }
    } catch (Exception ex) {
      if (!"select".equals(cmd.action) && !"catalog".equals(cmd.action)
          && !"usekey".equals(cmd.action)) frozen = true;
      bridge().status(cmd.id, "ERROR", ex.toString());
    }
  }

  ServerPlayer player(MinecraftServer server) {
    if (server.getPlayerList().getPlayers().size() != 1)
      throw new IllegalStateException("Epoch v0.1 requires exactly one player");
    ServerPlayer p = server.getPlayerList().getPlayers().get(0);
    if (p.level().dimension() != Level.OVERWORLD)
      throw new IllegalStateException("Epoch v0.1 supports the Overworld only");
    if (p.getX() < -32
        || p.getX() >= 32
        || p.getZ() < -32
        || p.getZ() >= 32
        || p.getY() < 0
        || p.getY() >= 256)
      throw new IllegalStateException("Outside Epoch v0.1 shared region [-32,32), y [0,256)");
    return p;
  }

  Snapshot exportWorld(MinecraftServer server) {
    ServerPlayer p = player(server);
    ServerLevel level = server.overworld();
    Snapshot s = new Snapshot();
    s.version = VERSION;
    s.x = p.getX();
    s.y = p.getY();
    s.z = p.getZ();
    s.yaw = p.getYRot();
    s.pitch = p.getXRot();
    s.gameTime = level.getGameTime();
    s.dayTime = level.getDayTime();
    s.inventory = p.getInventory().save(new ListTag()).toString();
    s.selectedSlot = p.getInventory().selected;
    Map<String, Integer> ids = new HashMap<>();
    Map<BlockState, String> encodedStates = new IdentityHashMap<>();
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    List<Integer> runs = new ArrayList<>();
    int previous = -1, count = 0;
    for (int y = 0; y < s.sizeY; y++)
      for (int z = 0; z < s.sizeZ; z++)
        for (int x = 0; x < s.sizeX; x++) {
          int index = (y * s.sizeZ + z) * s.sizeX + x;
          BlockPos pos = cursor.set(x + s.x0, y + s.y0, z + s.z0);
          BlockState state = level.getBlockState(pos);
          BlockEntity be = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
          String snbt =
              encodedStates.computeIfAbsent(
                  state, value -> NbtUtils.writeBlockState(value).toString());
          String beNbt = be == null ? null : be.saveWithFullMetadata().toString();
          if (be instanceof UnknownEntity unknown && !unknown.originalState.isEmpty()) {
            snbt = unknown.originalState;
            beNbt = unknown.originalNbt.isEmpty() ? null : unknown.originalNbt;
          }
          if (beNbt != null) s.blockEntities.put(Integer.toString(index), beNbt);
          Integer id = ids.get(snbt);
          if (id == null) {
            id = s.palette.size();
            ids.put(snbt, id);
            s.palette.add(snbt);
          }
          if (id == previous) count++;
          else {
            if (count > 0) {
              runs.add(previous);
              runs.add(count);
            }
            previous = id;
            count = 1;
          }
        }
    if (count > 0) {
      runs.add(previous);
      runs.add(count);
    }
    s.runs = runs.stream().mapToInt(Integer::intValue).toArray();
    return s;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  BlockState decode(String text) throws Exception {
    CompoundTag tag = TagParser.parseTag(text);
    if (tag.contains("EpochLegacyVersion")) return null;
    ResourceLocation id = new ResourceLocation(tag.getString("Name"));
    if (!BuiltInRegistries.BLOCK.containsKey(id)) return null;
    BlockState state = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
    CompoundTag properties = tag.getCompound("Properties");
    for (String key : properties.getAllKeys()) {
      Property prop = state.getBlock().getStateDefinition().getProperty(key);
      if (prop == null) return null;
      Optional value = prop.getValue(properties.getString(key));
      if (value.isEmpty()) return null;
      state = state.setValue(prop, (Comparable) value.get());
    }
    return state;
  }

  void importWorld(MinecraftServer server, Snapshot s) throws Exception {
    s.validate();
    if (s.schema != 1
        || s.x0 != -32
        || s.z0 != -32
        || s.y0 != 0
        || s.sizeX != 64
        || s.sizeZ != 64
        || s.sizeY != 256) throw new IllegalArgumentException("Unsupported snapshot bounds/schema");
    ServerPlayer p = player(server);
    ServerLevel level = server.overworld();
    BlockState[] palette = new BlockState[s.palette.size()];
    for (int n = 0; n < palette.length; n++) palette[n] = decode(s.palette.get(n));
    Map<Integer, CompoundTag> parsedPayloads = new HashMap<>();
    for (Map.Entry<String, String> entry : s.blockEntities.entrySet())
      parsedPayloads.put(Integer.parseInt(entry.getKey()), TagParser.parseTag(entry.getValue()));
    Tag inventoryTag =
        new TagParser(new com.mojang.brigadier.StringReader(s.inventory)).readValue();
    if (!(inventoryTag instanceof ListTag))
      throw new IllegalArgumentException("Inventory must be an NBT list");
    validateInventory((ListTag) inventoryTag);
    Map<Integer, BlockEntity> preparedEntities = new HashMap<>();
    Set<Integer> incompatibleEntities = new HashSet<>();
    int plannedIndex = 0;
    for (int r = 0; r < s.runs.length; r += 2) {
      BlockState state = palette[s.runs[r]];
      for (int k = 0; k < s.runs[r + 1]; k++, plannedIndex++) {
        CompoundTag payload = parsedPayloads.get(plannedIndex);
        if (payload == null || state == null) continue;
        BlockPos pos =
            new BlockPos(
                plannedIndex % 64 - 32, plannedIndex / (64 * 64), (plannedIndex / 64) % 64 - 32);
        try {
          if (!(state.getBlock() instanceof EntityBlock factory))
            throw new IllegalArgumentException("Block cannot hold NBT");
          BlockEntity fresh = factory.newBlockEntity(pos, state);
          if (fresh == null
              || !BuiltInRegistries.BLOCK_ENTITY_TYPE
                  .getKey(fresh.getType())
                  .toString()
                  .equals(payload.getString("id")))
            throw new IllegalArgumentException("Incompatible block entity type");
          CompoundTag copy = payload.copy();
          copy.putInt("x", pos.getX());
          copy.putInt("y", pos.getY());
          copy.putInt("z", pos.getZ());
          fresh.load(copy.copy());
          if (!preservesNbt(copy, fresh.saveWithFullMetadata())) throw new IllegalArgumentException("Block entity would lose NBT");
          preparedEntities.put(plannedIndex, fresh);
        } catch (Exception incompatible) {
          incompatibleEntities.add(plannedIndex);
        }
      }
    }
    requestClientHold(new double[] {s.x, s.y, s.z, s.yaw, s.pitch});
    int index = 0, unknowns = 0;
    for (int r = 0; r < s.runs.length; r += 2) {
      int id = s.runs[r];
      if (id < 0 || id >= palette.length || s.runs[r + 1] < 1)
        throw new IllegalArgumentException("Invalid runs");
      for (int k = 0; k < s.runs[r + 1]; k++, index++) {
        if (index >= 64 * 64 * 256) throw new IllegalArgumentException("Snapshot overflow");
        BlockPos pos = new BlockPos(index % 64 - 32, index / (64 * 64), (index / 64) % 64 - 32);
        String payload = s.blockEntities.get(Integer.toString(index));
        BlockState state = palette[id];
        boolean unknown = state == null || incompatibleEntities.contains(index);
        BlockState placed = unknown ? UNKNOWN.get().defaultBlockState() : state;
        if (level.getBlockState(pos) != placed) {
          // Vanilla onRemove would otherwise eject stale target inventory and duplicate contents.
          BlockEntity previous = level.getBlockEntity(pos);
          if (previous instanceof net.minecraft.world.Clearable clearable) clearable.clearContent();
          level.setBlock(pos, placed, 2 | 16);
        }
        BlockEntity be = preparedEntities.get(index);
        if (unknown || be == null && placed.hasBlockEntity())
          be = ((EntityBlock) placed.getBlock()).newBlockEntity(pos, placed);
        if (unknown) {
          unknowns++;
          if (be instanceof UnknownEntity u) {
            u.originalState = s.palette.get(id);
            u.originalNbt = payload == null ? "" : payload;
            u.sourceVersion = s.version;
            u.setChanged();
          }
        }
        if (be != null) {
          // Never merge imported NBT into an old live BE; absent fields reset to defaults.
          level.removeBlockEntity(pos);
          level.setBlockEntity(be);
          be.setChanged();
        }
      }
    }
    if (index != 64 * 64 * 256) throw new IllegalArgumentException("Snapshot incomplete");
    server.getWorldData().overworldData().setGameTime(s.gameTime);
    level.setDayTime(s.dayTime);
    p.teleportTo(level, s.x, s.y, s.z, s.yaw, s.pitch);
    p.getInventory().load((ListTag) inventoryTag);
    p.getInventory().selected = s.selectedSlot;
    p.inventoryMenu.broadcastChanges();
    p.connection.send(
        new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(s.selectedSlot));
    p.connection.send(
        new net.minecraft.network.protocol.game.ClientboundSetTimePacket(
            s.gameTime,
            s.dayTime,
            level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DAYLIGHT)));
    List<java.util.concurrent.CompletableFuture<?>> lights = new ArrayList<>();
    for (int cz = -2; cz < 2; cz++)
      for (int cx = -2; cx < 2; cx++)
        lights.add(
            level.getChunkSource().getLightEngine().lightChunk(level.getChunk(cx, cz), true));
    java.util.concurrent.CompletableFuture<?> lighting =
        java.util.concurrent.CompletableFuture.allOf(
            lights.toArray(new java.util.concurrent.CompletableFuture<?>[0]));
    long deadline = System.currentTimeMillis() + 15000;
    while (!lighting.isDone()) {
      level.getChunkSource().getLightEngine().tryScheduleUpdate();
      level.getChunkSource().pollTask();
      if (System.currentTimeMillis() > deadline)
        throw new IllegalStateException("Lighting timed out");
      Thread.sleep(2);
    }
    lighting.join();
    for (int cz = -2; cz < 2; cz++)
      for (int cx = -2; cx < 2; cx++)
        p.connection.send(
            new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                level.getChunk(cx, cz), level.getChunkSource().getLightEngine(), null, null));
    bridge().diagnostics.put("unknownBlocks", unknowns);
  }

  static void validateInventory(ListTag inventory) {
    InventorySlots slots = new InventorySlots(true);
    for (Tag element : inventory) {
      if (!(element instanceof CompoundTag))
        throw new IllegalArgumentException("Inventory entries must be compounds");
      CompoundTag expected = ((CompoundTag) element).copy();
      if (!expected.contains("Slot", 1))
        throw new IllegalArgumentException("Player inventory Slot must be a byte");
      slots.claim(expected.getByte("Slot"));
      expected.remove("Slot"); // Inventory handles the location independently of ItemStack NBT.
      ItemStack decoded = ItemStack.of(expected.copy());
      CompoundTag restored = decoded.save(new CompoundTag());
      if (!preservesNbt(expected, restored))
        throw new IllegalArgumentException(
            "Inventory item cannot be preserved in " + VERSION + ": " + expected.getString("id"));
    }
  }

  static boolean preservesNbt(Tag expected, Tag actual) {
    if (actual == null) return false;
    if (expected instanceof CompoundTag) {
      if (!(actual instanceof CompoundTag)) return false;
      CompoundTag wanted = (CompoundTag) expected, got = (CompoundTag) actual;
      for (String key : wanted.getAllKeys())
        if (!preservesNbt(wanted.get(key), got.get(key))) return false;
      return true;
    }
    if (expected instanceof ListTag) {
      if (!(actual instanceof ListTag)) return false;
      ListTag wanted = (ListTag) expected, got = (ListTag) actual;
      if (wanted.size() != got.size()) return false;
      for (int index = 0; index < wanted.size(); index++)
        if (!preservesNbt(wanted.get(index), got.get(index))) return false;
      return true;
    }
    if (expected instanceof NumericTag && actual instanceof NumericTag) {
      try {
        return new java.math.BigDecimal(((NumericTag) expected).getAsNumber().toString())
            .compareTo(new java.math.BigDecimal(((NumericTag) actual).getAsNumber().toString())) == 0;
      } catch (NumberFormatException invalid) {
        return expected.equals(actual);
      }
    }
    return expected.equals(actual);
  }

  void testsetup(MinecraftServer server) {
    ServerLevel level = server.overworld();
    for (int z = -12; z <= 12; z++)
      for (int x = -12; x <= 12; x++) {
        level.setBlock(new BlockPos(x, 63, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        for (int y = 64; y <= 72; y++) {
          BlockPos pos = new BlockPos(x, y, z);
          level.removeBlockEntity(pos);
          level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        }
      }
    // Reset only the diagnostic fixture's dropped items when repeating setup.
    for (net.minecraft.world.entity.item.ItemEntity item : level.getEntitiesOfClass(
        net.minecraft.world.entity.item.ItemEntity.class,
        new net.minecraft.world.phys.AABB(-12, 63, -12, 13, 74, 13))) item.discard();
    level.setBlock(new BlockPos(0, 64, 3), Blocks.CHERRY_PLANKS.defaultBlockState(), 2);
    level.setBlock(new BlockPos(1, 64, 3), Blocks.STONE.defaultBlockState(), 2);
    level.setBlock(new BlockPos(2, 64, 3), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), 2);
    if (level.getBlockEntity(new BlockPos(2, 64, 3))
        instanceof ChiseledBookShelfBlockEntity shelf) {
      ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
      CompoundTag tag = book.getOrCreateTag();
      tag.putString("title", "Epoch preserved payload");
      tag.putString("author", "Epoch Lab");
      ListTag pages = new ListTag();
      pages.add(StringTag.valueOf("{\"text\":\"Across versions, this page survives.\"}"));
      tag.put("pages", pages);
      shelf.setItem(0, book);
      shelf.setChanged();
    }
    level.setBlock(new BlockPos(-1, 64, 3), Blocks.CHEST.defaultBlockState(), 2);
    if (level.getBlockEntity(new BlockPos(-1, 64, 3)) instanceof ChestBlockEntity chest) {
      ItemStack sentinel = new ItemStack(Items.DIAMOND, 7);
      sentinel.setHoverName(Component.literal("Epoch NBT sentinel"));
      chest.setItem(0, sentinel);
      chest.setChanged();
    }
    ServerPlayer p = server.getPlayerList().getPlayers().get(0);
    p.teleportTo(level, 0.5, 64, -3.5, 0, 10);
    p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
    p.getInventory().setItem(0, new ItemStack(KEY.get()));
    level.setDayTime(6000);
    level
        .getGameRules()
        .getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT)
        .set(false, server);
    level
        .getGameRules()
        .getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE)
        .set(false, server);
    level
        .getGameRules()
        .getRule(net.minecraft.world.level.GameRules.RULE_RANDOMTICKING)
        .set(0, server);
  }

  void probe(MinecraftServer server) {
    ServerLevel level = server.overworld();
    bridge().diagnostics.put("itemEntityCount", level.getEntitiesOfClass(
        net.minecraft.world.entity.item.ItemEntity.class,
        new net.minecraft.world.phys.AABB(-32, 0, -32, 32, 256, 32)).size());
    for (int x = -1; x <= 2; x++) {
      BlockPos pos = new BlockPos(x, 64, 3);
      bridge()
          .diagnostics
          .put("block_" + x, NbtUtils.writeBlockState(level.getBlockState(pos)).toString());
      BlockEntity be = level.getBlockEntity(pos);
      if (be != null) bridge().diagnostics.put("nbt_" + x, be.saveWithFullMetadata().toString());
    }
    ServerPlayer p = server.getPlayerList().getPlayers().get(0);
    bridge()
        .diagnostics
        .put("camera", Arrays.asList(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()));
  }

  public static class UnknownBlock extends BaseEntityBlock {
    UnknownBlock() {
      super(BlockBehaviour.Properties.of().strength(2));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos p, BlockState s) {
      return new UnknownEntity(p, s);
    }

    @Override
    public RenderShape getRenderShape(BlockState s) {
      return RenderShape.MODEL;
    }

    @Override
    public ItemStack getCloneItemStack(
        net.minecraft.world.level.BlockGetter level, BlockPos pos, BlockState state) {
      ItemStack stack = new ItemStack(UNKNOWN_ITEM.get());
      BlockEntity be = level.getBlockEntity(pos);
      if (be instanceof UnknownEntity)
        stack.getOrCreateTag().put("BlockEntityTag", be.saveWithFullMetadata());
      return stack;
    }
  }

  public static class UnknownEntity extends BlockEntity {
    String originalState = "", originalNbt = "", sourceVersion = "";

    public UnknownEntity(BlockPos p, BlockState s) {
      super(UNKNOWN_ENTITY.get(), p, s);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
      super.saveAdditional(tag);
      tag.putString("OriginalState", originalState);
      tag.putString("OriginalNbt", originalNbt);
      tag.putString("SourceVersion", sourceVersion);
    }

    @Override
    public CompoundTag getUpdateTag() {
      return saveWithFullMetadata();
    }

    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
      return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void load(CompoundTag tag) {
      super.load(tag);
      originalState = tag.getString("OriginalState");
      originalNbt = tag.getString("OriginalNbt");
      sourceVersion = tag.getString("SourceVersion");
    }
  }
}
