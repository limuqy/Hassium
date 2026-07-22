# Unified Cache Persist & Disconnect Optimization

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate redundant IO by removing `persistDecompressedChunk` (write without light), unifying all cache persistence to unload time, and restructuring disconnect to capture `clearLevel()` unloads.

**Architecture:** Server chunks are no longer persisted on receipt. Instead, all persistence happens at chunk unload via `CacheSaveQueue.enqueue` → `levelChunkToNbt` (which captures block states + light + heightmaps in one shot). On disconnect, the save infrastructure stays alive through vanilla's `clearLevel()` so unload-triggered saves are captured.

**Tech Stack:** Java 17, Minecraft 1.20.1–1.21.11, Mixin 0.8.7, ZSTD

## Global Constraints

- All code in `common/` — no loader-specific imports
- `#if MC_VER` only in `compat/` files, never in business logic
- Mixin fields: `@Unique` + `hassium$` prefix
- Storage gate: `isStorageEnabled()` check at entry points
- `lightCacheEnabled` default — server strips light from packets; client caches after first recompute
- Cache data is expendable — dirty/corrupt cache is the only hard failure

---

### Task 1: Remove `persistDecompressedChunk` calls

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/ClientChunkHandler.java:178-247`

**Interfaces:**
- Removes: `persistDecompressedChunk` calls from `handleCompressedChunk` and `decompressAndApply`
- Side effect: section hash pending maps (`pendingContentHashes`, `pendingSectionHashes`) become unused for server chunk path — this is OK, they expire via TTL

- [ ] **Step 1: Remove `persistDecompressedChunk` call from `handleCompressedChunk`**

In `ClientChunkHandler.java`, the background thread lambda (lines 178-210) currently calls `persistDecompressedChunk` after `MainThreadDispatcher.execute`. Remove lines 201-205:

```java
// REMOVE these lines:
                try {
                    persistDecompressedChunk(chunkX, chunkZ, decompressed);
                } catch (Throwable t) {
                    Constants.LOG.warn("Hassium: Failed to persist chunk [{}, {}] after apply", chunkX, chunkZ, t);
                }
