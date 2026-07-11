package io.github.limuqy.mc.hassium.config;

import com.google.gson.*;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 带中文注释的 JSON 写入器
 * <p>
 * 在生成 JSON 时添加中文注释，帮助用户理解每个配置项的含义。
 * 注释以 "//" 开头，符合 JSON5 规范（虽然标准 JSON 不支持）。
 * 加载时会自动移除这些注释行。
 */
public class CommentedJsonWriter {

    /**
     * 配置项注释映射
     * Key: JSON 路径（如 "storage.enabled"）
     * Value: 中文注释
     */
    private static final Map<String, String> COMMENTS = new LinkedHashMap<>();

    static {
        // ===== 存储配置 =====
        COMMENTS.put("storage", "区块存储配置");
        COMMENTS.put("storage.enabled", "是否启用 Hassium 存储（默认关闭，启用前请备份存档）");
        COMMENTS.put("storage.mode", "存储模式：mirror=镜像原版, readonly_vanilla=只读原版, hassium_only=仅 Hassium");
        COMMENTS.put("storage.zstdLevel", "ZSTD 压缩等级（1-22，默认9，越高压缩比越好但越慢）");
        COMMENTS.put("storage.zstdDictionaryId", "ZSTD 字典 ID（使用预训练字典提高压缩比）");
        COMMENTS.put("storage.storageCompressionAlgorithm", "存储压缩算法（hassium:zstd）");
        COMMENTS.put("storage.verifyChecksum", "是否校验数据完整性");

        // ===== 客户端缓存配置 =====
        COMMENTS.put("clientCache", "客户端区块缓存配置");
        COMMENTS.put("clientCache.enabled", "是否启用客户端缓存（减少重复区块传输）");
        COMMENTS.put("clientCache.maxSizeMb", "缓存最大容量（MB）");
        COMMENTS.put("clientCache.maxAgeDays", "缓存过期天数");
        COMMENTS.put("clientCache.hotScoreThreshold", "热点分数阈值");
        COMMENTS.put("clientCache.recencyWeight", "最近访问权重");
        COMMENTS.put("clientCache.frequencyWeight", "访问频率权重");
        COMMENTS.put("clientCache.cleanupIntervalTicks", "清理间隔（游戏刻）");
        COMMENTS.put("clientCache.targetCacheSizeMb", "目标缓存大小（MB）");
        COMMENTS.put("clientCache.minCleanupBatchSize", "最小清理批次大小");
        COMMENTS.put("clientCache.bloomFilterEnabled", "是否启用 Bloom Filter 预筛（减少无效 .mca 文件读取）");
        COMMENTS.put("clientCache.bloomFilterExpectedInsertions", "Bloom Filter 预期元素数量（影响内存占用）");
        COMMENTS.put("clientCache.bloomFilterFpp", "Bloom Filter 期望假阳性率（0.01 = 1%）");

        // ===== 网络配置 =====
        COMMENTS.put("network", "网络配置");
        COMMENTS.put("network.enabled", "是否启用 Hassium 自定义通道压缩（hassium:* 通道的 ZSTD 压缩）");
        COMMENTS.put("network.compressionAlgorithm", "压缩算法（hassium:zstd）");
        COMMENTS.put("network.compressionLevel", "压缩等级（默认3，速度优先）");
        COMMENTS.put("network.maxChunksPerTick", "每玩家每 tick 最大发送区块数");
        COMMENTS.put("network.globalPacketCompression", "是否启用全局包压缩（用 ZSTD 替换原版 Zlib，影响所有数据包）");
        COMMENTS.put("network.globalCompressionLevel", "全局压缩等级");
        COMMENTS.put("network.globalCompressionThreshold", "全局压缩阈值（字节）");
        COMMENTS.put("network.compressionBlacklist", "压缩黑名单（这些包不压缩）");
        COMMENTS.put("network.useContextCompression", "是否使用上下文压缩");
        COMMENTS.put("network.magiclessZstd", "是否使用无 magic 的 ZSTD 格式");
        COMMENTS.put("network.enablePacketAggregation", "是否启用包聚合");
        COMMENTS.put("network.aggregationMinBatchSize", "聚合最小批量大小");
        COMMENTS.put("network.aggregationMaxWaitTimeMs", "聚合最大等待时间（毫秒）");
        COMMENTS.put("network.aggregationMaxSize", "聚合最大大小（字节）");
        COMMENTS.put("network.enableCompactHeader", "是否启用紧凑包头");
        COMMENTS.put("network.serverChunkPushThreads", "服务端区块推送线程数");
        COMMENTS.put("network.clientChunkLoadThreads", "客户端区块加载线程数");
        COMMENTS.put("network.lightStripEnabled", "是否启用光照剥离");
        COMMENTS.put("network.backgroundThreads", "后台线程池大小");
        COMMENTS.put("network.maxChunksPerFrame", "每帧最多应用缓存区块数");
        COMMENTS.put("network.maxCallbacksPerFrame", "每帧最多主线程异步回调数");
        COMMENTS.put("network.metricsEnabled", "是否启用网络指标收集（流量、缓存命中率等）");
        COMMENTS.put("network.targetFPS", "目标 FPS（用于自适应每帧区块应用数调整）");
        COMMENTS.put("network.maxLightRecomputePerFrame", "每帧最多重算光照区块数");
        COMMENTS.put("network.dynamicThreadPoolEnabled", "是否启用动态线程池调整（根据队列负载自动扩缩容）");
        COMMENTS.put("network.minPushThreads", "最小推送线程数（动态调整时的下限）");
        COMMENTS.put("network.maxPushThreads", "最大推送线程数（动态调整时的上限）");

        // ===== 兼容性配置 =====
        COMMENTS.put("compat", "兼容性配置");
        COMMENTS.put("compat.requireClientMod", "是否强制要求客户端安装模组");
        COMMENTS.put("compat.autoDowngradeOnError", "出错时是否自动降级到原版");

        // ===== 调试配置 =====
        COMMENTS.put("debug", "调试日志配置（开启会产生大量日志，仅调试时使用）");
        COMMENTS.put("debug.metadataLogging", "元数据日志：接收、比对、应用");
        COMMENTS.put("debug.dispatcherLogging", "主线程调度器日志：回调队列操作");
        COMMENTS.put("debug.asyncLogging", "异步任务日志：后台任务执行");
        COMMENTS.put("debug.compressionLogging", "压缩日志：数据压缩和解压");
        COMMENTS.put("debug.chunkApplyLogging", "区块应用日志：区块加载到世界");
        COMMENTS.put("debug.networkLogging", "网络日志：数据包收发");
        COMMENTS.put("debug.cacheLogging", "缓存日志：缓存读写操作");
    }

