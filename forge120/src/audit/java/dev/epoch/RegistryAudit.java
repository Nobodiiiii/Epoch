package dev.epoch;
import dev.epoch.bridge.*;
import java.nio.file.*;
import java.util.*;
public final class RegistryAudit {
 public static void main(String[] args) throws Exception {
  // Seed the untransformed event listener list through the real Event API.
  // Normal Forge launch supplies this list with its event class transformer.
  new net.minecraftforge.network.NetworkEvent(() -> null).getListenerList();
  new net.minecraftforge.network.NetworkEvent.GatherLoginPayloadsEvent(new ArrayList<>(), false).getListenerList();
  net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
  Map<String,String> mods = Collections.singletonMap("minecraft",Epoch.VERSION);
  NativeStates.Context context = new NativeStates.Context(CompatibilityRules.load(CompatibilityRules.directory()),mods);
  Path output=Paths.get(args[0]);
  RegistryCatalog catalog=NativeStates.catalog(mods);
  catalog.write(output.resolve("catalog-"+Epoch.VERSION+".json"));
  Map<String,Object> own=context.audit(catalog);
  if(args.length>1)try(DirectoryStream<Path> files=Files.newDirectoryStream(output,"catalog-*.json")){
   for(Path source:files){RegistryCatalog input=RegistryCatalog.read(source);RegistryCatalog.writeJson(output.resolve("audit-"+input.version+"-to-"+Epoch.VERSION+".json"),context.audit(input));}
  }
  System.out.println("Registry exported "+Epoch.VERSION+": "+catalog.states.size()+" states");
 }
}
