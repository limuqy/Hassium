package io.github.limuqy.mc.hassium.network;

import java.util.ArrayList;
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