    /**
     * 将对象序列化为带注释的 JSON 字符串
     */
    public static String toJson(Object src) {
        return toJson(src, Set.of());
    }

    /**
     * 将对象序列化为带注释的 JSON 字符串，跳过指定路径的配置项
     *
     * @param src       要序列化的对象
     * @param skipPaths 需要跳过的 JSON 路径前缀集合（如 "storage" 会跳过整个 storage 组）
     */
    public static String toJson(Object src, Set<String> skipPaths) {
        Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .setLenient()
                .create();

        JsonElement jsonElement = gson.toJsonTree(src);
        StringWriter stringWriter = new StringWriter();
        try {
            writeJsonWithComments(jsonElement, stringWriter, "", "", skipPaths);
        } catch (IOException e) {
            // Fallback to standard JSON
            return gson.toJson(src);
        }
        return stringWriter.toString();
    }

    /**
     * 获取当前环境应跳过的配置路径集合
     *
     * @param isPhysicalClient true=客户端环境, false=服务端环境
     * @return 需要跳过的路径前缀集合
     */
    public static Set<String> getSkipPaths(boolean isPhysicalClient) {
        if (isPhysicalClient) {
            // 客户端：跳过服务端专用配置
            return Set.of(
                    "storage",
                    "network.maxChunksPerTick",
                    "network.serverChunkPushThreads",
                    "network.dynamicThreadPoolEnabled",
                    "network.minPushThreads",
                    "network.maxPushThreads"
            );
        } else {
            // 服务端：跳过客户端专用配置
            return Set.of(
                    "clientCache",
                    "network.clientChunkLoadThreads",
                    "network.lightStripEnabled",
                    "network.backgroundThreads",
                    "network.maxChunksPerFrame",
                    "network.maxCallbacksPerFrame",
                    "network.targetFPS",
                    "network.maxLightRecomputePerFrame"
            );
        }
    }

