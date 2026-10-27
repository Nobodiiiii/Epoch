package dev.epoch;
import dev.epoch.bridge.*;
import java.nio.file.*;
import java.util.*;
public final class RegistryAudit {
 public static void main(String[] args)throws Exception{
  cpw.mods.fml.common.Loader.injectData("7","99","40","1614","1.7.10","9.05",new java.io.File("."),java.util.Collections.emptyList()); net.minecraft.init.Bootstrap.func_151354_b();
  Map<String,String> mods=Collections.singletonMap("minecraft",Epoch.VERSION);
  Path output=Paths.get(args[0]);RegistryCatalog catalog=LegacyRegistry.catalog(mods);
  catalog.write(output.resolve("catalog-"+Epoch.VERSION+".json"));
  Map<String,Object> own=LegacyRegistry.audit(catalog,mods);
  RegistryCatalog.writeJson(output.resolve("audit-"+Epoch.VERSION+"-to-"+Epoch.VERSION+".json"),own);
  if(args.length>1)try(DirectoryStream<Path> files=Files.newDirectoryStream(output,"catalog-*.json")){
   for(Path source:files){RegistryCatalog input=RegistryCatalog.read(source);RegistryCatalog.writeJson(output.resolve("audit-"+input.version+"-to-"+Epoch.VERSION+".json"),LegacyRegistry.audit(input,mods));}
  }
  System.out.println("Registry exported "+Epoch.VERSION+": "+catalog.states.size()+" states; self audit "+own.get("stateTotals"));
 }
}
