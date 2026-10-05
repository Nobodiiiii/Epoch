package dev.epoch;

/** Discovered only by JourneyMap; Epoch remains usable when the map is absent. */
@journeymap.api.v2.common.JourneyMapPlugin(apiVersion="2.0.0")
public final class EpochMapPlugin implements journeymap.api.v2.client.IClientPlugin {
    public String getModId() { return "epoch"; }
    public void initialize(journeymap.api.v2.client.IClientAPI api) {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
        dev.epoch.bridge.JourneyMapOrigins.initialize(api, true);
    }
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void draw(net.minecraftforge.client.event.GuiScreenEvent.DrawScreenEvent.Post event) {
        final net.minecraft.client.Minecraft mc=net.minecraft.client.Minecraft.getInstance();
        dev.epoch.bridge.MapOriginLegend.draw(new dev.epoch.bridge.MapOriginLegend.Painter() {
            public void fill(int x0,int y0,int x1,int y1,int argb) { net.minecraft.client.gui.AbstractGui.fill(event.getMatrixStack(),x0,y0,x1,y1,argb); }
            public void text(String text,int x,int y,int argb) { mc.font.drawShadow(event.getMatrixStack(),text,x,y,argb); }
        }, event.getGui().width, event.getGui().height, org.lwjgl.glfw.GLFW.glfwGetKey(mc.getWindow().getWindow(),org.lwjgl.glfw.GLFW.GLFW_KEY_V)==org.lwjgl.glfw.GLFW.GLFW_PRESS);
    }
}
