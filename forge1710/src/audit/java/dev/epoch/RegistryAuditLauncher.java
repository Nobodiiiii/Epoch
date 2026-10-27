package dev.epoch;
/** Provides the legacy registry exporter with Forge's expected class loader. */
public final class RegistryAuditLauncher {
 public static void main(String[] args)throws Exception {
  java.net.URL[] urls=((java.net.URLClassLoader)RegistryAuditLauncher.class.getClassLoader()).getURLs();
  net.minecraft.launchwrapper.LaunchClassLoader loader=new net.minecraft.launchwrapper.LaunchClassLoader(urls);
  net.minecraft.launchwrapper.Launch.classLoader=loader;
  net.minecraft.launchwrapper.Launch.blackboard=new java.util.HashMap<String,Object>();
  Thread.currentThread().setContextClassLoader(loader);
  Class.forName("dev.epoch.RegistryAudit",true,loader).getMethod("main",String[].class).invoke(null,(Object)args);
 }
}
