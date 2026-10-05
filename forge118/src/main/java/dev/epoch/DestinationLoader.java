package dev.epoch;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.*;
import com.mojang.datafixers.util.Either;

/** One bounded destination request per integrated world, advanced by the server tick. */
final class DestinationLoader {
    interface Arrival { void run() throws Exception; }
    private static final TicketType<ChunkPos> TICKET = TicketType.create("epoch_destination", Comparator.comparingLong(ChunkPos::toLong));
    private static final int MAX_PENDING = 4;
    private final ServerLevel level;
    private Request active;
    private Map<String,Object> lastReport = Collections.emptyMap();
    DestinationLoader(ServerLevel level) { this.level = level; }

    private static final class Pending {
        CompletableFuture<?> future;
        boolean lighting;
        long stageStarted = System.nanoTime();
        Pending(CompletableFuture<?> future) { this.future = future; }
    }
    private static final class Request {
        final ServerPlayer player;
        final Arrival arrival;
        final double x, y, z;
        final float yaw, pitch;
        final long started = System.nanoTime();
        final CompletableFuture<Void> completion = new CompletableFuture<>();
        final List<ChunkPos> todo = new ArrayList<>();
        final Set<ChunkPos> tickets = new LinkedHashSet<>();
        final Map<ChunkPos,Pending> pending = new LinkedHashMap<>();
        int next, ready, skippedLights, peakPending;
        long loadNanos, lightNanos, lastProgress;
        Request(ServerPlayer player, double x, double y, double z, float yaw, float pitch, Arrival arrival) {
            this.arrival=arrival;
            this.player=player; this.x=x; this.y=y; this.z=z; this.yaw=yaw; this.pitch=pitch;
            int cx=(int)Math.floor(x/16), cz=(int)Math.floor(z/16);
            for(int radius=0;radius<=2;radius++) for(int dz=-radius;dz<=radius;dz++) for(int dx=-radius;dx<=radius;dx++)
                if(Math.max(Math.abs(dx),Math.abs(dz))==radius) todo.add(new ChunkPos(cx+dx,cz+dz));
        }
    }

    CompletableFuture<Void> start(ServerPlayer player, double x, double y, double z, float yaw, float pitch) throws IOException {
        return start(player,x,y,z,yaw,pitch,() -> player.connection.teleport(x,y,z,yaw,pitch));
    }

    CompletableFuture<Void> start(ServerPlayer player, double x, double y, double z, float yaw, float pitch, Arrival arrival) throws IOException {
        if (!level.getServer().isSameThread()) throw new IOException("Destination request requires server thread");
        if (active != null) throw new IOException("A destination is already loading");
        if (!StreamingWorld.enabled(level) || player.getLevel()!=level || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x)>29999960 || Math.abs(z)>29999960 || y<0 || y>=256 || !Float.isFinite(yaw) || !Float.isFinite(pitch))
            throw new IOException("Destination is outside the shared world's supported coordinates");
        if (!level.getWorldBorder().isWithinBounds(new net.minecraft.core.BlockPos(x,y,z)))
            throw new IOException("Destination is outside the world border");
        active = new Request(player,x,y,z,yaw,pitch,arrival);
        player.displayClientMessage(new net.minecraft.network.chat.TextComponent("正在加载目的地附近地形…"),true);
        return active.completion;
    }

    void tick() {
        Request request=active; if(request==null)return;
        try {
            if (request.player.isRemoved() || request.player.getLevel()!=level) throw new IOException("Player left the destination request's world");
            if(System.nanoTime()-request.started>TimeUnit.SECONDS.toNanos(60)) throw new IOException("Destination preparation timed out");
            for(Iterator<Map.Entry<ChunkPos,Pending>> it=request.pending.entrySet().iterator();it.hasNext();) {
                Pending pending=it.next().getValue(); if(!pending.future.isDone())continue;
                Object value=pending.future.join();
                long completed=System.nanoTime();
                if(!pending.lighting) {
                    request.loadNanos += completed-pending.stageStarted;
                    Object chunk=((Either<?,?>)value).left().orElse(null);
                    if(!(chunk instanceof LevelChunk full)) throw new IOException("Destination chunk failed to load");
                    if(full.isLightCorrect()) { it.remove(); request.ready++; request.skippedLights++; }
                    else {
                        pending.stageStarted=completed;
                        pending.future=level.getChunkSource().getLightEngine().lightChunk(full,false); pending.lighting=true;
                    }
                } else { request.lightNanos+=completed-pending.stageStarted; it.remove(); request.ready++; }
            }
            // getChunkFuture blocks when called on the server thread; its off-thread branch schedules safely.
            if(request.next<request.todo.size() && request.pending.size()<MAX_PENDING) {
                ChunkPos pos=request.todo.get(request.next++);
                level.getChunkSource().addRegionTicket(TICKET,pos,2,pos); request.tickets.add(pos);
                CompletableFuture<?> future=CompletableFuture.supplyAsync(() -> level.getChunkSource().getChunkFuture(pos.x,pos.z,ChunkStatus.FULL,true))
                        .thenCompose(result -> result);
                request.pending.put(pos,new Pending(future));
                request.peakPending=Math.max(request.peakPending,request.pending.size());
            }
            level.getChunkSource().getLightEngine().tryScheduleUpdate();
            if(System.nanoTime()-request.lastProgress>=TimeUnit.SECONDS.toNanos(1)) {
                request.lastProgress=System.nanoTime();
                request.player.displayClientMessage(new net.minecraft.network.chat.TextComponent("正在准备目的地："+request.ready+"/"+request.todo.size()),true);
            }
            if(request.ready==request.todo.size()) {
                request.arrival.run();
                request.player.displayClientMessage(new net.minecraft.network.chat.TextComponent("目的地已就绪"),true);
                release(request); lastReport=report(request,"complete"); request.completion.complete(null);
            }
        } catch(Exception error) {
            release(request); lastReport=report(request,"failed"); request.completion.completeExceptionally(error);
            request.player.displayClientMessage(new net.minecraft.network.chat.TextComponent("目的地加载失败，未执行传送："+error.getMessage()),false);
        }
    }

    CompletableFuture<Void> currentCompletion() {
        if(active==null) throw new IllegalStateException("No active destination request");
        return active.completion;
    }

    Map<String,Object> diagnostics() { return active==null ? new LinkedHashMap<>(lastReport) : report(active,"preparing"); }
    private Map<String,Object> report(Request request,String status) {
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("status",status); report.put("x",request.x); report.put("y",request.y); report.put("z",request.z); report.put("elapsedMs",(System.nanoTime()-request.started)/1e6);
        report.put("ready",request.ready); report.put("total",request.todo.size());
        report.put("loadAggregateMs",request.loadNanos/1e6); report.put("lightAggregateMs",request.lightNanos/1e6);
        report.put("skippedLights",request.skippedLights); report.put("peakPending",request.peakPending);
        report.put("pendingLimit",MAX_PENDING); report.put("heldTickets",request.tickets.size()); return report;
    }

    void close() {
        if(active!=null) { Request request=active; release(request); lastReport=report(request,"cancelled"); request.completion.completeExceptionally(new IOException("Destination request cancelled")); }
    }
    private void release(Request request) {
        for(ChunkPos pos:request.tickets) level.getChunkSource().removeRegionTicket(TICKET,pos,2,pos);
        request.tickets.clear(); if(active==request)active=null;
    }
}
