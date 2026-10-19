package io.github.limuqy.mc.hassium.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/** Typed calls also work after production remapping. Only used by the dev smoke entrypoint. */
public final class SmokeConnectCompat {
    private SmokeConnectCompat() {}

    public static void connect(Minecraft client, String host) {
        ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(host),
                new ServerData("HassiumTest", host, ServerData.Type.OTHER), false, null);
    }
}
