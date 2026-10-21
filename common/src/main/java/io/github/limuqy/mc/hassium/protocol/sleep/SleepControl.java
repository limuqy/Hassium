package io.github.limuqy.mc.hassium.protocol.sleep;

import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;

/** Small, bounded control plane; never carries game actions or old world packets. */
public final class SleepControl {
    public enum Op { ENTER, PREPARED, CONFIRM, SLEEPING, WAKE, WAKE_READY, RECOVERING, FINISH, ACTIVE, ERROR, SAFETY, PROBE, RESUME, COLUMN_APPLIED, COLUMN_READY, COLUMN_RETRY, COLUMN_REQUEST }
    public record Message(Op op, long generation, long sequence, float threshold, int holdSeconds, String detail, float interactionRadius) {
        public Message(Op op,long generation,long sequence,float threshold,int holdSeconds,String detail) { this(op,generation,sequence,threshold,holdSeconds,detail,8); }
        public Message {
            if (op == null || generation < 0 || sequence <= 0 || !Float.isFinite(threshold) || threshold < 0
                    || holdSeconds < 0 || holdSeconds > 3600 || detail == null || detail.length() > 256
                    || !Float.isFinite(interactionRadius) || interactionRadius<8 || interactionRadius>1024)
                throw new IllegalArgumentException("Invalid sleep control");
        }
    }
    private SleepControl() {}
    public static byte[] encode(Message message) {
        FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.buffer());
        try {
            b.writeVarInt(message.op().ordinal()); b.writeLong(message.generation()); b.writeLong(message.sequence());
            b.writeFloat(message.threshold()); b.writeVarInt(message.holdSeconds()); b.writeUtf(message.detail(),256); b.writeFloat(message.interactionRadius());
            byte[] bytes = new byte[b.readableBytes()]; b.readBytes(bytes); return bytes;
        } finally { b.release(); }
    }
    public static Message decode(byte[] bytes) {
        if (bytes.length > 1024) throw new IllegalArgumentException("Sleep control too large");
        FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            int ordinal = b.readVarInt();
            if (ordinal < 0 || ordinal >= Op.values().length) throw new IllegalArgumentException("Unknown sleep operation");
            Message m = new Message(Op.values()[ordinal],b.readLong(),b.readLong(),b.readFloat(),b.readVarInt(),b.readUtf(256),b.readFloat());
            if (b.isReadable()) throw new IllegalArgumentException("Trailing sleep bytes");
            return m;
        } finally { b.release(); }
    }
    public static boolean lowHealth(float health, float threshold) {
        return threshold > 0 && health > 0 && health <= threshold;
    }
    public static boolean validThreshold(float threshold, float maximum) {
        return Float.isFinite(threshold) && threshold >= 0 && Float.isFinite(maximum) && threshold <= maximum;
    }
}
