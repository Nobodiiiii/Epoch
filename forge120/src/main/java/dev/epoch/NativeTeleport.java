package dev.epoch;

import java.lang.reflect.*;
import java.util.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.TeleportCommand;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Defer the whole native coordinate command, including its success message and post-teleport effects. */
public final class NativeTeleport {
    private static final ThreadLocal<Boolean> REPLAY=ThreadLocal.withInitial(() -> false);
    private static Method nativePosition;
    private static Method nativePosition() throws Exception {
        if(nativePosition==null) {
            for(Method method:TeleportCommand.class.getDeclaredMethods()) {
                Class<?>[] types=method.getParameterTypes();
                if(Modifier.isStatic(method.getModifiers()) && method.getReturnType()==int.class && types.length==6
                        && types[0]==CommandSourceStack.class && types[1]==Collection.class && types[2]==ServerLevel.class
                        && types[3]==Coordinates.class && types[4]==Coordinates.class) {
                    if(nativePosition!=null)throw new IllegalStateException("Ambiguous native teleport entry");
                    nativePosition=method;
                }
            }
            if(nativePosition==null)throw new IllegalStateException("Missing native teleport entry");
            nativePosition.setAccessible(true);
        }
        return nativePosition;
    }
    /** -1 falls through to vanilla; nonnegative values are the immediate command result. */
    public static int defer(CommandSourceStack source, Collection<? extends Entity> targets, ServerLevel level,
            Coordinates position, Coordinates rotation, Object lookAt) {
        if(REPLAY.get() || !StreamingWorld.enabled(level) || targets.size()!=1
                || !(targets.iterator().next() instanceof ServerPlayer player) || player.serverLevel()!=level)return -1;
        try {
            Vec3 destination=position.getPosition(source);
            Method method=nativePosition();
            StreamingWorld.travelAction(player,destination.x,destination.y,destination.z,() -> {
                REPLAY.set(true);
                try { method.invoke(null,source,Collections.singletonList(player),level,position,rotation,lookAt); }
                catch(InvocationTargetException error) {
                    Throwable cause=error.getCause();
                    if(cause instanceof Exception exception)throw exception;
                    throw new RuntimeException(cause);
                } finally { REPLAY.remove(); }
            });
            return 1;
        } catch(Exception error) {
            source.sendFailure(Component.literal("Destination preparation failed: "+error.getMessage()));
            return 0;
        }
    }
}
