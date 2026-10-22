package io.github.limuqy.mc.hassium.protocol.egress;

import io.github.limuqy.mc.hassium.compression.ZstdRuntimeBridge;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Consumer;
import java.util.zip.CRC32C;

/** One codec per transport direction; state advances only after a complete frame validates. */
public final class EgressFrameCodec implements AutoCloseable {
    public static final int VERSION = 3;
    public static final int MAX_FRAME_BYTES = 1_048_576 - 512;
    public static final int MAX_LOGICAL_BYTES = 8 * 1024 * 1024;
    public static final int MAX_PACKETS = 4096;
    public static final int HISTORY_BYTES = 65536;
    public static final int RAW = 0, ZSTD = 1, HISTORY = 2, TRAINED = 3;
    private final ZstdRuntimeBridge.CompressCtx compressor;
    private final ZstdRuntimeBridge.DecompressCtx decompressor;
    private final TemplateDictionary templates = new TemplateDictionary();
    private final boolean historyEnabled, templatesEnabled;
    private byte[] history = new byte[0];
    private long epoch, sequence;
    private long templateAttempts, templateSkips, templateWins;
    private boolean closed;

    public static final class PacketBytes {
        private final String key;
        private final byte[] bytes;
        public PacketBytes(String key, byte[] bytes) { this(key, bytes, false); }
        private PacketBytes(String key, byte[] bytes, boolean owned) {
            if (key == null || bytes == null || bytes.length == 0 || bytes.length > MAX_FRAME_BYTES) throw EgressBytes.invalid("Invalid packet");
            this.key = key; this.bytes = owned ? bytes : bytes.clone();
        }
        /** Only pass freshly allocated bytes whose ownership is transferred to this packet. */
        static PacketBytes owned(String key, byte[] bytes) { return new PacketBytes(key, bytes, true); }
        public String key() { return key; }
        public byte[] bytes() { return bytes.clone(); }
        // Package-private read-only view. Callers must never modify the owned array.
        byte[] content() { return bytes; }
    }
    public record Dictionary(int epoch, byte[] bytes) {
        public Dictionary { if (epoch <= 0 || bytes == null || bytes.length == 0) throw EgressBytes.invalid("Invalid dictionary"); bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    public record Encoded(byte[] bytes, int originalBytes, int mode, boolean mapped,
                          int exactReferences, int templateReferences, int definitionBytes, int availableDictionaryEpoch) {}

    public EgressFrameCodec(int level) { this(level, true, true); }
    public EgressFrameCodec(int level, boolean historyEnabled, boolean templatesEnabled) {
        this.historyEnabled = historyEnabled; this.templatesEnabled = templatesEnabled;
        var c = ZstdRuntimeBridge.newCompressCtx();
        ZstdRuntimeBridge.DecompressCtx d = null;
        try {
            c.setLevel(level); c.setMagicless(true);
            d = ZstdRuntimeBridge.newDecompressCtx(); d.setMagicless(true);
        } catch (Throwable failure) {
            try { c.close(); } catch (Throwable closing) { failure.addSuppressed(closing); }
            if (d != null) try { d.close(); } catch (Throwable closing) { failure.addSuppressed(closing); }
            throw failure;
        }
        compressor = c; decompressor = d;
    }
    public synchronized void setLevel(int level) { open(); compressor.setLevel(level); }
    public synchronized Encoded encode(List<PacketBytes> packets, long sessionEpoch, Dictionary dictionary) {
        return encode(packets, sessionEpoch, dictionary, null);
    }
    /** The observer may retain, but must never mutate, the already serialized logical batch. */
    synchronized Encoded encode(List<PacketBytes> packets, long sessionEpoch, Dictionary dictionary, Consumer<byte[]> logicalObserver) {
        open();
        if (sessionEpoch <= 0 || packets == null || packets.isEmpty() || packets.size() > MAX_PACKETS) throw EgressBytes.invalid("Invalid outbound frame");
        if (epoch != sessionEpoch) { if (epoch != 0 && sessionEpoch <= epoch) throw EgressBytes.invalid("Epoch regression"); reset(sessionEpoch); }
        if (sequence == Long.MAX_VALUE) throw EgressBytes.invalid("Sequence exhausted");
        byte[] logical = logical(packets);
        int checksum = checksum(logical);
        long next = sequence + 1;
        Candidate best = choose(logical, false, logical.length, checksum, next, dictionary);
        TemplateDictionary.Transaction transaction = null;
        if (templatesEnabled) {
            if (templates.shouldAttempt(packets, best.size, logical.length)) { templateAttempts++; transaction = templates.prepare(packets); }
            else templateSkips++;
        }
        if (transaction != null && transaction.bytes().length <= MAX_LOGICAL_BYTES) {
            Candidate mapped = choose(transaction.bytes(), true, logical.length, checksum, next, dictionary);
            if (mapped.size < best.size) best = mapped;
        }
        if (best.size > MAX_FRAME_BYTES) throw EgressBytes.invalid("Carrier exceeds payload limit");
        byte[] frame = assemble(best, logical.length, checksum, next);
        int exact = 0, partial = 0, definitions = 0;
        if (best.mapped) { transaction.commit(); exact = transaction.exact; partial = transaction.partial; definitions = transaction.definitions; }
        if (templatesEnabled) {
            templates.selected(best.mapped);
            if (best.mapped) templateWins++;
        }
        if (historyEnabled) appendHistory(best.payload);
        sequence = next;
        if (logicalObserver != null) logicalObserver.accept(logical);
        return new Encoded(frame, logical.length, best.mode, best.mapped, exact, partial, definitions,
                dictionary == null ? 0 : dictionary.epoch);
    }
    public record TemplateStats(long attempts, long skips, long wins) {}
    public synchronized TemplateStats templateStats() { return new TemplateStats(templateAttempts, templateSkips, templateWins); }
    private Candidate choose(byte[] payload, boolean mapped, int length, int crc, long next, Dictionary dictionary) {
        Candidate best = candidate(payload, payload, mapped, RAW, 0, length, crc, next);
        if (historyEnabled && history.length != 0 && !dictionaryMagic(history)) {
            compressor.loadDict(history);
            Candidate c = candidate(compressor.compress(payload), payload, mapped, HISTORY, 0, length, crc, next);
            if (c.size < best.size) best = c;
        }
        compressor.loadDict(dictionary == null ? new byte[0] : dictionary.bytes);
        Candidate c = candidate(compressor.compress(payload), payload, mapped, dictionary == null ? ZSTD : TRAINED,
                dictionary == null ? 0 : dictionary.epoch, length, crc, next);
        return c.size < best.size ? c : best;
    }
    // The embedded JNI loader auto-detects formatted dictionaries. Opaque bytes with that
    // reserved prefix are not a valid raw-content candidate; other complete candidates remain eligible.
    private static boolean dictionaryMagic(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 0x37 && bytes[1] == (byte)0xa4 && bytes[2] == 0x30 && bytes[3] == (byte)0xec;
    }
    private Candidate candidate(byte[] encoded, byte[] payload, boolean mapped, int mode, int dictEpoch, int length, int crc, long next) {
        int size = EgressBytes.numberSize(VERSION) + EgressBytes.numberSize(epoch) + EgressBytes.numberSize(next)
                + 1 + EgressBytes.numberSize(length) + EgressBytes.numberSize(payload.length) + 4
                + (mode == TRAINED ? EgressBytes.numberSize(dictEpoch) : 0) + encoded.length;
        return new Candidate(encoded, payload, mode, mapped, dictEpoch, size);
    }
    private byte[] assemble(Candidate selected, int length, int crc, long next) {
        EgressBytes.Writer w = new EgressBytes.Writer(selected.size);
        w.integer(VERSION).number(epoch).number(next).octet(selected.mode | (selected.mapped ? 4 : 0))
                .integer(length).integer(selected.payload.length).fixedInt(crc);
        if (selected.mode == TRAINED) w.integer(selected.dictEpoch);
        w.bytes(selected.encoded);
        return w.toByteArray();
    }
    public synchronized List<byte[]> decode(byte[] frame, IntFunction<byte[]> dictionaries) {
        open();
        if (frame == null || frame.length > MAX_FRAME_BYTES) throw EgressBytes.invalid("Invalid carrier length");
        EgressBytes.Reader r = new EgressBytes.Reader(frame);
        if (r.integer(VERSION) != VERSION) throw EgressBytes.invalid("Unsupported frame version");
        long nextEpoch = r.number(), next = r.number();
        int flags = r.octet();
        if (nextEpoch == 0 || next == 0 || (flags & ~7) != 0) throw EgressBytes.invalid("Invalid frame header");
        int mode = flags & 3;
        boolean mapped = (flags & 4) != 0;
        boolean fresh = nextEpoch != epoch;
        if (fresh && (nextEpoch <= epoch || next != 1 || mode == HISTORY)) throw EgressBytes.invalid("Invalid epoch boundary");
        if (!fresh && (sequence == Long.MAX_VALUE || next != sequence + 1)) throw EgressBytes.invalid("Sequence mismatch");
        int logicalLength = r.integer(MAX_LOGICAL_BYTES), payloadLength = r.integer(MAX_LOGICAL_BYTES), crc = r.fixedInt();
        if (logicalLength == 0 || payloadLength == 0) throw EgressBytes.invalid("Empty frame");
        byte[] dict = new byte[0];
        if (mode == HISTORY) { if (history.length == 0) throw EgressBytes.invalid("Missing history"); dict = history; }
        if (mode == TRAINED) {
            int id = r.integer(Integer.MAX_VALUE);
            if (id == 0) throw EgressBytes.invalid("Invalid trained dictionary epoch");
            dict = dictionaries == null ? null : dictionaries.apply(id);
            if (dict == null || dict.length == 0) throw EgressBytes.invalid("Missing dictionary " + id);
        }
        byte[] data = r.bytes(r.remaining());
        byte[] payload;
        if (mode == RAW) payload = data;
        else { decompressor.loadDict(dict); payload = decompressor.decompress(data, payloadLength); }
        if (payload.length != payloadLength) throw EgressBytes.invalid("Decompressed length mismatch");
        TemplateDictionary receiver = fresh ? new TemplateDictionary() : templates;
        TemplateDictionary.Decoded mappedPayload = mapped ? receiver.decode(payload) : null;
        byte[] logical = mapped ? mappedPayload.bytes() : payload;
        if (logical.length != logicalLength || checksum(logical) != crc) throw EgressBytes.invalid("CRC or restored length mismatch");
        List<byte[]> packets = splitLogical(logical);
        if (fresh) reset(nextEpoch);
        if (mapped) mappedPayload.commit();
        if (mapped && fresh) templates.replace(receiver);
        appendHistory(payload); sequence = next;
        return packets;
    }
    private static byte[] logical(List<PacketBytes> packets) {
        int size = EgressBytes.numberSize(packets.size());
        for (PacketBytes p : packets) { size += EgressBytes.numberSize(p.bytes.length) + p.bytes.length; if (size > MAX_LOGICAL_BYTES) throw EgressBytes.invalid("Logical frame too large"); }
        EgressBytes.Writer w = new EgressBytes.Writer(size); w.integer(packets.size());
        for (PacketBytes p : packets) w.integer(p.bytes.length).bytes(p.bytes);
        return w.toByteArray();
    }
    static List<byte[]> splitLogical(byte[] logical) {
        EgressBytes.Reader r = new EgressBytes.Reader(logical);
        int count = r.integer(MAX_PACKETS); if (count == 0) throw EgressBytes.invalid("Empty packet batch");
        List<byte[]> packets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) { int n = r.integer(MAX_FRAME_BYTES); if (n == 0) throw EgressBytes.invalid("Empty packet"); packets.add(r.bytes(n)); }
        r.end(); return List.copyOf(packets);
    }
    private static int checksum(byte[] data) { CRC32C crc = new CRC32C(); crc.update(data); return (int)crc.getValue(); }
    private void appendHistory(byte[] data) {
        int added = Math.min(data.length, HISTORY_BYTES), kept = Math.min(history.length, HISTORY_BYTES - added);
        byte[] next = new byte[kept + added];
        System.arraycopy(history, history.length - kept, next, 0, kept);
        System.arraycopy(data, data.length - added, next, kept, added); history = next;
    }
    private void reset(long sessionEpoch) { epoch = sessionEpoch; sequence = 0; history = new byte[0]; templates.clear(); }
    private void open() { if (closed) throw EgressBytes.invalid("Codec closed"); }
    @Override public synchronized void close() { if (!closed) { closed = true; compressor.close(); decompressor.close(); history = new byte[0]; templates.clear(); } }
    private record Candidate(byte[] encoded, byte[] payload, int mode, boolean mapped, int dictEpoch, int size) {}
}
