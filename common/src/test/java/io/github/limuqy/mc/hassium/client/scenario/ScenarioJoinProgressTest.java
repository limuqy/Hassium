package io.github.limuqy.mc.hassium.client.scenario;

import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioJoinProgressTest {
    @Test void loginWithoutPlayConnectionMustRemainInProgress() throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        assertTrue(ScenarioEngine.joinInProgress((ConnectScreen) unsafe.allocateInstance(ConnectScreen.class)));
        assertFalse(ScenarioEngine.joinInProgress((TitleScreen) unsafe.allocateInstance(TitleScreen.class)));
        assertFalse(ScenarioEngine.joinInProgress(null));
    }
}
