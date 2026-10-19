package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.shadow.storage.ShadowBlockEntitySnapshots;
import java.io.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShadowBlockEntitySnapshotsTest {
    private final BlockPos pos = new BlockPos(-37, -12, 85);

    @Test void nullNetworkTagStillKeepsTheEntityAndItsIdentity() {
        var snapshots = new ShadowBlockEntitySnapshots();
        snapshots.put(pos, "treasure2:proximity_mobset_spawner", null);
        CompoundTag expected = new CompoundTag();
        expected.putString("id", "treasure2:proximity_mobset_spawner");
        expected.putInt("x", -37); expected.putInt("y", -12); expected.putInt("z", 85);
        expected.putBoolean("keepPacked", false); expected.putBoolean(ShadowBlockEntitySnapshots.MARKER, true);
        assertEquals(expected, snapshots.get(pos));
        assertFalse(snapshots.get(pos).contains("minMobs"), "Do not invent server-only state");
    }

    @Test void ownsBothReceivedAndReturnedNbtAndCanReplaceAndRemove() {
        var snapshots = new ShadowBlockEntitySnapshots();
        CompoundTag data = new CompoundTag(); data.putString("custom", "first");
        snapshots.put(pos, "fixture:be", data);
        CompoundTag original = snapshots.get(pos);
        data.putString("custom", "mutated"); snapshots.get(pos).remove("custom");
        assertEquals(original, snapshots.get(pos));
        snapshots.put(pos, "fixture:replacement", null);
        assertFalse(snapshots.get(pos).contains("custom"));
        snapshots.remove(pos); assertFalse(snapshots.contains(pos));
        snapshots.put(pos, "fixture:be", data); snapshots.clear(); assertNull(snapshots.get(pos));
    }

    @Test void nativeNbtRoundTripRestoresAllClientDataWithoutConvertingOrdinarySaves() throws Exception {
        var snapshots = new ShadowBlockEntitySnapshots();
        CompoundTag data = new CompoundTag();
        CompoundTag nested = new CompoundTag(); nested.putString("key", "mod data"); data.put("nested", nested);
        snapshots.put(pos, "fixture:be", data);
        ListTag list = new ListTag(); list.add(snapshots.get(pos));
        CompoundTag ordinary = new CompoundTag(); ordinary.putString("id", "fixture:server_only"); list.add(ordinary);
        CompoundTag root = new CompoundTag(); root.put("block_entities", list);
        var bytes = new ByteArrayOutputStream(); NbtIo.write(root, new DataOutputStream(bytes));
        var restored = new ShadowBlockEntitySnapshots();
        ShadowBlockEntityCompat.restore(restored, NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        assertEquals(snapshots.get(pos), restored.get(pos));
        assertEquals(1, restored.restoredCount());
        assertEquals(1, restored.restoredCount("fixture:be"));
        assertEquals(0, snapshots.restoredCount("fixture:be"));
        assertFalse(restored.contains(BlockPos.ZERO));
    }
}
