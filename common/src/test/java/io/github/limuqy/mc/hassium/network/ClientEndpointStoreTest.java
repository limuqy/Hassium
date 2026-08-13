package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.config.HassiumConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ClientEndpointStoreTest {
    @TempDir
    Path tempDir;

    private static HassiumConfig.ReachableEndpoint endpoint(String host, int port, int priority) {
        return new HassiumConfig.ReachableEndpoint(host, port, priority);
    }

    private static String key(String mainAddress) {
        return "server." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mainAddress.getBytes(StandardCharsets.UTF_8));
    }

    private static Properties readProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static int countRecords(Path path) throws IOException {
        return (int) readProperties(path).stringPropertyNames().stream()
                .filter(k -> k.startsWith("server.")).count();
    }

    @Test
    void recordsAndReloadsFormat2Entry() throws IOException {
        Path path = tempDir.resolve("failover-endpoints.properties");
        ClientEndpointStore store = new ClientEndpointStore(path);

        // 缺失文件 → empty，不创建
        assertTrue(store.lookup("primary.example:25565").isEmpty());

        store.record("primary.example:25565", List.of(
                endpoint("backup.example", 25566, 10),
                endpoint("127.0.0.1", 25567, 5)));

        // 文件头 format=2；键 server.<base64>；值 ts=<epochMillis>;endpoints=<host:port:priority;...>
        Properties file = readProperties(path);
        assertEquals("2", file.getProperty("format"));
        String value = file.getProperty(key("primary.example:25565"));
        assertNotNull(value, "recorded key must be server.<base64(mainAddress)>");
        assertTrue(value.matches("ts=\\d+;endpoints=backup\\.example:25566:10;127\\.0\\.0\\.1:25567:5"),
                "unexpected value: " + value);

        ClientEndpointStore reloaded = new ClientEndpointStore(path);
        Optional<ClientEndpointStore.Entry> entry = reloaded.lookup("primary.example:25565");
        assertTrue(entry.isPresent());
        assertTrue(entry.get().ts() > 0);
        assertEquals(List.of(
                endpoint("backup.example", 25566, 10),
                endpoint("127.0.0.1", 25567, 5)), entry.get().endpoints());
        // 未记录的主地址 → empty
        assertTrue(reloaded.lookup("other.example:25565").isEmpty());
    }

    @Test
    void recordRefreshesTsAndReplacesEndpoints() {
        Path path = tempDir.resolve("failover-endpoints.properties");
        ClientEndpointStore store = new ClientEndpointStore(path);

        store.record("a.example:25565", List.of(endpoint("e1", 25566, 1)), 100L);
        store.record("a.example:25565", List.of(endpoint("e2", 25566, 2)), 200L);

        Optional<ClientEndpointStore.Entry> entry = store.lookup("a.example:25565");
        assertTrue(entry.isPresent());
        assertEquals(200L, entry.get().ts(), "re-record refreshes ts");
        assertEquals(List.of(endpoint("e2", 25566, 2)), entry.get().endpoints(),
                "re-record replaces endpoints");
    }

    @Test
    void evictsOldestTsBeyondCapacity() throws IOException {
        Path path = tempDir.resolve("failover-endpoints.properties");
        ClientEndpointStore store = new ClientEndpointStore(path);

        for (int i = 0; i < ClientEndpointStore.MAX_ENTRIES; i++) {
            store.record("server-" + i + ":25565", List.of(endpoint("e", 25566, 1)), i);
        }
        // 第 33 条 → 淘汰 ts 最小（=0）的 server-0
        store.record("server-32:25565", List.of(endpoint("e", 25566, 1)), 1000L);

        assertEquals(ClientEndpointStore.MAX_ENTRIES, countRecords(path));
        assertTrue(store.lookup("server-0:25565").isEmpty(), "oldest ts must be evicted");
        assertTrue(store.lookup("server-1:25565").isPresent());
        assertTrue(store.lookup("server-32:25565").isPresent());

        // 刷新已存在条目 ts → 不再是淘汰候选；再写入一条 → 淘汰次旧（server-2）
        store.record("server-1:25565", List.of(endpoint("e", 25566, 1)), 2000L);
        store.record("server-33:25565", List.of(endpoint("e", 25566, 1)), 3000L);
        assertTrue(store.lookup("server-2:25565").isEmpty(), "next oldest evicted");
        assertTrue(store.lookup("server-1:25565").isPresent(), "refreshed entry survives");
    }

    @Test
    void badLinesAreIgnored() throws IOException {
        Path path = tempDir.resolve("failover-endpoints.properties");
        Files.writeString(path, String.join("\n",
                "format=2",
                key("legacy") + "..count=3",                    // 旧 format=1 样式键（含第二个 '.'）
                key("nots") + "=not-a-ts-value",                // 值无 ts= 前缀
                key("badts") + "=ts=abc;endpoints=x:1:1",       // ts 非数字
                key("negts") + "=ts=-1;endpoints=x:1:1",        // ts 负数
                key("badseg") + "=ts=5;endpoints=host:1:1;badseg", // 端点段坏（缺段）
                key("badport") + "=ts=6;endpoints=h:99999:1",   // 端口越界
                key("ok") + "=ts=7;endpoints=ok.example:25566:10", // 合法
                ""));

        ClientEndpointStore store = new ClientEndpointStore(path);
        assertTrue(store.lookup("legacy").isEmpty());
        assertTrue(store.lookup("nots").isEmpty());
        assertTrue(store.lookup("badts").isEmpty());
        assertTrue(store.lookup("negts").isEmpty());
        assertTrue(store.lookup("badseg").isEmpty());
        assertTrue(store.lookup("badport").isEmpty());
        // 合法条目不受坏行影响
        Optional<ClientEndpointStore.Entry> entry = store.lookup("ok");
        assertTrue(entry.isPresent());
        assertEquals(7L, entry.get().ts());
        assertEquals(List.of(endpoint("ok.example", 25566, 10)), entry.get().endpoints());
    }

    @Test
    void format1FileIsIgnoredAndClearedOnFirstWrite() throws IOException {
        Path path = tempDir.resolve("failover-endpoints.properties");
        // 旧 format=1 残留（server.<b64>..count 样式，同 fabric/run 残留样例）
        Files.writeString(path, String.join("\n",
                "#Hassium failover endpoints",
                "format=1",
                "server.MTI3LjAuMC4xOjI1NTY1..count=0",
                "server.MTI3LjAuMC4xOjI1NTY2..count=2",
                ""));

        ClientEndpointStore store = new ClientEndpointStore(path);
        assertTrue(store.lookup("127.0.0.1:25565").isEmpty(), "format=1 读取忽略");
        assertTrue(store.lookup("127.0.0.1:25566").isEmpty());

        store.record("127.0.0.1:25565", List.of(endpoint("gw.example", 25567, 10)));

        // 首次写入覆盖：头变 format=2，旧 format=1 键清除
        Properties file = readProperties(path);
        assertEquals("2", file.getProperty("format"));
        assertFalse(file.stringPropertyNames().stream()
                        .anyMatch(k -> k.startsWith("server.") && k.indexOf('.', "server.".length()) >= 0),
                "legacy format=1 keys must be cleared on first write");
        assertTrue(store.lookup("127.0.0.1:25565").isPresent());
    }

    @Test
    void atomicWriteCreatesNestedDirsAndLeavesNoTempFile() throws IOException {
        Path path = tempDir.resolve("nested").resolve("config").resolve("failover-endpoints.properties");
        ClientEndpointStore store = new ClientEndpointStore(path);

        store.record("primary.example:25565", List.of(endpoint("backup.example", 25566, 3)));

        assertTrue(Files.exists(path));
        assertFalse(Files.exists(path.resolveSibling(path.getFileName() + ".tmp")),
                "no .tmp residue after atomic write");
        assertTrue(new ClientEndpointStore(path).lookup("primary.example:25565").isPresent());
    }

    @Test
    void corruptFileIsTreatedAsEmpty() throws IOException {
        Path path = tempDir.resolve("failover-endpoints.properties");
        // 非法 Unicode 转义 → Properties.load 抛 IllegalArgumentException → 视为空存储
        Files.writeString(path, "bad=\\uZZZZ\n");

        ClientEndpointStore store = new ClientEndpointStore(path);
        assertTrue(store.lookup("any:25565").isEmpty(), "unreadable file → empty, no throw");

        // 覆盖写恢复
        store.record("any:25565", List.of(endpoint("gw.example", 25567, 10)));
        assertTrue(store.lookup("any:25565").isPresent());
    }
}
