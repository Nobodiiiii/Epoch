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
        if (bytes == null || !dev.epoch.bridge.StreamingMode.enabled()) return bytes;
        boolean provider = transformedName.equals("net.minecraft.world.gen.ChunkProviderServer");
        boolean loader = transformedName.equals("net.minecraft.world.chunk.storage.AnvilChunkLoader");
        boolean chunk = transformedName.equals("net.minecraft.world.chunk.Chunk");
        if (!provider && !loader && !chunk) return bytes;
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        int hooks = 0;
        for (MethodNode method : node.methods) {
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
            if (chunk && (method.name.equals("markDirty") || method.name.equals("func_76630_e")) && method.desc.equals("()V")) {
                InsnList code = new InsnList(); code.add(new VarInsnNode(ALOAD, 0)); code.add(call("dirty", "(L" + CHUNK + ";)V"));
                method.instructions.insert(code); hooks++;
            }
            if (chunk && (method.name.equals("setBlockState") || method.name.equals("func_177436_a")) && method.desc.equals("(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;)Lnet/minecraft/block/state/IBlockState;")) {
                for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
                    if (instruction.getOpcode() != ARETURN) continue;
                    InsnList code = new InsnList(); code.add(new VarInsnNode(ALOAD, 0)); code.add(call("dirty", "(L" + CHUNK + ";)V"));
                    method.instructions.insertBefore(instruction, code);
                }
                hooks++;
            }
        }
        if (hooks != (chunk ? 2 : 1)) throw new IllegalStateException("Epoch missing native hooks in " + transformedName + ": " + hooks);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer);
        dev.epoch.bridge.StreamingMode.installed(provider ? "provider" : loader ? "loader" : "chunk");
        return writer.toByteArray();
    }
}
