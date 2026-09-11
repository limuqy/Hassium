package io.github.limuqy.mc.hassium.network;

/**
 * 聚合就绪确认包
 * <p>
 * 客户端发送给服务端，表示客户端已准备好接收聚合压缩数据。
 * 服务端收到后将连接状态从 PENDING 提升为 ENABLED。
 */
public class AggregationReadyPayload {

    private final boolean ready;

    public AggregationReadyPayload(boolean ready) {
        this.ready = ready;
    }

    public boolean isReady() {
        return ready;
    }

    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
        buf.writeBoolean(ready);
    }

    public static AggregationReadyPayload decode(net.minecraft.network.FriendlyByteBuf buf) {
        boolean ready = buf.readBoolean();
        return new AggregationReadyPayload(ready);
    }
}
