package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.shadow.server.ShadowAssemblyScope;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;

class ShadowCacheResourceCallbacksTest {
    private static final String CALLBACK = "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";

    @Test void executesTransformedClassAndPreservesNormalAndNonConstructorCallbacks() throws Exception {
        ClassNode node = fixture();
        assertEquals(1, ShadowCacheResourceCallbacks.guard(node));
        assertEquals(0, ShadowCacheResourceCallbacks.guard(node), "Guarding twice is idempotent");
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        Class<?> fixture = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass("fixture.CacheResources", bytes, 0, bytes.length); }
        }.define();
        Object ordinary = construct(fixture);
        assertEquals(1, fixture.getField("external").getInt(ordinary));
        Object cache = ShadowAssemblyScope.cacheOnly(() -> construct(fixture));
        assertEquals(0, fixture.getField("external").getInt(cache));
        assertEquals(1, fixture.getField("vanilla").getInt(cache));
        assertEquals(1, fixture.getField("own").getInt(cache));
        ShadowAssemblyScope.cacheOnly(() -> {
            try { fixture.getMethod("later", org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class).invoke(cache, new Object[]{null}); }
            catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            return null;
        });
        assertEquals(1, fixture.getField("later").getInt(cache), "Callbacks not called by constructors still execute");
        assertEquals(1, fixture.getField("external").getInt(construct(fixture)), "Normal worlds still run all callbacks after cache assembly");
        assertFalse(ShadowAssemblyScope.isActive());
        assertFalse(ShadowAssemblyScope.isCacheOnlyAssembly());
    }

    @Test void restoresAssemblyScopeAfterFailureAndNestedCalls() {
        ShadowAssemblyScope.call(() -> {
            assertThrows(IllegalStateException.class, () -> ShadowAssemblyScope.cacheOnly(() -> {
                assertTrue(ShadowAssemblyScope.isActive());
                assertTrue(ShadowAssemblyScope.isCacheOnlyAssembly());
                throw new IllegalStateException("fixture failure");
            }));
            assertTrue(ShadowAssemblyScope.isActive());
            assertFalse(ShadowAssemblyScope.isCacheOnlyAssembly());
            return null;
        });
        assertFalse(ShadowAssemblyScope.isActive());
        assertFalse(ShadowAssemblyScope.isCacheOnlyAssembly());
    }

    private static Object construct(Class<?> type) {
        try { return type.getConstructor().newInstance(); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static ClassNode fixture() {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = "fixture/CacheResources";
        node.superName = "java/lang/Object";
        for (String field : List.of("vanilla", "external", "own", "later")) {
            node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, field, "I", null, null));
        }
        MethodNode ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        increment(ctor, node.name, "vanilla");
        for (String callback : List.of("external", "own")) {
            ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            ctor.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, callback, CALLBACK, false));
        }
        ctor.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(ctor);
        for (String name : List.of("external", "own", "later")) {
            MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, CALLBACK, null, null);
            AnnotationNode merged = new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");
            merged.values = new ArrayList<>(List.of("mixin", name.equals("own")
                    ? "io.github.limuqy.mc.hassium.fixture.Resources" : "fixture.thirdparty.Resources"));
            method.visibleAnnotations = new ArrayList<>(List.of(merged));
            increment(method, node.name, name);
            method.instructions.add(new InsnNode(Opcodes.RETURN));
            node.methods.add(method);
        }
        return node;
    }

    private static void increment(MethodNode method, String owner, String field) {
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, field, "I"));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, field, "I"));
    }
}