```

The lambda becomes:

```java
        executor.submit(() -> {
            try {
                DebugLogger.info(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Decompressing chunk [{}, {}] in background", chunkX, chunkZ);
                byte[] decompressed = ChunkCompressionHandler.decompressChunkDataFromRaw(chunkX, chunkZ, compData, algorithm);
                if (decompressed == null) {
                    DebugLogger.error("[HANDLE_COMPRESSED] Failed to decompress chunk data for [{}, {}]", chunkX, chunkZ);
                    return;
                }

                DebugLogger.info(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Decompressed chunk [{}, {}] ({} -> {} bytes)",
                    chunkX, chunkZ, compData.length, decompressed.length);

                // 回主线程应用区块（缓存由卸载路径统一处理）
                MainThreadDispatcher.execute(() -> {
                    DebugLogger.info(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Applying chunk [{}, {}] to world", chunkX, chunkZ);
                    if (applyChunkData(chunkX, chunkZ, decompressed, false)) {
                        DebugLogger.info(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Successfully applied chunk [{}, {}] from server", chunkX, chunkZ);
                    } else {
                        DebugLogger.warn(LogType.COMPRESSION, "[HANDLE_COMPRESSED] Failed to apply chunk [{}, {}] from server", chunkX, chunkZ);
                    }
                }, new ChunkPos(chunkX, chunkZ), TaskCategory.SAFE_TO_CANCEL);

            } catch (Exception e) {
                DebugLogger.error("[HANDLE_COMPRESSED] Error in background decompress for chunk [{}, {}]", e, chunkX, chunkZ);
            }
        }, TaskCategory.SAFE_TO_CANCEL);
```

- [ ] **Step 2: Remove `persistDecompressedChunk` call from `decompressAndApply`**

In `decompressAndApply` (lines 216-247), remove lines 230-235:

```java
// REMOVE these lines:
            try {
                persistDecompressedChunk(compressed.chunkX, compressed.chunkZ, decompressed);
            } catch (Throwable t) {
                Constants.LOG.warn("Hassium: Failed to persist chunk [{}, {}] after apply (fallback)",
                        compressed.chunkX, compressed.chunkZ, t);
            }
```

The method becomes:

```java
    private static void decompressAndApply(ChunkCompressionHandler.CompressedChunkData compressed) {
        try {
            byte[] decompressed = ChunkCompressionHandler.decompressChunkData(compressed);
            if (decompressed == null) {
                Constants.LOG.error("Hassium: Failed to decompress chunk data for [{}, {}]",
                    compressed.chunkX, compressed.chunkZ);
                return;
            }

            Constants.LOG.debug("Hassium: Decompressed chunk [{}, {}] on main thread (fallback), size: {} -> {} bytes",
                compressed.chunkX, compressed.chunkZ, compressed.compressedData.length, decompressed.length);

            // 应用区块（缓存由卸载路径统一处理）
            boolean applied = applyChunkData(compressed.chunkX, compressed.chunkZ, decompressed, false);
            if (applied) {
                Constants.LOG.debug("Hassium: Applied chunk [{}, {}] from server",
                        compressed.chunkX, compressed.chunkZ);
            } else {
                Constants.LOG.warn("Hassium: Failed to apply chunk [{}, {}] from server",
                        compressed.chunkX, compressed.chunkZ);
            }
        } catch (Exception e) {
            Constants.LOG.error("Hassium: Error in fallback decompress for chunk [{}, {}]",
                compressed.chunkX, compressed.chunkZ, e);
        }
    }
```

- [ ] **Step 3: Delete the `persistDecompressedChunk` method**

Delete lines 415-472 (the entire `persistDecompressedChunk` method). Also delete the `computeSectionHashesFromData` method (lines 474-524) since it's only used by `persistDecompressedChunk`.

- [ ] **Step 4: Compile**

```bash
./gradlew --no-daemon common:compileJava
```

Expected: BUILD SUCCESSFUL

---

### Task 2: Optimize `updateCacheWithLightData` — in-memory NBT overload

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/cache/client/ClientLightRecomputeService.java:41-125`

**Interfaces:**
- Produces: `applyLightEngineNow(ChunkPos, CompoundTag)` — public static, accepts optional in-memory NBT
- Produces: `updateCacheWithLightData(ClientLevel, ChunkPos, CompoundTag)` — private static, uses in-memory NBT if non-null

- [ ] **Step 1: Add `applyLightEngineNow` overload**

After the existing `applyLightEngineNow(ChunkPos)` method (line 53), add:

```java
    /**
     * 同步执行光照重算，使用内存中的 NBT（避免从磁盘读取）。
     *
     * @param chunkPos  区块坐标
     * @param cachedNbt 内存中的缓存 NBT（可为 null，null 时回退磁盘读取）
     */
    public static void applyLightEngineNow(ChunkPos chunkPos, net.minecraft.nbt.CompoundTag cachedNbt) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
        if (chunk == null) {
            return;
        }
        applyLightEngine(level, chunk, chunkPos);
        updateCacheWithLightData(level, chunkPos, cachedNbt);
    }
```

- [ ] **Step 2: Add `updateCacheWithLightData` overload**

After the existing `updateCacheWithLightData(ClientLevel, ChunkPos)` method (line 125), add:

```java
    /**
     * 从光照引擎提取光照数据，更新缓存（优先使用内存 NBT）。
     */
    private static void updateCacheWithLightData(ClientLevel level, ChunkPos chunkPos,
                                                  net.minecraft.nbt.CompoundTag cachedNbt) {
        try {
            ClientHassiumStorage storage = ClientChunkHandler.getClientStorage();
            if (storage == null) return;

            net.minecraft.nbt.CompoundTag nbt = cachedNbt;
            if (nbt == null) {
                // fallback：从磁盘读取
                byte[] cachedData = storage.loadAndDecompress(chunkPos);
                if (cachedData == null) return;
                nbt = ChunkDiskCodec.bytesToNbt(cachedData);
                if (nbt == null) return;
            }

            // 检查是否已有光照数据
            net.minecraft.nbt.Tag lightOnTag = nbt.get("is_light_on");
            if (lightOnTag instanceof net.minecraft.nbt.ByteTag bt && bt.getAsByte() != 0) {
                return;
            }

            // 从光照引擎提取光照数据
            net.minecraft.world.level.lighting.LevelLightEngine lightEngine = level.getLightEngine();
            net.minecraft.world.level.lighting.LayerLightEventListener skyListener =
                    lightEngine.getLayerListener(net.minecraft.world.level.LightLayer.SKY);
            net.minecraft.world.level.lighting.LayerLightEventListener blockListener =
                    lightEngine.getLayerListener(net.minecraft.world.level.LightLayer.BLOCK);

            int minSection = level.getMinSection();
            int maxSection = level.getMaxSection();
            net.minecraft.nbt.ListTag sectionsList = CompoundTagCompat.getList(nbt, "sections");

            boolean hasAnyLight = false;
            for (int sectionY = minSection; sectionY < maxSection; sectionY++) {
                int idx = sectionY - minSection;
                if (idx >= sectionsList.size()) break;

                net.minecraft.nbt.Tag t = sectionsList.get(idx);
                if (!(t instanceof net.minecraft.nbt.CompoundTag sectionTag)) continue;

                net.minecraft.core.SectionPos sectionPos =
                        net.minecraft.core.SectionPos.of(chunkPos.x, sectionY, chunkPos.z);

                net.minecraft.world.level.chunk.DataLayer skyData = skyListener.getDataLayerData(sectionPos);
                if (skyData != null && !skyData.isEmpty()) {
                    sectionTag.putByteArray("sky_light", skyData.getData().clone());
                    hasAnyLight = true;
                }

                net.minecraft.world.level.chunk.DataLayer blockData = blockListener.getDataLayerData(sectionPos);
                if (blockData != null && !blockData.isEmpty()) {
                    sectionTag.putByteArray("block_light", blockData.getData().clone());
                    hasAnyLight = true;
                }
            }

            if (hasAnyLight) {
                nbt.putByte("is_light_on", (byte) 1);
                byte[] updatedBytes = ChunkDiskCodec.nbtToBytes(nbt);
                if (updatedBytes != null) {
                    storage.persist(chunkPos, updatedBytes, 0L, null);
                    Constants.LOG.debug("Hassium: Updated cache with light data for {}", chunkPos);
                }
            }
        } catch (Exception e) {
            Constants.LOG.debug("Hassium: Failed to update cache with light data for {}", chunkPos, e);
        }
    }
```

- [ ] **Step 3: Update existing `applyLightEngineNow(ChunkPos)` to delegate**

Change the existing method (lines 41-53) to delegate to the new overload:

```java
    public static void applyLightEngineNow(ChunkPos chunkPos) {
        applyLightEngineNow(chunkPos, null);
    }
```

- [ ] **Step 4: Compile**

```bash
./gradlew --no-daemon common:compileJava
```

Expected: BUILD SUCCESSFUL

---

### Task 3: Add `applyChunkData` overload with NBT parameter

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/network/ClientChunkHandler.java:264-320`

**Interfaces:**
- Produces: `applyChunkData(int, int, byte[], boolean, CompoundTag)` — accepts pre-built NBT, passes to `applyLightEngineNow`

- [ ] **Step 1: Add `applyChunkData` overload**

After the existing `applyChunkData` method (line 320), add:

```java
    /**
     * 将解压后的区块数据应用到客户端世界（接受预构建的 NBT 以避免光照回写时重复读盘）。
     *
     * @param chunkX     区块X坐标
     * @param chunkZ     区块Z坐标
     * @param chunkData  NBT 字节或 packet 字节
     * @param renderOnly true=仅渲染不参与逻辑tick
     * @param cachedNbt  内存中的缓存 NBT（可为 null，null 时回退磁盘读取）
     */
    public static boolean applyChunkData(int chunkX, int chunkZ, byte[] chunkData,
                                         boolean renderOnly, net.minecraft.nbt.CompoundTag cachedNbt) {
        // 调用原方法完成主要逻辑
        boolean result = applyChunkData(chunkX, chunkZ, chunkData, renderOnly);
        // 如果是 renderOnly 且成功应用，用内存 NBT 更新光照缓存（跳过磁盘读）
        if (result && renderOnly && cachedNbt != null) {
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            ClientLightRecomputeService.updateCacheWithLightData(
                    Minecraft.getInstance().level, pos, cachedNbt);
        }
        return result;
    }
```

Wait — `updateCacheWithLightData` is private. Need to make it package-private or add a public wrapper.

- [ ] **Step 1b: Make `updateCacheWithLightData(ChunkPos, CompoundTag)` accessible**

In `ClientLightRecomputeService.java`, add a public wrapper method:

```java
    /**
     * 公开入口：使用内存 NBT 更新光照缓存（供 applyChunkData 调用）。
     */
    public static void updateCacheWithLightNbt(ChunkPos chunkPos, net.minecraft.nbt.CompoundTag cachedNbt) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        updateCacheWithLightData(level, chunkPos, cachedNbt);
    }
```

Then the `applyChunkData` overload becomes:

```java
    public static boolean applyChunkData(int chunkX, int chunkZ, byte[] chunkData,
                                         boolean renderOnly, net.minecraft.nbt.CompoundTag cachedNbt) {
        boolean result = applyChunkData(chunkX, chunkZ, chunkData, renderOnly);
        if (result && renderOnly && cachedNbt != null) {
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            ClientLightRecomputeService.updateCacheWithLightNbt(pos, cachedNbt);
        }
        return result;
    }
```

But wait — `applyChunkData(renderOnly=true)` already calls `applyLightEngineNow(pos)` which calls `updateCacheWithLightData(level, chunkPos)` (the no-NBT overload, reading from disk). If we then also call `updateCacheWithLightNbt`, we'd do the work twice.

Better approach: modify `applyChunkData` to pass the NBT through to `applyLightEngineNow`:

```java
    public static boolean applyChunkData(int chunkX, int chunkZ, byte[] chunkData,
                                         boolean renderOnly, net.minecraft.nbt.CompoundTag cachedNbt) {
        // Same as applyChunkData but passes cachedNbt to applyLightEngineNow
        // ... (copy of applyChunkData logic, line 307 changed)
        // Line 307: ClientLightRecomputeService.applyLightEngineNow(pos);
        // Becomes: ClientLightRecomputeService.applyLightEngineNow(pos, cachedNbt);
    }
```

This duplicates the method body. To avoid duplication, refactor the original to delegate:

- [ ] **Step 1c (refined): Refactor `applyChunkData` to accept optional NBT**

Change the existing method signature to add a private parameter:

```java
    public static boolean applyChunkData(int chunkX, int chunkZ, byte[] chunkData, boolean renderOnly) {
        return applyChunkData(chunkX, chunkZ, chunkData, renderOnly, null);
    }

    private static boolean applyChunkData(int chunkX, int chunkZ, byte[] chunkData,
                                          boolean renderOnly, net.minecraft.nbt.CompoundTag cachedNbt) {
        // ... existing body, with line 307 changed:
        // ClientLightRecomputeService.applyLightEngineNow(pos);
        // →
        // ClientLightRecomputeService.applyLightEngineNow(pos, cachedNbt);
    }
```

The public overload for external callers (like `trySubstituteOnUnload`):

```java
    public static boolean applyChunkData(int chunkX, int chunkZ, byte[] chunkData,
                                         boolean renderOnly, net.minecraft.nbt.CompoundTag cachedNbt) {
        return applyChunkData(chunkX, chunkZ, chunkData, renderOnly, cachedNbt);
    }
```

Wait, that's the same signature as the private one. Let me just make it one method with a default null:

```java
    public static boolean applyChunkData(int chunkX, int chunkZ, byte[] chunkData, boolean renderOnly) {
        return applyChunkDataInternal(chunkX, chunkZ, chunkData, renderOnly, null);
    }

    public static boolean applyChunkData(int chunkX, int chunkZ, byte[] chunkData,
                                         boolean renderOnly, net.minecraft.nbt.CompoundTag cachedNbt) {
        return applyChunkDataInternal(chunkX, chunkZ, chunkData, renderOnly, cachedNbt);
    }

    private static boolean applyChunkDataInternal(int chunkX, int chunkZ, byte[] chunkData,
                                                   boolean renderOnly, net.minecraft.nbt.CompoundTag cachedNbt) {
        // existing body, with applyLightEngineNow(pos, cachedNbt) on the renderOnly path
    }
```

- [ ] **Step 2: Update the renderOnly path in `applyChunkDataInternal`**

In the `applyChunkDataInternal` method, change line 307:

```java
// FROM:
                ClientLightRecomputeService.applyLightEngineNow(pos);
// TO:
                ClientLightRecomputeService.applyLightEngineNow(pos, cachedNbt);
```

- [ ] **Step 3: Compile**

```bash
./gradlew --no-daemon common:compileJava
```

Expected: BUILD SUCCESSFUL

---

### Task 4: Wire `trySubstituteOnUnload` to pass NBT

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/cache/client/ViewDistanceExtensionService.java:597-598`

- [ ] **Step 1: Pass NBT to `applyChunkData`**

In `trySubstituteOnUnload`, change line 598:

```java
// FROM:
            if (ClientChunkHandler.applyChunkData(pos.x, pos.z, nbtBytes, true)) {
// TO:
            if (ClientChunkHandler.applyChunkData(pos.x, pos.z, nbtBytes, true, nbt)) {
```

- [ ] **Step 2: Compile**

```bash
./gradlew --no-daemon common:compileJava
```

Expected: BUILD SUCCESSFUL

---

### Task 5: Add `CacheSaveQueue.drainRemaining`

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/cache/client/CacheSaveQueue.java`

**Interfaces:**
- Produces: `drainRemaining(long timeoutMs)` — public, synchronously drains all pending save tasks

- [ ] **Step 1: Add `drainRemaining` method**

After `flushAsync` (line 276), add:

```java
    /**
     * 同步排空队列中所有待处理任务（断连 finalize 阶段调用）。
     * <p>
     * 与 {@link #flushAsync} 不同，此方法不停止 save 线程——
     * 调用方负责后续调用 {@link #shutdown()}。
     *
     * @param timeoutMs 最大等待时间（毫秒）
     */
    public void drainRemaining(long timeoutMs) {
        List<Runnable> tasks = new ArrayList<>();
        SaveTask task;
        while ((task = taskQueue.poll()) != null) {
            final SaveTask t = task;
            tasks.add(() -> processTask(t));
        }
        if (tasks.isEmpty()) {
            return;
        }

        Constants.LOG.info("Hassium: [CACHE SAVE] Final drain - {} tasks (timeout={}ms)", tasks.size(), timeoutMs);

        HassiumTaskExecutor executor = HassiumTaskExecutor.getClient();
        if (executor != null && executor.isRunning()) {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable r : tasks) {
                futures.add(executor.submit(() -> { r.run(); return null; }, TaskCategory.MISSION_CRITICAL));
            }
            long deadline = System.currentTimeMillis() + timeoutMs;
            int completed = 0;
            for (Future<?> future : futures) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    Constants.LOG.warn("Hassium: [CACHE SAVE] Final drain timed out, {} tasks lost",
                            futures.size() - completed);
                    break;
                }
                try {
                    future.get(remaining, TimeUnit.MILLISECONDS);
                    completed++;
                } catch (TimeoutException e) {
                    break;
                } catch (Exception e) {
                    Constants.LOG.error("Hassium: [CACHE SAVE] Final drain task failed", e);
                    completed++;
                }
            }
            Constants.LOG.info("Hassium: [CACHE SAVE] Final drain complete: {}/{}", completed, tasks.size());
        } else {
            // executor 不可用：同步执行
            int completed = 0;
            for (Runnable r : tasks) {
                try {
                    r.run();
                    completed++;
                } catch (Exception e) {
                    Constants.LOG.error("Hassium: [CACHE SAVE] Final drain sync task failed", e);
                }
            }
            Constants.LOG.info("Hassium: [CACHE SAVE] Final drain sync complete: {}/{}", completed, tasks.size());
        }
    }
