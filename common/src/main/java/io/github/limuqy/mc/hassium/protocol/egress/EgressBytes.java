package io.github.limuqy.mc.hassium.protocol.egress;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Bounded, canonical wire primitives shared by the v3 codec and its replay tests. */
final class EgressBytes {
    static final class Writer extends ByteArrayOutputStream {
        Writer() {}
        Writer(int capacity) { super(capacity); }
        Writer integer(int value) {
            if (value < 0) throw new IllegalArgumentException("Negative wire integer");
            do { int b = value & 127; value >>>= 7; write(b | (value == 0 ? 0 : 128)); } while (value != 0);
            return this;
        }
        Writer number(long value) {
            if (value < 0) throw new IllegalArgumentException("Negative wire number");
            do { int b = (int) value & 127; value >>>= 7; write(b | (value == 0 ? 0 : 128)); } while (value != 0);
            return this;
        }
        Writer octet(int value) { write(value); return this; }
        Writer bytes(byte[] value) { writeBytes(value); return this; }
        Writer fixedInt(int value) { for (int s = 24; s >= 0; s -= 8) write(value >>> s); return this; }
    }
    static int numberSize(long value) { int size = 1; while ((value >>>= 7) != 0) size++; return size; }
    static final class Reader {
        private final byte[] bytes;
        private int position;
        Reader(byte[] bytes) { this.bytes = bytes; }
        int remaining() { return bytes.length - position; }
        int octet() { if (remaining() == 0) throw invalid("Truncated frame"); return bytes[position++] & 255; }
        long number() {
            long value = 0;
            for (int s = 0; s <= 56; s += 7) {
                int b = octet(); value |= (long) (b & 127) << s;
                if ((b & 128) == 0) {
                    if (s != 0 && (b & 127) == 0) throw invalid("Noncanonical number");
                    return value;
                }
            }
            throw invalid("Wire number overflow");
        }
        int integer(int maximum) { long n = number(); if (n > maximum) throw invalid("Wire length exceeds limit"); return (int)n; }
        byte[] bytes(int size) { if (size < 0 || size > remaining()) throw invalid("Truncated bytes"); byte[] out = Arrays.copyOfRange(bytes, position, position + size); position += size; return out; }
        int fixedInt() { return octet() << 24 | octet() << 16 | octet() << 8 | octet(); }
        String utf(int maximum) { return new String(bytes(integer(maximum)), StandardCharsets.UTF_8); }
        void end() { if (remaining() != 0) throw invalid("Trailing frame bytes"); }
    }
    static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("Hassium egress v3: " + message); }
    private EgressBytes() {}
}
