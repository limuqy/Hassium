package io.github.limuqy.mc.hassium.compat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Cache resources have no gameplay reload lifecycle; guard only constructor-injected void callbacks. */
public final class ShadowCacheResourceCallbacks {
    private static final String SCOPE = "io/github/limuqy/mc/hassium/shadow/server/ShadowAssemblyScope";
    private ShadowCacheResourceCallbacks() {}

    public static int guard(ClassNode target) {
        Set<String> constructorCalls = new HashSet<>();
        for (MethodNode method : target.methods) {
            if (!"<init>".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(target.name)) {
                    constructorCalls.add(call.name + call.desc);
                }
            }
        }
        int count = 0;
        for (MethodNode method : target.methods) {
            if (!constructorCalls.contains(method.name + method.desc)
                    || Type.getReturnType(method.desc).getSort() != Type.VOID
                    || !method.desc.contains("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;")
                    || !externalMixin(method.visibleAnnotations) && !externalMixin(method.invisibleAnnotations)) continue;
            boolean guarded = false;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(SCOPE)
                        && call.name.equals("isCacheOnlyAssembly")) guarded = true;
            }
            if (guarded) continue;
            LabelNode ordinary = new LabelNode();
            InsnList gate = new InsnList();
            gate.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SCOPE, "isCacheOnlyAssembly", "()Z", false));
            gate.add(new JumpInsnNode(Opcodes.IFEQ, ordinary));
            gate.add(new InsnNode(Opcodes.RETURN));
            gate.add(ordinary);
            gate.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            method.instructions.insert(gate);
            method.maxStack = Math.max(1, method.maxStack);
            count++;
        }
        return count;
    }

    private static boolean externalMixin(List<AnnotationNode> annotations) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if (!"Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;".equals(annotation.desc)
                    || annotation.values == null) continue;
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                if ("mixin".equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof String name) {
                    return !name.startsWith("io.github.limuqy.mc.hassium.");
                }
            }
        }
        return false;
    }
}
