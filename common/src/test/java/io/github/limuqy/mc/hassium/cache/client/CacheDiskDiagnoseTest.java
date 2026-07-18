package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.compat.CompoundTagCompat;
import io.github.limuqy.mc.hassium.compression.HassiumCompression;
import io.github.limuqy.mc.hassium.network.DictionaryManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 联机缓存诊断：比对 MetadataTable hash 与 NBT section 重算 hash，定位「HIT 但地形错」。
 */
class CacheDiskDiagnoseTest {

    @Test
    void diagnoseHashConsistencyAgainstDiskNbt() throws Exception {
        Path gameDir = Path.of("..", "fabric", "run", "client").toAbsolutePath().normalize();
        Path cacheRoot = gameDir.resolve("hassium_cache/server_127.0.0.1_25565/minecraft_overworld");
        if (!Files.isDirectory(cacheRoot)) {
            System.out.println("SKIP: no cache at " + cacheRoot);
            return;
        }

        DictionaryManager.loadChunkDictionary();
        if (!HassiumCompression.isInitialized()) {
            HassiumCompression.initialize();
        }

        ClientHassiumStorage storage = new ClientHassiumStorage(
                gameDir, "server_127.0.0.1_25565", "minecraft_overworld");

        ChunkPos[] samples = {
                new ChunkPos(-1, -9),
                new ChunkPos(0, -9),
                new ChunkPos(-2, -9),
                new ChunkPos(5, -7),
                new ChunkPos(-7, -7),
        };

        int mismatch = 0;
        for (ChunkPos pos : samples) {
            long metaHash = storage.readChunkHash(pos);
            long[] storedSections = storage.readSectionHashes(pos);
            byte[] raw = storage.loadAndDecompress(pos);
            if (raw == null) {
                System.out.printf("chunk %s: load FAIL meta=%s%n", pos, Long.toHexString(metaHash));
                mismatch++;
                continue;
            }
            CompoundTag nbt = ChunkDiskCodec.bytesToNbt(raw);
            ListTag sections = CompoundTagCompat.getList(nbt, "sections");
            long[] fromNbt = ChunkDiskCodec.computeSectionHashesFromNbt(nbt, sections.size(), null);
            long nbtHash = ChunkContentHashUtil.combineSectionHashesFromArray(fromNbt);
            long storedCombine = storedSections == null ? 0L
                    : ChunkContentHashUtil.combineSectionHashesFromArray(storedSections);

            boolean ok = metaHash == nbtHash;
            if (!ok) mismatch++;
            System.out.printf(
                    "chunk %s: sections=%d meta=%s nbtCombine=%s storedCombine=%s match=%s storedSec=%s%n",
                    pos, sections.size(),
                    Long.toHexString(metaHash),
                    Long.toHexString(nbtHash),
                    Long.toHexString(storedCombine),
                    ok,
                    storedSections == null ? "null" : Integer.toString(storedSections.length));
        }
        storage.close();
        System.out.println("hash mismatches: " + mismatch + "/" + samples.length);
    }
}
