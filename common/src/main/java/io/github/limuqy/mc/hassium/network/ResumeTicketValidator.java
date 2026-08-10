package io.github.limuqy.mc.hassium.network;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 续流票据验证器（服务端 B 侧）：共享密钥验签 + epoch 递增防重放 + 签发时间窗口。
 * <p>
 * 每玩家只接受严格递增的 epoch：旧连接票据（epoch ≤ 已接受值）一律拒绝，
 * 同票重放（epoch 相等）同样拒绝。新格式票据（含签发时间戳）另受时间窗口约束
 * （默认 5min，配置键 {@code master.resumeTicketTtlMs}）；旧格式票据（issuedAt=0）无期限。
 * 纯 Java 可单测。
 * <p>
 * 防重放状态持久化：{@link #configureStateFile} 指定 {@code hassium-state.json} 后，
 * 启动时 {@link #load()} 加载，停机 {@link #save()} / 定期 {@link #persistIfDue()}（60s）落盘，
 * 原子写（临时文件 + rename）；{@link #clear}/{@link #clearAll} 清表后同步落盘。
 * <p>
 * 注意：完整部署（T8）时，新会话自身 {@code DataPlaneUdpServer.beginControlConnection}
 * 的 per-player epoch 也应并入本表；当前骨架仅记录票据 epoch。
 */
public final class ResumeTicketValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ResumeTicketValidator");

    /** 票据有效期默认值（ms；5min，配置键 master.resumeTicketTtlMs 范围 1000~Long.MAX） */
    public static final long DEFAULT_TTL_MS = 300_000L;

    /** 定期落盘间隔（ms） */
    private static final long PERSIST_INTERVAL_MS = 60_000L;

    private static final Map<UUID, Long> LAST_ACCEPTED_EPOCH = new ConcurrentHashMap<>();

    /** 票据有效期（ms；旧格式票据 issuedAt=0 不受时间窗口限制）；启动时由配置覆盖。 */
    private static volatile long ttlMs = DEFAULT_TTL_MS;

    /** 时钟源（测试注入；null 恢复系统时钟） */
    private static volatile LongSupplier clockMs = System::currentTimeMillis;

    /** epoch 表落盘路径（config 目录 hassium-state.json）；null = 持久化禁用（纯内存，测试默认）。 */
    private static volatile Path stateFile;

    /** 上次落盘时刻（ms；0=尚未落盘） */
    private static volatile long lastPersistMs;

    private static final Object persistLock = new Object();

    /** 验票结果 */
    public record Verification(boolean accepted, long epoch) {
        public static final Verification REJECTED = new Verification(false, Long.MIN_VALUE);

        public static Verification accepted(long epoch) {
            return new Verification(true, epoch);
        }
    }

    private ResumeTicketValidator() {}

    /** 配置票据有效期（ms）；非正数回退默认（300000）。 */
    public static void configureTtlMs(long ttl) {
        ttlMs = ttl > 0 ? ttl : DEFAULT_TTL_MS;
    }

    /** 测试缝：注入时钟源（null 恢复系统时钟）。 */
    public static void configureClockMs(LongSupplier clock) {
        clockMs = clock != null ? clock : System::currentTimeMillis;
    }

    /** 配置 epoch 表落盘路径（config 目录 hassium-state.json）；null = 禁用持久化。 */
    public static void configureStateFile(Path file) {
        synchronized (persistLock) {
            stateFile = file;
        }
    }

    /**
     * 平台侧统一入口：解码票据 → 校验玩家 UUID 一致 → 验签 → epoch 防重放 + 时间窗口。
     * 任一环节失败返回 {@link Verification#REJECTED}。
     */
    public static Verification verifyRequest(UUID playerId, byte[] ticketBytes) {
        if (playerId == null || ticketBytes == null) {
            return Verification.REJECTED;
        }
        final ResumeTicket ticket;
        try {
            ticket = ResumeTicket.decode(ticketBytes);
        } catch (IllegalArgumentException e) {
            return Verification.REJECTED;
        }
        if (!playerId.equals(ticket.playerId())) {
            return Verification.REJECTED;
        }
        return verifyAndAccept(ticket) ? Verification.accepted(ticket.epoch()) : Verification.REJECTED;
    }

    /**
     * 验签 + 时间窗口 + 防重放；通过 → true 并记录 epoch。
     * epoch ≤ 该玩家已接受值（旧连接重放）→ false；
     * 新格式票据签发时间超过有效期（默认 5min）→ false（旧格式 0=无期限不受限）。
     */
    public static boolean verifyAndAccept(ResumeTicket ticket) {
        if (ticket == null || !ticket.verify()) {
            return false;
        }
        // 时间窗口：签发时间戳过期 → 拒绝（旧格式票据 issuedAtMs=0 无期限，向后兼容）
        if (ticket.issuedAtMs() > 0 && clockMs.getAsLong() - ticket.issuedAtMs() > ttlMs) {
            return false;
        }
        UUID id = ticket.playerId();
        long epoch = ticket.epoch();
        while (true) {
            Long prev = LAST_ACCEPTED_EPOCH.get(id);
            if (prev != null && epoch <= prev) {
                return false; // 旧 epoch / 同票重放
            }
            if (prev == null) {
                if (LAST_ACCEPTED_EPOCH.putIfAbsent(id, epoch) == null) {
                    return true;
                }
            } else if (LAST_ACCEPTED_EPOCH.replace(id, prev, epoch)) {
                return true;
            }
        }
    }

    public static long lastAcceptedEpoch(UUID playerId) {
        return LAST_ACCEPTED_EPOCH.getOrDefault(playerId, Long.MIN_VALUE);
    }

    /** 清单个玩家防重放记录并落盘（管理/测试入口；生产续流语义不清理）。 */
    public static void clear(UUID playerId) {
        LAST_ACCEPTED_EPOCH.remove(playerId);
        save();
    }

    /** 清空整表并落盘。 */
    public static void clearAll() {
        LAST_ACCEPTED_EPOCH.clear();
        save();
    }

    public static int size() {
        return LAST_ACCEPTED_EPOCH.size();
    }

    // ==================== 防重放状态持久化（hassium-state.json） ====================

    /**
     * 启动加载：从 {@code stateFile} 合并 (playerId → lastAcceptedEpoch)。
     * 只提升不覆盖（Math::max）——多次调用/热加载时不得回退内存中的更新值。
     */
    public static void load() {
        final Path file = stateFile;
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Map<UUID, Long> loaded = parseStateJson(content);
            if (!loaded.isEmpty()) {
                loaded.forEach((id, epoch) -> LAST_ACCEPTED_EPOCH.merge(id, epoch, Math::max));
                LOGGER.info("Hassium: ResumeTicketValidator loaded {} epoch entries from {}", loaded.size(), file);
            }
        } catch (Exception e) {
            LOGGER.warn("Hassium: ResumeTicketValidator state load failed from {}: {}", file, e.toString());
        }
    }

    /** 停机/手动落盘：内存表快照原子写（临时文件 + rename）。 */
    public static void save() {
        final Path file = stateFile;
        if (file == null) {
            return;
        }
        synchronized (persistLock) {
            try {
                atomicWrite(file, toStateJson(new HashMap<>(LAST_ACCEPTED_EPOCH)));
                lastPersistMs = clockMs.getAsLong();
            } catch (Exception e) {
                LOGGER.warn("Hassium: ResumeTicketValidator state save failed to {}: {}", file, e.toString());
            }
        }
    }

    /**
     * 定期落盘：距上次落盘 ≥ 60s 且表非空时保存（服务端每 tick 调用，内部限频；
     * 停机窗口内重放由 5min 票据时间窗口兜底）。
     */
    public static void persistIfDue() {
        if (stateFile == null || LAST_ACCEPTED_EPOCH.isEmpty()) {
            return;
        }
        if (clockMs.getAsLong() - lastPersistMs >= PERSIST_INTERVAL_MS) {
            save();
        }
    }

    /** 序列化：{"version":1,"players":{"<uuid>":<epoch>,...}} */
    private static String toStateJson(Map<UUID, Long> table) {
        StringBuilder sb = new StringBuilder(32 + table.size() * 56);
        sb.append("{\"version\":1,\"players\":{");
        boolean first = true;
        for (Map.Entry<UUID, Long> entry : table.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
        }
        sb.append("}}");
        return sb.toString();
    }

    /** 解析本类写出的状态文件；损坏/未知字段容错（跳过，不抛）。 */
    private static Map<UUID, Long> parseStateJson(String content) {
        Map<UUID, Long> map = new HashMap<>();
        if (content == null) {
            return map;
        }
        int players = content.indexOf("\"players\":");
        if (players < 0) {
            return map;
        }
        int start = content.indexOf('{', players);
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return map;
        }
        String body = content.substring(start + 1, end);
        for (String pair : body.split(",")) {
            pair = pair.trim();
            if (pair.isEmpty()) {
                continue;
            }
            int colon = pair.indexOf(':');
            if (colon <= 2 || colon >= pair.length() - 1) {
                continue;
            }
            String uuidStr = pair.substring(1, colon - 1).trim();
            String epochStr = pair.substring(colon + 1).trim();
            try {
                map.put(UUID.fromString(uuidStr), Long.parseLong(epochStr));
            } catch (IllegalArgumentException ignored) {
                // 单条损坏不阻断其余条目
            }
        }
        return map;
    }

    /** 原子写：临时文件 + rename（ATOMIC_MOVE，不支持时回退 REPLACE_EXISTING）。 */
    private static void atomicWrite(Path file, String content) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
