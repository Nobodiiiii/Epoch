package dev.epoch;

/** Discovered only by JourneyMap; Epoch remains usable when the map is absent. */
@journeymap.client.api.ClientPlugin
public final class EpochMapPlugin implements journeymap.client.api.IClientPlugin {
    public String getModId() { return "epoch"; }
    public void initialize(journeymap.client.api.IClientAPI api) {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
        dev.epoch.bridge.JourneyMapOrigins.initialize(api, false);
    }
    public void onEvent(journeymap.client.api.event.ClientEvent event) {}
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void draw(net.minecraftforge.client.event.ScreenEvent.DrawScreenEvent.Post event) {
        final net.minecraft.client.Minecraft mc=net.minecraft.client.Minecraft.getInstance();
        dev.epoch.bridge.MapOriginLegend.draw(new dev.epoch.bridge.MapOriginLegend.Painter() {
            public void fill(int x0,int y0,int x1,int y1,int argb) { net.minecraft.client.gui.GuiComponent.fill(event.getPoseStack(),x0,y0,x1,y1,argb); }
            public void text(String text,int x,int y,int argb) { mc.font.drawShadow(event.getPoseStack(),text,x,y,argb); }
        }, event.getScreen().width, event.getScreen().height, org.lwjgl.glfw.GLFW.glfwGetKey(mc.getWindow().getWindow(),org.lwjgl.glfw.GLFW.GLFW_KEY_V)==org.lwjgl.glfw.GLFW.GLFW_PRESS);
    }
}
