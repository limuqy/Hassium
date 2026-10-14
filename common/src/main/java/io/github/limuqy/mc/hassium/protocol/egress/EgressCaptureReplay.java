package io.github.limuqy.mc.hassium.protocol.egress;

import com.google.gson.*;
import io.github.limuqy.mc.hassium.protocol.HassiumPacketIds;
import io.github.limuqy.mc.hassium.protocol.DictionarySnapshot;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Replays v3 captures with exact batching/dictionaries; encoding policy may evolve without changing v3. */
public final class EgressCaptureReplay {
    private static final class Session implements AutoCloseable {
        final EgressFrameCodec receiver = new EgressFrameCodec(3);
        public void close() { receiver.close(); }
    }
    private record ReplayFrame(String connection, String phase, List<EgressFrameCodec.PacketBytes> packets,
                               byte[] captured, long epoch, EgressFrameCodec.Dictionary dictionary) {}
    private static final class ReplayState implements AutoCloseable {
        final EgressFrameCodec sender, verifier = new EgressFrameCodec(3);
        ReplayState(int mode) { sender = new EgressFrameCodec(3, mode != 0, mode == 2); }
        public void close() { sender.close(); verifier.close(); }
    }
    private static final class Phase {
        long frames, packets, actualWire, exact, partial, definitions;
        long attempts = -1, skips = -1, wins = -1;
        final long[] wire = new long[3], wall = new long[3], cpu = new long[3];
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 4) throw new IllegalArgumentException("Usage: EgressCaptureReplay <native.jsonl> <report.csv> [repeats=1] [verifySelected=true]");
        int repeats = args.length >= 3 ? Integer.parseInt(args[2]) : 1;
        if (repeats < 1 || repeats > 20) throw new IllegalArgumentException("Repeats must be 1..20");
        boolean verifySelected = args.length < 4 || Boolean.parseBoolean(args[3]);
        Map<Integer, byte[]> dictionaries = new HashMap<>(); Map<Integer,String> packetTypes = new HashMap<>();
        Map<String, Session> sessions = new HashMap<>(); Map<String, Phase> phases = new LinkedHashMap<>();
        Map<Integer, EgressFrameCodec.Dictionary> immutableDictionaries = new HashMap<>();
        List<ReplayFrame> frames = new ArrayList<>();
        String phase = "warmup"; int customPayloadId = -1;
        try (var input = Files.newBufferedReader(Path.of(args[0]))) {
            String line;
            while ((line = input.readLine()) != null) {
                JsonObject row = JsonParser.parseString(line).getAsJsonObject();
                String source = row.get("src").getAsString(), type = row.get("type").getAsString();
                if (source.equals("phase_v3")) { phase = type; continue; }
                if (!source.equals("native_v3") && !source.equals("agg_v3")) continue;
                byte[] bytes = Base64.getDecoder().decode(row.get("payload").getAsString());
                if (source.equals("native_v3")) {
                    EgressBytes.Reader r = new EgressBytes.Reader(bytes); int id = r.integer(Integer.MAX_VALUE);
                    packetTypes.put(id, type);
                    if (type.equals(HassiumPacketIds.DICTIONARY_SYNC_S2C)) {
                        customPayloadId = id; r.utf(32767); int bodyLength = r.integer(EgressFrameCodec.MAX_FRAME_BYTES);
                        EgressBytes.Reader body = new EgressBytes.Reader(r.bytes(bodyLength)); r.end(); body.octet();
                        byte[] dictionary = body.bytes(body.integer(256 * 1024)); int epoch = body.integer(Integer.MAX_VALUE);
                        long hash = (long)body.fixedInt() << 32 | body.fixedInt() & 0xffffffffL; body.end();
                        if (dictionary.length > 0) {
                            if (DictionarySnapshot.hash(dictionary) != hash) throw new IllegalStateException("Captured dictionary hash mismatch");
                            dictionaries.put(epoch, dictionary);
                        }
                    }
                    continue;
                }
                String connection = row.get("conn").getAsString();
                Session session = sessions.computeIfAbsent(connection, ignored -> new Session());
                EgressBytes.Reader header = new EgressBytes.Reader(bytes);
                if (header.integer(3) != 3) throw new IllegalStateException("Not a v3 capture");
                long sessionEpoch = header.number();
                List<byte[]> restored = session.receiver.decode(bytes, dictionaries::get);
                List<EgressFrameCodec.PacketBytes> packets = new ArrayList<>();
                for (byte[] packet : restored) {
                    EgressBytes.Reader nativePacket = new EgressBytes.Reader(packet); int id = nativePacket.integer(Integer.MAX_VALUE);
                    String key = id == customPayloadId ? nativePacket.utf(32767) : packetTypes.getOrDefault(id, "native:" + id);
                    packets.add(new EgressFrameCodec.PacketBytes(key, packet));
                }
                Phase totals = phases.computeIfAbsent(phase, ignored -> new Phase()); totals.frames++; totals.packets += packets.size();
                totals.actualWire += wireSize(bytes.length, customPayloadId);
                int epoch = row.get("trainedEpoch").getAsInt();
                var dictionary = epoch == 0 ? null : immutableDictionaries.computeIfAbsent(epoch,
                        id -> new EgressFrameCodec.Dictionary(id, dictionaries.get(id)));
                frames.add(new ReplayFrame(connection, phase, List.copyOf(packets), bytes, sessionEpoch, dictionary));
            }
        } finally { sessions.values().forEach(Session::close); }
        int[] baselineLengths = new int[frames.size()];
        // Windows CPU clocks have coarse resolution. Measure a whole contiguous phase per mode,
        // excluding native restoration and verification, rather than summing tiny interleaved samples.
        // With repeats > 1 discard the first full pass to warm the Java/JNI paths.
        for (int iteration = 0; iteration < repeats; iteration++) for (int mode = 0; mode < 3; mode++) {
            Map<String, ReplayState> states = new HashMap<>();
            try {
                for (int start = 0; start < frames.size();) {
                    int end = start + 1;
                    String name = frames.get(start).phase();
                    while (end < frames.size() && frames.get(end).phase().equals(name)) end++;
                    List<EgressFrameCodec.Encoded> encodedFrames = new ArrayList<>(end - start);
                    // Construct contexts before the timed block.
                    for (int i = start; i < end; i++) {
                        String connection = frames.get(i).connection();
                        if (!states.containsKey(connection)) states.put(connection, new ReplayState(mode));
                    }
                    long[] before = mode == 2 && iteration == repeats - 1 ? templateStats(states) : null;
                    long wall = System.nanoTime(), cpu = EgressCpuClock.now();
                    for (int i = start; i < end; i++) {
                        ReplayFrame frame = frames.get(i);
                        encodedFrames.add(states.get(frame.connection()).sender.encode(frame.packets(), frame.epoch(), frame.dictionary()));
                    }
                    Phase totals = phases.get(name);
                    long elapsedWall = System.nanoTime() - wall, elapsedCpu = EgressCpuClock.now() - cpu;
                    if (repeats == 1 || iteration > 0) { totals.wall[mode] += elapsedWall; totals.cpu[mode] += elapsedCpu; }
                    if (before != null) {
                        long[] after = templateStats(states);
                        if (totals.attempts < 0) totals.attempts = totals.skips = totals.wins = 0;
                        totals.attempts += after[0] - before[0]; totals.skips += after[1] - before[1]; totals.wins += after[2] - before[2];
                    }
                    for (int i = start; i < end; i++) {
                        ReplayFrame frame = frames.get(i);
                        var encoded = encodedFrames.get(i - start);
                        if (iteration == repeats - 1) totals.wire[mode] += wireSize(encoded.bytes().length, customPayloadId);
                        if (mode == 0) baselineLengths[i] = encoded.bytes().length;
                        else if (encoded.bytes().length > baselineLengths[i]) throw new IllegalStateException("Final-size selection exceeded baseline");
                        if (mode == 2 && verifySelected && !Arrays.equals(encoded.bytes(), frame.captured())) throw new IllegalStateException("Replay did not reproduce the actual selected frame");
                        List<byte[]> replayed = states.get(frame.connection()).verifier.decode(encoded.bytes(), dictionaries::get);
                        if (replayed.size() != frame.packets().size()) throw new IllegalStateException("Replay packet count mismatch");
                        for (int p = 0; p < replayed.size(); p++) if (!Arrays.equals(frame.packets().get(p).bytes(), replayed.get(p))) throw new IllegalStateException("Replay packet bytes/order mismatch");
                        if (mode == 2 && iteration == repeats - 1) { totals.exact += encoded.exactReferences(); totals.partial += encoded.templateReferences(); totals.definitions += encoded.definitionBytes(); }
                    }
                    start = end;
                }
            } finally { states.values().forEach(ReplayState::close); }
        }
        try (var out = Files.newBufferedWriter(Path.of(args[1]))) {
            out.write("phase,frames,packets,actualAggregateWire,independentAggregateWire,historyAggregateWire,templateAggregateWire,exactRefs,templateRefs,definitionBytes,independentWallNs,historyWallNs,templateWallNs,independentCpuNs,historyCpuNs,templateCpuNs,templateAttempts,templateSkips,templateWinningFrames\n");
            for (var entry : phases.entrySet()) {
                int measured = Math.max(1, repeats - 1);
                for (int mode = 0; mode < 3; mode++) { entry.getValue().wall[mode] /= measured; entry.getValue().cpu[mode] /= measured; }
                Phase p = entry.getValue(); String row = entry.getKey()+","+p.frames+","+p.packets+","+p.actualWire+","+p.wire[0]+","+p.wire[1]+","+p.wire[2]
                        +","+p.exact+","+p.partial+","+p.definitions+","+p.wall[0]+","+p.wall[1]+","+p.wall[2]+","+p.cpu[0]+","+p.cpu[1]+","+p.cpu[2]+","+p.attempts+","+p.skips+","+p.wins;
                out.write(row + "\n"); System.out.println(row);
            }
        }
    }
    /** Reflection keeps this replay runner usable with archived pre-admission codec classes. */
    private static long[] templateStats(Map<String, ReplayState> states) throws Exception {
        var method = Arrays.stream(EgressFrameCodec.class.getMethods()).filter(m -> m.getName().equals("templateStats")).findFirst();
        if (method.isEmpty()) return null;
        long[] result = new long[3]; String[] names = {"attempts", "skips", "wins"};
        for (ReplayState state : states.values()) {
            Object stats = method.get().invoke(state.sender);
            for (int i = 0; i < 3; i++) result[i] += (long)stats.getClass().getMethod(names[i]).invoke(stats);
        }
        return result;
    }
    private static int wireSize(int frame, int packetId) {
        int packet = varIntSize(packetId) + 1 + HassiumPacketIds.AGGREGATION_S2C.length() + varIntSize(frame) + frame;
        return packet + 1 + varIntSize(packet + 1);
    }
    private static int varIntSize(int n) { int size = 1; while ((n >>>= 7) != 0) size++; return size; }
    private EgressCaptureReplay() {}
}
