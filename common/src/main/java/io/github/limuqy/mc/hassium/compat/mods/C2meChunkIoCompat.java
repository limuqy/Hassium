package io.github.limuqy.mc.hassium.compat.mods;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import io.github.limuqy.mc.hassium.storage.HassiumType126Codec;
import io.github.limuqy.mc.hassium.storage.ShadowStorageHashes;
import net.minecraft.world.level.ChunkPos;

import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * C2ME 自实现区块 IO（{@code ioSystem.replaceImpl}）下的 type 126 写侧接管。
 * <p>
 * <b>问题</b>：C2ME 的 {@code C2MEStorageThread} 自己拼 sector
 * （{@code out.write(0)×4} → {@code out.write(format.getId())} → {@code format.wrap(out)} →
 * {@code putInt(0, size-5+1)} → {@code invokeWriteChunk(pos, buf)}），**绕过** vanilla 的
 * {@code getChunkDataOutputStream}，因此挂在后者的 Hassium 写钩子永不触发 → type 126 永不产生，
 * 影子缓存读侧又拒绝非 126（{@code shouldSkipVanillaChunkParse}）→ 缓存永久 miss。
 * <p>
 * <b>接管方式（零重压）</b>：两个 hook 都落在 **vanilla 类**上，不引用 C2ME 内部类：
 * <ul>
 *   <li>{@code RegionFileVersion.wrap(OutputStream)} — 这是 C2ME 与 vanilla
 *       {@code getChunkDataOutputStream} 压缩载荷的唯一入口；在该 gate 下 vanilla 路径已被
 *       {@code MixinRegionFile} HEAD 取消，故此处必为外部 IO 调用。替换为
 *       {@link HassiumPayloadStream}，从原始 NBT 一次性 ZSTD+字典压缩（不重压）。</li>
 *   <li>{@code RegionFile.write(ChunkPos, ByteBuffer)} — 所有写入者的汇合点，在此按坐标回填
 *       type 字节与 chunkHash。签名检测（magic + 全零 hash）保证对 vanilla 压缩载荷与
 *       Hassium 自身已完成的 126 槽都是幂等 no-op。</li>
 * </ul>
 */
public final class C2meChunkIoCompat {

    private C2meChunkIoCompat() {
    }

    /**
     * 与 {@code MixinRegionFile} 写路径同口径的门控：仅"专用服开启存储"或"影子端"
     * （影子存档固定写 126，不受 storage.enabled 约束）时接管。
     */
    public static boolean takeoverEnabled() {
        if (!ModCompatFlags.c2meChunkIoReplaced()) {
            return false;
        }
        try {
            HassiumConfigService config = HassiumConfigService.getInstance();
            boolean shadow = RuntimeServerContext.isShadowServerContext();
            if (shadow) {
                ModCompatFlags.announceOnce();
                return true;
            }
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
