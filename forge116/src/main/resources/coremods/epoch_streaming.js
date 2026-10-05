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
        'load_authority_before_generation': {
            target: target('net.minecraft.world.server.ChunkManager', 'func_223170_j', '(Lnet/minecraft/util/math/ChunkPos;)Lcom/mojang/datafixers/util/Either;'),
            transformer: function(method) {
                var count = 0;
                for (var i = method.instructions.getFirst(); i !== null; i = i.getNext()) {
                    if ((i.getOpcode() === Opcodes.INVOKEVIRTUAL || i.getOpcode() === Opcodes.INVOKESPECIAL) && i.name === ASMAPI.mapMethod('func_219178_f')) {
                        var list = new InsnList();
                        list.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        list.add(new FieldInsnNode(Opcodes.GETFIELD, 'net/minecraft/world/server/ChunkManager', ASMAPI.mapField('field_219255_i'), 'Lnet/minecraft/world/server/ServerWorld;'));
                        list.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        list.add(call('loadingSync', '(Lnet/minecraft/nbt/CompoundNBT;Lnet/minecraft/world/server/ServerWorld;Lnet/minecraft/util/math/ChunkPos;)Lnet/minecraft/nbt/CompoundNBT;'));
                        method.instructions.insert(i, list); count++;
                    }
                }
                if (count !== 1) throw new Error('Epoch: expected one native chunk read, found ' + count);
                // Vanilla catches Exception here and generates a replacement chunk. Authority failures
                // must leave the load failed instead of replacing previously committed terrain.
                var guarded = 0;
                for (var n = 0; n < method.tryCatchBlocks.size(); n++) {
                    var block = method.tryCatchBlocks.get(n);
                    if (block.type !== 'java/lang/Exception') continue;
                    var handler = block.handler.getNext();
                    while (handler !== null && handler.getOpcode() < 0) handler = handler.getNext();
                    if (handler === null || handler.getOpcode() !== Opcodes.ASTORE)
                        throw new Error('Epoch: unexpected native load exception handler');
                    var guard = new InsnList(); guard.add(new VarInsnNode(Opcodes.ALOAD, handler.var));
                    guard.add(call('rejectFallback', '(Ljava/lang/Throwable;)V'));
                    method.instructions.insert(handler, guard); guarded++;
                }
                if (guarded === 0) throw new Error('Epoch: native load exception handler missing');
                return method;
            }
        },
        'apply_authority_before_chunk_activation': {
            target: target('net.minecraft.world.chunk.storage.ChunkSerializer', 'func_222656_a', '(Lnet/minecraft/world/server/ServerWorld;Lnet/minecraft/world/gen/feature/template/TemplateManager;Lnet/minecraft/village/PointOfInterestManager;Lnet/minecraft/util/math/ChunkPos;Lnet/minecraft/nbt/CompoundNBT;)Lnet/minecraft/world/chunk/ChunkPrimer;'),
            transformer: function(method) { return afterReturns(method, function() {
                var list = new InsnList();
                list.add(new VarInsnNode(Opcodes.ALOAD, 0)); list.add(new VarInsnNode(Opcodes.ALOAD, 3)); list.add(new VarInsnNode(Opcodes.ALOAD, 4));
                list.add(call('decoded', '(Lnet/minecraft/world/chunk/ChunkPrimer;Lnet/minecraft/world/server/ServerWorld;Lnet/minecraft/util/math/ChunkPos;Lnet/minecraft/nbt/CompoundNBT;)Lnet/minecraft/world/chunk/ChunkPrimer;'));
                return list;
            }); }
        },
        'persist_projection_with_native_chunk': {
            target: target('net.minecraft.world.chunk.storage.ChunkSerializer', 'func_222645_a', '(Lnet/minecraft/world/server/ServerWorld;Lnet/minecraft/world/chunk/IChunk;)Lnet/minecraft/nbt/CompoundNBT;'),
            transformer: function(method) { return afterReturns(method, function() {
                var list = new InsnList(); list.add(new VarInsnNode(Opcodes.ALOAD, 0)); list.add(new VarInsnNode(Opcodes.ALOAD, 1));
                list.add(call('encoded', '(Lnet/minecraft/nbt/CompoundNBT;Lnet/minecraft/world/server/ServerWorld;Lnet/minecraft/world/chunk/IChunk;)Lnet/minecraft/nbt/CompoundNBT;'));
                return list;
            }); }
        },
        'track_direct_block_mutations': {
            target: target('net.minecraft.world.chunk.Chunk', 'func_177436_a', '(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Z)Lnet/minecraft/block/BlockState;'),
            transformer: function(method) { return afterReturns(method, function() {
                var list = new InsnList(); list.add(new VarInsnNode(Opcodes.ALOAD, 0));
                list.add(new InsnNode(Opcodes.ICONST_1));
                list.add(call('dirty', '(Lnet/minecraft/world/chunk/IChunk;Z)V')); return list;
            }); }
        },
        'track_native_chunk_dirtiness': {
            target: target('net.minecraft.world.chunk.Chunk', 'func_177427_f', '(Z)V'),
            transformer: function(method) {
                var list = new InsnList(); list.add(new VarInsnNode(Opcodes.ALOAD, 0)); list.add(new VarInsnNode(Opcodes.ILOAD, 1));
                list.add(call('dirty', '(Lnet/minecraft/world/chunk/IChunk;Z)V'));
                method.instructions.insert(list); return method;
            }
        }
    };
}
