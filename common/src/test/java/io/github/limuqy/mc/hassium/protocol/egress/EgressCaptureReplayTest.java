package io.github.limuqy.mc.hassium.protocol.egress;

import com.google.gson.JsonObject;
import io.github.limuqy.mc.hassium.protocol.DictionarySnapshot;
import io.github.limuqy.mc.hassium.protocol.HassiumPacketIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EgressCaptureReplayTest {
    @TempDir Path directory;
    private static JsonObject row(String source, String type, byte[] bytes) {
        JsonObject row = new JsonObject(); row.addProperty("src", source); row.addProperty("type", type); row.addProperty("conn", "test:connection");
        row.addProperty("payload", bytes == null ? null : Base64.getEncoder().encodeToString(bytes)); return row;
    }
    private static EgressBytes.Writer utf(EgressBytes.Writer out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8); return out.integer(bytes.length).bytes(bytes);
    }
    @Test void currentCaptureReplayUsesExactNativePacketsDictionaryAndSelectedFrames() throws Exception {
        replayEnvelope(false);
    }
    @Test void forgeCaptureReplayUsesNativeDiscriminatorAndCarrierEnvelope() throws Exception {
        replayEnvelope(true);
    }
    private void replayEnvelope(boolean forge) throws Exception {
        List<String> lines = new ArrayList<>();
        byte[] raw = utf(new EgressBytes.Writer().integer(24), "fixture:opaque").bytes(new byte[500]).toByteArray();
        byte[] dictionary = raw.clone(); long hash = DictionarySnapshot.hash(dictionary);
        byte[] body = new EgressBytes.Writer().octet(0).integer(dictionary.length).bytes(dictionary).integer(7)
                .fixedInt((int)(hash >>> 32)).fixedInt((int)hash).toByteArray();
        EgressBytes.Writer envelope = utf(new EgressBytes.Writer().integer(24), forge ? "hassium:main" : HassiumPacketIds.DICTIONARY_SYNC_S2C);
        if (forge) envelope.integer(130); // Two-byte loader message discriminator.
        byte[] nativeDictionary = envelope.integer(body.length).bytes(body).toByteArray();
        JsonObject dictionaryRow = row("native_v3", HassiumPacketIds.DICTIONARY_SYNC_S2C, nativeDictionary);
        if (forge) dictionaryRow.addProperty("envelopePrefixBytes", 2);
        lines.add(dictionaryRow.toString());
        lines.add(row("phase_v3", "mods", null).toString());
        try (var sender = new EgressFrameCodec(3)) {
            for (int i = 0; i < 3; i++) {
                byte[] bytes = raw.clone(); bytes[bytes.length-1] = (byte)i;
                lines.add(row("native_v3", "fixture:opaque", bytes).toString());
                var encoded = sender.encode(List.of(new EgressFrameCodec.PacketBytes("fixture:opaque", bytes)), 1,
                        new EgressFrameCodec.Dictionary(7, dictionary));
                JsonObject frame = row("agg_v3", HassiumPacketIds.AGGREGATION_S2C, encoded.bytes());
                if (forge) { frame.addProperty("envelopeFixedBytes", 15); frame.addProperty("compressionPrefixBytes", 0); }
                frame.addProperty("trainedEpoch", encoded.availableDictionaryEpoch()); lines.add(frame.toString());
            }
        }
        Path capture = directory.resolve("current.jsonl"), report = directory.resolve("report.csv");
        Files.write(capture, lines);
        EgressCaptureReplay.main(new String[]{capture.toString(), report.toString()});
        List<String> result = Files.readAllLines(report); assertEquals(2, result.size());
        String[] totals = result.get(1).split(","); assertEquals("mods", totals[0]); assertEquals("3", totals[1]);
        assertEquals(totals[3], totals[6]);
    }
}
