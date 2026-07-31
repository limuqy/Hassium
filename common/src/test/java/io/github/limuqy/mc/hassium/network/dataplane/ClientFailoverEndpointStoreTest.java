package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClientFailoverEndpointStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void writesAndReloadsEndpoints() {
        Path path = tempDir.resolve("failover-endpoints.properties");
        ClientFailoverEndpointStore store = new ClientFailoverEndpointStore(path);
        List<ControlEndpoint> expected = List.of(
                new ControlEndpoint("backup.example", 25565, 10));

        assertEquals(expected, store.merge("primary.example:25565", expected));
        assertEquals(expected, new ClientFailoverEndpointStore(path)
                .load("primary.example:25565"));
    }

    @Test
    void mergeRetainsPersistedEndpointsOmittedByAdvertisement() {
        Path path = tempDir.resolve("failover-endpoints.properties");
        ClientFailoverEndpointStore store = new ClientFailoverEndpointStore(path);
        ControlEndpoint first = new ControlEndpoint("first.example", 25565, 1);
        ControlEndpoint second = new ControlEndpoint("second.example", 25565, 2);
        store.merge("primary.example:25565", List.of(first, second));

        assertEquals(List.of(second, first), store.merge("primary.example:25565", List.of(second)));
    }

    @Test
    void newerPriorityReplacesSameCoordinateAndCapsAtFour() {
        Path path = tempDir.resolve("failover-endpoints.properties");
        ClientFailoverEndpointStore store = new ClientFailoverEndpointStore(path);
        String primary = "primary.example:25565";
        store.merge(primary, List.of(
                new ControlEndpoint("a.example", 25565, 1),
                new ControlEndpoint("b.example", 25565, 2),
                new ControlEndpoint("c.example", 25565, 3),
                new ControlEndpoint("d.example", 25565, 4)));

        List<ControlEndpoint> merged = store.merge(primary, List.of(
                new ControlEndpoint("a.example", 25565, 99),
                new ControlEndpoint("e.example", 25565, 5)));

        assertEquals(4, merged.size());
        assertEquals(99, merged.get(0).priority());
        assertTrue(merged.stream().anyMatch(e -> e.host().equals("e.example")));
        assertFalse(merged.stream().anyMatch(e -> e.host().equals("b.example")));
    }

    @Test
    void invalidPropertiesAreIgnored() throws Exception {
        Path path = tempDir.resolve("failover-endpoints.properties");
        Files.writeString(path, "format=1\nprimary.invalid=not-a-record\n");

        ClientFailoverEndpointStore store = new ClientFailoverEndpointStore(path);

        assertDoesNotThrow(() -> store.load("primary.example:25565"));
        assertTrue(store.load("primary.example:25565").isEmpty());
    }

    @Test
    void persistsWhenAtomicMoveIsUnavailable() {
        Path path = tempDir.resolve("nested").resolve("failover-endpoints.properties");
        ClientFailoverEndpointStore store = new ClientFailoverEndpointStore(path);

        store.merge("primary.example:25565", List.of(
                new ControlEndpoint("backup.example", 25565, 3)));

        assertTrue(Files.exists(path));
        assertEquals(1, new ClientFailoverEndpointStore(path)
                .load("primary.example:25565").size());
    }
}
