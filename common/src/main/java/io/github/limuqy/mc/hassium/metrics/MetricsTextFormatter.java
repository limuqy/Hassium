package io.github.limuqy.mc.hassium.metrics;

import java.util.Locale;

/**
 * Stable text formatting helpers for Hassium metrics output.
 */
public final class MetricsTextFormatter {
    private MetricsTextFormatter() {
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static String formatPercent(double percent) {
        double value = Double.isFinite(percent) ? percent : 0.0;
        if (value < 0.0) value = 0.0;
        if (value > 100.0) value = 100.0;
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    public static String formatCompressionRatio(long originalBytes, long actualBytes) {
        if (actualBytes <= 0) {
            return "0.00:1";
        }
        double ratio = (double) originalBytes / actualBytes;
        if (!Double.isFinite(ratio) || ratio < 0.0) {
            ratio = 0.0;
        }
        return String.format(Locale.ROOT, "%.2f:1", ratio);
    }
}
