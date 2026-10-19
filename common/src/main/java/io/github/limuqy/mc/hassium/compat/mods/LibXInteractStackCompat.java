package io.github.limuqy.mc.hassium.compat.mods;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;

/** LibX 1.20.1 inserts five arguments before ARETURN, above the original result (peak six).
 * Its coremod leaves maxStack=5; Summoning Rituals local capture analyzes it before the
 * final class writer can repair that metadata. Recompute only the affected method, before injection.
 */
public final class LibXInteractStackCompat {
    private LibXInteractStackCompat() {}

    public static int repair(ClassNode target) {
        int changed = 0;
        for (MethodNode method : target.methods) {
            boolean affected = false;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("org/moddingx/libx/impl/libxcore/CoreInteract")
                        && call.name.equals("useItemOn")) affected = true;
            }
            if (!affected) continue;
            int previousStack = method.maxStack;
            try {
                new Analyzer<BasicValue>(new BasicInterpreter()).analyzeAndComputeMaxs(target.name, method);
            } catch (AnalyzerException failure) {
                throw new IllegalStateException("Cannot analyze LibX interaction hook " + target.name + "." + method.name, failure);
            }
            if (method.maxStack != previousStack) changed++;
        }
        return changed;
    }
}
