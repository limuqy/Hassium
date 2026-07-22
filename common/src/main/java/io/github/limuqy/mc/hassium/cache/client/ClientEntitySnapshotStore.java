package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 客户端实体快照的独立存储。每个区块使用一个普通 NBT 文件，不与区块 Region 缓存混用。
 */
public final class ClientEntitySnapshotStore implements AutoCloseable {

    private static final String CACHE_DIR = "hassium_cache";
    private static final String ENTITY_DIR = "entities";
    private static volatile ClientEntitySnapshotStore current;

    private final Path entityDirectory;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Hassium-Entity-Snapshot-Writer");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;

    private ClientEntitySnapshotStore(Path cacheDimensionRoot) throws IOException {
        this.entityDirectory = cacheDimensionRoot.resolve(ENTITY_DIR);
        Files.createDirectories(entityDirectory);
    }

    public static synchronized void initialize(Path gameDir, String serverId, String dimension) throws IOException {
        closeCurrent();
        // 维度目录名：将冒号替换为下划线（与 ClientChunkHandler.initStorage 一致）
        String dimDir = dimension.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path dimensionRoot = gameDir.resolve(CACHE_DIR)
                .resolve(serverId)
                .resolve(dimDir);
        current = new ClientEntitySnapshotStore(dimensionRoot);
    }

    public static ClientEntitySnapshotStore current() {
        return current;
    }

    public static synchronized void closeCurrent() {
        ClientEntitySnapshotStore store = current;
        current = null;
        if (store != null) {
            store.close();
        }
    }

    public static <T> List<T> selectSnapshotRoots(Iterable<T> candidates, Predicate<T> inChunk,
                                           Predicate<T> isPassenger, Predicate<T> containsPlayer,
                                           Function<T, T> snapshotCopy) {
        java.util.ArrayList<T> snapshots = new java.util.ArrayList<>();
        for (T candidate : candidates) {
            if (inChunk.test(candidate) && !isPassenger.test(candidate) && !containsPlayer.test(candidate)) {
                T snapshot = snapshotCopy.apply(candidate);
                if (snapshot != null) {
                    snapshots.add(snapshot);
                }
            }
        }
        return List.copyOf(snapshots);
    }

    /**
     * 原版 entity chunk 根结构：DataVersion、Position:[x,z]、Entities:[...].
     */
    public static CompoundTag createChunkTag(int dataVersion, int chunkX, int chunkZ,
                                             List<CompoundTag> entities) {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", dataVersion);
        root.put("Position", new IntArrayTag(new int[]{chunkX, chunkZ}));
        ListTag entityList = new ListTag();
        for (CompoundTag entity : entities) {
            entityList.add(entity.copy());
        }
        root.put("Entities", entityList);
        return root;
    }

    public void writeChunk(ChunkPos pos, List<CompoundTag> entities) {
        if (closed) {
            return;
        }
        CompoundTag root = createChunkTag(currentDataVersion(), pos.x, pos.z, entities);
        try {
            writer.execute(() -> writeChunkFile(pos, root));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Disconnect raced with unload capture; the closed store no longer accepts snapshots.
        }
    }

    private void writeChunkFile(ChunkPos pos, CompoundTag root) {
        Path target = chunkPath(pos.x, pos.z);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(entityDirectory);
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(temporary))) {
                NbtIo.write(root, output);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Constants.LOG.warn("Hassium: Failed to persist entity snapshot for chunk {}", pos, e);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // best effort cleanup
            }
        }
    }

    /**
     * 导出端可按区块读取原版 entity chunk NBT；无快照时返回 null。
     */
    public CompoundTag readChunk(int chunkX, int chunkZ) throws IOException {
        Path file = chunkPath(chunkX, chunkZ);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
            return NbtIo.read(input);
        }
    }

    public static CompoundTag readChunk(Path cacheDimensionRoot, int chunkX, int chunkZ) throws IOException {
        Path file = cacheDimensionRoot.resolve(ENTITY_DIR).resolve(fileName(chunkX, chunkZ));
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
            return NbtIo.read(input);
        }
    }

    public Path entityDirectory() {
        return entityDirectory;
    }

    private Path chunkPath(int chunkX, int chunkZ) {
        return entityDirectory.resolve(fileName(chunkX, chunkZ));
    }

    private static String fileName(int chunkX, int chunkZ) {
        return "c." + chunkX + "." + chunkZ + ".nbt";
    }

    private static int currentDataVersion() {
#if MC_VER < MC_1_21_6
        return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
#else
        return SharedConstants.getCurrentVersion().dataVersion().version();
#endif
    }

    @Override
    public void close() {
        closed = true;
        writer.shutdown();
        try {
            if (!writer.awaitTermination(3, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
