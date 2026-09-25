package io.github.limuqy.mc.hassium.protocol;

/**
 * 聚合就绪确认包
 * <p>
 * 客户端发送给服务端，表示客户端已准备好接收聚合压缩数据。
 * 服务端收到后将连接状态从 PENDING 提升为 ENABLED。
 * <p>
 * 扩展（向后兼容）：新版客户端总是追加 {@code [epoch:VarInt] [id:long]} 字典回执——
 * 语义从「index 已装」升级为「index + 当前字典均已安装且校验通过」。服务端据此：
 * <ul>
 *   <li>判定连接是否 epoch 感知（DICT 帧头是否写 epoch）；</li>
 *   <li>校验客户端安装的字典与服务端激活字典一致，不一致不 ENABLE；</li>
 *   <li>热更 rollout：对候选版本 ACK 的连接计入门控，全员确认后才切换。</li>
 * </ul>
 * 旧服务端解码只读 boolean，忽略尾随字段。
 */
public class AggregationReadyPayload {

    private final boolean ready;
    private final int dictionaryEpoch;
    private final long dictionaryId;
    private final boolean hasDictionaryInfo;

    /** 客户端发送（总是携带字典回执）。 */
    public AggregationReadyPayload(boolean ready, int dictionaryEpoch, long dictionaryId) {
        this.ready = ready;
        this.dictionaryEpoch = dictionaryEpoch;
        this.dictionaryId = dictionaryId;
        this.hasDictionaryInfo = true;
    }

    /** 旧格式兼容构造（无字典回执）。 */
    public AggregationReadyPayload(boolean ready) {
        this.ready = ready;
        this.dictionaryEpoch = 0;
        this.dictionaryId = 0L;
        this.hasDictionaryInfo = false;
    }

    public boolean isReady() {
        return ready;
    }

    public int getDictionaryEpoch() {
        return dictionaryEpoch;
    }

    public long getDictionaryId() {
        return dictionaryId;
    }

    /** 是否携带字典回执字段（false = 旧协议客户端）。 */
    public boolean hasDictionaryInfo() {
        return hasDictionaryInfo;
    }

    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
        buf.writeBoolean(ready);
        buf.writeVarInt(dictionaryEpoch);
        buf.writeLong(dictionaryId);
    }

    public static AggregationReadyPayload decode(net.minecraft.network.FriendlyByteBuf buf) {
        boolean ready = buf.readBoolean();
        if (!buf.isReadable()) {
            return new AggregationReadyPayload(ready);
        }
        int epoch = buf.readVarInt();
        long id = buf.readLong();
        if (buf.isReadable()) {
            throw new IllegalArgumentException(
                    "AggregationReadyPayload: " + buf.isReadable() + " trailing bytes after dictionary fields");
        }
        return new AggregationReadyPayload(ready, epoch, id);
    }
}
