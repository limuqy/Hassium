package io.github.limuqy.mc.hassium.protocol.replay;

import com.google.gson.*;
import io.github.limuqy.mc.hassium.compat.EntityMotionReplayNativeCompat;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.world.phys.Vec3;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Standalone test-classpath oracle; never loaded by the mod lifecycle. */
public final class EntityMotionReplayNativeCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("<native-requests.jsonl.gz> <native-result.json>");
        Path input = Path.of(args[0]), output = Path.of(args[1]);
        if (Files.exists(output)) throw new IllegalStateException("Use a new output path");
        String compiledVersion = EntityMotionReplayNativeCompat.version();
        Map<String, Long> kinds = new TreeMap<>(), sources = new TreeMap<>();
        long checked = 0, positionChecks = 0;
        JsonObject metadata;
        try (var reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(input)), StandardCharsets.UTF_8))) {
            metadata = JsonParser.parseString(reader.readLine()).getAsJsonObject().getAsJsonObject("metadata");
            if (!compiledVersion.equals(metadata.get("mcVersion").getAsString())) throw new IllegalArgumentException("Wrong Minecraft oracle version");
            String line;
            while ((line = reader.readLine()) != null) {
                checked++;
                JsonObject row = JsonParser.parseString(line).getAsJsonObject();
                String kind = row.get("kind").getAsString();
                JsonObject actual = EntityMotionReplayNativeCompat.decodeAndRoundTrip(kind,
                        Base64.getDecoder().decode(row.get("raw").getAsString()));
                JsonObject expected = row.getAsJsonObject("fields");
                if (!actual.equals(expected)) throw new IllegalStateException("Native field mismatch at request " + checked + ": " + actual + " != " + expected);
                if (row.has("before")) {
                    VecDeltaCodec codec = new VecDeltaCodec();
                    codec.setBase(vector(row.getAsJsonArray("before")));
                    Vec3 target = switch (kind) {
                        case "position", "position_rotation" -> codec.decode(actual.get("dx").getAsLong(), actual.get("dy").getAsLong(), actual.get("dz").getAsLong());
                        case "teleport" -> new Vec3(actual.get("x").getAsDouble(), actual.get("y").getAsDouble(), actual.get("z").getAsDouble());
                        default -> vector(row.getAsJsonArray("before"));
                    };
                    Vec3 expectedTarget = vector(row.getAsJsonArray("after"));
                    if (!same(target, expectedTarget)) throw new IllegalStateException("Native position mismatch at request " + checked + ": " + target + " != " + expectedTarget);
                    codec.setBase(target);
                    if (!same(codec.decode(0, 0, 0), target)) throw new IllegalStateException("Zero delta changed native base");
                    positionChecks++;
                }
                kinds.merge(kind, 1L, Long::sum);
                sources.merge(row.get("source").getAsString(), 1L, Long::sum);
            }
        }
        if (checked < 100 || positionChecks < 100 || kinds.size() != 6) throw new IllegalStateException("Insufficient native codec coverage");
        JsonObject report = new JsonObject();
        report.add("metadata", metadata);
        report.addProperty("compiledMinecraftVersion", compiledVersion);
        report.addProperty("result", "PASS");
        report.addProperty("nativePacketRoundTrips", checked);
        report.addProperty("nativePositionChecks", positionChecks);
        report.addProperty("negativeControlsPassed", baselineFailureControls());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(input)) {
            byte[] block = new byte[65536];
            int length;
            while ((length = stream.read(block)) != -1) digest.update(block, 0, length);
        }
        report.addProperty("nativeRequestsSHA256", HexFormat.of().formatHex(digest.digest()));
        report.add("kinds", new Gson().toJsonTree(kinds));
        report.add("sources", new Gson().toJsonTree(sources));
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
        System.out.println(report);
    }

    private static Vec3 vector(JsonArray values) {
        return new Vec3(values.get(0).getAsDouble(), values.get(1).getAsDouble(), values.get(2).getAsDouble());
    }

    private static boolean same(Vec3 a, Vec3 b) {
        return a.x == b.x && a.y == b.y && a.z == b.z;
    }

    private static int baselineFailureControls() {
        VecDeltaCodec codec = new VecDeltaCodec();
        codec.setBase(new Vec3(1.0, 64.0, 0.0));
        Vec3 expected = codec.decode(2048, 0, 0);
        codec.setBase(new Vec3(1.25, 64.0, 0.0));
        Vec3 wrongCurrent = codec.decode(2048, 0, 0);
        if (expected.x != 1.5 || wrongCurrent.x != 1.75 || same(expected, wrongCurrent))
            throw new IllegalStateException("Current-world-position failure control did not fail");
        codec.setBase(new Vec3(1.0, 64.0, 0.0));
        // A late snapshot after forwarding the relative packet overwrites the valid target.
        if (same(codec.decode(0, 0, 0), expected))
            throw new IllegalStateException("Late-snapshot failure control did not fail");
        return 2;
    }
}
