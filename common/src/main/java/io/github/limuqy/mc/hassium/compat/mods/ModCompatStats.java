package io.github.limuqy.mc.hassium.compat.mods;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 兼容层命中计数（供冒烟 PROBE 断言，防空测）。
 * <p>
 * 只做累加，不做采样；{@code reset()} 供会话起点清零。
 */
public final class ModCompatStats {

    private static final AtomicLong C2ME_PAYLOAD_STREAMS = new AtomicLong();
    private static final AtomicLong C2ME_TYPE126_PATCHED = new AtomicLong();

    private ModCompatStats() {
    }

    /** 外部 IO 写路径被替换为 Hassium type 126 载荷流的次数。 */
    public static void onPayloadStream() {
        C2ME_PAYLOAD_STREAMS.incrementAndGet();
    }

    /** {@code RegionFile.write} 处成功把外地产出补丁为 type 126 槽的次数。 */
    public static void onType126Patched() {
        C2ME_TYPE126_PATCHED.incrementAndGet();
    }

    public static long payloadStreams() {
        return C2ME_PAYLOAD_STREAMS.get();
    }

    public static long type126Patched() {
        return C2ME_TYPE126_PATCHED.get();
    }

    public static void reset() {
        C2ME_PAYLOAD_STREAMS.set(0L);
        C2ME_TYPE126_PATCHED.set(0L);
    }
}
