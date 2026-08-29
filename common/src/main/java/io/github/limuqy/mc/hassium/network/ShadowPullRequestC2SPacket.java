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
                        List<Long> sectionHashes, int lightGeneration) {
        public Entry {
            if (sectionHashes == null || sectionHashes.size() > 64) {
                throw new IllegalArgumentException("sectionHashes exceeds limit");
            }
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
            for (long hash : entry.sectionHashes()) {
                buf.writeLong(hash);
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
            for (int j = 0; j < sectionCount; j++) {
                sectionHashes.add(buf.readLong());
            }
            entries.add(new Entry(x, z, chunkHash, sectionHashes, lightGeneration));
        }
        return new ShadowPullRequestC2SPacket(dimension, epoch, requestId, entries);
    }
}
