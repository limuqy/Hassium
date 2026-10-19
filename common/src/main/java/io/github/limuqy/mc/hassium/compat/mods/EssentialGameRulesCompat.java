package io.github.limuqy.mc.hassium.compat.mods;

import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Guard only Essential's merged SPS callback; vanilla gamerule callbacks remain intact. */
public final class EssentialGameRulesCompat {
    private static final String ESSENTIAL_MIXIN =
            "gg.essential.mixins.transformers.server.integrated.Mixin_SetGameRules";
    private static final String SHADOW_SERVER =
            "io/github/limuqy/mc/hassium/shadow/server/ShadowSeedServer";

    private EssentialGameRulesCompat() {
    }

    public static boolean supportsValueCallbacks() {
#if MC_VER < MC_1_21_11
        return true;
#else
        return false;
#endif
    }

    public static int guardShadowCallbacks(ClassNode target) {
        return guardShadowCallbacks(target, SHADOW_SERVER);
    }

    static int guardShadowCallbacks(ClassNode target, String shadowType) {
        int guarded = 0;
        for (MethodNode method : target.methods) {
            if (!method.name.endsWith("onNotifyChange") || !mergedFromEssential(method)
                    || (method.access & Opcodes.ACC_STATIC) != 0
                    || Type.getReturnType(method.desc).getSort() != Type.VOID) continue;
            Type[] args = Type.getArgumentTypes(method.desc);
            if (args.length == 0 || args[0].getSort() != Type.OBJECT) continue;
            guarded++;
            boolean existing = false;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.INSTANCEOF
                        && shadowType.equals(type.desc)) {
                    existing = true;
                    break;
                }
            }
            if (existing) continue;
            LabelNode original = new LabelNode();
            InsnList guard = new InsnList();
            guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
            guard.add(new TypeInsnNode(Opcodes.INSTANCEOF, shadowType));
            guard.add(new JumpInsnNode(Opcodes.IFEQ, original));
            guard.add(new InsnNode(Opcodes.RETURN));
            guard.add(original);
            guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            method.instructions.insert(guard);
            method.maxStack = Math.max(method.maxStack, 1);
        }
        return guarded;
    }

    private static boolean mergedFromEssential(MethodNode method) {
        return mergedFromEssential(method.visibleAnnotations) || mergedFromEssential(method.invisibleAnnotations);
    }

    private static boolean mergedFromEssential(List<AnnotationNode> annotations) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if (!"Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;".equals(annotation.desc)
                    || annotation.values == null) continue;
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                if ("mixin".equals(annotation.values.get(i))
                        && ESSENTIAL_MIXIN.equals(annotation.values.get(i + 1))) return true;
            }
        }
        return false;
    }
}
