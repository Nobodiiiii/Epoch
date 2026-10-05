package dev.epoch.core;

import java.util.Map;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.SortingIndex(1001)
@IFMLLoadingPlugin.TransformerExclusions({"dev.epoch.core."})
public final class StreamingPlugin implements IFMLLoadingPlugin {
    public String[] getASMTransformerClass() { return new String[]{"dev.epoch.core.StreamingTransformer"}; }
    public String getModContainerClass() { return null; }
    public String getSetupClass() { return null; }
    public void injectData(Map<String,Object> data) { }
    public String getAccessTransformerClass() { return null; }
}
