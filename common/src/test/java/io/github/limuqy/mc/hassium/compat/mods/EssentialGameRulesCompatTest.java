package io.github.limuqy.mc.hassium.compat.mods;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;

class EssentialGameRulesCompatTest {
    private static final String ESSENTIAL_HANDLER = String.join(Character.toString(36),
            "handler", "test", "essential", "onNotifyChange");
    private static final String OTHER_HANDLER = String.join(Character.toString(36),
            "handler", "other", "onNotifyChange");
    @Test
    void guardsOnlyEssentialCallbackAndRetainsOtherCallbacks() throws Exception {
        ClassNode type = fixture();
        assertEquals(1, EssentialGameRulesCompat.guardShadowCallbacks(type, "java/lang/String"));
        int size = type.methods.get(1).instructions.size();
        assertEquals(1, EssentialGameRulesCompat.guardShadowCallbacks(type, "java/lang/String"));
        assertEquals(size, type.methods.get(1).instructions.size(), "guard must be idempotent");
        ClassWriter writer = new ClassWriter(0);
        type.accept(writer);
        Class<?> fixture = new FixtureLoader().define(writer.toByteArray());
        Object receiver = fixture.getConstructor().newInstance();
        var essential = fixture.getMethod(ESSENTIAL_HANDLER, Object.class);
        assertDoesNotThrow(() -> essential.invoke(receiver, "shadow-marker"));
        AtomicInteger calls = new AtomicInteger();
        essential.invoke(receiver, (Runnable) calls::incrementAndGet);
        assertEquals(1, calls.get(), "real server callback must still execute");
        var unrelated = fixture.getMethod(OTHER_HANDLER, Object.class);
        var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> unrelated.invoke(receiver, "shadow-marker"));
        assertInstanceOf(ClassCastException.class, failure.getCause(), "other mod callbacks must remain untouched");
    }

    private static ClassNode fixture() {
        ClassNode type = new ClassNode();
        type.version = Opcodes.V17;
        type.access = Opcodes.ACC_PUBLIC;
        type.name = "hassium/test/EssentialCallbackFixture";
        type.superName = "java/lang/Object";
        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.instructions.add(new InsnNode(Opcodes.RETURN));
        init.maxStack = 1;
        init.maxLocals = 1;
        type.methods.add(init);
        type.methods.add(callback(ESSENTIAL_HANDLER,
                "gg.essential.mixins.transformers.server.integrated.Mixin_SetGameRules"));
        type.methods.add(callback(OTHER_HANDLER, "example.OtherMod"));
        return type;
    }

    private static MethodNode callback(String name, String mixin) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, "(Ljava/lang/Object;)V", null, null);
        AnnotationNode merged = new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");
        merged.values = List.of("mixin", mixin);
        method.visibleAnnotations = List.of(merged);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Runnable"));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 1;
        method.maxLocals = 2;
        return method;
    }

    private static final class FixtureLoader extends ClassLoader {
        Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}
