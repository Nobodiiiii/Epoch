package dev.epoch;

import dev.epoch.bridge.Appearance;
import dev.epoch.bridge.AppearanceAnimation;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.model.BakedQuad;
import net.minecraft.client.renderer.tileentity.*;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.client.renderer.model.IBakedModel;
import net.minecraft.util.Direction;
import net.minecraft.nbt.*;
import net.minecraft.util.ResourceLocation;
import net.minecraft.item.*;
import net.minecraft.block.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;
import java.io.*;

/** All native model/texture access stays on the client render thread. */
@Mod.EventBusSubscriber(modid="epoch", bus=Mod.EventBusSubscriber.Bus.MOD, value=Dist.CLIENT)
public final class AppearanceClient {
    private static final Map<ResourceLocation,String> exported = new HashMap<>();
    private static final Map<String,Material> textures = new HashMap<>();
    private static Material fallbackMaterial;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final net.minecraft.util.math.BlockPos.Mutable NEIGHBOR = new net.minecraft.util.math.BlockPos.Mutable();
    private static final class Material {
        final ResourceLocation location;
        final DynamicTexture texture;
        final AppearanceAnimation animation;
        final int[] pixels;
        final RenderType renderType;
        Material(byte[] png) throws IOException {
            animation = new AppearanceAnimation(png);
            pixels = new int[animation.width * animation.height];
            texture = new DynamicTexture(new NativeImage(animation.width, animation.height, false));
            location = Minecraft.getInstance().getTextureManager().register("epoch_appearance", texture);
            renderType = animation.translucent ? RenderType.entityTranslucent(location) : RenderType.entityCutout(location);
        }
        void update(long frame) {
            if (!animation.update(pixels, frame, true)) return;
            NativeImage image = texture.getPixels();
            for (int y=0;y<animation.height;y++) for (int x=0;x<animation.width;x++)
                image.setPixelRGBA(x,y,pixels[y*animation.width+x]);
            texture.upload();
        }
    }
    private static final ResourceLocation FALLBACK = new ResourceLocation("epoch", "textures/block/unknown_block.png");
    @SubscribeEvent public static void renderers(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ClientRegistry.bindTileEntityRenderer(Epoch.UNKNOWN_TILE.get(), Blocks::new));
    }
    @SubscribeEvent public static void reloadSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ((net.minecraft.resources.IReloadableResourceManager)Minecraft.getInstance().getResourceManager()).registerReloadListener((net.minecraft.resources.IResourceManagerReloadListener) manager -> reset()));
    }
    private static void reset() {
        for (Material material : textures.values()) Minecraft.getInstance().getTextureManager().release(material.location);
        if (fallbackMaterial != null) Minecraft.getInstance().getTextureManager().release(fallbackMaterial.location);
        fallbackMaterial = null;
        textures.clear(); exported.clear(); Appearance.reload();
    }
    static void tick() { if (Minecraft.getInstance().level != null) Appearance.tick(AppearanceClient::bake); }
    private static Appearance.Mesh bake(String text) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        CompoundNBT tag = JsonToNBT.parseTag(text);
        BlockState state = NBTUtil.readBlockState(tag);
        if (state.isAir() || state.getBlock() == Epoch.UNKNOWN.get()) return null;
        IBakedModel model = mc.getBlockRenderer().getBlockModel(state);
        Appearance.Mesh mesh = new Appearance.Mesh();
        List<Direction> sides = new ArrayList<>(Arrays.asList(Direction.values())); sides.add(null);
        for (Direction side : sides) for (BakedQuad quad : model.getQuads(state, side, new Random(42))) {
            TextureAtlasSprite sprite = quad.getSprite(); ResourceLocation name = sprite.getName();
            String texture = exported.get(name);
            if (texture == null) {
                ResourceLocation location = new ResourceLocation(name.getNamespace(), "textures/" + name.getPath() + ".png");
                texture = Appearance.texture(Appearance.firstFrame(Appearance.bytes(mc.getResourceManager().getResource(location).getInputStream())));
                exported.put(name, texture);
            }
            Appearance.Face face = new Appearance.Face(); face.texture = texture;
            Direction normal = quad.getDirection(); face.nx = normal.getStepX(); face.ny = normal.getStepY(); face.nz = normal.getStepZ();
            if (quad.isTinted()) face.color = mc.getBlockColors().getColor(state, mc.level, mc.player == null ? net.minecraft.util.math.BlockPos.ZERO : mc.player.blockPosition(), quad.getTintIndex());
            int[] data = quad.getVertices(); int stride = data.length / 4;
            for (int i=0;i<4;i++) {
                for(int j=0;j<3;j++) face.vertices[i*5+j] = Float.intBitsToFloat(data[i*stride+j]);
                face.vertices[i*5+3] = (Float.intBitsToFloat(data[i*stride+4])-sprite.getU0())/(sprite.getU1()-sprite.getU0());
                face.vertices[i*5+4] = (Float.intBitsToFloat(data[i*stride+5])-sprite.getV0())/(sprite.getV1()-sprite.getV0());
            }
            mesh.faces.add(face);
        }
        mesh.occludes=state.isSolidRender(mc.level,mc.player == null ? net.minecraft.util.math.BlockPos.ZERO : mc.player.blockPosition());
        return mesh;
    }
    private static Material material(String source, String id) {
        Material found = id == null ? fallbackMaterial : textures.get(id);
        if (found != null) return found;
        try {
            if (id == null) {
                fallbackMaterial = new Material(Appearance.bytes(Minecraft.getInstance().getResourceManager().getResource(FALLBACK).getInputStream()));
                return fallbackMaterial;
            }
            byte[] png = Appearance.image(source,id);
            if (png == null) return material(source,null);
            Material result = new Material(png); textures.put(id,result); return result;
        } catch (IOException error) { return null; }
    }
    static void draw(String source, String version, String state, MatrixStack pose, IRenderTypeBuffer buffers, int light, int overlay, int hidden) {
        source=Appearance.source(source,version);
        Appearance.Mesh mesh = Appearance.get(source, state);
        if (mesh == null) mesh = fallback();
        long frame = AppearanceAnimation.frame();
        Material previous = null;
        IVertexBuilder out = null;
        for (Appearance.Face face : mesh.faces) {
            int side=Appearance.boundary(face);if(side>=0&&(hidden&(1<<side))!=0)continue;
            Material selected = material(source,face.texture);
            if (selected == null) continue;
            if (selected != previous) {
                selected.update(frame);
                out = buffers.getBuffer(selected.renderType); previous = selected;
            }
            float red=((face.color>>16)&255)/255f, green=((face.color>>8)&255)/255f, blue=(face.color&255)/255f;
            for (int i=0;i<4;i++) vertex(out,pose,face,i,red,green,blue,light,overlay);
        }
    }
    private static void vertex(IVertexBuilder out, MatrixStack pose, Appearance.Face f, int index, float r, float g, float blue, int light, int overlay) {
        float[] v=f.vertices; int offset=index*5;
        out.vertex(pose.last().pose(),v[offset],v[offset+1],v[offset+2]).color(r,g,blue,1).uv(v[offset+3],v[offset+4])
            .overlayCoords(overlay).uv2(light).normal(pose.last().normal(),f.nx,f.ny,f.nz).endVertex();
    }
    private static final Appearance.Mesh CUBE = makeFallback();
    private static Appearance.Mesh fallback() { return CUBE; }
    private static Appearance.Mesh makeFallback() {
        Appearance.Mesh mesh = new Appearance.Mesh();
        float[][] faces={{0,0,0, 0,0,1, 0,1,1, 0,1,0},{1,0,1, 1,0,0, 1,1,0, 1,1,1},{0,0,1, 0,0,0, 1,0,0, 1,0,1},{0,1,0, 0,1,1, 1,1,1, 1,1,0},{1,0,0, 0,0,0, 0,1,0, 1,1,0},{0,0,1, 1,0,1, 1,1,1, 0,1,1}};
        int[][] normals={{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
        for(int n=0;n<6;n++){Appearance.Face f=new Appearance.Face();f.nx=normals[n][0];f.ny=normals[n][1];f.nz=normals[n][2];for(int i=0;i<4;i++){for(int j=0;j<3;j++)f.vertices[i*5+j]=faces[n][i*3+j];f.vertices[i*5+3]=(i==1||i==2)?1:0;f.vertices[i*5+4]=i<2?1:0;}mesh.faces.add(f);}return mesh;
    }
    private static int hidden(Epoch.UnknownTile tile) {
        if(tile.getLevel()==null)return 0;int mask=0;
        for(Direction side:DIRECTIONS){
            net.minecraft.util.math.BlockPos pos=NEIGHBOR.set(tile.getBlockPos().getX()+side.getStepX(),tile.getBlockPos().getY()+side.getStepY(),tile.getBlockPos().getZ()+side.getStepZ());
            if(tile.getLevel().getBlockState(pos).isSolidRender(tile.getLevel(),pos))mask|=1<<side.get3DDataValue();
            else { net.minecraft.tileentity.TileEntity neighbor=tile.getLevel().getBlockEntity(pos);
                if(neighbor instanceof Epoch.UnknownTile){Epoch.UnknownTile other=(Epoch.UnknownTile)neighbor;
                    Appearance.Mesh mesh=Appearance.get(Appearance.source(other.sourceInstance,other.sourceVersion),other.originalState);
                    if(mesh!=null&&mesh.occludes)mask|=1<<side.get3DDataValue();
                }
            }
        }return mask;
    }
    public static final class Blocks extends TileEntityRenderer<Epoch.UnknownTile> {
        Blocks(TileEntityRendererDispatcher dispatcher) { super(dispatcher); }
        public void render(Epoch.UnknownTile tile, float partial, MatrixStack pose, IRenderTypeBuffer buffers, int light, int overlay) { draw(tile.sourceInstance,tile.sourceVersion,tile.originalState,pose,buffers,light,overlay,hidden(tile)); }
    }
    public static final class Items extends ItemStackTileEntityRenderer {
        static final Items INSTANCE = new Items();
        public Items() {}
        @Override public void renderByItem(ItemStack stack, net.minecraft.client.renderer.model.ItemCameraTransforms.TransformType context, MatrixStack pose, IRenderTypeBuffer buffers, int light, int overlay) {
            CompoundNBT tag = stack.getTagElement("BlockEntityTag");
            draw(tag == null ? "" : tag.getString("SourceInstance"), tag == null ? "" : tag.getString("SourceVersion"), tag == null ? "" : tag.getString("OriginalState"), pose,buffers,light,overlay,0);
        }
    }
}
