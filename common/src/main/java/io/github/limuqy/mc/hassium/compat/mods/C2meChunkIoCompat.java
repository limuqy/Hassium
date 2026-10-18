package io.github.limuqy.mc.hassium.compat.mods;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import io.github.limuqy.mc.hassium.shadow.storage.HassiumType126Codec;
import io.github.limuqy.mc.hassium.shadow.storage.ShadowStorageHashes;
import net.minecraft.world.level.ChunkPos;

import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * C2ME external IO: ownerless RegionFileVersion.wrap may replace compression only on a
 * dedicated server with storage enabled. Physical clients retain vanilla compression here;
 * MixinRegionFile.write identifies the owning shadow directory and re-encodes/adopts the sector.
 * This costs one decode/encode for external shadow IO, but cannot rewrite integrated-world IO.
 */
public final class C2meChunkIoCompat {

    private C2meChunkIoCompat() {
    }

    /**
     * 无目录参数的压缩入口只允许专用服存储模式；影子存档在有归属的 RegionFile.write 收编。
     */
    public static boolean takeoverEnabled() {
        if (!ModCompatFlags.c2meChunkIoReplaced()) {
            return false;
        }
        try {
            HassiumConfigService config = HassiumConfigService.getInstance();
            // wrap(OutputStream) has no world owner. On physical clients keep vanilla encoding;
            // the owning shadow RegionFile.write adopts/re-encodes the sector into its image.
            if (RuntimeServerContext.isDedicatedServerContext() && config.isStorageEnabled()) {
                ModCompatFlags.announceOnce();
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 外部 IO 的压缩入口：返回 Hassium 载荷流，或 {@code null} 表示不接管（保持原样）。
     *
     * @param delegate 外部 IO 的底层输出流（本流只写载荷，不写 length/type）
     */
    public static OutputStream wrap(OutputStream delegate) {
        if (delegate == null || !takeoverEnabled()) {
            return null;
        }
        try {
            int level = HassiumConfigService.getInstance().getStorageCompressionLevel();
            ModCompatStats.onPayloadStream();
            return new HassiumPayloadStream(delegate, level);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * {@code RegionFile.write} 处的槽补丁：检出本兼容层产出的载荷流（{@code 0x48} + 全零 hash），
     * 写入 type 126 并按坐标回填 chunkHash。
     * <p>
     * 幂等：vanilla 压缩载荷（zlib/gzip/lz4/none，首字节均不等于 {@code 0x48}）与 Hassium
     * 自身已写完的 126 槽（hash 非零）都会被跳过。
     */
    public static void patchSector(ChunkPos pos, ByteBuffer buffer) {
        if (pos == null) {
            return;
        }
        Long hash = ShadowStorageHashes.get(pos);
        if (patchSectorBuffer(buffer, hash == null ? 0L : hash, hash != null)) {
            ModCompatStats.onType126Patched();
        }
    }

    /**
     * MC 无关的补丁核心（便于单测；不触碰 ChunkPos / 注册表）。
     *
     * @param contentHash 非 0 时写入；{@code hashKnown=false} 时保留全零占位
     * @return true = 本次确实识别为本兼容层产出并写入
     */
    public static boolean patchSectorBuffer(ByteBuffer buffer, long contentHash, boolean hashKnown) {
        if (buffer == null) {
            return false;
        }
        try {
            if (buffer.capacity() < 5 + 1 + HassiumType126Codec.HASH_LENGTH) {
                return false;
            }
            if (buffer.get(5) != HassiumType126Codec.HASH_MAGIC) {
                // 非本兼容层产出：vanilla 压缩载荷（zlib/gzip/lz4/none）首字节均不等于 0x48
                return false;
            }
            if (buffer.getLong(6) != 0L) {
                // hash 已填 = 已完成槽（本 mod 自身写路径，或本补丁已跑过）
                return false;
            }
            buffer.put(4, HassiumType126Codec.COMPRESSION_TYPE);
            if (hashKnown) {
                buffer.putLong(6, contentHash);
            }
            return true;
        } catch (Throwable ignored) {
            // 补丁失败不阻断写入：退化为"原版压缩 + 无 hash 元数据"，读侧仍可走 vanilla
            return false;
        }
    }
}