```

- [ ] **Step 2: Compile**

```bash
./gradlew --no-daemon common:compileJava
```

Expected: BUILD SUCCESSFUL

---

### Task 6: Restructure `cleanupOnDisconnect`

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/cache/client/ClientLifecycleHelper.java:66-103`

**Interfaces:**
- Modifies: `cleanupOnDisconnect()` — lightweight, keeps save infrastructure alive
- Produces: `finalizeDisconnect()` — heavyweight, called at TAIL after `clearLevel()`
- Produces: `drainLoadQueueWithRaisedBudget()` — private helper

- [ ] **Step 1: Rewrite `cleanupOnDisconnect`**

Replace the entire method (lines 66-103):

```java
    /**
     * 断开连接时清理（HEAD 注入，vanilla clearLevel 之前）。
     * <p>
     * 轻量清理：拉高预算消费加载队列，排空已有 save 队列，取消后台任务。
     * 保留 save 线程和 executor 存活——vanilla clearLevel() 会触发所有 chunk 的 unload，
     * unload Mixin 会 enqueue 到 save 队列，由仍在运行的 save 线程消费。
     * <p>
     * 重量清理（executor shutdown、storage close）推迟到 {@link #finalizeDisconnect()}。
     */
    public static void cleanupOnDisconnect() {
        initialized = false;
        ClientMainThreadBudget.clearJoinBoost();

        // ① 拉高预算，尽可能消费加载队列中的缓存区块
        drainLoadQueueWithRaisedBudget();

        // ② 排空已有 save 队列（save 线程 + executor 仍然存活）
        CacheSaveQueue.getInstance().flushAsync(5000);

        // ③ 清空加载队列（不再有新区块需要加载）
        ClientCacheLoadQueue.getInstance().clear();
        ViewDistanceExtensionService.getInstance().clearAllRenderOnly();

        // ④ 取消后台任务（但不关闭 executor，save 还需要它）
        HassiumTaskExecutor clientExecutor = HassiumTaskExecutor.getClient();
        if (clientExecutor != null) {
            clientExecutor.cancelAll(TaskCategory.SAFE_TO_CANCEL);
        }

        // ⑤ 清空主线程回调队列
        MainThreadDispatcher.clearClient(false);
        ClientLightRecomputeService.clear();
        ClientMetadataHandler.clearPendingState();

        Constants.LOG.info("Hassium: Disconnect cleanup done (save infrastructure alive for clearLevel)");
    }

    /**
     * 断开连接最终清理（TAIL 注入，vanilla clearLevel 之后）。
     * <p>
     * 排空 clearLevel() 触发的 unload → enqueue 的 save 任务，然后关闭所有基础设施。
     */
    public static void finalizeDisconnect() {
        // ⑥ finalDrain：排空 clearLevel 产生的 save 任务
        CacheSaveQueue.getInstance().drainRemaining(5000);

        // ⑦ 关闭 executor
        HassiumTaskExecutor.shutdownClient(5000);

        // ⑧ 停止 save 线程
        CacheSaveQueue.getInstance().shutdown();

        // ⑨ 关闭 storage
        ClientHassiumStorage storage = ClientChunkHandler.getClientStorage();
        if (storage != null) {
            try {
                storage.close();
            } catch (Exception e) {
                Constants.LOG.warn("Hassium: Failed to close client storage on disconnect", e);
            }
        }
        ClientEntitySnapshotStore.closeCurrent();
        ClientHassiumStorage.closeSharedDatabase();
        ClientChunkHandler.resetStorage();
        Constants.LOG.info("Hassium: Client disconnected, cache cleaned up");
    }

    /**
     * 断连时拉高预算，尽可能消费加载队列中的缓存区块。
     * <p>
     * 未 apply 的区块在断连后丢失（可接受），但 apply 过的区块在卸载时会被 save。
     */
    private static void drainLoadQueueWithRaisedBudget() {
        ClientCacheLoadQueue loadQueue = ClientCacheLoadQueue.getInstance();
        int pending = loadQueue.getPendingSize() + loadQueue.getReadySize();
        if (pending <= 0) {
            return;
        }

        Constants.LOG.info("Hassium: Disconnect drain - {} chunks pending, raising budget", pending);

        long deadlineNs = System.nanoTime() + 5_000_000_000L; // 5秒总超时
        while (System.nanoTime() < deadlineNs) {
            int ready = loadQueue.getReadySize();
            int pendingTasks = loadQueue.getPendingSize();
            if (ready == 0 && pendingTasks == 0) {
                break;
            }

            // 消费 ready 队列（主线程 apply + 光照重算）
            if (ready > 0) {
                long frameBudgetNs = 50_000_000L; // 每帧 50ms（正常 ~10ms）
                loadQueue.processQueueUntil(System.nanoTime() + frameBudgetNs);
            }

            // 等待 pending → ready（后台解压 + NBT 重组）
            if (loadQueue.getReadySize() == 0 && loadQueue.getPendingSize() > 0) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        Constants.LOG.info("Hassium: Disconnect drain complete");
    }
```

