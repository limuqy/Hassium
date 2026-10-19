package io.github.limuqy.mc.hassium.compat.mods;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import static org.junit.jupiter.api.Assertions.*;

class LibXInteractStackCompatTest {
    @Test void repairsLibXReturnHookBeforeLocalCaptureWithoutChangingInstructions() throws Exception {
        ClassNode node = new ClassNode(); node.name = "fixture/GameMode";
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "useItemOn", "()Ljava/lang/Object;", null, null);
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL)); // Original result remains on stack.
        for (int i = 0; i < 5; i++) method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/moddingx/libx/impl/libxcore/CoreInteract", "useItemOn",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
        method.instructions.add(new InsnNode(Opcodes.POP)); method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 5; node.methods.add(method);
        assertThrows(AnalyzerException.class, () -> new Analyzer<>(new BasicInterpreter()).analyze(node.name, method));
        var instructions = method.instructions.toArray();
        assertEquals(1, LibXInteractStackCompat.repair(node));
        assertEquals(6, method.maxStack);
        assertArrayEquals(instructions, method.instructions.toArray());
        assertDoesNotThrow(() -> new Analyzer<>(new BasicInterpreter()).analyze(node.name, method));
        assertEquals(0, LibXInteractStackCompat.repair(node));
    }

    @Test void leavesUnrelatedMethodsAlone() {
        ClassNode node = new ClassNode(); node.name = "fixture/Other";
        MethodNode method = new MethodNode(); method.maxStack = 99; node.methods.add(method);
        assertEquals(0, LibXInteractStackCompat.repair(node)); assertEquals(99, method.maxStack);
    }
}
