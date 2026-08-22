package io.github.limuqy.mc.hassium.server;

import io.github.limuqy.mc.hassium.compat.CompoundTagCompat;
import io.github.limuqy.mc.hassium.compat.EntitySnapshotCompat;
import io.github.limuqy.mc.hassium.compat.ReflectionCompat;
import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

#if MC_VER < MC_1_21_9
import com.mojang.authlib.GameProfile;
import net.minecraft.server.players.GameProfileCache;
#else
import net.minecraft.server.players.NameAndId;
#endif

/**
 * 续流玩家磁盘数据加载（A6）。
 * <p>
 * 读取服务器世界 playerdata 目录下 {@code <uuid>.dat}（缺失回退 {@code .dat_old}），
 * 经 DataFixer 升级到当前版本后由 {@link EntitySnapshotCompat#loadFromTag} 应用到
 * ServerPlayer（背包/末影箱/经验/位置/维度等；1.21.6+ ValueInput 差异已封装）。
 * <p>
 * 版本差异（全部实测）：
 * <ul>
 *   <li>NbtIo 读取：{@code < 1.20.5} {@code readCompressed(File)}；1.20.5+
 *       {@code readCompressed(Path, NbtAccounter)}（1.20.4 已存在双参但 1.20.2/1.20.3
 *       仍只有单参 File 版，故边界取 1.20.5）</li>
 *   <li>CompoundTag {@code get(String)} 全版本返回可空 {@code Tag}（1.21.5+ 仅类型化
 *       getter 变 Optional）；StringTag 取值 {@code < 1.21.5 getAsString()} /
 *       1.21.5+ {@code value()}</li>
 *   <li>玩家名缓存：{@code < 1.21.9} {@code server.getProfileCache()}（usercache.json，
 *       可空）；1.21.9+ Services 重构后 {@code server.services().nameToIdCache()}</li>
 * </ul>
 * playerdata 目录定位：{@code MinecraftServer.storageSource} 为 protected 无访问器，
 * 经 {@link ReflectionCompat} 按字段类型匹配（与 SRG/intermediary 映射无关）。
 */
