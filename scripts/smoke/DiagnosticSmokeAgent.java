import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

/** Test-only: unlock the existing smoke entrypoints, without changing gameplay code. */
public final class DiagnosticSmokeAgent {
    public static void premain(String args, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                    ProtectionDomain domain, byte[] bytes) {
                if (name == null) return null;
                if (Boolean.getBoolean("hassium.diagnostic.traceMixin")
                        && name.equals("org/spongepowered/asm/util/Locals")) return traceLocals(bytes);
                if (Boolean.getBoolean("hassium.diagnostic.traceLogin")
                        && (name.equals("net/minecraft/network/Connection")
                        || name.equals("net/minecraft/network/PacketEncoder")
                        || name.equals("net/minecraft/network/PacketDecoder"))) return traceLogin(name, bytes);
                if (Boolean.getBoolean("hassium.diagnostic.traceEventSubscribers")
                        && (name.startsWith("net/minecraft/world/entity/")
                        || name.startsWith("net/minecraftforge/common/extensions/IForge")
                        || name.startsWith("quek/undergarden/entity/"))) {
                    traceEntityShape(name, bytes);
                }
                if ("net/minecraftforge/eventbus/EventBus".equals(name)
                        && Boolean.getBoolean("hassium.diagnostic.traceEventSubscribers")) {
                    return traceSubscribers(bytes);
                }
                if (!"io/github/limuqy/mc/hassium/client/ClientSmokeTest".equals(name)
                        && !"io/github/limuqy/mc/hassium/server/ServerSmokeTest".equals(name)) return null;
                ClassWriter writer = new ClassWriter(0);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        if (name.equals("isEnabled") && descriptor.equals("()Z")) return null;
                        return super.visitMethod(access, name, descriptor, signature, exceptions);
                    }
                    public void visitEnd() {
                        MethodVisitor method = super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                "isEnabled", "()Z", null, null);
                        method.visitCode();
                        method.visitLdcInsn(nameProperty(name));
                        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "getBoolean",
                                "(Ljava/lang/String;)Z", false);
                        method.visitInsn(Opcodes.IRETURN);
                        method.visitMaxs(1, 0);
                        method.visitEnd();
                        super.visitEnd();
                    }
                }, 0);
                System.err.println("[DIAGNOSTIC-AGENT] Enabled existing smoke entrypoint: " + name);
                return writer.toByteArray();
            }
        });
    }

    private static void traceEntityShape(String name, byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        if (name.endsWith("/Dweller") || name.endsWith("/Animal") || name.endsWith("/AgeableMob")
                || name.endsWith("/PathfinderMob") || name.endsWith("/Mob")
                || name.endsWith("/LivingEntity") || name.endsWith("/Entity")) {
            System.err.println("[DIAGNOSTIC-ENTITY] " + name + " extends " + reader.getSuperName()
                    + " interfaces=" + java.util.Arrays.toString(reader.getInterfaces()));
        }
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                       String signature, String[] exceptions) {
                if ((access & Opcodes.ACC_PUBLIC) != 0 && descriptor.contains("PoseStack"))
                    System.err.println("[DIAGNOSTIC-ENTITY] client signature " + name + "." + method + descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    /** Log the inspected class before Forge resolves its inherited method types. */
    private static byte[] traceSubscribers(byte[] bytes) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("registerClass") || !descriptor.equals("(Ljava/lang/Class;)V")) return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override public void visitCode() {
                        super.visitCode();
                        visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
                        visitLdcInsn("[DIAGNOSTIC-EVENTBUS] Inspecting subscriber: ");
                        visitVarInsn(Opcodes.ALOAD, 1);
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false);
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static String nameProperty(String className) {
        return className.contains("/server/") ? "hassium.serverSmokeTest" : "hassium.smokeTest";
    }

    private static byte[] traceLocals(byte[] bytes) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
                if (!name.equals("generateLocalVariableTable")) return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    public void visitCode() {
                        super.visitCode();
                        visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
                        visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder"); visitInsn(Opcodes.DUP);
                        visitLdcInsn("[DIAGNOSTIC-MIXIN] locals ");
                        visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
                        String[] fields = {"name", "name", "desc", "maxStack"};
                        for (int i = 0; i < fields.length; i++) {
                            visitVarInsn(Opcodes.ALOAD, i == 0 ? 0 : 1);
                            visitFieldInsn(Opcodes.GETFIELD, "org/objectweb/asm/tree/" + (i == 0 ? "ClassNode" : "MethodNode"), fields[i], i == 3 ? "I" : "Ljava/lang/String;");
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", i == 3 ? "(I)Ljava/lang/StringBuilder;" : "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                            visitLdcInsn(" "); visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                        }
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    /** Test-only packet class/size trace; never logs packet payloads or changes control flow. */
    private static byte[] traceLogin(String owner, byte[] bytes) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                int argument;
                String phase;
                boolean text = false, size = false;
                if (owner.endsWith("/Connection") && name.equals("channelRead0")
                        && descriptor.contains("Lnet/minecraft/network/protocol/Packet;")) {
                    argument = 2; phase = "receive";
                } else if (owner.endsWith("/Connection")
                        && descriptor.startsWith("(Lnet/minecraft/network/protocol/Packet;")) {
                    argument = 1; phase = "send";
                } else if (owner.endsWith("/Connection")
                        && descriptor.equals("(Lnet/minecraft/network/chat/Component;)V")) {
                    argument = 1; phase = "disconnect"; text = true;
                } else if (owner.endsWith("/PacketEncoder") && name.equals("encode")
                        && descriptor.contains("Lnet/minecraft/network/protocol/Packet;")) {
                    argument = 2; phase = "encode";
                } else if (owner.endsWith("/PacketDecoder") && name.equals("decode")
                        && descriptor.contains("Lio/netty/buffer/ByteBuf;")) {
                    argument = 2; phase = "decode"; size = true;
                } else return delegate;
                final int slot = argument;
                final String tag = "[DIAGNOSTIC-LOGIN] " + phase + " " + name + " ";
                final boolean asText = text, asSize = size;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override public void visitCode() {
                        super.visitCode();
                        visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
                        visitLdcInsn(tag);
                        visitVarInsn(Opcodes.ALOAD, slot);
                        if (asSize) {
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "io/netty/buffer/ByteBuf", "readableBytes", "()I", false);
                            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false);
                        } else if (asText) {
                            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", false);
                        } else {
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false);
                        }
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }
}
