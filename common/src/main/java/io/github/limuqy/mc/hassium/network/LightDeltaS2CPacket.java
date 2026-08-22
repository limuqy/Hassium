package io.github.limuqy.mc.hassium.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * 服务端 -> 客户端：光照增量通知（轻量）
 * <p>
 * 拦截 {@code ClientboundLightUpdatePacket} 后，剥离光照数据，仅通知客户端
 * 哪些区块/section 的光照发生变化。影子端据此清光重算，再把权威光以官方
 * {@code ClientboundLightUpdatePacket} 回传。
 * <p>
 * Wire format（append-only 兼容）：
 * {@code [entryCount:VarInt] ([chunkX:VarInt, chunkZ:VarInt, skyYMask:BitSet,
 * blockYMask:BitSet] * N) ([emptySkyYMask:BitSet, emptyBlockYMask:BitSet] * N)?}
 * <p>
 * 尾部 empty 掩码块为新版追加：旧接收端读完 N 个 entry 即结束（尾字节不读，
 * 天然兼容）；新版接收端在尾块缺失（旧服务端）时视为空掩码（降级为旧语义）。
 */
public record LightDeltaS2CPacket(List<Entry> entries) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buf.writeVarInt(entry.chunkX);
            buf.writeVarInt(entry.chunkZ);
            buf.writeBitSet(entry.skyYMask);
            buf.writeBitSet(entry.blockYMask);
        }
        if (!entries.isEmpty()) {
            // append-only 尾块：旧接收端按旧格式读完即止，不会读到这里。
            for (Entry entry : entries) {
                buf.writeBitSet(entry.emptySkyYMask);
                buf.writeBitSet(entry.emptyBlockYMask);
            }
        }
    }

    public static LightDeltaS2CPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int chunkX = buf.readVarInt();
            int chunkZ = buf.readVarInt();
            BitSet skyYMask = buf.readBitSet();
            BitSet blockYMask = buf.readBitSet();
            entries.add(new Entry(chunkX, chunkZ, skyYMask, blockYMask));
        }
        // append-only 尾块：旧服务端不写；新版写 entries.size() 对空掩码。若剩余字节
        // 不足以再读一对 BitSet，说明是旧格式（或空尾块），按全空处理。
        for (Entry entry : entries) {
            if (buf.isReadable()) {
                int before = buf.readerIndex();
                try {
                    entry.emptySkyYMask.or(buf.readBitSet());
                    entry.emptyBlockYMask.or(buf.readBitSet());
                } catch (RuntimeException e) {
                    buf.readerIndex(before); // 旧格式无尾块：回退，按空掩码
                    return new LightDeltaS2CPacket(entries);
                }
            }
        }
        return new LightDeltaS2CPacket(entries);
    }

    /**
     * 单个区块的光照变更条目
     *
     * @param chunkX         区块 X 坐标
     * @param chunkZ         区块 Z 坐标
     * @param skyYMask       天空光照变更（有数据）的 section 位掩码
     * @param blockYMask     方块光照变更（有数据）的 section 位掩码
     * @param emptySkyYMask  天空光照变为全空的 section 位掩码（新版追加字段）
     * @param emptyBlockYMask 方块光照变为全空的 section 位掩码（新版追加字段）
     */
    public record Entry(int chunkX, int chunkZ, BitSet skyYMask, BitSet blockYMask,
                        BitSet emptySkyYMask, BitSet emptyBlockYMask) {

        /** 旧格式兼容构造：empty 掩码按空处理。 */
        public Entry(int chunkX, int chunkZ, BitSet skyYMask, BitSet blockYMask) {
            this(chunkX, chunkZ, skyYMask, blockYMask, new BitSet(), new BitSet());
        }

        /**
         * 掩码是否有任何变更位。BitSet 由 decode 直接读出，调用方不得修改；
         * 合并/清空语义由 {@code ShadowLightCompute} 的 copy 侧保证。
         */
        public boolean hasAnySection() {
            return !skyYMask.isEmpty() || !blockYMask.isEmpty()
                    || !emptySkyYMask.isEmpty() || !emptyBlockYMask.isEmpty();
        }
    }
}
