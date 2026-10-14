package io.github.limuqy.mc.hassium.protocol;

import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import net.minecraft.network.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/** Debug JSONL export of complete native encoder bytes and separately labelled v3 carriers.
 * Original packets are encoded once. Count native_v3 for input and agg_v3 only for transport;
 * never add both when estimating egress. Disabled unless debug.exportAggregatedPackets is set.
 */
public final class AggregatedPacketExporter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/AggExport");

    private static final Object LOCK = new Object();
    private static BufferedWriter writer;
    private static boolean disabled;

    private AggregatedPacketExporter() {
    }

    /** Complete native bytes from the real encoder. Never invoke a third-party codec for diagnostics. */
    public static void exportNativeBytes(Connection connection, String type, io.netty.buffer.ByteBuf encoded) {
        if (!enabled()) return;
        byte[] bytes = new byte[encoded.readableBytes()]; encoded.getBytes(encoded.readerIndex(), bytes);
        String line = toJsonLine(System.currentTimeMillis(), "native_v3", safeConn(connection), type, null, bytes);
        int prefix = io.github.limuqy.mc.hassium.platform.Services.NETWORK_MANAGER.egressEnvelopePrefixBytes(type);
        writeLine(prefix == 0 ? line : line.substring(0, line.length() - 1) + ",\"envelopePrefixBytes\":" + prefix + "}");
    }

    public static void exportV3Frame(Connection connection, byte[] frame, int packets, int trainedEpoch) { exportV3Frame(connection, frame, packets, trainedEpoch, -1, 1); }
    public static void exportV3Frame(Connection connection, byte[] frame, int packets, int trainedEpoch, int envelopeFixedBytes, int compressionPrefixBytes) {
        if (!enabled()) return;
        String line = toJsonLine(System.currentTimeMillis(), "agg_v3", safeConn(connection), HassiumPacketIds.AGGREGATION_S2C, packets, frame);
        writeLine(line.substring(0, line.length() - 1) + ",\"trainedEpoch\":" + trainedEpoch + ",\"envelopeFixedBytes\":" + envelopeFixedBytes + ",\"compressionPrefixBytes\":" + compressionPrefixBytes + "}");
    }
    public static void exportPhase(String phase) {
        if (enabled()) writeLine(toJsonLine(System.currentTimeMillis(), "phase_v3", "server", phase, null, null));
    }

    private static boolean enabled() {
        try {
            HassiumConfigService service = HassiumConfigService.getInstance();
            return service.isConfigLoaded() && service.isExportAggregatedPacketsEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private static String safeConn(Connection connection) {
        try {
            return String.valueOf(connection.getRemoteAddress());
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static void writeLine(String line) {
        synchronized (LOCK) {
            if (disabled) {
                return;
            }
            try {
                if (writer == null) {
                    Path file = newExportFile();
                    Files.createDirectories(file.getParent());
                    writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    LOGGER.info("Hassium: 导出聚合包调试流 → {}", file.toAbsolutePath());
                }
                writer.write(line);
                writer.write('\n');
                writer.flush();
            } catch (Exception e) {
                disabled = true;
                LOGGER.warn("Hassium: 聚合包导出写盘失败，本次运行内自动停用", e);
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (IOException ignored) {
                    }
                    writer = null;
                }
            }
        }
    }

    private static Path newExportFile() {
        String name = "agg-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + ".jsonl";
        return Paths.get("logs", "hassium-aggregated-packets", name);
    }

    /** JSONL 行编码（纯函数测试缝）。payload 为 null 时记 size=0/payload=null（如序列化失败）。 */
    static String toJsonLine(long ts, String src, String conn, String type, Integer subs, byte[] payload) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"ts\":").append(ts)
                .append(",\"src\":\"").append(jsonEscape(src)).append('"')
                .append(",\"conn\":\"").append(jsonEscape(conn)).append('"')
                .append(",\"type\":\"").append(jsonEscape(type)).append('"');
        if (subs != null) {
            sb.append(",\"subs\":").append(subs.intValue());
        }
        if (payload == null) {
            sb.append(",\"size\":0,\"payload\":null");
        } else {
            sb.append(",\"size\":").append(payload.length)
                    .append(",\"payload\":\"").append(Base64.getEncoder().encodeToString(payload)).append('"');
        }
        return sb.append('}').toString();
    }

    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String escaped = switch (c) {
                case '"' -> "\\\"";
                case '\\' -> "\\\\";
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                default -> c < 0x20 ? String.format("\\u%04x", (int) c) : null;
            };
            if (escaped != null) {
                if (sb == null) {
                    sb = new StringBuilder(s.length() + 8);
                    sb.append(s, 0, i);
                }
                sb.append(escaped);
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb != null ? sb.toString() : s;
    }
}