    /**
     * 写入带注释的 JSON
     */
    private static void writeJsonWithComments(JsonElement element, Writer writer, String indent, String path, Set<String> skipPaths) throws IOException {
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            writer.write("{\n");

            // 先统计需要输出的字段数量（排除被跳过的字段）
            int totalVisible = 0;
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                String currentPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                if (!shouldSkip(currentPath, skipPaths)) {
                    totalVisible++;
                }
            }

            int count = 0;
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                String key = entry.getKey();
                JsonElement value = entry.getValue();
                String currentPath = path.isEmpty() ? key : path + "." + key;

                // 跳过当前环境不相关的配置项
                if (shouldSkip(currentPath, skipPaths)) {
                    continue;
                }

                // 写入注释
                String comment = COMMENTS.get(currentPath);
                if (comment != null) {
                    writer.write(indent + "  // " + comment + "\n");
                }

                // 写入键值对
                writer.write(indent + "  " + gson.toJson(key) + ": ");
                writeJsonWithComments(value, writer, indent + " ", currentPath, skipPaths);

                count++;
                if (count < totalVisible) {
                    writer.write(",");
                }
                writer.write("\n");
            }

            writer.write(indent + "}");
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (array.size() == 0) {
                writer.write("[]");
                return;
            }

            // 简单数组写在一行
            if (array.size() <= 5 && !hasNestedObjects(array)) {
                writer.write("[");
                for (int i = 0; i < array.size(); i++) {
                    if (i > 0) writer.write(", ");
                    writeJsonWithComments(array.get(i), writer, indent, path, skipPaths);
                }
                writer.write("]");
            } else {
                writer.write("[\n");
                for (int i = 0; i < array.size(); i++) {
                    writer.write(indent + "  ");
                    writeJsonWithComments(array.get(i), writer, indent + " ", path, skipPaths);
                    if (i < array.size() - 1) {
                        writer.write(",");
                    }
                    writer.write("\n");
                }
                writer.write(indent + "]");
            }
        } else {
            writer.write(gson.toJson(element));
        }
    }

    /**
     * 检查数组是否包含嵌套对象
     */
    private static boolean hasNestedObjects(JsonArray array) {
        for (JsonElement element : array) {
            if (element.isJsonObject() || element.isJsonArray()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断给定路径是否应被跳过
     * <p>
     * 匹配规则：路径等于某个 skipPath，或以某个 skipPath + "." 开前缀（子路径）
     */
    private static boolean shouldSkip(String currentPath, Set<String> skipPaths) {
        for (String skip : skipPaths) {
            if (currentPath.equals(skip) || currentPath.startsWith(skip + ".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从带注释的 JSON 字符串解析对象
     * 自动移除注释行，并与默认配置合并（缺失字段使用默认值）
     */
    public static <T> T fromJson(String json, Class<T> classOfT) {
        // 移除注释行
        String cleanJson = removeComments(json);
        Gson gson = new GsonBuilder()
                .setLenient()
                .create();

        // 先解析为 JsonObject，检查缺失字段并用默认值填充
        JsonElement loadedElement = gson.fromJson(cleanJson, JsonElement.class);
        if (loadedElement != null && loadedElement.isJsonObject() && classOfT == HassiumConfig.class) {
            JsonElement defaultElement = gson.toJsonTree(HassiumConfig.DEFAULT);
            if (defaultElement.isJsonObject()) {
                // 深度合并：缺失的字段从默认值补充
                JsonObject merged = deepMerge(defaultElement.getAsJsonObject(), loadedElement.getAsJsonObject());
                return gson.fromJson(merged, classOfT);
            }
        }

        return gson.fromJson(cleanJson, classOfT);
    }

    /**
     * 将加载的配置与默认配置合并，确保新增字段有正确的默认值
     */
    @SuppressWarnings("unchecked")
    private static <T> T mergeWithDefaults(Gson gson, T loaded, Class<T> classOfT) {
        try {
            // 将加载的配置转为 JsonObject
            JsonElement loadedElement = gson.toJsonTree(loaded);
            if (!loadedElement.isJsonObject()) return loaded;

            // 将默认配置转为 JsonObject
            JsonElement defaultElement = gson.toJsonTree(HassiumConfig.DEFAULT);
            if (!defaultElement.isJsonObject()) return loaded;

            // 深度合并：缺失的字段使用默认值
            JsonObject merged = deepMerge(defaultElement.getAsJsonObject(), loadedElement.getAsJsonObject());
            return gson.fromJson(merged, classOfT);
        } catch (Exception e) {
            // 合并失败时返回原始配置
            return loaded;
        }
    }

    /**
     * 深度合并两个 JsonObject，优先使用 override 中的值，缺失字段从 base 中补充
     */
    private static JsonObject deepMerge(JsonObject base, JsonObject override) {
        JsonObject result = new JsonObject();
        // 先添加 base 的所有字段
        for (Map.Entry<String, JsonElement> entry : base.entrySet()) {
            result.add(entry.getKey(), entry.getValue());
        }
        // 再用 override 覆盖
        for (Map.Entry<String, JsonElement> entry : override.entrySet()) {
            String key = entry.getKey();
            JsonElement overrideValue = entry.getValue();
            JsonElement baseValue = result.get(key);
            // 如果两边都是 JsonObject，递归合并
            if (baseValue != null && baseValue.isJsonObject() && overrideValue.isJsonObject()) {
                result.add(key, deepMerge(baseValue.getAsJsonObject(), overrideValue.getAsJsonObject()));
            } else {
                result.add(key, overrideValue);
            }
        }
        return result;
    }

    /**
     * 从带注释的 JSON 字符串解析对象
     */
    public static <T> T fromJson(String json, com.google.gson.reflect.TypeToken<T> typeToken) {
        String cleanJson = removeComments(json);
        Gson gson = new GsonBuilder()
                .setLenient()
                .create();
        return gson.fromJson(cleanJson, typeToken.getType());
    }

    /**
     * 移除 JSON 中的注释
     */
    private static String removeComments(String json) {
        StringBuilder result = new StringBuilder();
        String[] lines = json.split("\n");
        boolean inString = false;
        boolean escaped = false;

        for (String line : lines) {
            StringBuilder cleanLine = new StringBuilder();
            inString = false;
            escaped = false;

            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);

                if (escaped) {
                    cleanLine.append(c);
                    escaped = false;
                    continue;
                }

                if (c == '\\' && inString) {
                    cleanLine.append(c);
                    escaped = true;
                    continue;
                }

                if (c == '"') {
                    inString = !inString;
                    cleanLine.append(c);
                    continue;
                }

                if (!inString && c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                    // 找到注释，跳过本行剩余部分
                    break;
                }

                cleanLine.append(c);
            }

            String trimmed = cleanLine.toString().trim();
            if (!trimmed.isEmpty()) {
                result.append(trimmed).append("\n");
            }
        }

        return result.toString();
    }

    // 使用静态 Gson 实例用于简单序列化
    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .setLenient()
            .create();
}