- [ ] **Step 2: Add missing import**

At the top of `ClientLifecycleHelper.java`, add:

```java
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
```

(These are for `CacheSaveQueue.drainRemaining`, not this file — check if already imported in CacheSaveQueue.)

- [ ] **Step 3: Compile**

```bash
./gradlew --no-daemon common:compileJava
```

Expected: BUILD SUCCESSFUL

---

### Task 7: Add TAIL injection for `finalizeDisconnect`

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/mixin/MixinClientCommonPacketListenerImpl.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/mixin/MixinClientPacketListener.java`

- [ ] **Step 1: Add TAIL injection in `MixinClientCommonPacketListenerImpl`**

After the existing HEAD injection (line 42), add:

```java
#if MC_VER >= MC_1_20_2
    @Inject(method = "onDisconnect", at = @At("TAIL"))
    private void hassium$onDisconnectTail(
#if MC_VER < MC_1_21_1
            net.minecraft.network.chat.Component reason,
#else
            net.minecraft.network.DisconnectionDetails details,
#endif
            CallbackInfo ci) {
        ClientLifecycleHelper.finalizeDisconnect();
    }
#endif
```

- [ ] **Step 2: Add TAIL injection in `MixinClientPacketListener`**

After the existing HEAD injection (line 53), add:

```java
#if MC_VER < MC_1_20_2
    @Inject(method = "onDisconnect", at = @At("TAIL"))
    private void hassium$onDisconnectTail(net.minecraft.network.chat.Component reason, CallbackInfo ci) {
        ClientLifecycleHelper.finalizeDisconnect();
    }
#endif
```

- [ ] **Step 3: Compile all loaders**

```bash
./gradlew --no-daemon common:compileJava fabric:compileJava neoforge:compileJava "-Pmc_ver=1.20.1"
```

Expected: BUILD SUCCESSFUL

---

### Task 8: Smoke test

- [ ] **Step 1: Run 1.20.1 Fabric smoke test**

```bash
.\scripts\runtime-smoke-test.ps1 -Ver 1.20.1 -Loader fabric -Phase I -SessionId "1.20.1_fabric_unified_persist"
```

Expected: PASS (light cache hit rate ≥ 78%, no dirty cache)

- [ ] **Step 2: Verify disconnect preservation**

Check smoke test logs for:
- "Disconnect drain" log with chunk count > 0
- "Final drain" log with save tasks processed
- No "Failed to persist" errors during disconnect
