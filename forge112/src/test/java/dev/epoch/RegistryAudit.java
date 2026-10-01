package dev.epoch;
import dev.epoch.bridge.*;
import java.nio.file.*;
import java.util.*;
public final class RegistryAudit {
 public static void main(String[] args)throws Exception{
  net.minecraft.init.Bootstrap.register();
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
