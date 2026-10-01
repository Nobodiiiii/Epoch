package dev.epoch;

/** Gives Forge's registry code its expected loader without starting Minecraft. */
public final class LegacyTestLauncher {
 public static void main(String[] args)throws Exception{
  java.net.URL[] urls=((java.net.URLClassLoader)LegacyTestLauncher.class.getClassLoader()).getURLs();
  net.minecraft.launchwrapper.LaunchClassLoader loader=new net.minecraft.launchwrapper.LaunchClassLoader(urls);
  net.minecraft.launchwrapper.Launch.classLoader=loader;
  net.minecraft.launchwrapper.Launch.blackboard=new java.util.HashMap<String,Object>();
  Thread.currentThread().setContextClassLoader(loader);
  Class.forName("dev.epoch.LegacySelfTest",true,loader).getMethod("main",String[].class).invoke(null,(Object)args);
 }
}
