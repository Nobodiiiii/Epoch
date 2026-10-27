package dev.epoch;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dev.epoch.bridge.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import org.lwjgl.opengl.Display;
import java.lang.reflect.*;
import java.util.concurrent.*;

public final class ClientHooks {
 private static final dev.epoch.bridge.PauseOverride focusPause = new dev.epoch.bridge.PauseOverride();
 static void resetWorld(){execute(() -> {Minecraft mc=Minecraft.getMinecraft();mc.gameSettings.pauseOnLostFocus=focusPause.update(mc.gameSettings.pauseOnLostFocus,false);frozenPose=null;listClosePose=null;listCloseFrames=0;resumeAt=0;frames=0;terrainReadyLastFrame=false;if(capture!=null)capture.cancel(false);capture=null;});}
 private static BridgeFiles files;
 private static final ConcurrentLinkedQueue<Runnable> tasks=new ConcurrentLinkedQueue<Runnable>();
 private static volatile double[] frozenPose;
 private static volatile double[] listClosePose;private static volatile int listCloseFrames;
 private static volatile CompletableFuture<double[]> capture;
 private static volatile int frames;private static volatile boolean terrainReadyLastFrame;
 static volatile long resumeAt;
 private static long heartbeat;private static boolean starting;
 static BridgeFiles bridge(){if(files==null)files=new BridgeFiles(AutoRuntime.controlDirectory(Minecraft.getMinecraft().mcDataDir.toPath(),Epoch.VERSION),Epoch.VERSION);return files;}
 static void execute(Runnable action){tasks.add(action);}
 static long hwnd(){
  try{Method method=Display.class.getDeclaredMethod("getImplementation");method.setAccessible(true);Object implementation=method.invoke(null);Field field=implementation.getClass().getDeclaredField("hwnd");field.setAccessible(true);return field.getLong(implementation);}
  catch(Exception error){bridge().diagnostics.put("windowError",error.toString());return 0;}
 }
 static double[] captureFrozen()throws Exception{CompletableFuture<double[]> done=new CompletableFuture<double[]>();capture=done;return done.get(15,TimeUnit.SECONDS);}
 static void freezeCurrent(){execute(()->{Minecraft mc=Minecraft.getMinecraft();if(mc.thePlayer!=null)applyFreeze(pose(mc));});}
 static void freezeAt(double x,double y,double z,float yaw,float pitch)throws Exception{CompletableFuture<Void> done=new CompletableFuture<Void>();execute(()->{try{applyFreeze(new double[]{x,y,z,yaw,pitch});done.complete(null);}catch(Exception e){done.completeExceptionally(e);}});done.get(10,TimeUnit.SECONDS);}
 static void markImported(String id){frames=0;terrainReadyLastFrame=false;bridge().clientReady=false;bridge().status(id,"IMPORTED","Snapshot applied; waiting for rendered terrain");execute(()->Minecraft.getMinecraft().renderGlobal.loadRenderers());}
 static double[] pose(Minecraft mc){return new double[]{mc.thePlayer.posX,mc.thePlayer.boundingBox.minY,mc.thePlayer.posZ,mc.thePlayer.rotationYaw,mc.thePlayer.rotationPitch};}
 static void applyFreeze(double[] pose){frozenPose=pose;Minecraft mc=Minecraft.getMinecraft();KeyBinding.unPressAllKeys();mc.displayGuiScreen(new FreezeScreen());mc.setIngameNotInFocus();lockPose(mc);}
 static void lockPose(Minecraft mc){double[] pose=frozenPose!=null?frozenPose:listCloseFrames>0?listClosePose:null;if(pose==null||mc.thePlayer==null)return;mc.thePlayer.ySize=0;mc.thePlayer.setPosition(pose[0],pose[1]+mc.thePlayer.yOffset,pose[2]);mc.thePlayer.prevPosX=mc.thePlayer.lastTickPosX=mc.thePlayer.posX;mc.thePlayer.prevPosY=mc.thePlayer.lastTickPosY=mc.thePlayer.posY;mc.thePlayer.prevPosZ=mc.thePlayer.lastTickPosZ=mc.thePlayer.posZ;mc.thePlayer.rotationYaw=mc.thePlayer.prevRotationYaw=(float)pose[3];mc.thePlayer.rotationPitch=mc.thePlayer.prevRotationPitch=(float)pose[4];mc.thePlayer.motionX=mc.thePlayer.motionY=mc.thePlayer.motionZ=0;}
 static void listClosed(){Minecraft mc=Minecraft.getMinecraft();if(mc.thePlayer!=null){listClosePose=pose(mc);listCloseFrames=2;}flushMouse(mc);}
 private static void flushMouse(Minecraft mc){if(org.lwjgl.input.Mouse.isCreated()){org.lwjgl.input.Mouse.getDX();org.lwjgl.input.Mouse.getDY();}mc.mouseHelper.deltaX=mc.mouseHelper.deltaY=0;}
 public static final class FreezeScreen extends GuiScreen {public boolean doesGuiPauseGame(){return false;}public void drawScreen(int x,int y,float partial){}protected void keyTyped(char ch,int key){}}
 static void request(){execute(()->{Minecraft mc=Minecraft.getMinecraft();if(mc.isSingleplayer()&&mc.thePlayer!=null)mc.displayGuiScreen(new VersionListScreen());else if(mc.thePlayer!=null)mc.thePlayer.addChatMessage(new ChatComponentText("Epoch supports singleplayer only"));});}
 static void select(String id){Minecraft mc=Minecraft.getMinecraft();AutoRuntime.select(mc.mcDataDir.toPath(),Epoch.VERSION,id);execute(()->{if(mc.currentScreen instanceof VersionListScreen)mc.displayGuiScreen(null);});}
 static void catalog(){Minecraft mc=Minecraft.getMinecraft();bridge().diagnostics.put("instances",AutoRuntime.instances(mc.mcDataDir.toPath()));bridge().diagnostics.put("runtimeStatus",AutoRuntime.statusText(mc.mcDataDir.toPath()));bridge().diagnostics.put("listScreenOpen",mc.currentScreen instanceof VersionListScreen);}
 static void useKey(String id){execute(()->{try{Minecraft mc=Minecraft.getMinecraft();int slot=-1;for(int n=0;n<9;n++)if(mc.thePlayer.inventory.getStackInSlot(n)!=null&&mc.thePlayer.inventory.getStackInSlot(n).getItem()==Epoch.KEY){slot=n;break;}if(slot<0)throw new IllegalStateException("Epoch Key missing from hotbar");mc.thePlayer.inventory.currentItem=slot;Epoch.KEY.onItemRightClick(mc.thePlayer.getHeldItem(),mc.theWorld,mc.thePlayer);}catch(Exception e){bridge().status(id,"ERROR",e.toString());}});}
 @SubscribeEvent public void render(TickEvent.RenderTickEvent event){
  if(event.phase==TickEvent.Phase.START)AppearanceClient.tick();
  Minecraft mc=Minecraft.getMinecraft();if(event.phase==TickEvent.Phase.START){if(listCloseFrames>0)flushMouse(mc);lockPose(mc);return;}
  if(listCloseFrames>0){flushMouse(mc);lockPose(mc);if(--listCloseFrames==0)listClosePose=null;}
  CompletableFuture<double[]> done=capture;if(done!=null){capture=null;try{double[] pose=pose(mc);java.io.File dir=AutoRuntime.controlDirectory(mc.mcDataDir.toPath(),Epoch.VERSION).toFile();net.minecraft.util.ScreenShotHelper.saveScreenshot(dir,"frozen.png",mc.displayWidth,mc.displayHeight,mc.getFramebuffer());java.nio.file.Path generated=dir.toPath().resolve("screenshots/frozen.png");if(java.nio.file.Files.exists(generated))java.nio.file.Files.move(generated,dir.toPath().resolve("frozen.png"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);applyFreeze(pose);done.complete(pose);}catch(Exception e){done.completeExceptionally(e);}}
  int received=mc.theWorld!=null&&mc.thePlayer!=null?ClientTerrain.receivedNearby(mc.theWorld.getChunkProvider(),mc.thePlayer.posX,mc.thePlayer.posZ):0;
  bridge().diagnostics.put("clientReceivedNearbyChunks",received);
  bridge().windowHandle=hwnd();boolean terrainReady=AppearanceClient.packedReady&&received==9&&(mc.currentScreen==null||mc.currentScreen instanceof FreezeScreen)&&++frames>20&&mc.renderGlobal.updateRenderers(mc.thePlayer,false);bridge().clientReady=terrainReady&&terrainReadyLastFrame;terrainReadyLastFrame=terrainReady;
 }
 @SubscribeEvent public void tick(TickEvent.ClientTickEvent event){
  Minecraft mc=Minecraft.getMinecraft();lockPose(mc);if(event.phase!=TickEvent.Phase.END)return;
  dev.epoch.bridge.JourneyMapOrigins.tick(bridge().diagnostics,mc.theWorld!=null&&mc.thePlayer!=null);
  for(Runnable action;(action=tasks.poll())!=null;)action.run();
  if(mc.isGamePaused()&&bridge().needsWorldTick())mc.displayGuiScreen(null);
  if(mc.theWorld!=null&&mc.thePlayer!=null&&mc.isSingleplayer()){
   if(mc.isFullScreen())mc.toggleFullscreen();
   AutoRuntime.tick(mc.mcDataDir.toPath(),Epoch.VERSION,hwnd(),mc.getIntegratedServer().getFolderName(),mc.thePlayer.getGameProfile().getName(),mc.thePlayer.getGameProfile().getId().toString());
  }
  mc.gameSettings.pauseOnLostFocus=focusPause.update(mc.gameSettings.pauseOnLostFocus,frozenPose!=null||Handoff.preparing()||bridge().needsWorldTick());
  if(Boolean.getBoolean("epoch.fixedView")){mc.gameSettings.fovSetting=70;mc.gameSettings.viewBobbing=false;}
  if(resumeAt>0&&System.currentTimeMillis()>resumeAt){resumeAt=0;frozenPose=null;if(mc.currentScreen instanceof FreezeScreen)mc.displayGuiScreen(null);mc.setIngameFocus();}
  if(System.currentTimeMillis()-heartbeat>250){heartbeat=System.currentTimeMillis();if(mc.thePlayer!=null){bridge().diagnostics.put("clientCamera",pose(mc));bridge().diagnostics.put("clientSelectedSlot",mc.thePlayer.inventory.currentItem);bridge().diagnostics.put("clientFrozen",frozenPose!=null);}bridge().heartbeat();}
  if(!starting&&Boolean.getBoolean("epoch.autoStart")&&mc.currentScreen instanceof GuiMainMenu){starting=true;String name=System.getProperty("epoch.world","Epoch Lab");mc.launchIntegratedServer(name,name,null);}
 }
}
