package io.github.limuqy.mc.hassium.protocol.sleep;

import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;

/** Small, bounded control plane; never carries game actions or old world packets. */
public final class SleepControl {
    public enum Op { ENTER, PREPARED, CONFIRM, SLEEPING, RESUME, ACTIVE, ERROR, PROBE, COLUMN_APPLIED, COLUMN_READY, COLUMN_RETRY, COLUMN_REQUEST }
    public record Message(Op op, long generation, long sequence, String detail) {
        public Message {
            if (op == null || generation < 0 || sequence <= 0
                    || detail == null || detail.length() > 256)
                throw new IllegalArgumentException("Invalid sleep control");
        }
    }
    private SleepControl() {}
    public static byte[] encode(Message message) {
        FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.buffer());
        try {
            b.writeVarInt(message.op().ordinal()); b.writeLong(message.generation()); b.writeLong(message.sequence());
            b.writeUtf(message.detail(),256);
            byte[] bytes = new byte[b.readableBytes()]; b.readBytes(bytes); return bytes;
        } finally { b.release(); }
    }
    public static Message decode(byte[] bytes) {
        if (bytes.length > 1024) throw new IllegalArgumentException("Sleep control too large");
        FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            int ordinal = b.readVarInt();
            if (ordinal < 0 || ordinal >= Op.values().length) throw new IllegalArgumentException("Unknown sleep operation");
            Message m = new Message(Op.values()[ordinal],b.readLong(),b.readLong(),b.readUtf(256));
            if (b.isReadable()) throw new IllegalArgumentException("Trailing sleep bytes");
            return m;
        } finally { b.release(); }
    }

}
