package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil;
import io.github.limuqy.mc.hassium.compat.CompoundTagCompat;
import io.github.limuqy.mc.hassium.compression.HassiumCompression;
import io.github.limuqy.mc.hassium.network.DictionaryManager;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.DetectedVersion;
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

        // MappedRegistry/BuiltInRegistries 构造器要求 Bootstrap 已启动（1.20.x 与 1.21.11 的
        // internalRegister 均调 checkBootstrapCalled，反编译核实），否则抛 IllegalArgumentException:
        // Not bootstrapped。gradle test 环境无 datapack → biome 缺失，bootstrap 仍需先跑。
        SharedConstants.setVersion(DetectedVersion.BUILT_IN);
        Bootstrap.bootStrap();

        // biome 注册表在 1.20.x 由 RegistryDataLoader 从 datapack 资源加载，gradle test 环境
        // 没有 datapack → 无法重建 LevelChunkSection，只能 SKIP（诊断测试保持手动运行）。
        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
#if MC_VER < MC_1_21_2
        if (registryAccess.registry(Registries.BIOME).isEmpty()) {
#else
        // 1.21.2+ RegistryAccess.registry 移除，改用 lookup（RegistryCompat 同款分界）
        if (registryAccess.lookup(Registries.BIOME).isEmpty()) {
#endif
            System.out.println("SKIP: biome registry unavailable without datapack resources");
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
            long[] fromNbt = ChunkDiskCodec.computeSectionHashesFromNbt(
                    nbt, sections.size(), registryAccess);
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
