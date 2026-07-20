package io.github.limuqy.mc.hassium.metrics;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetricsTextFormatterTest {

    @Test
    void formatBytesUsesBinaryBoundaries() {
        assertEquals("0 B", MetricsTextFormatter.formatBytes(0));
        assertEquals("1023 B", MetricsTextFormatter.formatBytes(1023));
        assertEquals("1.0 KB", MetricsTextFormatter.formatBytes(1024));
        assertEquals("1.0 MB", MetricsTextFormatter.formatBytes(1024L * 1024));
        assertEquals("1.0 GB", MetricsTextFormatter.formatBytes(1024L * 1024 * 1024));
    }

    @Test
    void formatPercentClampsInvalidValues() {
        assertEquals("0.0%", MetricsTextFormatter.formatPercent(Double.NaN));
        assertEquals("0.0%", MetricsTextFormatter.formatPercent(-1.0));
        assertEquals("100.0%", MetricsTextFormatter.formatPercent(101.0));
        assertEquals("12.5%", MetricsTextFormatter.formatPercent(12.5));
    }

    @Test
    void formatCompressionRatioHandlesZeroActualBytes() {
        assertEquals("0.00:1", MetricsTextFormatter.formatCompressionRatio(100, 0));
        assertEquals("4.00:1", MetricsTextFormatter.formatCompressionRatio(400, 100));
    }

    @Test
    void formattingUsesRootLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("1.5 KB", MetricsTextFormatter.formatBytes(1536));
            assertEquals("12.5%", MetricsTextFormatter.formatPercent(12.5));
            assertEquals("1.50:1", MetricsTextFormatter.formatCompressionRatio(3, 2));
        } finally {
            Locale.setDefault(original);
        }
    }
}
