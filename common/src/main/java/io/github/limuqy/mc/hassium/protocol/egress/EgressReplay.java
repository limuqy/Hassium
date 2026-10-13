package io.github.limuqy.mc.hassium.protocol.egress;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Offline opaque-byte replay. Does not instantiate Minecraft or infer packet fields. */
public final class EgressReplay {
    private static final class Session implements AutoCloseable {
        final EgressFrameCodec[] encoders = {new EgressFrameCodec(3, false, false), new EgressFrameCodec(3, true, false), new EgressFrameCodec(3, true, true)};
        final EgressFrameCodec[] decoders = {new EgressFrameCodec(3),new EgressFrameCodec(3),new EgressFrameCodec(3)};
        public void close() { for (var codec : encoders) codec.close(); for (var codec : decoders) codec.close(); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: EgressReplay <corpus.eg3r> <report.csv>");
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(Path.of(args[0]))));
             var report = Files.newBufferedWriter(Path.of(args[1]))) {
            if (input.readInt() != 0x45473352) throw new IOException("Invalid replay corpus");
            int datasets = input.readInt();
            report.write("dataset,frames,packets,inputBytes,independentWireBytes,historyWireBytes,templateWireBytes,exactRefs,templateRefs,definitionBytes,independentNanos,historyNanos,templateNanos\n");
            for (int dataset = 0; dataset < datasets; dataset++) {
                String name = input.readUTF(); int n = input.readInt();
                Map<Integer, byte[]> dictionaries = new HashMap<>();
                for (int i = 0; i < n; i++) { int id = input.readInt(), length = input.readInt(); dictionaries.put(id, input.readNBytes(length)); }
                int frames = input.readInt(); Map<String, Session> sessions = new HashMap<>();
                long packets = 0, raw = 0, exact = 0, partial = 0, definitions = 0;
                long[] wire = new long[3], nanos = new long[3];
                try {
                    for (int frame = 0; frame < frames; frame++) {
                        String connection = input.readUTF(); int dictionaryId = input.readInt(), count = input.readInt();
                        List<EgressFrameCodec.PacketBytes> batch = new ArrayList<>(count);
                        for (int p = 0; p < count; p++) {
                            String key = input.readUTF(); int size = input.readInt();
                            if (size <= 0 || size > EgressFrameCodec.MAX_FRAME_BYTES) throw new IOException("Packet bound exceeded");
                            byte[] bytes = input.readNBytes(size); if (bytes.length != size) throw new EOFException();
                            batch.add(new EgressFrameCodec.PacketBytes(key, bytes)); raw += size; packets++;
                        }
                        Session session = sessions.computeIfAbsent(connection, ignored -> new Session());
                        var dictionary = dictionaryId == 0 ? null : new EgressFrameCodec.Dictionary(dictionaryId, dictionaries.get(dictionaryId));
                        int baseline = 0;
                        for (int mode = 0; mode < 3; mode++) {
                            long start = System.nanoTime();
                            var encoded = session.encoders[mode].encode(batch, 1, dictionary);
                            nanos[mode] += System.nanoTime() - start;
                            wire[mode] += wireSize(encoded.bytes().length);
                            if (mode == 0) baseline = encoded.bytes().length;
                            else if (encoded.bytes().length > baseline) throw new IllegalStateException("Final-size selection exceeded independent baseline");
                            List<byte[]> restored = session.decoders[mode].decode(encoded.bytes(), dictionaries::get);
                            if (restored.size() != batch.size()) throw new IllegalStateException("Replay packet count mismatch");
                            for (int p = 0; p < batch.size(); p++) if (!Arrays.equals(batch.get(p).bytes(), restored.get(p))) throw new IllegalStateException("Replay packet bytes/order mismatch");
                            if (mode == 2) { exact += encoded.exactReferences(); partial += encoded.templateReferences(); definitions += encoded.definitionBytes(); }
                        }
                    }
                } finally { sessions.values().forEach(Session::close); }
                String row = name + "," + frames + "," + packets + "," + raw + "," + wire[0] + "," + wire[1] + "," + wire[2]
                        + "," + exact + "," + partial + "," + definitions + "," + nanos[0] + "," + nanos[1] + "," + nanos[2];
                report.write(row + "\n"); report.flush(); System.out.println(row);
            }
            if (input.read() != -1) throw new IOException("Trailing replay corpus bytes");
        }
    }
    /** Native custom payload + byte-array envelope + vanilla compression escape + TCP length frame. */
    private static int wireSize(int frame) {
        int packet = 1 + 1 + "hassium:aggregation_s2c".length() + varIntSize(frame) + frame;
        return packet + 1 + varIntSize(packet + 1);
    }
    private static int varIntSize(int n) { int size = 1; while ((n >>>= 7) != 0) size++; return size; }
    private EgressReplay() {}
}
