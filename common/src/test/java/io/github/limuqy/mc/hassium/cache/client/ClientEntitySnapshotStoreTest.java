package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.compat.CompoundTagCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientEntitySnapshotStoreTest {

    @Test
    void createsVanillaEntityChunkSchema() {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:pig");

        CompoundTag root = ClientEntitySnapshotStore.createChunkTag(4321, -12, 34, List.of(entity));

        assertEquals(4321, CompoundTagCompat.getInt(root, "DataVersion", -1));
        assertArrayEquals(new int[]{-12, 34}, ((IntArrayTag) root.get("Position")).getAsIntArray());
        assertTrue(CompoundTagCompat.containsList(root, "Entities"));
        ListTag entities = CompoundTagCompat.getList(root, "Entities");
        assertEquals(1, entities.size());
    }

    @Test
    void snapshotsOwnImmutableEntityCopies() {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:pig");

        CompoundTag root = ClientEntitySnapshotStore.createChunkTag(1, 0, 0, List.of(entity));
        entity.putString("id", "minecraft:cow");
        CompoundTag stored = (CompoundTag) CompoundTagCompat.getList(root, "Entities").get(0);

        assertEquals("minecraft:pig", CompoundTagCompat.getString((net.minecraft.nbt.StringTag) stored.get("id")));
        assertNotSame(entity, stored);
    }

    @Test
    void filtersPassengersAndPlayerTreesBeforeSerialization() {
        Candidate normal = new Candidate(true, false, false);
        Candidate passenger = new Candidate(true, true, false);
        Candidate playerTree = new Candidate(true, false, true);
        Candidate otherChunk = new Candidate(false, false, false);

        List<Candidate> roots = ClientEntitySnapshotStore.selectSnapshotRoots(
                List.of(normal, passenger, playerTree, otherChunk),
                Candidate::inChunk,
                Candidate::passenger,
                Candidate::containsPlayer,
                candidate -> candidate
        );

        assertEquals(List.of(normal), roots);
        assertFalse(roots.contains(passenger));
        assertFalse(roots.contains(playerTree));
    }

    private record Candidate(boolean inChunk, boolean passenger, boolean containsPlayer) {
    }
}
