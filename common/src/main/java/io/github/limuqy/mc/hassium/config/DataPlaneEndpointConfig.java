package io.github.limuqy.mc.hassium.config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Data plane 端点配置的无加载器校验与规范化。
 */
public final class DataPlaneEndpointConfig {
    private static final Set<String> WILDCARD_HOSTS = Set.of("0.0.0.0", "::", "[::]");
    private static final int MAX_HOST_UTF8_BYTES = 255;

    private DataPlaneEndpointConfig() {
    }

    public static String validateReachableHost(String host, String fieldName) {
        String normalized = validateHost(host, fieldName);
        if (WILDCARD_HOSTS.contains(normalized)) {
            throw new IllegalArgumentException(fieldName + " must not use a wildcard host");
        }
        return normalized;
    }

    public static String validateBindHost(String host) {
        return validateHost(host, "UDP bind host");
    }

    public static void validatePort(int port, String fieldName) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(fieldName + " port must be in [1, 65535]");
        }
    }

    public static void validateNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " must be non-negative");
        }
    }

    public static void validatePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    public static List<HassiumConfig.ReachableEndpoint> normalizeReachableEndpoints(
            List<HassiumConfig.ReachableEndpoint> endpoints, int maxEntries, String fieldName) {
        Objects.requireNonNull(endpoints, fieldName + " must not be null");
        if (maxEntries < 0) {
            throw new IllegalArgumentException(fieldName + " maxEntries must be non-negative");
        }
        if (endpoints.size() > maxEntries) {
            throw new IllegalArgumentException(fieldName + " exceeds " + maxEntries + " entries");
        }

        Map<String, HassiumConfig.ReachableEndpoint> unique = new LinkedHashMap<>();
        for (HassiumConfig.ReachableEndpoint endpoint : endpoints) {
            Objects.requireNonNull(endpoint, fieldName + " must not contain null");
            HassiumConfig.ReachableEndpoint validated = new HassiumConfig.ReachableEndpoint(
                    endpoint.host(), endpoint.port(), endpoint.priority());
            String key = validated.host() + '\u0000' + validated.port();
            HassiumConfig.ReachableEndpoint existing = unique.get(key);
            if (existing == null || validated.priority() > existing.priority()) {
                unique.put(key, validated);
            }
        }

        List<HassiumConfig.ReachableEndpoint> normalized = new ArrayList<>(unique.values());
        normalized.sort(Comparator.comparingInt(HassiumConfig.ReachableEndpoint::priority).reversed()
                .thenComparing(HassiumConfig.ReachableEndpoint::host)
                .thenComparingInt(HassiumConfig.ReachableEndpoint::port));
        return List.copyOf(normalized);
    }

    public static List<HassiumConfig.UdpListenerConfig> normalizeUdpListeners(
            boolean enabled, List<HassiumConfig.UdpListenerConfig> listeners) {
        Objects.requireNonNull(listeners, "UDP listeners must not be null");
        if (enabled && listeners.isEmpty()) {
            throw new IllegalArgumentException("enabled data plane requires at least one UDP listener");
        }

        Set<String> bindAddresses = new LinkedHashSet<>();
        List<HassiumConfig.UdpListenerConfig> normalized = new ArrayList<>(listeners.size());
        for (HassiumConfig.UdpListenerConfig listener : listeners) {
            Objects.requireNonNull(listener, "UDP listeners must not contain null");
            HassiumConfig.UdpListenerConfig validated = new HassiumConfig.UdpListenerConfig(
                    listener.bindHost(), listener.bindPort(), listener.weight(), listener.reachableEndpoints());
            String bindKey = validated.bindHost() + '\u0000' + validated.bindPort();
            if (!bindAddresses.add(bindKey)) {
                throw new IllegalArgumentException("duplicate UDP listener bind address: "
                        + validated.bindHost() + ':' + validated.bindPort());
            }
            if (enabled && validated.reachableEndpoints().isEmpty()) {
                throw new IllegalArgumentException("enabled UDP listener requires reachable endpoints");
            }
            normalized.add(validated);
        }
        return List.copyOf(normalized);
    }
    public static String encodeReachable(HassiumConfig.ReachableEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "reachable endpoint must not be null");
        return endpoint.host() + ',' + endpoint.port() + ',' + endpoint.priority();
    }

    public static HassiumConfig.ReachableEndpoint decodeReachable(String encoded) {
        String[] parts = split(encoded, 3, "reachable endpoint");
        return new HassiumConfig.ReachableEndpoint(parts[0], parseInt(parts[1], "reachable endpoint port"),
                parseInt(parts[2], "reachable endpoint priority"));
    }

    public static String encodeListener(HassiumConfig.UdpListenerConfig listener) {
        Objects.requireNonNull(listener, "UDP listener must not be null");
        StringBuilder encoded = new StringBuilder(listener.bindHost()).append(',').append(listener.bindPort())
                .append(',').append(listener.weight());
        for (HassiumConfig.ReachableEndpoint endpoint : listener.reachableEndpoints()) {
            encoded.append(';').append(encodeReachable(endpoint));
        }
        return encoded.toString();
    }

    public static HassiumConfig.UdpListenerConfig decodeListener(String encoded) {
        Objects.requireNonNull(encoded, "UDP listener must not be null");
        String[] segments = encoded.split(";", -1);
        String[] bind = split(segments[0], 3, "UDP listener");
        List<HassiumConfig.ReachableEndpoint> reachableEndpoints = new ArrayList<>(segments.length - 1);
        Set<String> reachableAddresses = new LinkedHashSet<>();
        for (int index = 1; index < segments.length; index++) {
            HassiumConfig.ReachableEndpoint endpoint = decodeReachable(segments[index]);
            if (!reachableAddresses.add(endpoint.host() + '\u0000' + endpoint.port())) {
                throw new IllegalArgumentException("duplicate reachable endpoint: " + endpoint.host() + ':' + endpoint.port());
            }
            reachableEndpoints.add(endpoint);
        }
        return new HassiumConfig.UdpListenerConfig(bind[0], parseInt(bind[1], "UDP bind port"),
                parseInt(bind[2], "UDP listener weight"), reachableEndpoints);
    }

    private static String[] split(String encoded, int expectedParts, String fieldName) {
        Objects.requireNonNull(encoded, fieldName + " must not be null");
        String[] parts = encoded.split(",", -1);
        if (parts.length != expectedParts) {
            throw new IllegalArgumentException(fieldName + " must contain exactly " + expectedParts + " comma-separated values");
        }
        return parts;
    }

    private static int parseInt(String value, String fieldName) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + " must be an integer", e);
        }
    }

 
 
    private static String validateHost(String host, String fieldName) {
        Objects.requireNonNull(host, fieldName + " must not be null");
        String normalized = host.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        if (normalized.indexOf(',') >= 0 || normalized.indexOf(';') >= 0) {
            throw new IllegalArgumentException(fieldName + " must not contain ',' or ';'");
        }
        if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_HOST_UTF8_BYTES) {
            throw new IllegalArgumentException(fieldName + " exceeds " + MAX_HOST_UTF8_BYTES + " UTF-8 bytes");
        }
        return normalized;
    }
}
