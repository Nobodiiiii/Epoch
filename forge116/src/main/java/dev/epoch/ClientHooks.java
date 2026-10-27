package dev.epoch;
import dev.epoch.bridge.BridgeFiles;
import dev.epoch.bridge.AutoRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.MainMenuScreen;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.Dist;
import org.lwjgl.glfw.GLFWNativeWin32;
import java.nio.file.Paths;
@Mod.EventBusSubscriber(modid="epoch",value=Dist.CLIENT)
public class ClientHooks {
 private static final dev.epoch.bridge.PauseOverride focusPause = new dev.epoch.bridge.PauseOverride();
 static void resetWorld(){Minecraft.getInstance().execute(() -> {Minecraft mc=Minecraft.getInstance();mc.options.pauseOnLostFocus=focusPause.update(mc.options.pauseOnLostFocus,false);frozenPose=null;resumeAt=0;renderTicks=0;if(frameCapture!=null)frameCapture.cancel(false);frameCapture=null;});}
 private static BridgeFiles files;
 private static boolean starting; private static long heartbeat;
 private static final Object readyLock=new Object(); static volatile int renderTicks; static volatile long resumeAt; private static volatile double[] frozenPose; private static boolean wasNoGravity;
 static BridgeFiles bridge(){if(files==null)files=new BridgeFiles(AutoRuntime.controlDirectory(Minecraft.getInstance().gameDirectory.toPath(),Epoch.VERSION),Epoch.VERSION);return files;}
 private static volatile java.util.concurrent.CompletableFuture<double[]> frameCapture;
 static double[] captureFrozen() throws Exception {
  java.util.concurrent.CompletableFuture<double[]> done=new java.util.concurrent.CompletableFuture<>();
  frameCapture=done;
  return done.get(15,java.util.concurrent.TimeUnit.SECONDS);
 }
 private static void completeCapture(Minecraft mc){
  java.util.concurrent.CompletableFuture<double[]> done=frameCapture;
  if(done==null)return;
  frameCapture=null;
  try(net.minecraft.client.renderer.texture.NativeImage frame=net.minecraft.util.ScreenShotHelper.takeScreenshot(mc.getWindow().getWidth(),mc.getWindow().getHeight(),mc.getMainRenderTarget())){
   java.nio.file.Path dir=AutoRuntime.controlDirectory(mc.gameDirectory.toPath(),Epoch.VERSION);
   frame.writeToFile(dir.resolve("frozen.png"));
   net.minecraft.client.renderer.ActiveRenderInfo camera=mc.gameRenderer.getMainCamera();
   net.minecraft.util.math.vector.Vector3d position=camera.getPosition();
   double eye=mc.player==null?1.62:mc.player.getEyeHeight();
   double feetY=position.y-eye;double roundedY=Math.rint(feetY);if(Math.abs(feetY-roundedY)<0.000001)feetY=roundedY;
   double[] pose=new double[]{position.x,feetY,position.z,camera.getYRot(),camera.getXRot()};
   applyFreeze(mc,pose);
   done.complete(pose);
  }catch(Exception e){done.completeExceptionally(e);}
 }
 static void markImported(String commandId){synchronized(readyLock){renderTicks=0;bridge().clientReady=false;bridge().status(commandId,"IMPORTED","Snapshot applied; simulation frozen");}}
 static void useKey(String commandId){
  Minecraft mc=Minecraft.getInstance();mc.execute(()->{
   try{
    if(mc.player==null||mc.level==null)throw new IllegalStateException("No local player");
    int slot=-1;for(int i=0;i<9;i++)if(mc.player.inventory.getItem(i).getItem()==Epoch.KEY.get()){slot=i;break;}
    if(slot<0)throw new IllegalStateException("Epoch Key missing from hotbar");
    mc.player.inventory.selected=slot;
    Epoch.KEY.get().use(mc.level,mc.player,net.minecraft.util.Hand.MAIN_HAND);
   }catch(Exception error){bridge().status(commandId,"ERROR",error.toString());}
  });
 }
 static void request(){Minecraft mc=Minecraft.getInstance();mc.execute(()->{if(mc.hasSingleplayerServer()&&mc.player!=null)mc.setScreen(new VersionListScreen());else if(mc.player!=null)mc.player.displayClientMessage(new StringTextComponent("Epoch supports singleplayer only"),false);});}
 static void catalog(){Minecraft mc=Minecraft.getInstance();bridge().diagnostics.put("instances",AutoRuntime.instances(mc.gameDirectory.toPath()));bridge().diagnostics.put("runtimeStatus",AutoRuntime.statusText(mc.gameDirectory.toPath()));bridge().diagnostics.put("listScreenOpen",mc.screen instanceof VersionListScreen);}
 static void select(String id){Minecraft mc=Minecraft.getInstance();AutoRuntime.select(mc.gameDirectory.toPath(),Epoch.VERSION,id);mc.execute(()->{if(mc.screen instanceof VersionListScreen)mc.setScreen(null);});}
 public static final class FreezeScreen extends net.minecraft.client.gui.screen.Screen {
  FreezeScreen(){super(new StringTextComponent("Epoch handoff"));}
  public boolean isPauseScreen(){return false;}
  public boolean shouldCloseOnEsc(){return false;}
  public void render(com.mojang.blaze3d.matrix.MatrixStack stack,int x,int y,float partial){}
 }
 private static void lockPose(Minecraft mc){
  double[] pose=frozenPose;
  if(pose==null||mc.player==null)return;
  mc.player.setPos(pose[0],pose[1],pose[2]);
  mc.player.xo=mc.player.xOld=pose[0];mc.player.yo=mc.player.yOld=pose[1];mc.player.zo=mc.player.zOld=pose[2];
  mc.player.yRot=mc.player.yRotO=(float)pose[3];mc.player.xRot=mc.player.xRotO=(float)pose[4];
  mc.player.setDeltaMovement(0,0,0);mc.player.setNoGravity(true);
 }
 private static void applyFreeze(Minecraft mc,double[] pose){
  if(frozenPose==null && mc.player!=null)wasNoGravity=mc.player.isNoGravity();
  frozenPose=pose;
  net.minecraft.client.settings.KeyBinding.releaseAll();
  if(!(mc.screen instanceof FreezeScreen))mc.setScreen(new FreezeScreen());
  mc.mouseHandler.releaseMouse();lockPose(mc);
 }
 static void freezeAt(double x,double y,double z,float yaw,float pitch) throws Exception {
  Minecraft mc=Minecraft.getInstance();
  java.util.concurrent.CompletableFuture<Void> ready=new java.util.concurrent.CompletableFuture<>();
  mc.execute(()->{try{applyFreeze(mc,new double[]{x,y,z,yaw,pitch});ready.complete(null);}catch(Exception error){ready.completeExceptionally(error);}});
  ready.get(10,java.util.concurrent.TimeUnit.SECONDS);
 }
 static void freezeCurrent(){Minecraft mc=Minecraft.getInstance();mc.execute(()->{if(mc.player!=null)applyFreeze(mc,new double[]{mc.player.getX(),mc.player.getY(),mc.player.getZ(),mc.player.yRot,mc.player.xRot});});}
 private static void unfreeze(Minecraft mc){
  frozenPose=null;if(mc.player!=null)mc.player.setNoGravity(wasNoGravity);
  if(mc.screen instanceof FreezeScreen)mc.setScreen(null);
  mc.mouseHandler.grabMouse();
 }
 @SubscribeEvent public static void render(TickEvent.RenderTickEvent e){
  Minecraft mc=Minecraft.getInstance();
  if(e.phase==TickEvent.Phase.START){lockPose(mc);return;}
  completeCapture(mc);bridge().windowHandle=GLFWNativeWin32.glfwGetWin32Window(mc.getWindow().getWindow());
  synchronized(readyLock){bridge().clientReady=mc.level!=null&&mc.player!=null&&(mc.screen==null||mc.screen instanceof FreezeScreen)&&++renderTicks>20&&mc.levelRenderer.hasRenderedAllChunks();}
 }
 @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){
  if(e.phase==TickEvent.Phase.START) AppearanceClient.tick();
  Minecraft mc=Minecraft.getInstance();lockPose(mc);if(e.phase!=TickEvent.Phase.END)return;
  dev.epoch.bridge.JourneyMapOrigins.tick(bridge().diagnostics,mc.level!=null&&mc.player!=null);
  if(mc.level!=null&&mc.player!=null&&mc.hasSingleplayerServer()){
   if(mc.getWindow().isFullscreen())mc.getWindow().toggleFullScreen();
   AutoRuntime.tick(mc.gameDirectory.toPath(),Epoch.VERSION,GLFWNativeWin32.glfwGetWin32Window(mc.getWindow().getWindow()),mc.getSingleplayerServer().getWorldPath(net.minecraft.world.storage.FolderName.ROOT).normalize().getFileName().toString(),mc.player.getGameProfile().getName(),mc.player.getGameProfile().getId().toString());
  }
  if(mc.isPaused() && bridge().needsWorldTick())mc.setScreen(null);
  mc.options.pauseOnLostFocus=focusPause.update(mc.options.pauseOnLostFocus,frozenPose!=null||Handoff.preparing()||bridge().needsWorldTick());
  if(Boolean.getBoolean("epoch.fixedView")){mc.options.fov=70;mc.options.bobView=false;mc.options.fovEffectScale=0;}
  if(resumeAt>0&&System.currentTimeMillis()>resumeAt){resumeAt=0;unfreeze(mc);}
  if(System.currentTimeMillis()-heartbeat>250){
   heartbeat=System.currentTimeMillis();
   if(mc.player!=null){bridge().diagnostics.put("clientCamera",java.util.Arrays.asList(mc.player.getX(),mc.player.getY(),mc.player.getZ(),mc.player.yRot,mc.player.xRot));bridge().diagnostics.put("clientSelectedSlot",mc.player.inventory.selected);bridge().diagnostics.put("clientFrozen",frozenPose!=null);}
   bridge().heartbeat();
  }
  if(!starting&&Boolean.getBoolean("epoch.autoStart")&&mc.screen instanceof MainMenuScreen){starting=true;mc.loadLevel(System.getProperty("epoch.world","Epoch Lab"));}
 }
}
