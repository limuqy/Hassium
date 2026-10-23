import java.lang.instrument.*;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;
import org.objectweb.asm.*;

/** Benchmark-only instrumentation; the same agent is installed in both arms. */
public final class SparkBenchAgent {
    public static void premain(String helper, Instrumentation instrumentation) throws Exception {
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(helper));
        Class.forName("SparkBenchBridge", true, null).getMethod("start").invoke(null);
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader, String type, Class<?> redefined,
                                    ProtectionDomain domain, byte[] bytes) {
                boolean client = "net/minecraft/client/Minecraft".equals(type);
                boolean server = "net/minecraft/server/MinecraftServer".equals(type);
                boolean spark = "me/lucko/spark/common/SparkPlatform".equals(type);
                boolean encoder = "net/minecraft/network/CompressionEncoder".equals(type);
                boolean packet = type != null && (type.equals("net/minecraft/network/protocol/game/ClientboundSetEntityDataPacket")
                        || type.equals("net/minecraft/network/protocol/game/ClientboundTeleportEntityPacket")
                        || type.equals("net/minecraft/network/protocol/game/ClientboundMoveEntityPacket"));
                if (!client && !server && !spark && !encoder && !packet) return null;
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        MethodVisitor target = super.visitMethod(access, name, descriptor, signature, exceptions);
                        boolean loop = client && (name.equals("runTick") || name.equals("m_91383_")) && descriptor.equals("(Z)V")
                                || server && (name.equals("tickServer") || name.equals("m_5705_"))
                                && descriptor.equals("(Ljava/util/function/BooleanSupplier;)V");
                        boolean capture = spark && name.equals("<init>");
                        boolean countBytes = encoder && descriptor.equals("(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Lio/netty/buffer/ByteBuf;)V");
                        boolean countPacket = packet && descriptor.equals("(Lnet/minecraft/network/protocol/game/ClientGamePacketListener;)V");
                        if (!loop && !capture && !countBytes && !countPacket) return target;
                        System.err.println("[SPARK-BENCH] instrument " + type + "." + name + descriptor);
                        return new MethodVisitor(Opcodes.ASM9, target) {
                            private void bridge(String method, int slot) {
                                visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
                                visitLdcInsn("sparkbench." + method);
                                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer");
                                visitVarInsn(Opcodes.ALOAD, slot);
                                visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", "(Ljava/lang/Object;)V", true);
                            }
                            public void visitCode() { super.visitCode(); if (loop) bridge("beginLoop", 0); if (countPacket) bridge("packet", 0); }
                            public void visitInsn(int opcode) {
                                if (opcode == Opcodes.RETURN && !countPacket) bridge(countBytes ? "encoded" : capture ? "captureSpark" : "endLoop", countBytes ? 3 : 0);
                                super.visitInsn(opcode);
                            }
                        };
                    }
                }, 0);
                return writer.toByteArray();
            }
        });
    }
}
