package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.Constants;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;

/**
 * 压缩就绪确认包
 * <p>
 * 客户端发送给服务端，表示客户端已准备好接收聚合压缩数据。
 * 服务端收到后将连接状态从 PENDING 提升为 ENABLED。
 */
public class CompressionReadyPayload {
    public static final
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
CHANNEL = ResourceLocationCompat.create(Constants.MOD_ID, "compression_ready_c2s");

    private final boolean ready;

    public CompressionReadyPayload(boolean ready) {
        this.ready = ready;
    }

    public boolean isReady() {
        return ready;
    }

    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
        buf.writeBoolean(ready);
    }

    public static CompressionReadyPayload decode(net.minecraft.network.FriendlyByteBuf buf) {
        boolean ready = buf.readBoolean();
        return new CompressionReadyPayload(ready);
    }
}
