package dev.epoch;

import dev.epoch.bridge.Appearance;
import dev.epoch.bridge.AppearanceAnimation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.model.*;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.client.renderer.tileentity.*;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.*;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.*;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.opengl.GL11;
import java.util.*;
import java.io.*;

final class AppearanceClient {
    private static final Map<ResourceLocation,String> exported = new HashMap<>();
    private static final Map<String,Material> textures = new HashMap<>();
    private static Material fallbackMaterial;
    private static final EnumFacing[] DIRECTIONS=EnumFacing.values();
    private static final net.minecraft.util.math.BlockPos.MutableBlockPos NEIGHBOR=new net.minecraft.util.math.BlockPos.MutableBlockPos();
    private static final class Material {
        final ResourceLocation location;
        final DynamicTexture texture;
        final AppearanceAnimation animation;
        Material(byte[] png) throws IOException {
            animation = new AppearanceAnimation(png);
            texture = new DynamicTexture(animation.width,animation.height);
            location = Minecraft.getMinecraft().getTextureManager().getDynamicTextureLocation("epoch_appearance",texture);
        }
        void update(long frame) {
            if (animation.update(texture.getTextureData(),frame,false)) texture.updateDynamicTexture();
        }
    }
    private static final ResourceLocation FALLBACK = new ResourceLocation("epoch", "textures/block/unknown_block.png");
    static void init() {
        ((net.minecraft.client.resources.IReloadableResourceManager)Minecraft.getMinecraft().getResourceManager()).registerReloadListener(manager -> {
            for(Material material:textures.values())Minecraft.getMinecraft().getTextureManager().deleteTexture(material.location);
            if(fallbackMaterial!=null)Minecraft.getMinecraft().getTextureManager().deleteTexture(fallbackMaterial.location);
            fallbackMaterial=null;
            textures.clear();exported.clear();Appearance.reload();
        });
        ClientRegistry.bindTileEntitySpecialRenderer(Epoch.UnknownTile.class, new Blocks());
        Item.getItemFromBlock(Epoch.UNKNOWN).setTileEntityItemStackRenderer(new Items());
    }
    static void tick() { if (Minecraft.getMinecraft().world != null) Appearance.tick(AppearanceClient::bake); }
    private static Appearance.Mesh bake(String text) throws Exception {
        Minecraft mc = Minecraft.getMinecraft(); IBlockState state = LegacyStates.decode(text);
        if (state == null || state.getBlock() == Epoch.UNKNOWN || state.getBlock() == net.minecraft.init.Blocks.AIR) return null;
        IBakedModel model = mc.getBlockRendererDispatcher().getModelForState(state);
        Appearance.Mesh mesh = new Appearance.Mesh();
        List<EnumFacing> sides = new ArrayList<>(Arrays.asList(EnumFacing.values())); sides.add(null);
        for (EnumFacing side : sides) for (BakedQuad quad : model.getQuads(state, side, 42)) {
            TextureAtlasSprite sprite = quad.getSprite(); ResourceLocation name = new ResourceLocation(sprite.getIconName());
            String texture = exported.get(name);
            if (texture == null) {
                ResourceLocation location = new ResourceLocation(name.getNamespace(), "textures/" + name.getPath() + ".png");
                texture = Appearance.texture(Appearance.firstFrame(Appearance.bytes(mc.getResourceManager().getResource(location).getInputStream())));
                exported.put(name, texture);
            }
            Appearance.Face face = new Appearance.Face(); face.texture = texture;
            EnumFacing normal = quad.getFace(); face.nx = normal.getXOffset(); face.ny = normal.getYOffset(); face.nz = normal.getZOffset();
            if (quad.hasTintIndex()) face.color = mc.getBlockColors().colorMultiplier(state, mc.world, mc.player == null ? net.minecraft.util.math.BlockPos.ORIGIN : mc.player.getPosition(), quad.getTintIndex());
            int[] data = quad.getVertexData(); int stride = data.length / 4;
            for (int i=0;i<4;i++) {
                for(int j=0;j<3;j++) face.vertices[i*5+j] = Float.intBitsToFloat(data[i*stride+j]);
                face.vertices[i*5+3] = (Float.intBitsToFloat(data[i*stride+4])-sprite.getMinU())/(sprite.getMaxU()-sprite.getMinU());
                face.vertices[i*5+4] = (Float.intBitsToFloat(data[i*stride+5])-sprite.getMinV())/(sprite.getMaxV()-sprite.getMinV());
            }
            mesh.faces.add(face);
        }
        mesh.occludes=state.isOpaqueCube();
        return mesh;
    }
    private static Material material(String source, String id) {
        Material found=id==null?fallbackMaterial:textures.get(id);if(found!=null)return found;
        try {
            if(id==null){fallbackMaterial=new Material(Appearance.bytes(Minecraft.getMinecraft().getResourceManager().getResource(FALLBACK).getInputStream()));return fallbackMaterial;}
            byte[] png=Appearance.image(source,id);if(png==null)return material(source,null);
            Material result=new Material(png);textures.put(id,result);return result;
        }catch(IOException error){return null;}
    }
    static void draw(String source, String version, String state, int hidden) {
        source=Appearance.source(source,version);
        Appearance.Mesh mesh=Appearance.get(source,state);if(mesh==null)mesh=CUBE;
        // Do not save/restore GL_TEXTURE_BIT: Minecraft's texture binding cache must
        // continue to agree with the actual binding, without glGetInteger per block.
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT|GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT|GL11.GL_CURRENT_BIT|GL11.GL_LIGHTING_BIT);
        Tessellator tess=Tessellator.getInstance(); BufferBuilder out=tess.getBuffer();
        boolean drawing=false;String activeTexture=null;Material active=null;
        try {
            GL11.glEnable(GL11.GL_CULL_FACE);GL11.glEnable(GL11.GL_ALPHA_TEST);
            long frame=AppearanceAnimation.frame();
            for(Appearance.Face face:mesh.faces){
                int side=Appearance.boundary(face);if(side>=0&&(hidden&(1<<side))!=0)continue;
                if(active==null||!Objects.equals(activeTexture,face.texture)){
                    if(drawing){tess.draw();drawing=false;}
                    active=material(source,face.texture);activeTexture=face.texture;
                    if(active==null)continue;
                    active.update(frame);
                    Minecraft.getMinecraft().getTextureManager().bindTexture(active.location);
                    if(active.animation.translucent){GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);GL11.glAlphaFunc(GL11.GL_GREATER,0);}
                    else{GL11.glDisable(GL11.GL_BLEND);GL11.glAlphaFunc(GL11.GL_GREATER,.1f);}
                    out.begin(GL11.GL_QUADS,DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);drawing=true;
                }
                float r=((face.color>>16)&255)/255f,g=((face.color>>8)&255)/255f,blue=(face.color&255)/255f;
                for(int i=0;i<4;i++)vertex(out,face,i,r,g,blue);
            }
        }finally{try{if(drawing)tess.draw();}finally{GL11.glPopAttrib();}}
    }
    private static void vertex(BufferBuilder out,Appearance.Face f,int index,float r,float g,float blue){
        float[] v=f.vertices;int offset=index*5;
        out.pos(v[offset],v[offset+1],v[offset+2]).tex(v[offset+3],v[offset+4]).color(r,g,blue,1).normal(f.nx,f.ny,f.nz).endVertex();
    }
    private static final Appearance.Mesh CUBE = makeFallback();
    private static Appearance.Mesh fallback() { return CUBE; }
    private static Appearance.Mesh makeFallback() {
        Appearance.Mesh mesh = new Appearance.Mesh();
        float[][] faces={{0,0,0, 0,0,1, 0,1,1, 0,1,0},{1,0,1, 1,0,0, 1,1,0, 1,1,1},{0,0,1, 0,0,0, 1,0,0, 1,0,1},{0,1,0, 0,1,1, 1,1,1, 1,1,0},{1,0,0, 0,0,0, 0,1,0, 1,1,0},{0,0,1, 1,0,1, 1,1,1, 0,1,1}};
        int[][] normals={{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
        for(int n=0;n<6;n++){Appearance.Face f=new Appearance.Face();f.nx=normals[n][0];f.ny=normals[n][1];f.nz=normals[n][2];for(int i=0;i<4;i++){for(int j=0;j<3;j++)f.vertices[i*5+j]=faces[n][i*3+j];f.vertices[i*5+3]=(i==1||i==2)?1:0;f.vertices[i*5+4]=i<2?1:0;}mesh.faces.add(f);}return mesh;
    }

    private static int hidden(Epoch.UnknownTile tile){
        if(tile.getWorld()==null)return 0;int mask=0;
        for(EnumFacing side:DIRECTIONS){
            net.minecraft.util.math.BlockPos pos=NEIGHBOR.setPos(tile.getPos().getX()+side.getXOffset(),tile.getPos().getY()+side.getYOffset(),tile.getPos().getZ()+side.getZOffset());
            if(tile.getWorld().getBlockState(pos).isOpaqueCube())mask|=1<<side.getIndex();
            else {net.minecraft.tileentity.TileEntity neighbor=tile.getWorld().getTileEntity(pos);
                if(neighbor instanceof Epoch.UnknownTile){Epoch.UnknownTile other=(Epoch.UnknownTile)neighbor;Appearance.Mesh mesh=Appearance.get(Appearance.source(other.sourceInstance,other.sourceVersion),other.originalState);if(mesh!=null&&mesh.occludes)mask|=1<<side.getIndex();}
            }
        }return mask;
    }
    public static final class Blocks extends TileEntitySpecialRenderer<Epoch.UnknownTile> {
        @Override public void render(Epoch.UnknownTile tile,double x,double y,double z,float partial,int destroy,float alpha) {
            GL11.glPushMatrix(); try { GL11.glTranslated(x,y,z); draw(tile.sourceInstance,tile.sourceVersion,tile.originalState,hidden(tile)); } finally { GL11.glPopMatrix(); }
        }
    }
    public static final class Items extends TileEntityItemStackRenderer {
        @Override public void renderByItem(ItemStack stack,float partial) {
            NBTTagCompound tag=stack.hasTagCompound()?stack.getTagCompound().getCompoundTag("BlockEntityTag"):new NBTTagCompound();
            draw(tag.getString("SourceInstance"),tag.getString("SourceVersion"),tag.getString("OriginalState"),0);
        }
    }
}
