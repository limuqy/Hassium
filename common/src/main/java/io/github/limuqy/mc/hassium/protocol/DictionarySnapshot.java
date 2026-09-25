package io.github.limuqy.mc.hassium.protocol;

/**
 * 聚合字典版本化快照（不可变）。
 * <p>
 * 把字典从「全局 {@code byte[]}」提升为「带版本的协议状态」的最小单元：
 * <ul>
 *   <li>{@code epoch}：单调递增的字典代（同一服务端进程内从 1 起算；跨进程重启后客户端
 *       经 dictionary_sync 重新学习，不要求跨进程唯一）；</li>
 *   <li>{@code id}：字典内容 hash（FNV-1a 64，跨 JVM 确定性）——客户端可自行校验收到的
 *       字节与 id 一致，防止截断/损坏字典被静默安装；</li>
 *   <li>{@code data}：字典字节。构造时防御性克隆；{@link #data()} 返回内部数组，
 *       调用方必须视作不可变（编码热路径按引用读取，不做逐帧克隆）。</li>
 * </ul>
 * epoch + id 二元组在单次连接会话内唯一标识一份字典；客户端幂等安装、按 epoch 查找
 * （当前 + 上一版，覆盖热切换窗口内的在途旧帧）。
 */
public record DictionarySnapshot(int epoch, long id, byte[] data) {

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    public DictionarySnapshot {
        data = data == null ? new byte[0] : data.clone();
    }

    /** 无字典/空字典快照（epoch 0 + id 0 仅作为「未安装」标记，不参与 epoch 查找）。 */
    public boolean isEmpty() {
        return data.length == 0;
    }

    /**
     * 字典内容 hash（FNV-1a 64）。非加密 hash，仅用于版本识别与完整性核对。
     */
    public static long hash(byte[] data) {
        long h = FNV_OFFSET_BASIS;
        if (data != null) {
            for (byte b : data) {
                h ^= (b & 0xffL);
                h *= FNV_PRIME;
            }
        }
        return h;
    }

    /** 由 epoch + 内容构造快照；空内容归一为「未安装」快照（epoch 0 / id 0）。 */
    public static DictionarySnapshot of(int epoch, byte[] data) {
        if (data == null || data.length == 0) {
            return new DictionarySnapshot(0, 0L, new byte[0]);
        }
        return new DictionarySnapshot(epoch, hash(data), data);
    }

    /** 是否为给定的 (epoch, id) 对（即客户端 ACK / 服务端校验用的相等判据）。 */
    public boolean matches(int otherEpoch, long otherId) {
        return epoch == otherEpoch && id == otherId;
    }
}
