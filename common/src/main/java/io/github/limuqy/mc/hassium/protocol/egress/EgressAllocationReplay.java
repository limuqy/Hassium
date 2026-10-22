package io.github.limuqy.mc.hassium.protocol.egress;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;

/** Deterministic, offline before/after comparison. Native codecs, input generation and verification are outside timing. */
public final class EgressAllocationReplay {
    private static volatile long consumed;
    private record Raw(String key, byte[] bytes) {}
    private record Frame(List<Raw> packets, long epoch) {}
    private record Totals(long prepareNs, long codecNs, long sampleNs, long allocated, long wire, String sha256) {}
    public static void main(String[] args) throws Throwable {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("Usage: EgressAllocationReplay <report.csv> [baseline.csv]");
        Map<String, String[]> baseline = new HashMap<>();
        if (args.length == 2) for (String line : Files.readAllLines(Path.of(args[1]))) {
            String[] columns = line.split(","); baseline.put(columns[0], columns);
        }
        MethodHandle factory, observe = null;
        boolean ownership;
        var lookup = MethodHandles.lookup();
        try {
            factory = lookup.findStatic(EgressFrameCodec.PacketBytes.class, "owned", MethodType.methodType(EgressFrameCodec.PacketBytes.class, String.class, byte[].class));
            ownership = true;
        } catch (NoSuchMethodException e) {
            factory = lookup.findConstructor(EgressFrameCodec.PacketBytes.class, MethodType.methodType(void.class, String.class, byte[].class));
            ownership = false;
        }
        try { observe = lookup.findVirtual(EgressFrameCodec.class, "encode", MethodType.methodType(EgressFrameCodec.Encoded.class,
                List.class, long.class, EgressFrameCodec.Dictionary.class, Consumer.class)); }
        catch (NoSuchMethodException ignored) {}
        List<String> rows = new ArrayList<>();
        rows.add("workload,frames,prepareNs,codecNs,sampleNs,javaAllocatedBytes,wireBytes,frameSha256,ownershipTransfer");
        for (String workload : List.of("tiny", "mod", "random", "templates")) {
            List<Frame> frames = corpus(workload);
            run(frames, factory, observe); // Discard warmup, including native-context initialization/JIT.
            long preparation = 0, codec = 0, sample = 0, allocated = 0, wire = 0; String hash = null;
            for (int repeat = 0; repeat < 4; repeat++) {
                Totals result = run(frames, factory, observe);
                if (hash != null && !hash.equals(result.sha256)) throw new IllegalStateException("Non-deterministic selected frames");
                hash = result.sha256; preparation += result.prepareNs; codec += result.codecNs; sample += result.sampleNs;
                allocated = result.allocated < 0 || allocated < 0 ? -1 : allocated + result.allocated; wire = result.wire;
            }
            String row = workload + "," + frames.size() + "," + preparation / 4 + "," + codec / 4 + "," + sample / 4 + ","
                    + (allocated < 0 ? -1 : allocated / 4) + "," + wire + "," + hash + "," + ownership;
            if (args.length == 2) {
                String[] expected = baseline.get(workload);
                if (expected == null || !expected[6].equals(Long.toString(wire)) || !expected[7].equals(hash))
                    throw new IllegalStateException("Selected frames differ from baseline: " + workload);
            }
            rows.add(row); System.out.println(row);
        }
        Path output = Path.of(args[0]); if (output.getParent() != null) Files.createDirectories(output.getParent()); Files.write(output, rows);
    }
    private static Totals run(List<Frame> frames, MethodHandle factory, MethodHandle observe) throws Throwable {
        long preparation = 0, codecNs = 0, sample = 0, allocated = 0, wire = 0;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] dictionaryBytes = new byte[65536]; new Random(99).nextBytes(dictionaryBytes);
        var dictionary = new EgressFrameCodec.Dictionary(128, dictionaryBytes);
        byte[][] logical = new byte[1][]; Consumer<byte[]> observer = bytes -> logical[0] = bytes;
        try (var codec = new EgressFrameCodec(3); var decoder = new EgressFrameCodec(3)) {
            for (int f = 0; f < frames.size(); f++) {
                Frame frame = frames.get(f);
                long allocationStart = EgressProfiler.currentThreadAllocatedBytes(), start = System.nanoTime();
                List<EgressFrameCodec.PacketBytes> packets = new ArrayList<>(frame.packets.size());
                for (Raw packet : frame.packets) {
                    byte[] copiedFromNativeBuffer = packet.bytes.clone();
                    packets.add((EgressFrameCodec.PacketBytes) factory.invokeExact(packet.key, copiedFromNativeBuffer));
                }
                preparation += System.nanoTime() - start;
                var dict = f % 3 == 0 ? dictionary : null;
                start = System.nanoTime();
                EgressFrameCodec.Encoded encoded = observe == null ? codec.encode(packets, frame.epoch, dict)
                        : (EgressFrameCodec.Encoded) observe.invokeExact(codec, (List)packets, frame.epoch, dict, (Consumer)observer);
                codecNs += System.nanoTime() - start; start = System.nanoTime();
                if (observe == null) {
                    var raw = new EgressBytes.Writer(); raw.integer(packets.size());
                    for (var packet : packets) { byte[] bytes = packet.bytes(); raw.integer(bytes.length).bytes(bytes); }
                    logical[0] = raw.toByteArray();
                }
                // Emulate the normal non-sampling path: consume but do not retain the raw batch.
                consumed ^= logical[0].length; logical[0] = null;
                sample += System.nanoTime() - start;
                long allocationEnd = EgressProfiler.currentThreadAllocatedBytes();
                allocated = allocationStart < 0 || allocationEnd < 0 || allocated < 0 ? -1 : allocated + allocationEnd - allocationStart;
                wire += encoded.bytes().length;
                digest.update(new byte[]{(byte)(encoded.bytes().length >>> 24), (byte)(encoded.bytes().length >>> 16), (byte)(encoded.bytes().length >>> 8), (byte)encoded.bytes().length});
                digest.update(encoded.bytes());
                List<byte[]> restored = decoder.decode(encoded.bytes(), id -> id == 128 ? dictionaryBytes : null);
                if (restored.size() != frame.packets.size()) throw new IllegalStateException("Packet count mismatch");
                for (int p = 0; p < restored.size(); p++) if (!Arrays.equals(restored.get(p), frame.packets.get(p).bytes)) throw new IllegalStateException("Packet bytes/order mismatch");
            }
        }
        return new Totals(preparation, codecNs, sample, allocated, wire, HexFormat.of().formatHex(digest.digest()));
    }
    private static List<Frame> corpus(String workload) {
        Random random = new Random(20261022); List<Frame> frames = new ArrayList<>();
        int count = switch (workload) { case "tiny" -> 128; case "mod" -> 64; case "templates" -> 256; default -> 8; };
        int size = switch (workload) { case "tiny" -> 9; case "mod" -> 1024; case "templates" -> 512; default -> 8192; };
        byte[][] bases = new byte[8][size]; for (byte[] base : bases) random.nextBytes(base);
        for (int f = 0; f < 180; f++) {
            List<Raw> packets = new ArrayList<>();
            for (int p = 0; p < count; p++) {
                byte[] bytes = bases[p % 8].clone();
                if (workload.equals("random")) random.nextBytes(bytes);
                else { bytes[0] = (byte)p; bytes[size / 2] = (byte)f; }
                packets.add(new Raw("fixture:type_" + p % 8, bytes));
            }
            frames.add(new Frame(List.copyOf(packets), f < 129 ? 1 : 128));
        }
        return frames;
    }
    private EgressAllocationReplay() {}
}
