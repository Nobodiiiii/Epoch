function initializeCoreMod() {
    var Opcodes = Java.type('org.objectweb.asm.Opcodes');
    var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
    var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
    var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var FieldInsnNode = Java.type('org.objectweb.asm.tree.FieldInsnNode');
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
    var hook = 'dev/epoch/StreamingWorld';
    function call(name, descriptor) { return new MethodInsnNode(Opcodes.INVOKESTATIC, hook, name, descriptor, false); }
    function target(cls, method, descriptor) {
        return {type: 'METHOD', 'class': cls, methodName: ASMAPI.mapMethod(method), methodDesc: descriptor};
    }
    function afterReturns(method, callback) {
        var count = 0;
        for (var i = method.instructions.getFirst(); i !== null; i = i.getNext()) {
            if (i.getOpcode() === Opcodes.ARETURN) { method.instructions.insertBefore(i, callback()); count++; }
        }
        if (count === 0) throw new Error('Epoch: missing return in native chunk method');
        return method;
    }
    return {
        'prepare_native_coordinate_teleport': {
            target: target('net.minecraft.server.commands.TeleportCommand', 'm_139025_', '(Lnet/minecraft/commands/CommandSourceStack;Ljava/util/Collection;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/commands/arguments/coordinates/Coordinates;Lnet/minecraft/commands/arguments/coordinates/Coordinates;Lnet/minecraft/server/commands/TeleportCommand$LookAt;)I'),
            transformer: function(method) {
                var LabelNode=Java.type('org.objectweb.asm.tree.LabelNode');
                var JumpInsnNode=Java.type('org.objectweb.asm.tree.JumpInsnNode');
                var resume=new LabelNode(),list=new InsnList();
                for(var n=0;n<6;n++)list.add(new VarInsnNode(Opcodes.ALOAD,n));
                list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,'dev/epoch/NativeTeleport','defer','(Lnet/minecraft/commands/CommandSourceStack;Ljava/util/Collection;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/commands/arguments/coordinates/Coordinates;Lnet/minecraft/commands/arguments/coordinates/Coordinates;Ljava/lang/Object;)I',false));
                list.add(new InsnNode(Opcodes.DUP));list.add(new JumpInsnNode(Opcodes.IFLT,resume));
                list.add(new InsnNode(Opcodes.IRETURN));list.add(resume);
                list.add(new InsnNode(Opcodes.POP));method.instructions.insert(list);return method;
            }
        },
        'load_authority_before_generation': {
            target: target('net.minecraft.server.level.ChunkMap', 'm_140417_', '(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;'),
            transformer: function(method) {
                var count = 0;
                for (var i = method.instructions.getFirst(); i !== null; i = i.getNext()) {
                    if ((i.getOpcode() === Opcodes.INVOKEVIRTUAL || i.getOpcode() === Opcodes.INVOKESPECIAL) && i.name === ASMAPI.mapMethod('m_214963_')) {
                        var list = new InsnList();
                        list.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        list.add(new FieldInsnNode(Opcodes.GETFIELD, 'net/minecraft/server/level/ChunkMap', ASMAPI.mapField('f_140133_'), 'Lnet/minecraft/server/level/ServerLevel;'));
                        list.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        list.add(call('loading', '(Ljava/util/concurrent/CompletableFuture;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;'));
                        method.instructions.insert(i, list); count++;
                    }
                }
                if (count !== 1) throw new Error('Epoch: expected one native chunk read, found ' + count);
                return method;
            }
        },
        'apply_authority_before_chunk_activation': {
            target: target('net.minecraft.world.level.chunk.storage.ChunkSerializer', 'm_188230_', '(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/ai/village/poi/PoiManager;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/chunk/ProtoChunk;'),
            transformer: function(method) { return afterReturns(method, function() {
                var list = new InsnList();
                list.add(new VarInsnNode(Opcodes.ALOAD, 0)); list.add(new VarInsnNode(Opcodes.ALOAD, 2)); list.add(new VarInsnNode(Opcodes.ALOAD, 3));
                list.add(call('decoded', '(Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/chunk/ProtoChunk;'));
                return list;
            }); }
        },
        'persist_projection_with_native_chunk': {
            target: target('net.minecraft.world.level.chunk.storage.ChunkSerializer', 'm_63454_', '(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)Lnet/minecraft/nbt/CompoundTag;'),
            transformer: function(method) { return afterReturns(method, function() {
                var list = new InsnList(); list.add(new VarInsnNode(Opcodes.ALOAD, 0)); list.add(new VarInsnNode(Opcodes.ALOAD, 1));
                list.add(call('encoded', '(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)Lnet/minecraft/nbt/CompoundTag;'));
                return list;
            }); }
        },
        'reject_regeneration_after_authority_failure': {
            target: target('net.minecraft.server.level.ChunkMap', 'm_214901_', '(Ljava/lang/Throwable;Lnet/minecraft/world/level/ChunkPos;)Lcom/mojang/datafixers/util/Either;'),
            transformer: function(method) {
                var list = new InsnList(); list.add(new VarInsnNode(Opcodes.ALOAD, 1));
                list.add(call('rejectFallback', '(Ljava/lang/Throwable;)V')); method.instructions.insert(list); return method;
            }
        },
        'track_direct_block_mutations': {
            target: target('net.minecraft.world.level.chunk.LevelChunk', 'm_6978_', '(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;'),
            transformer: function(method) { return afterReturns(method, function() {
                var list = new InsnList(); list.add(new VarInsnNode(Opcodes.ALOAD, 0));
                list.add(new InsnNode(Opcodes.ICONST_1));
                list.add(call('dirty', '(Lnet/minecraft/world/level/chunk/ChunkAccess;Z)V')); return list;
            }); }
        },
        'track_native_chunk_dirtiness': {
            target: target('net.minecraft.world.level.chunk.ChunkAccess', 'm_8092_', '(Z)V'),
            transformer: function(method) {
                var list = new InsnList(); list.add(new VarInsnNode(Opcodes.ALOAD, 0)); list.add(new VarInsnNode(Opcodes.ILOAD, 1));
                list.add(call('dirty', '(Lnet/minecraft/world/level/chunk/ChunkAccess;Z)V'));
                method.instructions.insert(list); return method;
            }
        }
    };
}
