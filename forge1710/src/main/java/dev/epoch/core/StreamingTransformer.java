package dev.epoch.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Only inserts hooks; world access happens after normal mod initialization. */
public final class StreamingTransformer implements IClassTransformer, Opcodes {
    private static final String HOOK = "dev/epoch/StreamingWorld";
    private static final String CHUNK = "net/minecraft/world/chunk/Chunk";
    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(INVOKESTATIC, HOOK, name, descriptor, false);
    }
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null) return bytes;
        boolean streaming = Boolean.getBoolean("epoch.streaming");
        boolean provider = streaming && transformedName.equals("net.minecraft.world.gen.ChunkProviderServer");
        boolean loader = transformedName.equals("net.minecraft.world.chunk.storage.AnvilChunkLoader");
        boolean chunk = transformedName.equals("net.minecraft.world.chunk.Chunk");
        if (!provider && !loader && !chunk) return bytes;
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        String pendingField = null, lockField = null;
        if (loader) for (Object value : node.fields) {
            FieldNode field = (FieldNode)value;
            if (field.desc.equals("Ljava/util/List;") && (field.name.equals("chunksToRemove") || field.name.equals("field_75828_a"))) pendingField = field.name;
            if (field.desc.equals("Ljava/lang/Object;") && (field.name.equals("syncLockObject") || field.name.equals("field_75827_c"))) lockField = field.name;
        }
        if (loader && streaming && (pendingField == null || lockField == null)) throw new IllegalStateException("Epoch missing native save queue fields");
        int hooks = 0;
        for (Object value : node.methods) {
            MethodNode method=(MethodNode)value;
            if (provider && method.name.equals("loadChunk") && method.desc.equals("(IILjava/lang/Runnable;)L" + CHUNK + ";")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(ALOAD, 0)); code.add(new VarInsnNode(ILOAD, 1));
                code.add(new VarInsnNode(ILOAD, 2)); code.add(new VarInsnNode(ALOAD, 3));
                code.add(call("load", "(Lnet/minecraft/world/gen/ChunkProviderServer;IILjava/lang/Runnable;)L" + CHUNK + ";"));
                code.add(new InsnNode(DUP)); LabelNode fallback = new LabelNode();
                code.add(new JumpInsnNode(IFNULL, fallback)); code.add(new InsnNode(ARETURN));
                code.add(fallback); code.add(new FrameNode(F_SAME1, 0, null, 1, new Object[]{CHUNK})); code.add(new InsnNode(POP));
                method.instructions.insert(code); hooks++;
            }
            if (loader && method.name.equals("loadEntities") && method.desc.equals("(Lnet/minecraft/world/World;Lnet/minecraft/nbt/NBTTagCompound;L" + CHUNK + ";)V")) {
                InsnList code = new InsnList(); code.add(new VarInsnNode(ALOAD, 1)); code.add(new VarInsnNode(ALOAD, 2)); code.add(new VarInsnNode(ALOAD, 3));
                code.add(call("beforeEntities", method.desc)); method.instructions.insert(code); hooks++;
            }
            if (loader && streaming && (method.name.equals("saveChunk") || method.name.equals("func_75816_a"))
                    && method.desc.equals("(Lnet/minecraft/world/World;L" + CHUNK + ";)V")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(ALOAD, 0)); code.add(new FieldInsnNode(GETFIELD, node.name, pendingField, "Ljava/util/List;"));
                code.add(new VarInsnNode(ALOAD, 0)); code.add(new FieldInsnNode(GETFIELD, node.name, lockField, "Ljava/lang/Object;"));
                code.add(new MethodInsnNode(INVOKESTATIC, "dev/epoch/NativeSaveQueue", "awaitCapacity", "(Ljava/util/List;Ljava/lang/Object;)V", false));
                method.instructions.insert(code); hooks++;
            }
            if (chunk && (method.name.equals("setChunkModified") || method.name.equals("func_76630_e")) && method.desc.equals("()V")) {
                InsnList code = new InsnList(); code.add(new VarInsnNode(ALOAD, 0)); code.add(call("dirty", "(L" + CHUNK + ";)V"));
                method.instructions.insert(code); hooks++;
            }
            if (chunk && ((method.name.equals("func_150807_a") && method.desc.equals("(IIILnet/minecraft/block/Block;I)Z")) || ((method.name.equals("setBlockMetadata") || method.name.equals("func_76589_b")) && method.desc.equals("(IIII)Z")))) {
                for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
                    if (instruction.getOpcode() != IRETURN) continue;
                    InsnList code = new InsnList(); code.add(new VarInsnNode(ALOAD, 0)); code.add(call("dirty", "(L" + CHUNK + ";)V"));
                    method.instructions.insertBefore(instruction, code);
                }
                hooks++;
            }
        }
        if (hooks != (chunk ? 3 : loader ? (streaming ? 2 : 1) : 1)) throw new IllegalStateException("Epoch missing native hooks in " + transformedName + ": " + hooks);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
}
