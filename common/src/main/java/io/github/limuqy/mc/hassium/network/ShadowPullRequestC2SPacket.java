package io.github.limuqy.mc.hassium.network;

import io.netty.buffer.Unpooled;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

/** Client -> server: bounded shadowPullV1 request. */
public record ShadowPullRequestC2SPacket(
        String dimension,
        long epoch,
        long requestId,
        List<Entry> entries
) {
    public static final int MAX_ENTRIES = 384;
    public static final int MAX_DIMENSION_LENGTH = 128;

    /**
     * 单条 C2S 自定义载荷的硬上限 —— vanilla {@code ServerboundCustomPayloadPacket.MAX_PAYLOAD_SIZE}。
     * <p>
     * <b>S2C 不是这个数</b>（{@code ClientboundCustomPayloadPacket} 是 1 MiB），所以只有客户端→服务端
     * 这一侧需要按字节收敛；超限会被 vanilla 解码器以
     * {@code IllegalArgumentException: Payload may not be larger than 32767 bytes} 拒收并踢掉客户端。
     */
    public static final int MAX_PAYLOAD_BYTES = 32_767;

    public record Entry(int chunkX, int chunkZ, long chunkHash,
                        List<Long> sectionHashes, int[][] planes, int lightGeneration) {
        public Entry(int chunkX, int chunkZ, long chunkHash,
                     List<Long> sectionHashes, int lightGeneration) {
            this(chunkX, chunkZ, chunkHash, sectionHashes, null, lightGeneration);
        }

        public Entry {
            if (sectionHashes == null || sectionHashes.size() > 64
                    || (planes != null && planes.length > 64)) {
                throw new IllegalArgumentException("sectionHashes exceeds limit");
            }
            if (planes != null) {
                boolean anyPlane = false;
                for (int[] plane : planes) {
                    anyPlane |= plane != null;
                }
                if (!anyPlane) {
                    planes = null;
                }
            }
        }
        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Entry that)) return false;
            return chunkX == that.chunkX && chunkZ == that.chunkZ && chunkHash == that.chunkHash
                    && lightGeneration == that.lightGeneration
                    && java.util.Objects.equals(sectionHashes, that.sectionHashes)
                    && (planes == null || that.planes == null || java.util.Arrays.deepEquals(planes, that.planes));
        }
        @Override
        public int hashCode() {
            return java.util.Objects.hash(chunkX, chunkZ, chunkHash, sectionHashes, lightGeneration);
        }
    }

    public ShadowPullRequestC2SPacket {
        if (dimension == null || dimension.length() > MAX_DIMENSION_LENGTH
                || entries == null || entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("shadowPullV1 request exceeds limits");
        }
        if (epoch < 0 || requestId < 0) {
            throw new IllegalArgumentException("epoch/requestId must not be negative");
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(dimension, MAX_DIMENSION_LENGTH);
        buf.writeVarLong(epoch);
        buf.writeVarLong(requestId);
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buf.writeVarInt(entry.chunkX());
            buf.writeVarInt(entry.chunkZ());
            buf.writeLong(entry.chunkHash());
            buf.writeVarInt(entry.lightGeneration());
            buf.writeVarInt(entry.sectionHashes().size());
            for (int i = 0; i < entry.sectionHashes().size(); i++) {
                long hash = entry.sectionHashes().get(i);
                buf.writeLong(hash);
                if (hash != 0L) {
                    int[] plane = entry.planes() != null && i < entry.planes().length
                            ? entry.planes()[i] : null;
                    for (int p = 0; p < io.github.limuqy.mc.hassium.network.sectiondelta.SectionPlaneSyndrome.PLANE_COUNT; p++) {
                        buf.writeInt(plane != null && p < plane.length ? plane[p] : 0);
                    }
                }
            }
        }
    }

    /**
     * 按**编码后字节数**把条目切成若干批（每批同时 ≤ {@link #MAX_ENTRIES}）。
     * <p>
     * 为什么条数上限不够：{@link Entry} 里每个**非零**分段 hash 都要带
     * {@code PLANE_COUNT}(48) 个 int 分量 —— 单分段 {@code 8 + 48*4 = 200} 字节，单柱最多 64 段，
     * 于是一条请求可达数 KB。{@link #MAX_ENTRIES} 只界住**条数**，界不住**载荷**。
     * <p>
     * 超限的 C2S 载荷会被 vanilla 解码器以
     * {@code Payload may not be larger than 32767 bytes} 拒收（客户端被踢下线），故这里按**实际编码长度**
     * 二分切分，而不是猜每柱多少字节。
     * <p>
     * 单条自身超限且已不可再切时**原样返回**——由调用方决定放弃还是缩写，本类不做策略。
     */
    public static List<List<Entry>> batchesByEncodedSize(String dimension, List<Entry> entries, int maxBytes) {
        List<List<Entry>> batches = new ArrayList<>();
        Deque<List<Entry>> queue = new ArrayDeque<>();
        for (int start = 0; start < entries.size(); start += MAX_ENTRIES) {
            queue.addLast(entries.subList(start, Math.min(start + MAX_ENTRIES, entries.size())));
        }
        while (!queue.isEmpty()) {
            List<Entry> batch = queue.pollFirst();
            if (batch.size() <= 1 || encodedSize(dimension, batch) <= maxBytes) {
                batches.add(batch);
            } else {
                int half = batch.size() / 2;
                // 先放后半再放前半，pollFirst 拿到的仍是原顺序（近及远不由本方法负责，但顺序不该被打乱）。
                queue.addFirst(batch.subList(half, batch.size()));
                queue.addFirst(batch.subList(0, half));
            }
        }
        return batches;
    }

    /** {@code entries} 单独作为一条请求编码后的字节数（仅供分批时复核预算）。 */
    private static int encodedSize(String dimension, List<Entry> entries) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new ShadowPullRequestC2SPacket(dimension, 0L, 0L, entries).encode(buffer);
            return buffer.readableBytes();
        } finally {
            buffer.release();
        }
    }

    public static ShadowPullRequestC2SPacket decode(FriendlyByteBuf buf) {
        String dimension = buf.readUtf(MAX_DIMENSION_LENGTH);
        long epoch = buf.readVarLong();
        long requestId = buf.readVarLong();
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) {
            throw new IllegalArgumentException("shadowPullV1 entry count exceeds limit");
        }
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int x = buf.readVarInt();
            int z = buf.readVarInt();
            long chunkHash = buf.readLong();
            int lightGeneration = buf.readVarInt();
            int sectionCount = buf.readVarInt();
            if (sectionCount < 0 || sectionCount > 64) {
                throw new IllegalArgumentException("shadowPullV1 section count exceeds limit");
            }
            List<Long> sectionHashes = new ArrayList<>(sectionCount);
            int[][] planes = new int[sectionCount][];
            for (int j = 0; j < sectionCount; j++) {
                long hash = buf.readLong();
                sectionHashes.add(hash);
                if (hash != 0L) {
                    int[] plane = new int[io.github.limuqy.mc.hassium.network.sectiondelta.SectionPlaneSyndrome.PLANE_COUNT];
                    for (int p = 0; p < plane.length; p++) {
                        plane[p] = buf.readInt();
                    }
                    planes[j] = plane;
                }
            }
            entries.add(new Entry(x, z, chunkHash, sectionHashes, planes, lightGeneration));
        }
        return new ShadowPullRequestC2SPacket(dimension, epoch, requestId, entries);
    }
}
