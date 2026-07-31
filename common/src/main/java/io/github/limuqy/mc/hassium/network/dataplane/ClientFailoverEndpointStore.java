package io.github.limuqy.mc.hassium.network.dataplane;

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
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/** Persistent primary-address to control-endpoint association. */
public final class ClientFailoverEndpointStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/FailoverEndpointStore");
    private static final String FORMAT = "1";
    private static final String KEY_PREFIX = "server.";
    private static final String COUNT = ".count";
    private static final String HOST = ".host";
    private static final String PORT = ".port";
    private static final String PRIORITY = ".priority";

    private final Path path;
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder decoder = Base64.getUrlDecoder();

    public ClientFailoverEndpointStore(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    public synchronized List<ControlEndpoint> load(String primaryAddress) {
        Objects.requireNonNull(primaryAddress, "primaryAddress");
        Properties properties = readProperties();
        if (!FORMAT.equals(properties.getProperty("format"))) {
            return List.of();
        }
        String prefix = keyPrefix(primaryAddress);
        String countText = properties.getProperty(prefix + COUNT);
        if (countText == null) {
            return List.of();
        }
        try {
            int count = Integer.parseInt(countText);
            if (count < 0 || count > ControlEndpointManager.MAX_CANDIDATES) {
                throw new IllegalArgumentException("endpoint count out of range: " + count);
            }
            List<ControlEndpoint> endpoints = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String host = properties.getProperty(prefix + i + HOST);
                int port = Integer.parseInt(properties.getProperty(prefix + i + PORT));
                int priority = Integer.parseInt(properties.getProperty(prefix + i + PRIORITY));
                endpoints.add(new ControlEndpoint(host, port, priority));
            }
            return sortedUnique(endpoints);
        } catch (RuntimeException e) {
            LOGGER.warn("Hassium: Ignoring invalid failover endpoint record for {}", primaryAddress, e);
            return List.of();
        }
    }

    public synchronized List<ControlEndpoint> merge(String primaryAddress,
                                                      List<ControlEndpoint> advertised) {
        Objects.requireNonNull(primaryAddress, "primaryAddress");
        Objects.requireNonNull(advertised, "advertised");
        LinkedHashMap<String, ControlEndpoint> merged = new LinkedHashMap<>();
        for (ControlEndpoint endpoint : load(primaryAddress)) {
            merged.put(endpoint.coordinateKey(), endpoint);
        }
        for (ControlEndpoint endpoint : advertised) {
            if (endpoint != null) {
                merged.put(endpoint.coordinateKey(), endpoint);
            }
        }
        List<ControlEndpoint> result = sortedUnique(new ArrayList<>(merged.values()));
        writeRecord(primaryAddress, result);
        return result;
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

    private void writeRecord(String primaryAddress, List<ControlEndpoint> endpoints) {
        Properties properties = readProperties();
        properties.setProperty("format", FORMAT);
        String prefix = keyPrefix(primaryAddress);
        removeRecord(properties, prefix);
        properties.setProperty(prefix + COUNT, Integer.toString(endpoints.size()));
        for (int i = 0; i < endpoints.size(); i++) {
            ControlEndpoint endpoint = endpoints.get(i);
            properties.setProperty(prefix + i + HOST, endpoint.host());
            properties.setProperty(prefix + i + PORT, Integer.toString(endpoint.port()));
            properties.setProperty(prefix + i + PRIORITY, Integer.toString(endpoint.priority()));
        }

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

    private static void removeRecord(Properties properties, String prefix) {
        properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith(prefix))
                .toList()
                .forEach(properties::remove);
    }

    private List<ControlEndpoint> sortedUnique(List<ControlEndpoint> endpoints) {
        LinkedHashMap<String, ControlEndpoint> unique = new LinkedHashMap<>();
        for (ControlEndpoint endpoint : endpoints) {
            if (endpoint != null) {
                unique.put(endpoint.coordinateKey(), endpoint);
            }
        }
        return unique.values().stream()
                .sorted(Comparator.comparingInt(ControlEndpoint::priority).reversed()
                        .thenComparing(ControlEndpoint::host)
                        .thenComparingInt(ControlEndpoint::port))
                .limit(ControlEndpointManager.MAX_CANDIDATES)
                .toList();
    }

    private String keyPrefix(String primaryAddress) {
        return KEY_PREFIX + encoder.encodeToString(primaryAddress.getBytes(StandardCharsets.UTF_8)) + ".";
    }

    @SuppressWarnings("unused")
    private String decodePrimary(String encoded) {
        return new String(decoder.decode(encoded), StandardCharsets.UTF_8);
    }
}