public final class PlayerDataStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/PlayerDataStorage");

    private PlayerDataStorage() {
    }

    /** 加载结果：name=最终玩家名；loaded=是否读到磁盘存档；tag=DataFixer 后的原始 NBT（可为 null）。 */
    public record LoadResult(String name, boolean loaded, CompoundTag tag) {
    }

    /**
     * 解析玩家名缓存（playerdata NBT 无 name 字段）：{@code < 1.21.9} usercache.json
     * （GameProfileCache，可为 null）；1.21.9+ {@code services().nameToIdCache()}。
     * 无记录返回 null，调用方兜底占位名。
     */
    public static String resolveName(MinecraftServer server, UUID playerId) {
#if MC_VER < MC_1_21_9
        GameProfileCache cache = server.getProfileCache();
        if (cache != null) {
            java.util.Optional<GameProfile> profile = cache.get(playerId);
            if (profile.isPresent()) {
                String name = profile.get().getName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        }
        return null;
#else
        return server.services().nameToIdCache().get(playerId).map(NameAndId::name).orElse(null);
#endif
    }

    /**
     * 从磁盘加载玩家数据并应用到 {@code player}（调用时机：placeNewPlayer 之前、
     * muted 窗口内、续流物化主线程）。
     */
    public static LoadResult loadInto(MinecraftServer server, ServerPlayer player, String name) {
        CompoundTag tag = null;
        try {
            LevelStorageSource.LevelStorageAccess access = (LevelStorageSource.LevelStorageAccess)
                    ReflectionCompat.getFieldByType(server, LevelStorageSource.LevelStorageAccess.class, true);
            Path dir = access.getLevelPath(LevelResource.PLAYER_DATA_DIR);
            tag = readTag(dir, player.getUUID());
            if (tag != null) {
                int dataVersion = NbtUtils.getDataVersion(tag, -1);
                tag = DataFixTypes.PLAYER.updateToCurrentVersion(DataFixers.getDataFixer(), tag, dataVersion);
                EntitySnapshotCompat.loadFromTag(player, tag, server.registryAccess());
            }
        } catch (ReflectiveOperationException e) {
            LOGGER.error("[GATEWAY] playerdata storage access failed for {} (name={}) — resume with empty data",
                    player.getStringUUID(), name, e);
        } catch (Exception e) {
            LOGGER.warn("[GATEWAY] failed to apply playerdata for {} (name={}) — resume with empty data",
                    player.getStringUUID(), name, e);
        }
        return new LoadResult(name, tag != null, tag);
    }

    /**
     * 读取 {@code dataDir/<uuid>.dat}（缺失回退 {@code .dat_old}）并返回原始 NBT；
     * 文件缺失/损坏返回 null（不抛异常，续流降级为空数据）。
     * <p>
     * DataFixer 升级不在此处：{@link #loadInto} 读到非空 tag 后先升级再应用
     * （测试环境无 MC bootstrap，DataFixers 静态初始化会失败，故保持纯 IO 可单测）。
     */
    public static CompoundTag readTag(Path dataDir, UUID uuid) {
        try {
            CompoundTag tag = readCompressed(dataDir.resolve(uuid + ".dat"));
            if (tag == null) {
                tag = readCompressed(dataDir.resolve(uuid + ".dat_old"));
            }
            return tag;
        } catch (Exception e) {
            LOGGER.warn("[GATEWAY] failed to read playerdata for {} — {}", uuid, e.toString());
            return null;
        }
    }

    private static CompoundTag readCompressed(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
#if MC_VER < MC_1_21_1
            return NbtIo.readCompressed(file.toFile());
#else
            // review-fix: 有界配额 2MiB（防解压炸弹；旧分支保持现状）
            return NbtIo.readCompressed(file, NbtAccounter.create(2_097_152L));
#endif
        } catch (Exception e) {
            LOGGER.warn("[GATEWAY] corrupt playerdata file {} — skipped", file);
            return null;
        }
    }

    /**
     * 从玩家存档 NBT 解析存档维度（{@code "Dimension"} 键；兼容历史数值 id）。
     * 无键/解析失败返回 null。
     * <p>
     * 用 {@code Registries.DIMENSION} + {@code ResourceKey.codec} 而非 {@code Level.*}
     * 常量：两者语义完全等价（{@code Level.RESOURCE_KEY_CODEC} 即
     * {@code ResourceKey.codec(Registries.DIMENSION)}），且 Level 静态初始化在无 MC
     * bootstrap 的单元测试环境会失败（A6 实测），保持纯 Registry 引用可单测。
     */
    public static ResourceKey<Level> parseDimension(CompoundTag tag) {
        if (tag == null) {
            return null;
        }
        Tag dimTag = tag.get("Dimension");
        if (!(dimTag instanceof StringTag stringTag)) {
            return null;
        }
        String dim = CompoundTagCompat.getString(stringTag);
        if (dim.isEmpty()) {
            return null;
        }
        // 历史数值维度 id（DimensionType.parseLegacy 语义；1.21.11 起 vanilla 只走 codec）
        switch (dim) {
            case "-1":
                return ResourceKey.create(Registries.DIMENSION, ResourceLocationCompat.create("minecraft:the_nether"));
            case "0":
                return ResourceKey.create(Registries.DIMENSION, ResourceLocationCompat.create("minecraft:overworld"));
            case "1":
                return ResourceKey.create(Registries.DIMENSION, ResourceLocationCompat.create("minecraft:the_end"));
            default:
                return ResourceKey.codec(Registries.DIMENSION)
                        .parse(NbtOps.INSTANCE, StringTag.valueOf(dim))
                        .result()
                        .orElse(null);
        }
    }
}
