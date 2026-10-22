package io.github.limuqy.mc.hassium.protocol.egress;

import java.util.SplittableRandom;

/** Deterministic, bounded development workload; parameters must match on all three JVMs. */
public record EgressStressWorkload(int rate, int warmupTicks, int measureTicks, int drainTicks) {
    public EgressStressWorkload {
        if (rate < 4 || rate > 1536 || rate % 4 != 0 || warmupTicks < 20 || warmupTicks > 2400
                || measureTicks < 20 || measureTicks > 3600 || measureTicks % 2 != 0
                || drainTicks < 20 || drainTicks > 400)
            throw new IllegalArgumentException("Invalid egress stress workload");
    }

    public static EgressStressWorkload configured() {
        return new EgressStressWorkload(Integer.getInteger("hassium.egressStress.rate", 1024),
                Integer.getInteger("hassium.egressStress.warmupTicks", 900),
                Integer.getInteger("hassium.egressStress.measureTicks", 1200),
                Integer.getInteger("hassium.egressStress.drainTicks", 100));
    }

    public int expected() { return warmupTicks * (rate / 4) + 2 * measureTicks * rate; }
    public int totalTicks() { return warmupTicks + 2 * measureTicks + drainTicks; }
    public long clientWaitMs() { return totalTicks() * 50L + 60_000L; }
    public int phase(int tick) {
        return tick < warmupTicks ? 0 : tick < warmupTicks + measureTicks ? 1
                : tick < warmupTicks + 2 * measureTicks ? 2 : tick < totalTicks() ? 3 : 4;
    }
    public int beforeFlushCount(int tick) {
        return switch (phase(tick)) { case 0 -> rate / 4; case 1 -> rate; default -> 0; };
    }
    public int afterFlushCount(int tick) {
        return phase(tick) == 2 && (tick - warmupTicks - measureTicks) % 2 == 0 ? rate * 2 : 0;
    }

    // Cache a bounded corpus outside measured intervals: 1 KiB shared + 2 KiB pseudo-random ASCII.
    // Text generation itself must not become the benchmark's main-thread bottleneck.
    private static final class Texts {
        static final String[] VALUES = create();
        private static String[] create() {
            String[] result = new String[1024];
            String prefix = "hassium-state-field-".repeat(52).substring(0, 1024);
            for (int i = 0; i < result.length; i++) {
                var random = new SplittableRandom(0x45a792b1L + i);
                var text = new StringBuilder(prefix);
                for (int j = 0; j < 2048; j++) text.append((char) ('!' + random.nextInt(90)));
                result[i] = text.toString();
            }
            return result;
        }
    }
    public static String text(int sequence) { return Texts.VALUES[(sequence >>> 1) & 1023]; }
}
