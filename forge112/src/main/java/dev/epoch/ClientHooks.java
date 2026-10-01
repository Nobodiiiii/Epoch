package dev.epoch;

import dev.epoch.bridge.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.Display;
import java.util.concurrent.*;

public final class ClientHooks {
 private static BridgeFiles files;
 private static final Object readyLock=new Object();
 private static boolean starting,wasNoGravity;
 private static long heartbeat;
 private static volatile double[] frozenPose;
 private static volatile CompletableFuture<double[]> frameCapture;
 static volatile int renderTicks;
 static volatile long resumeAt;
 static BridgeFiles bridge(){if(files==null)files=new BridgeFiles(AutoRuntime.controlDirectory(Minecraft.getMinecraft().gameDir.toPath(),Epoch.VERSION),Epoch.VERSION);return files;}
 static long hwnd(){try{java.lang.reflect.Method method=Display.class.getDeclaredMethod("getImplementation");method.setAccessible(true);Object impl=method.invoke(null);java.lang.reflect.Field field=impl.getClass().getDeclaredField("hwnd");field.setAccessible(true);return field.getLong(impl);}catch(Exception error){bridge().diagnostics.put("windowHandleError",error.toString());return 0;}}
 static double[] captureFrozen()throws Exception{CompletableFuture<double[]> done=new CompletableFuture<double[]>();frameCapture=done;return done.get(15,TimeUnit.SECONDS);}
 private static void completeCapture(Minecraft mc,float partial){CompletableFuture<double[]> done=frameCapture;if(done==null)return;frameCapture=null;try{
  java.awt.image.BufferedImage image=ScreenShotHelper.createScreenshot(mc.displayWidth,mc.displayHeight,mc.getFramebuffer());
  javax.imageio.ImageIO.write(image,"png",AutoRuntime.controlDirectory(mc.gameDir.toPath(),Epoch.VERSION).resolve("frozen.png").toFile());
  if(mc.player==null)throw new IllegalStateException("No player camera");
  double[] pose={mc.player.lastTickPosX+(mc.player.posX-mc.player.lastTickPosX)*partial,mc.player.lastTickPosY+(mc.player.posY-mc.player.lastTickPosY)*partial,mc.player.lastTickPosZ+(mc.player.posZ-mc.player.lastTickPosZ)*partial,mc.player.prevRotationYaw+(mc.player.rotationYaw-mc.player.prevRotationYaw)*partial,mc.player.prevRotationPitch+(mc.player.rotationPitch-mc.player.prevRotationPitch)*partial};
  double rounded=Math.rint(pose[1]);if(Math.abs(pose[1]-rounded)<0.000001)pose[1]=rounded;applyFreeze(mc,pose);done.complete(pose);
 }catch(Exception error){done.completeExceptionally(error);}}
 static void markImported(String id){synchronized(readyLock){renderTicks=0;bridge().clientReady=false;bridge().status(id,"IMPORTED","Snapshot applied; simulation frozen");}}
 static void request(){Minecraft mc=Minecraft.getMinecraft();mc.addScheduledTask(()->{if(mc.isSingleplayer()&&mc.player!=null)mc.displayGuiScreen(new VersionListScreen());});}
 static void catalog(){Minecraft mc=Minecraft.getMinecraft();bridge().diagnostics.put("instances",AutoRuntime.instances(mc.gameDir.toPath()));bridge().diagnostics.put("runtimeStatus",AutoRuntime.statusText(mc.gameDir.toPath()));bridge().diagnostics.put("listScreenOpen",mc.currentScreen instanceof VersionListScreen);}
 static void select(String id){Minecraft mc=Minecraft.getMinecraft();AutoRuntime.select(mc.gameDir.toPath(),Epoch.VERSION,id);mc.addScheduledTask(()->{if(mc.currentScreen instanceof VersionListScreen)mc.displayGuiScreen(null);});}
 static void useKey(String id){Minecraft mc=Minecraft.getMinecraft();mc.addScheduledTask(()->{try{if(mc.player==null||mc.world==null)throw new IllegalStateException("No local player");int slot=-1;for(int n=0;n<9;n++)if(mc.player.inventory.getStackInSlot(n).getItem()==Epoch.KEY){slot=n;break;}if(slot<0)throw new IllegalStateException("Epoch Key missing from hotbar");mc.player.inventory.currentItem=slot;Epoch.KEY.onItemRightClick(mc.world,mc.player,EnumHand.MAIN_HAND);}catch(Exception error){bridge().status(id,"ERROR",error.toString());}});}
 public static class FreezeScreen extends GuiScreen {public boolean doesGuiPauseGame(){return false;}protected void keyTyped(char c,int key){}public void drawScreen(int x,int y,float partial){}}
 private static void lockPose(Minecraft mc){double[] pose=frozenPose;if(pose==null||mc.player==null)return;mc.player.setPosition(pose[0],pose[1],pose[2]);mc.player.prevPosX=mc.player.lastTickPosX=pose[0];mc.player.prevPosY=mc.player.lastTickPosY=pose[1];mc.player.prevPosZ=mc.player.lastTickPosZ=pose[2];mc.player.rotationYaw=mc.player.prevRotationYaw=(float)pose[3];mc.player.rotationPitch=mc.player.prevRotationPitch=(float)pose[4];mc.player.motionX=mc.player.motionY=mc.player.motionZ=0;mc.player.setNoGravity(true);}
 private static void applyFreeze(Minecraft mc,double[] pose){if(frozenPose==null&&mc.player!=null)wasNoGravity=mc.player.hasNoGravity();frozenPose=pose;KeyBinding.unPressAllKeys();if(!(mc.currentScreen instanceof FreezeScreen))mc.displayGuiScreen(new FreezeScreen());mc.setIngameNotInFocus();lockPose(mc);}
 static void freezeAt(double x,double y,double z,float yaw,float pitch)throws Exception{Minecraft mc=Minecraft.getMinecraft();CompletableFuture<Void> ready=new CompletableFuture<Void>();mc.addScheduledTask(()->{try{applyFreeze(mc,new double[]{x,y,z,yaw,pitch});ready.complete(null);}catch(Exception error){ready.completeExceptionally(error);}});ready.get(10,TimeUnit.SECONDS);}
 static void freezeCurrent(){Minecraft mc=Minecraft.getMinecraft();mc.addScheduledTask(()->{if(mc.player!=null)applyFreeze(mc,new double[]{mc.player.posX,mc.player.posY,mc.player.posZ,mc.player.rotationYaw,mc.player.rotationPitch});});}
 private static void unfreeze(Minecraft mc){frozenPose=null;if(mc.player!=null)mc.player.setNoGravity(wasNoGravity);if(mc.currentScreen instanceof FreezeScreen)mc.displayGuiScreen(null);mc.setIngameFocus();}
 @SubscribeEvent public void render(TickEvent.RenderTickEvent event){Minecraft mc=Minecraft.getMinecraft();if(event.phase==TickEvent.Phase.START){lockPose(mc);return;}completeCapture(mc,event.renderTickTime);bridge().windowHandle=hwnd();synchronized(readyLock){bridge().clientReady=mc.world!=null&&mc.player!=null&&(mc.currentScreen==null||mc.currentScreen instanceof FreezeScreen)&&++renderTicks>20&&mc.renderGlobal.hasNoChunkUpdates();}}
 @SubscribeEvent public void tick(TickEvent.ClientTickEvent event){Minecraft mc=Minecraft.getMinecraft();lockPose(mc);if(event.phase!=TickEvent.Phase.END)return;
  if(mc.isGamePaused()&&bridge().needsWorldTick())mc.displayGuiScreen(null);
  if(mc.world!=null&&mc.player!=null&&mc.isSingleplayer()){if(mc.isFullScreen())mc.toggleFullscreen();AutoRuntime.tick(mc.gameDir.toPath(),Epoch.VERSION,hwnd(),mc.getIntegratedServer().getFolderName(),mc.player.getGameProfile().getName(),mc.player.getGameProfile().getId().toString());}
  mc.gameSettings.pauseOnLostFocus=false;mc.gameSettings.fovSetting=70;mc.gameSettings.viewBobbing=false;mc.gameSettings.thirdPersonView=0;
  if(resumeAt>0&&System.currentTimeMillis()>resumeAt){resumeAt=0;unfreeze(mc);}
  if(System.currentTimeMillis()-heartbeat>250){heartbeat=System.currentTimeMillis();if(mc.player!=null){bridge().diagnostics.put("clientCamera",java.util.Arrays.asList(mc.player.posX,mc.player.posY,mc.player.posZ,mc.player.rotationYaw,mc.player.rotationPitch));bridge().diagnostics.put("clientSelectedSlot",mc.player.inventory.currentItem);bridge().diagnostics.put("clientFrozen",frozenPose!=null);}bridge().heartbeat();}
  if(!starting&&Boolean.getBoolean("epoch.autoStart")&&mc.currentScreen instanceof GuiMainMenu){starting=true;String world=System.getProperty("epoch.world","Epoch Lab");mc.launchIntegratedServer(world,world,null);}
 }
 @SubscribeEvent public void fixedFov(net.minecraftforge.client.event.FOVUpdateEvent event){event.setNewfov(1.0f);}
}

