package io.github.limuqy.mc.hassium.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/** Typed smoke-test connection calls so production remappers also remap their names. */
public final class SmokeConnectCompat {
    private SmokeConnectCompat() {
    }

    public static void connect(Minecraft minecraft, String host) {
        ServerAddress address = ServerAddress.parseString(host);
#if MC_VER < MC_1_21_1
        ServerData data = new ServerData("HassiumTest", host, false);
        ConnectScreen.startConnecting(new TitleScreen(), minecraft, address, data, false);
#else
        ServerData data = new ServerData("HassiumTest", host, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(new TitleScreen(), minecraft, address, data, false, null);
#endif
    }
}
