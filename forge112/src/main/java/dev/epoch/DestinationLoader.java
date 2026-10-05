package dev.epoch;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.*;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.ForgeChunkManager;

/** Legacy generation remains synchronous; spread destination preparation across server ticks. */
final class DestinationLoader {
    private static Object mod;
    static void register(Object instance) {
        mod=instance;
        ForgeChunkManager.setForcedChunkLoadingCallback(instance, (tickets, world) -> {
            // Requests do not survive a server restart.
            for (ForgeChunkManager.Ticket ticket : tickets) ForgeChunkManager.releaseTicket(ticket);
        });
    }
    private final WorldServer world;
    private Request active;
    private Map<String,Object> last=Collections.emptyMap();
    DestinationLoader(WorldServer world) { this.world=world; }
    private static final class Request {
        final EntityPlayerMP player;
        final double x,y,z;
        final float yaw,pitch;
        final ForgeChunkManager.Ticket ticket;
        final List<ChunkPos> chunks=new ArrayList<>();
        final CompletableFuture<Void> completion=new CompletableFuture<>();
        final long started=System.nanoTime();
        long progressAt;
        int next, lightCursor;
        Request(EntityPlayerMP player,double x,double y,double z,ForgeChunkManager.Ticket ticket) {
            this.player=player;this.x=x;this.y=y;this.z=z;this.ticket=ticket;
            yaw=player.rotationYaw;pitch=player.rotationPitch;
            int cx=(int)Math.floor(x/16),cz=(int)Math.floor(z/16);
            for(int r=0;r<=2;r++)for(int dz=-r;dz<=r;dz++)for(int dx=-r;dx<=r;dx++)
                if(Math.max(Math.abs(dx),Math.abs(dz))==r)chunks.add(new ChunkPos(cx+dx,cz+dz));
        }
    }
    CompletableFuture<Void> start(EntityPlayerMP player,double x,double y,double z) throws IOException {
        if(!world.getMinecraftServer().isCallingFromMinecraftThread())throw new IOException("Destination requires server thread");
        if(active!=null)throw new IOException("A destination is already loading");
        if(player.getServerWorld()!=world || !Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
                ||Math.abs(x)>29999960||Math.abs(z)>29999960||y<0||y>=256
                ||!world.getWorldBorder().contains(new BlockPos(x,y,z)))throw new IOException("Unsupported destination");
        ForgeChunkManager.Ticket ticket=ForgeChunkManager.requestTicket(mod,world,ForgeChunkManager.Type.NORMAL);
        if(ticket==null)throw new IOException("Destination chunk ticket unavailable");
        if(ticket.getChunkListDepth()<25){ForgeChunkManager.releaseTicket(ticket);throw new IOException("Destination needs a ticket for 25 chunks");}
        active=new Request(player,x,y,z,ticket);
        return active.completion;
    }
    void tick() {
        Request request=active;if(request==null)return;
        try {
            if(request.player.isDead||request.player.getServerWorld()!=world)throw new IOException("Player left world");
            if(System.nanoTime()-request.started>TimeUnit.SECONDS.toNanos(60))throw new IOException("Destination preparation timed out");
            if(request.next<request.chunks.size()) {
                ChunkPos pos=request.chunks.get(request.next);
                ForgeChunkManager.forceChunk(request.ticket,pos);
                // Native provider, including authority interception, owns load/generation and activation.
                world.getChunkProvider().provideChunk(pos.x,pos.z);
                request.next++;
            } else if(request.lightCursor<9) {
                // The outer ring supplies neighbours for lighting the central 3x3 landing area.
                ChunkPos pos=request.chunks.get(request.lightCursor);
                Chunk chunk=world.getChunkProvider().getLoadedChunk(pos.x,pos.z);
                if(chunk==null)throw new IOException("Destination lost retained chunk");
                if(!chunk.isLightPopulated())chunk.checkLight();
                if(chunk.isLightPopulated())request.lightCursor++;
            } else {
                request.player.connection.setPlayerLocation(request.x,request.y,request.z,request.yaw,request.pitch);
                release(request,"complete");request.completion.complete(null);return;
            }
            if(System.nanoTime()-request.progressAt>TimeUnit.SECONDS.toNanos(1)) {
                request.progressAt=System.nanoTime();
                request.player.sendStatusMessage(new TextComponentString("Preparing destination: "+request.next+"/25"),true);
            }
        } catch(Exception error) {
            release(request,"failed");request.completion.completeExceptionally(error);
            request.player.sendMessage(new TextComponentString("Destination failed: "+error.getMessage()));
        }
    }
    CompletableFuture<Void> currentCompletion(){if(active==null)throw new IllegalStateException("No destination request");return active.completion;}
    Map<String,Object> diagnostics(){return active==null?new LinkedHashMap<>(last):report(active,"preparing");}
    private Map<String,Object> report(Request request,String status){
        Map<String,Object> out=new LinkedHashMap<>();out.put("status",status);out.put("ready",request.next);out.put("total",25);
        out.put("lightReady",request.lightCursor);out.put("heldTickets",request.ticket.getChunkList().size());
        out.put("elapsedMs",(System.nanoTime()-request.started)/1e6);return out;
    }
    void close(){if(active!=null){Request request=active;release(request,"cancelled");request.completion.completeExceptionally(new IOException("Destination cancelled"));}}
    private void release(Request request,String status){ForgeChunkManager.releaseTicket(request.ticket);last=report(request,status);if(active==request)active=null;}
}
