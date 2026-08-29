package io.github.limuqy.mc.hassium.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

/** Server -> client: one terminal result per shadowPullV1 entry. */
public record ShadowPullResponseS2CPacket(
        String dimension,
        long epoch,
        long requestId,
        List<Result> results
) {
    public static final int MAX_RESULTS = ShadowPullRequestC2SPacket.MAX_ENTRIES;
    public static final int MAX_ERROR_LENGTH = 256;
    public static final int MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;

    public enum Kind {
        UNCHANGED(0),
        DELTA(1),
        FULL(2),
        ERROR(3);

        private final int wireValue;

        Kind(int wireValue) {
            this.wireValue = wireValue;
        }

        public int wireValue() {
            return wireValue;
        }

        public static Kind fromWire(int wireValue) {
            for (Kind kind : values()) {
                if (kind.wireValue == wireValue) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unknown shadowPullV1 result kind: " + wireValue);
        }
    }

    public record Result(int chunkX, int chunkZ, Kind kind, long chunkHash,
                         List<Long> sectionHashes, byte[] payload, String error) {
        public Result {
            if (kind == null || sectionHashes == null || sectionHashes.size() > 64
                    || payload == null || payload.length > MAX_PAYLOAD_BYTES
                    || error == null || error.length() > MAX_ERROR_LENGTH) {
                throw new IllegalArgumentException("shadowPullV1 result exceeds limits");
            }
        }

        public static Result unchanged(int x, int z, long hash, List<Long> sectionHashes) {
            return new Result(x, z, Kind.UNCHANGED, hash, sectionHashes, new byte[0], "");
        }

        public static Result payload(int x, int z, Kind kind, long hash,
                                     List<Long> sectionHashes, byte[] payload) {
            if (kind != Kind.DELTA && kind != Kind.FULL) {
                throw new IllegalArgumentException("payload result must be DELTA or FULL");
            }
            return new Result(x, z, kind, hash, sectionHashes, payload, "");
        }

        public static Result error(int x, int z, String error) {
            return new Result(x, z, Kind.ERROR, 0L, List.of(), new byte[0], error);
        }
    }


    public ShadowPullResponseS2CPacket {
        if (dimension == null || dimension.length() > ShadowPullRequestC2SPacket.MAX_DIMENSION_LENGTH
                || results == null || results.size() > MAX_RESULTS
                || epoch < 0 || requestId < 0) {
            throw new IllegalArgumentException("shadowPullV1 response exceeds limits");
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(dimension, ShadowPullRequestC2SPacket.MAX_DIMENSION_LENGTH);
        buf.writeVarLong(epoch);
        buf.writeVarLong(requestId);
        buf.writeVarInt(results.size());
        for (Result result : results) {
            buf.writeVarInt(result.chunkX());
            buf.writeVarInt(result.chunkZ());
            buf.writeVarInt(result.kind().wireValue());
            buf.writeLong(result.chunkHash());
            buf.writeVarInt(result.sectionHashes().size());
            for (long hash : result.sectionHashes()) {
                buf.writeLong(hash);
            }
            buf.writeByteArray(result.payload());
            buf.writeUtf(result.error(), MAX_ERROR_LENGTH);
        }
    }

    public static ShadowPullResponseS2CPacket decode(FriendlyByteBuf buf) {
        String dimension = buf.readUtf(ShadowPullRequestC2SPacket.MAX_DIMENSION_LENGTH);
        long epoch = buf.readVarLong();
        long requestId = buf.readVarLong();
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_RESULTS) {
            throw new IllegalArgumentException("shadowPullV1 result count exceeds limit");
        }
        List<Result> results = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int x = buf.readVarInt();
            int z = buf.readVarInt();
            Kind kind = Kind.fromWire(buf.readVarInt());
            long chunkHash = buf.readLong();
            int sectionCount = buf.readVarInt();
            if (sectionCount < 0 || sectionCount > 64) {
                throw new IllegalArgumentException("shadowPullV1 section count exceeds limit");
            }
            List<Long> sectionHashes = new ArrayList<>(sectionCount);
            for (int j = 0; j < sectionCount; j++) {
                sectionHashes.add(buf.readLong());
            }
            int payloadLength = buf.readVarInt();
            if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("shadowPullV1 payload exceeds limit");
            }
            byte[] payload = new byte[payloadLength];
            buf.readBytes(payload);
            String error = buf.readUtf(MAX_ERROR_LENGTH);
            results.add(new Result(x, z, kind, chunkHash, sectionHashes, payload, error));
        }
        return new ShadowPullResponseS2CPacket(dimension, epoch, requestId, results);
    }

}
