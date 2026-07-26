package io.github.limuqy.mc.hassium.network.dataplane;

import java.nio.ByteBuffer;

/**
 * Task 6 failover KCP control-frame payload codec.
 *
 * <p>Payloads are already AEAD-authenticated by {@link ReliableDatagramSession}; this class only
 * provides a strict canonical representation so malformed control frames never reach the state machine.
 */
public final class FailoverFrameCodec {

    private static final int REQUEST_FIXED_BYTES = Long.BYTES;
    private static final int PERMIT_BYTES = Long.BYTES * 2;

    public record Request(long connectionEpoch, int requestedEndpointId) { }
    public record Permit(long connectionEpoch, long expiryMs) { }

    private FailoverFrameCodec() { }

    /** {@code connectionEpoch[i64] + requestedEndpointId[varint]}. */
    public static byte[] encodeRequest(long connectionEpoch, int requestedEndpointId) {
        if (requestedEndpointId < 0) {
            throw new IllegalArgumentException("requestedEndpointId must be non-negative");
        }
        int varIntBytes = DataPlaneFrame.varIntSize(requestedEndpointId);
        ByteBuffer out = ByteBuffer.allocate(REQUEST_FIXED_BYTES + varIntBytes);
        out.putLong(connectionEpoch);
        writeVarInt(out, requestedEndpointId);
        return out.array();
    }

    public static Request decodeRequest(byte[] payload) {
        if (payload == null || payload.length <= REQUEST_FIXED_BYTES) {
            throw new IllegalArgumentException("failover request truncated");
        }
        ByteBuffer in = ByteBuffer.wrap(payload);
        long epoch = in.getLong();
        int endpointId = readCanonicalVarInt(in);
        if (in.hasRemaining()) {
            throw new IllegalArgumentException("failover request has trailing bytes");
        }
        return new Request(epoch, endpointId);
    }

    /** {@code connectionEpoch[i64] + expiryMs[i64]}. */
    public static byte[] encodePermit(long connectionEpoch, long expiryMs) {
        ByteBuffer out = ByteBuffer.allocate(PERMIT_BYTES);
        out.putLong(connectionEpoch);
        out.putLong(expiryMs);
        return out.array();
    }

    public static Permit decodePermit(byte[] payload) {
        if (payload == null || payload.length != PERMIT_BYTES) {
            throw new IllegalArgumentException("failover permit must be exactly " + PERMIT_BYTES + " bytes");
        }
        ByteBuffer in = ByteBuffer.wrap(payload);
        return new Permit(in.getLong(), in.getLong());
    }

    private static void writeVarInt(ByteBuffer out, int value) {
        while ((value & ~0x7F) != 0) {
            out.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.put((byte) value);
    }

    private static int readCanonicalVarInt(ByteBuffer in) {
        int value = 0;
        int shift = 0;
        while (true) {
            if (!in.hasRemaining() || shift >= 35) {
                throw new IllegalArgumentException("invalid failover endpoint VarInt");
            }
            int next = in.get() & 0xFF;
            value |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) {
                if (DataPlaneFrame.varIntSize(value) != shift / 7 + 1) {
                    throw new IllegalArgumentException("non-canonical failover endpoint VarInt");
                }
                return value;
            }
            shift += 7;
        }
    }
}
