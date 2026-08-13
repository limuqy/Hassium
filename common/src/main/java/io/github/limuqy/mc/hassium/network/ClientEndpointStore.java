package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.config.HassiumConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * 网关端点持久化存储（REQ M2；CONTRACTS §4 format=2）。
 *
 * <p>文件 {@code config/hassium/failover-endpoints.properties}（相对 gameDir），头 {@code format=2}；
 * 键 {@code server.<base64(逻辑主地址 ServerData.ip)>}；值
 * {@code ts=<epochMillis>;endpoints=<host:port:priority;...>}（端点三元组编码语义同
 * {@code DataPlaneEndpointConfig.encodeReachable}，分隔符按契约用冒号）。
 *
 * <p>写入：握手成功通告 controlEndpoints 非空 → {@link #record}。容量 32 LRU（超限删最旧 ts）；
 * 原子写（tmp + ATOMIC_MOVE，同退役 {@code ClientFailoverEndpointStore} 语义）。读取容错：
 * 坏行/坏条目忽略；旧 format=1 条目（{@code server.<b64>..count=N} 样式，键含第二个 '.'）
 * 读取忽略不迁移，首次写入清除。token 不落盘（安全）。
 *
 * <p>线程安全：文件为唯一事实源，{@code record}/{@code lookup} 均 synchronized。
 */
public final class ClientEndpointStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/EndpointStore");
    private static final String FORMAT = "2";
    private static final String KEY_PREFIX = "server.";
    private static final String VALUE_TS_PREFIX = "ts=";
    private static final String VALUE_ENDPOINTS_SEP = ";endpoints=";
    /** 容量上限（LRU；超限删最旧 ts）。 */
    static final int MAX_ENTRIES = 32;

    private final Path path;
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder decoder = Base64.getUrlDecoder();

    public ClientEndpointStore(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    /** 单个主地址的存储条目（ts = 最近一次记录时刻，epochMillis）。 */
    public record Entry(long ts, List<HassiumConfig.ReachableEndpoint> endpoints) {
        public Entry {
            endpoints = List.copyOf(endpoints);
        }
    }

    /** 记录（或刷新）主地址的端点池；ts=now；容量超限删除最旧 ts 条目。 */
    public synchronized void record(String mainAddress, List<HassiumConfig.ReachableEndpoint> endpoints) {
        record(mainAddress, endpoints, System.currentTimeMillis());
    }

    /** 显式 ts 版本（测试确定性；同包可见）。 */
    synchronized void record(String mainAddress, List<HassiumConfig.ReachableEndpoint> endpoints, long ts) {
        Objects.requireNonNull(mainAddress, "mainAddress");
        Objects.requireNonNull(endpoints, "endpoints");
        Properties properties = readProperties();
        // 旧 format=1 残留（server.<b64>..count 样式，键含第二个 '.'）→ 忽略不迁移，首次写入清除
        LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(KEY_PREFIX)) {
                continue;
            }
            String encoded = key.substring(KEY_PREFIX.length());
            if (encoded.isEmpty() || encoded.indexOf('.') >= 0) {
                continue; // 旧 format=1 键或异常键
            }
            String address = decodeMainAddress(encoded);
            if (address == null) {
                continue;
            }
            decodeEntry(properties.getProperty(key)).ifPresent(entry -> entries.put(address, entry));
        }
        entries.put(mainAddress, new Entry(ts, endpoints));
        while (entries.size() > MAX_ENTRIES) {
            Map.Entry<String, Entry> oldest = null;
            for (Map.Entry<String, Entry> candidate : entries.entrySet()) {
                if (oldest == null || candidate.getValue().ts() < oldest.getValue().ts()) {
                    oldest = candidate;
                }
            }
            if (oldest == null) {
                break;
            }
            entries.remove(oldest.getKey());
        }
        Properties fresh = new Properties();
        fresh.setProperty("format", FORMAT);
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            fresh.setProperty(key(entry.getKey()), encodeValue(entry.getValue()));
        }
        writeProperties(fresh);
    }

    /** 读取主地址的端点条目；文件缺失/非 format=2/无此键/条目坏 → empty。 */
    public synchronized Optional<Entry> lookup(String mainAddress) {
        Objects.requireNonNull(mainAddress, "mainAddress");
        Properties properties = readProperties();
        if (!FORMAT.equals(properties.getProperty("format"))) {
            return Optional.empty();
        }
        return decodeEntry(properties.getProperty(key(mainAddress)));
    }

    private Properties readProperties() {
        Properties properties = new Properties();
        if (!Files.exists(path)) {
            return properties;
        }
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.warn("Hassium: Ignoring unreadable failover endpoint store {}", path, e);
            return new Properties();
        }
        return properties;
    }

    private void writeProperties(Properties properties) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "Hassium failover endpoints");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("Hassium: Failed to persist failover endpoint store {}", path, e);
        }
    }

    /** 解析 format=2 值 {@code ts=<long>;endpoints=<host:port:priority;...>}；坏条目 → empty（坏行忽略）。 */
    static Optional<Entry> decodeEntry(String value) {
        if (value == null || !value.startsWith(VALUE_TS_PREFIX)) {
            return Optional.empty();
        }
        int endpointsAt = value.indexOf(VALUE_ENDPOINTS_SEP);
        if (endpointsAt < 0) {
            return Optional.empty();
        }
        long ts;
        try {
            ts = Long.parseLong(value.substring(VALUE_TS_PREFIX.length(), endpointsAt));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (ts < 0) {
            return Optional.empty();
        }
        String endpointsText = value.substring(endpointsAt + VALUE_ENDPOINTS_SEP.length());
        List<HassiumConfig.ReachableEndpoint> endpoints = new ArrayList<>();
        if (!endpointsText.isEmpty()) {
            for (String segment : endpointsText.split(";", -1)) {
                HassiumConfig.ReachableEndpoint endpoint = decodeEndpoint(segment);
                if (endpoint == null) {
                    return Optional.empty(); // 任一段坏 → 整条目忽略
                }
                endpoints.add(endpoint);
            }
        }
        return Optional.of(new Entry(ts, endpoints));
    }

    /**
     * 解析 {@code host:port:priority}。host 含 ':'（IPv6 字面量）时按最后两段为 port/priority，
     * 前段回拼 host。无效段 → null。
     */
    static HassiumConfig.ReachableEndpoint decodeEndpoint(String segment) {
        if (segment.isEmpty()) {
            return null;
        }
        String[] parts = segment.split(":", -1);
        if (parts.length < 3) {
            return null;
        }
        String host = String.join(":", Arrays.copyOf(parts, parts.length - 2));
        if (host.isEmpty()) {
            return null;
        }
        try {
            return new HassiumConfig.ReachableEndpoint(host,
                    Integer.parseInt(parts[parts.length - 2]),
                    Integer.parseInt(parts[parts.length - 1]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String encodeValue(Entry entry) {
        StringBuilder value = new StringBuilder();
        value.append(VALUE_TS_PREFIX).append(entry.ts()).append(VALUE_ENDPOINTS_SEP);
        for (int i = 0; i < entry.endpoints().size(); i++) {
            if (i > 0) {
                value.append(';');
            }
            HassiumConfig.ReachableEndpoint endpoint = entry.endpoints().get(i);
            value.append(endpoint.host()).append(':').append(endpoint.port()).append(':').append(endpoint.priority());
        }
        return value.toString();
    }

    private String key(String mainAddress) {
        return KEY_PREFIX + encoder.encodeToString(mainAddress.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeMainAddress(String encoded) {
        try {
            return new String(decoder.decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
