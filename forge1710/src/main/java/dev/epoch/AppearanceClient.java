package dev.epoch;

import dev.epoch.bridge.Appearance;
import dev.epoch.bridge.AppearanceAnimation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;

import net.minecraft.client.renderer.texture.*;
import net.minecraft.client.renderer.tileentity.*;

import net.minecraft.block.Block;
import net.minecraft.item.*;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.*;
import cpw.mods.fml.client.registry.ClientRegistry;
import org.lwjgl.opengl.GL11;
import java.util.*;
import java.io.*;

final class AppearanceClient {
    private static final Map<ResourceLocation,String> exported = new HashMap<>();
    private static final Map<String,Material> textures = new HashMap<>();
    private static Material fallbackMaterial;
    private static long resourceEpoch;
    static volatile boolean packedReady=true;
    private static final int[][] OFFSETS={{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
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
    private static final ResourceLocation FALLBACK = new ResourceLocation("epoch", "textures/blocks/unknown_block.png");
    static void init() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new PackedWorld());
        ((net.minecraft.client.resources.IReloadableResourceManager)Minecraft.getMinecraft().getResourceManager()).registerReloadListener(manager -> {
            for(Material material:textures.values())Minecraft.getMinecraft().getTextureManager().deleteTexture(material.location);
            if(fallbackMaterial!=null)Minecraft.getMinecraft().getTextureManager().deleteTexture(fallbackMaterial.location);
            fallbackMaterial=null;
            textures.clear();exported.clear();Appearance.reload();resourceEpoch++;
        });
        ClientRegistry.bindTileEntitySpecialRenderer(Epoch.UnknownTile.class, new Blocks());
        net.minecraftforge.client.MinecraftForgeClient.registerItemRenderer(Item.getItemFromBlock(Epoch.UNKNOWN),new Items());
    }
    static void tick() { if (Minecraft.getMinecraft().theWorld != null) Appearance.tick(AppearanceClient::bake); }
    private static Appearance.Mesh bake(String text) throws Exception {
        LegacyStates.State state=LegacyStates.decode(text);
        if(state==null||state.block==Epoch.UNKNOWN||state.block==net.minecraft.init.Blocks.air)return null;
        // Pre-model-system blocks expose icons and bounds. Special render types have no portable mesh.
        if(state.block.getRenderType()!=0)return null;
        Appearance.Mesh mesh=makeFallback(); int[] sides={4,5,0,1,2,3};
        for(int n=0;n<6;n++){
            IIcon icon=state.block.getIcon(sides[n],state.meta);if(icon==null)return null;
            ResourceLocation name=new ResourceLocation(icon.getIconName());String texture=exported.get(name);
            if(texture==null){ResourceLocation path=new ResourceLocation(name.getResourceDomain(),"textures/blocks/"+name.getResourcePath()+".png");texture=Appearance.texture(Appearance.firstFrame(Appearance.bytes(Minecraft.getMinecraft().getResourceManager().getResource(path).getInputStream())));exported.put(name,texture);}
            Appearance.Face face=mesh.faces.get(n);face.texture=texture;face.color=state.block.getRenderColor(state.meta);
        }
        mesh.occludes=state.block.isOpaqueCube();
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
        Tessellator tess=Tessellator.instance; Tessellator out=tess;
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
                    out.startDrawingQuads();drawing=true;
                }
                float r=((face.color>>16)&255)/255f,g=((face.color>>8)&255)/255f,blue=(face.color&255)/255f;
                for(int i=0;i<4;i++)vertex(out,face,i,r,g,blue);
            }
        }finally{try{if(drawing)tess.draw();}finally{GL11.glPopAttrib();}}
    }
    private static void vertex(Tessellator out,Appearance.Face f,int index,float r,float g,float blue){
        float[] v=f.vertices;int offset=index*5;
        out.setColorRGBA_F(r,g,blue,1);out.setNormal(f.nx,f.ny,f.nz);out.addVertexWithUV(v[offset],v[offset+1],v[offset+2],v[offset+3],v[offset+4]);
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
        if(tile.getWorldObj()==null)return 0;int mask=0;
        for(int side=0;side<6;side++){
            int x=tile.xCoord+OFFSETS[side][0],y=tile.yCoord+OFFSETS[side][1],z=tile.zCoord+OFFSETS[side][2];
            if(tile.getWorldObj().getBlock(x,y,z).isOpaqueCube())mask|=1<<side;
            else{net.minecraft.tileentity.TileEntity neighbor=tile.getWorldObj().getTileEntity(x,y,z);if(neighbor instanceof Epoch.UnknownTile){Epoch.UnknownTile other=(Epoch.UnknownTile)neighbor;Appearance.Mesh mesh=Appearance.get(Appearance.source(other.sourceInstance,other.sourceVersion),other.originalState);if(mesh!=null&&mesh.occludes)mask|=1<<side;}}
        }return mask;
    }
    public static final class Blocks extends TileEntitySpecialRenderer {
        @Override public void renderTileEntityAt(net.minecraft.tileentity.TileEntity value,double x,double y,double z,float partial) {
            Epoch.UnknownTile tile=(Epoch.UnknownTile)value;
            GL11.glPushMatrix(); try { GL11.glTranslated(x,y,z); draw(tile.sourceInstance,tile.sourceVersion,tile.originalState,hidden(tile)); } finally { GL11.glPopMatrix(); }
        }
    }
    public static final class Items implements net.minecraftforge.client.IItemRenderer {
        public boolean handleRenderType(ItemStack stack,ItemRenderType type){return true;}
        public boolean shouldUseRenderHelper(ItemRenderType type,ItemStack stack,ItemRendererHelper helper){return true;}
        public void renderItem(ItemRenderType type,ItemStack stack,Object... data){
            NBTTagCompound tag=stack.hasTagCompound()?stack.getTagCompound().getCompoundTag("BlockEntityTag"):new NBTTagCompound();
            GL11.glPushMatrix();try{if(type==ItemRenderType.ENTITY)GL11.glTranslatef(-.5f,-.5f,-.5f);draw(tag.getString("SourceInstance"),tag.getString("SourceVersion"),tag.getString("OriginalState"),0);}finally{GL11.glPopMatrix();}
        }
    }

    /** Ordinary solid placeholders are rendered as cached section meshes, not thousands of TESRs. */
    public static final class PackedWorld {
        private final Map<Long,Section[]> cache=new LinkedHashMap<Long,Section[]>(64,.75f,true);
        private net.minecraft.world.World world;
        private long epoch;
        private static final class Draw {
            final Appearance.Face face; final int x,y,z,light;
            Draw(Appearance.Face f,int x,int y,int z,int light){face=f;this.x=x;this.y=y;this.z=z;this.light=light;}
        }
        private static final class Section {
            dev.epoch.bridge.PackedBlocks data;
            final Map<Material,Integer> lists=new LinkedHashMap<Material,Integer>();
            long refresh, change; boolean incomplete;
            void close(){for(int id:lists.values())GL11.glDeleteLists(id,1);lists.clear();}
        }
        private void clear(){for(Section[] sections:cache.values())for(Section section:sections)if(section!=null)section.close();cache.clear();}
        @cpw.mods.fml.common.eventhandler.SubscribeEvent
        public void render(net.minecraftforge.client.event.RenderWorldLastEvent event){
            Minecraft mc=Minecraft.getMinecraft();
            if(world!=mc.theWorld||epoch!=resourceEpoch){clear();world=mc.theWorld;epoch=resourceEpoch;}
            if(world==null||mc.renderViewEntity==null||world.provider.dimensionId!=0){packedReady=true;return;}
            net.minecraft.entity.Entity eye=mc.renderViewEntity;
            double px=eye.lastTickPosX+(eye.posX-eye.lastTickPosX)*event.partialTicks;
            double py=eye.lastTickPosY+(eye.posY-eye.lastTickPosY)*event.partialTicks;
            double pz=eye.lastTickPosZ+(eye.posZ-eye.lastTickPosZ)*event.partialTicks;
            net.minecraft.client.renderer.culling.Frustrum frustum=new net.minecraft.client.renderer.culling.Frustrum();frustum.setPosition(px,py,pz);
            List<Map.Entry<Long,dev.epoch.bridge.PackedBlocks>> chunks=new ArrayList<>(CompactUnknowns.VISIBLE.entrySet());
            chunks.sort(Comparator.comparingDouble(e -> {int x=(int)(e.getKey()>>32),z=(int)(long)e.getKey();return Math.abs(x*16-px)+Math.abs(z*16-pz);}));
            boolean pending=false;int calls=0;long now=System.nanoTime(),deadline=now+2000000L,frame=AppearanceAnimation.frame();
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT|GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT|GL11.GL_CURRENT_BIT|GL11.GL_LIGHTING_BIT);
            try{
                GL11.glEnable(GL11.GL_CULL_FACE);GL11.glEnable(GL11.GL_TEXTURE_2D);GL11.glDisable(GL11.GL_BLEND);GL11.glEnable(GL11.GL_ALPHA_TEST);GL11.glDisable(GL11.GL_LIGHTING);
                mc.entityRenderer.enableLightmap(event.partialTicks);
                for(Map.Entry<Long,dev.epoch.bridge.PackedBlocks> chunk:chunks){
                    long key=chunk.getKey();int cx=(int)(key>>32),cz=(int)key;
                    if(Math.abs(cx-(int)Math.floor(px/16))>mc.gameSettings.renderDistanceChunks||Math.abs(cz-(int)Math.floor(pz/16))>mc.gameSettings.renderDistanceChunks||!world.getChunkProvider().chunkExists(cx,cz))continue;
                    Section[] sections=cache.get(key);if(sections==null){sections=new Section[16];cache.put(key,sections);}
                    for(int sy=0;sy<16;sy++){
                        if((chunk.getValue().sections()&(1<<sy))==0)continue;
                        if(!frustum.isBoundingBoxInFrustum(net.minecraft.util.AxisAlignedBB.getBoundingBox(cx*16,sy*16,cz*16,cx*16+16,sy*16+16,cz*16+16)))continue;
                        Section section=sections[sy];
                        long change=CompactUnknowns.CLIENT_CHANGES.getOrDefault(key,0L);
                        if(section==null||section.data!=chunk.getValue()||section.change!=change||now>=section.refresh){
                            if(System.nanoTime()<deadline){if(section!=null)section.close();section=build(chunk.getValue(),cx,cz,sy,now);sections[sy]=section;}
                            else if(section==null||section.data!=chunk.getValue()||section.change!=change){pending=true;continue;}
                        }
                        // A missing source appearance uses the fallback and retries; it must not
                        // hold the handoff readiness barrier indefinitely.
                        GL11.glPushMatrix();GL11.glTranslated(cx*16-px,sy*16-py,cz*16-pz);
                        for(Map.Entry<Material,Integer> list:section.lists.entrySet()){
                            Material material=list.getKey();material.update(frame);mc.getTextureManager().bindTexture(material.location);GL11.glCallList(list.getValue());calls++;
                        }
                        GL11.glPopMatrix();
                    }
                }
            }finally{mc.entityRenderer.disableLightmap(event.partialTicks);GL11.glPopAttrib();}
            for(Iterator<Map.Entry<Long,Section[]>> it=cache.entrySet().iterator();it.hasNext();){Map.Entry<Long,Section[]> entry=it.next();if(!CompactUnknowns.VISIBLE.containsKey(entry.getKey())){for(Section section:entry.getValue())if(section!=null)section.close();it.remove();}}
            // Keep the nearest geometry when the bounded cache is full.
            while(cache.size()>1024){long farthest=0;double distance=-1;for(long key:cache.keySet()){double d=Math.abs((int)(key>>32)*16-px)+Math.abs((int)key*16-pz);if(d>distance){distance=d;farthest=key;}}for(Section section:cache.remove(farthest))if(section!=null)section.close();}
            packedReady=!pending;
            ClientHooks.bridge().diagnostics.put("packedTerrainDrawCalls",calls);
            ClientHooks.bridge().diagnostics.put("packedTerrainChunks",CompactUnknowns.VISIBLE.size());
        }
        private Section build(dev.epoch.bridge.PackedBlocks data,int cx,int cz,int sy,long now){
            Section result=new Section();result.data=data;result.change=CompactUnknowns.CLIENT_CHANGES.getOrDefault(CompactUnknowns.key(cx,cz),0L);
            Map<Material,List<Draw>> groups=new LinkedHashMap<>();
            for(int n=data.first(sy<<12);n<data.size()&&data.position(n)<((sy+1)<<12);n++){
                int index=data.position(n),y=index>>8;
                int x=cx*16+(index&15),z=cz*16+((index>>4)&15);
                if(world.getBlock(x,y,z)!=CompactUnknowns.BLOCK){result.incomplete=true;continue;}
                int mask=0;for(int side=0;side<6;side++)if(world.getBlock(x+OFFSETS[side][0],y+OFFSETS[side][1],z+OFFSETS[side][2]).isOpaqueCube())mask|=1<<side;
                if(mask==63)continue;
                dev.epoch.bridge.PackedBlocks.Entry entry=data.value(n);String source=Appearance.source(entry.instance,entry.version);
                Appearance.Mesh mesh=Appearance.get(source,entry.state);if(mesh==null){mesh=CUBE;result.incomplete=true;}
                for(Appearance.Face face:mesh.faces){int side=Appearance.boundary(face);if(side>=0&&(mask&(1<<side))!=0)continue;Material material=material(source,face.texture);if(material==null){result.incomplete=true;continue;}
                    int lx=x,ly=y,lz=z;if(side>=0){lx+=OFFSETS[side][0];ly+=OFFSETS[side][1];lz+=OFFSETS[side][2];}
                    groups.computeIfAbsent(material,k->new ArrayList<>()).add(new Draw(face,x&15,y&15,z&15,world.getLightBrightnessForSkyBlocks(lx,ly,lz,0)));
                }
            }
            for(Map.Entry<Material,List<Draw>> group:groups.entrySet()){
                int id=GL11.glGenLists(1);GL11.glNewList(id,GL11.GL_COMPILE);Tessellator tess=Tessellator.instance;tess.startDrawingQuads();
                for(Draw draw:group.getValue()){
                    Appearance.Face f=draw.face;tess.setBrightness(draw.light);tess.setColorRGBA_F(((f.color>>16)&255)/255f,((f.color>>8)&255)/255f,(f.color&255)/255f,1);tess.setNormal(f.nx,f.ny,f.nz);
                    for(int v=0;v<4;v++){int offset=v*5;tess.addVertexWithUV(draw.x+f.vertices[offset],draw.y+f.vertices[offset+1],draw.z+f.vertices[offset+2],f.vertices[offset+3],f.vertices[offset+4]);}
                }
                tess.draw();GL11.glEndList();result.lists.put(group.getKey(),id);
            }
            // Retry unavailable appearance/client packets promptly; refresh light/boundary changes.
            result.refresh=now+(result.incomplete?250000000L:2000000000L);return result;
        }
    }
}
