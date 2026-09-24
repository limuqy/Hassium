package io.github.limuqy.mc.hassium.protocol;

import io.github.limuqy.mc.hassium.compat.PacketCodecCompat;
import io.github.limuqy.mc.hassium.compat.PacketId;
import io.github.limuqy.mc.hassium.compat.PacketPayloadCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
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

/**
 * {@code debug.exportAggregatedPackets} 调试导出器（JSONL）。
 * <p>
 * 两个导出点：
 * <ol>
 *   <li>{@code MixinConnection} 的 {@code Connection.send} 拦截点（仅 play 期玩家连接）：
 *       所有发往玩家的 S2C 包。调用点刻意放在握手 / 聚合开关 / 连接激活 / 黑名单
 *       gating 之前，因此单人/LAN 集成服的本机 memory 连接（不握手、不聚合）
 *       同样导出全量包流——这正是「单人生效」的落点。</li>
 *   <li>{@code HassiumAggregationManager.flushBatch}：编码后的完整聚合帧
 *       （{@code hassium:aggregation_s2c}，含子包头与字典 ZSTD 压缩字节）。</li>
 * </ol>
 * 输出 {@code logs/hassium-aggregated-packets/agg-<进程首包时间>.jsonl}，每行一包：
 * {@code {"ts":..,"src":"send|agg","conn":..,"type":..,"subs":..,"size":..,"payload":base64}}。
 * 序列化复用 {@link HassiumAggregationManager#takeOver} 同一链路（custom payload 提取 /
 * 原版包体 StreamCodec 序列化）。每包写盘并 flush，性能开销大，仅诊断用；
 * 任何异常自禁用并 warn 一次，绝不影响发包路径。
 */
public final class AggregatedPacketExporter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/AggExport");

    private static final Object LOCK = new Object();
    private static BufferedWriter writer;
    private static boolean disabled;

    private AggregatedPacketExporter() {
    }

    /**
     * 服务端 send 拦截点导出：所有经过 {@code Connection.send} 的 play 期 S2C 包。
     * <p>
     * 独立于握手/聚合 gating，memory 连接（单人）同样生效；开关关闭时仅一次配置读。
     */
    public static void exportSendPacket(Packet<?> packet, Connection connection) {
        if (!enabled()) {
            return;
        }
        try {
            PacketId type = PacketTypeHelper.getPacketType(packet);
            byte[] payload = null;
            if (type != null) {
                payload = PacketPayloadCompat.isCustomPayloadPacket(packet)
                        ? PacketPayloadCompat.extractPayloadData(packet)
                        : PacketCodecCompat.serializePacketBody(packet,
                                PacketCodecCompat.resolveRegistryAccess(connection));
            }
            // 类型无法识别（1.20.1 bundle / 索引未同步）仍记一行：类名兜底，保证「所有经过」语义
            String typeId = type != null ? type.fullId() : "unknown:" + packet.getClass().getSimpleName();
            writeLine(toJsonLine(System.currentTimeMillis(), "send", safeConn(connection), typeId, null, payload));
        } catch (Exception e) {
            writeLine(toJsonLine(System.currentTimeMillis(), "send", safeConn(connection),
                    "error:" + e.getClass().getSimpleName(), null, null));
        }
    }

    /**
     * 聚合冲刷点导出：编码后的完整聚合帧字节。
     */
    public static void exportAggregationFrame(FriendlyByteBuf encodedFrame, int subCount) {
        if (!enabled()) {
            return;
        }
        byte[] frame = new byte[encodedFrame.readableBytes()];
        encodedFrame.getBytes(encodedFrame.readerIndex(), frame);
        writeLine(toJsonLine(System.currentTimeMillis(), "agg", "server",
                HassiumPacketIds.AGGREGATION_S2C, subCount, frame));
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
