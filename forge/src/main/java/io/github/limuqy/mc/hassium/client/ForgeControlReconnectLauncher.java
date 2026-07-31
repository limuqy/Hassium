package io.github.limuqy.mc.hassium.client;

import io.github.limuqy.mc.hassium.network.dataplane.ControlEndpoint;
import io.github.limuqy.mc.hassium.network.dataplane.ControlReconnectLauncher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

public final class ForgeControlReconnectLauncher implements ControlReconnectLauncher {
    private final Screen parentScreen;
    public ForgeControlReconnectLauncher() { this(Minecraft.getInstance().screen); }
    public ForgeControlReconnectLauncher(Screen parentScreen) { this.parentScreen = parentScreen; }
    @Override public void connect(ControlEndpoint endpoint, Runnable onFailure) {
        Minecraft mc = Minecraft.getInstance();
        ServerAddress address = new ServerAddress(endpoint.host(), endpoint.port());
#if MC_VER < MC_1_20_2
        ServerData data = new ServerData("hassium-failover:" + endpoint.host() + ":" + endpoint.port(),
                endpoint.host() + ":" + endpoint.port(), false);
#else
        ServerData data = new ServerData("hassium-failover:" + endpoint.host() + ":" + endpoint.port(),
                endpoint.host() + ":" + endpoint.port(), ServerData.Type.OTHER);
#endif
#if MC_VER < MC_1_20_5
        mc.execute(() -> ConnectScreen.startConnecting(parentScreen, mc, address, data, false));
#else
        mc.execute(() -> ConnectScreen.startConnecting(parentScreen, mc, address, data, false, null));
#endif
    }
}
