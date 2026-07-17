package io.github.limuqy.mc.hassium.network;

import com.github.luben.zstd.ZstdDictTrainer;
import io.github.limuqy.mc.hassium.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ZSTD 字典生命周期管理器
 * <p>
 * 支持两种字典：
 * 1. 区块字典（静态）：用区块数据训练，所有用户通用，内置或预训练
 * 2. 聚合包字典（动态）：运行时采样训练，因 mod 组合而异
 * <p>
 * 参考 NEB 的 DictionaryManager，实现：
 * 1. 采样收集 - 从聚合包中收集原始数据作为训练样本
 * 2. 异步训练 - 收集足够样本后异步训练字典
 * 3. 持久化 - 训练完成的字典保存到磁盘
 * 4. 分发 - 握手时从服务端同步到客户端
 */
public class DictionaryManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Dictionary");

    /**
     * 训练所需样本数量
     */
    private static final int SAMPLE_THRESHOLD = 2000;

    /**
     * 字典大小（64KB）
     */
    private static final int DICT_SIZE = 64 * 1024;

    /**
     * 单个样本最大大小（16KB）
     */
    private static final int MAX_SAMPLE_SIZE = 16 * 1024;

    /**
     * 样本总字节数上限（32MB）
     */
    private static final int MAX_SAMPLE_BYTES = 32 * 1024 * 1024;

    /**
     * 聚合包字典持久化路径
     */
    private static final Path AGGREGATION_DICT_PATH = Path.of("config", Constants.MOD_ID, "hassium_aggregation_dict.bin");

    /**
     * 区块字典（静态，所有用户通用）
     */
    private static volatile byte[] chunkDict;

    /**
     * 聚合包字典（动态，因 mod 组合而异）
     */
    private static volatile byte[] aggregationDict;

    /**
     * 是否是服务端
     */
    private static volatile boolean serverSide = false;

    /**
     * 字典推送回调接口
     */
    public interface DictionaryPushCallback {
        void pushToAllClients(byte[] dictionary);
    }

    /**
     * 字典推送回调（由平台层设置）
     */
    private static volatile DictionaryPushCallback pushCallback;

    /**
     * 训练样本（仅用于聚合包字典）
     */
    private static final List<byte[]> samples = new ArrayList<>();

    /**
     * 样本总字节数
     */
    private static int totalSampleBytes = 0;

    /**
     * 是否正在训练
     */
    private static final AtomicBoolean training = new AtomicBoolean(false);

    /**
     * 初始化字典管理器（服务端启动时调用）
     */
    public static void init() {
        serverSide = true;
        loadAggregationDictionary();
    }

    /**
     * 加载区块字典（静态，内置在 mod 中）
     * <p>
     * 区块字典打包在 mod 的 resources/assets/hassium/hassium-dictionary.bin 中，
     * 客户端和服务端都有，不需要通过网络传输。
     */
    public static void loadChunkDictionary() {
        try (var stream = DictionaryManager.class.getResourceAsStream("/assets/hassium/hassium-dictionary.bin")) {
            if (stream != null) {
                chunkDict = stream.readAllBytes();
                LOGGER.info("Loaded built-in chunk dictionary ({} bytes)", chunkDict.length);
            } else {
                LOGGER.info("No built-in chunk dictionary found at /assets/hassium/hassium-dictionary.bin");
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load chunk dictionary", e);
        }
    }

    /**
     * 加载聚合包字典（动态训练）
     */
    private static void loadAggregationDictionary() {
        try {
            if (Files.exists(AGGREGATION_DICT_PATH)) {
                aggregationDict = Files.readAllBytes(AGGREGATION_DICT_PATH);
                LOGGER.info("Loaded trained aggregation dictionary from disk ({} bytes)", aggregationDict.length);
            } else {
                LOGGER.info("No trained aggregation dictionary found, will train from samples");
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load aggregation dictionary from disk", e);
        }
    }

    /**
     * 获取区块字典
     *
     * @return 区块字典数据，如果没有返回 null
     */
    public static byte[] getChunkDict() {
        return chunkDict;
    }

    /**
     * 获取聚合包字典
     *
     * @return 聚合包字典数据，如果没有返回 null
     */
    public static byte[] getAggregationDict() {
        return aggregationDict;
    }

    /**
     * 设置区块字典（客户端收到服务端同步后调用）
     *
     * @param dict 字典数据
     */
    public static void setChunkDict(byte[] dict) {
        if (dict != null && dict.length > 0) {
            chunkDict = dict;
            LOGGER.debug("Chunk dictionary loaded ({} bytes)", dict.length);
        } else {
            chunkDict = null;
        }
    }

    /**
     * 设置聚合包字典（客户端收到服务端同步后调用）
     *
     * @param dict 字典数据
     */
    public static void setAggregationDict(byte[] dict) {
        if (dict != null && dict.length > 0) {
            aggregationDict = dict;
            LOGGER.debug("Aggregation dictionary loaded ({} bytes)", dict.length);
        } else {
            aggregationDict = null;
        }
    }

    /**
     * 设置字典推送回调
     *
     * @param callback 回调实现
     */
    public static void setPushCallback(DictionaryPushCallback callback) {
        pushCallback = callback;
    }

    /**
     * 推送字典给所有已连接的客户端
     *
     * @param dict 字典数据
     */
    private static void pushDictionaryToClients(byte[] dict) {
        DictionaryPushCallback callback = pushCallback;
        if (callback != null && dict != null && dict.length > 0) {
            try {
                callback.pushToAllClients(dict);
                LOGGER.debug("Pushed aggregation dictionary to all connected clients ({} bytes)", dict.length);
            } catch (Exception e) {
                LOGGER.error("Failed to push dictionary to clients", e);
            }
        }
    }

    /**
     * 是否正在采样（没有聚合包字典且未在训练）
     */
    public static boolean isSampling() {
        if (training.get()) return false;
        return serverSide && aggregationDict == null;
    }

    /**
     * 获取聚合包字典大小
     */
    public static int getAggregationDictSize() {
        byte[] dict = aggregationDict;
        return dict != null ? dict.length : 0;
    }

    /**
     * 获取样本数量
     */
    public static int getSampleCount() {
        synchronized (samples) {
            return samples.size();
        }
    }

    /**
     * 获取样本阈值
     */
    public static int getSampleThreshold() {
        return SAMPLE_THRESHOLD;
    }

    /**
     * 收集训练样本（仅用于聚合包字典）
     *
     * @param raw 原始数据（压缩前的聚合包数据）
     */
    public static void collectSample(byte[] raw) {
        if (!isSampling() || raw.length > MAX_SAMPLE_SIZE) {
            return;
        }
        synchronized (samples) {
            // 双重检查：训练可能在 isSampling() 和这里之间开始
            if (aggregationDict != null || training.get()) {
                return;
            }
            if (totalSampleBytes >= MAX_SAMPLE_BYTES) {
                return;
            }
            samples.add(raw);
            totalSampleBytes += raw.length;
            int count = samples.size();
            // 每 25% 里程碑记录采样进度（debug 级别）
            if (count == SAMPLE_THRESHOLD / 4
                    || count == SAMPLE_THRESHOLD / 2
                    || count == SAMPLE_THRESHOLD * 3 / 4
                    || count == SAMPLE_THRESHOLD) {
                LOGGER.debug("Aggregation dictionary sampling: {}/{} samples ({} KB)",
                        count, SAMPLE_THRESHOLD, totalSampleBytes / 1024);
            }
            if (count >= SAMPLE_THRESHOLD) {
                trainAsync();
            }
        }
    }

    /**
     * 异步训练聚合包字典
     */
    private static void trainAsync() {
        if (!training.compareAndSet(false, true)) {
            return;
        }
        final List<byte[]> snapshot;
        synchronized (samples) {
            snapshot = new ArrayList<>(samples);
            samples.clear();
            totalSampleBytes = 0;
        }
        CompletableFuture.runAsync(() -> {
            try {
                LOGGER.debug("Training aggregation dictionary from {} samples...", snapshot.size());
                int sampleSum = snapshot.stream().mapToInt(s -> s.length).sum();
                var trainer = new ZstdDictTrainer(sampleSum, DICT_SIZE);
                for (byte[] sample : snapshot) {
                    trainer.addSample(sample);
                }
                byte[] dict = trainer.trainSamples();

                // 先保存到磁盘
                saveAggregationDict(dict);

                // 推送字典给所有已连接的客户端（在启用字典压缩之前）
                // 这样客户端收到字典后，服务端再开始使用字典压缩
                pushDictionaryToClients(dict);

                // 等待一小段时间，确保客户端收到字典
                Thread.sleep(100);

                // 最后才启用字典压缩
                aggregationDict = dict;
                LOGGER.debug("Aggregation dictionary trained, saved, and pushed to clients ({} bytes from {} samples)",
                        dict.length, snapshot.size());
            } catch (Exception e) {
                LOGGER.error("Aggregation dictionary training failed", e);
            } finally {
                training.set(false);
            }
        });
    }

    /**
     * 持久化聚合包字典到磁盘
     */
    private static void saveAggregationDict(byte[] dict) {
        try {
            Files.createDirectories(AGGREGATION_DICT_PATH.getParent());
            Files.write(AGGREGATION_DICT_PATH, dict);
            LOGGER.debug("Aggregation dictionary saved to {}", AGGREGATION_DICT_PATH);
        } catch (IOException e) {
            LOGGER.error("Failed to save aggregation dictionary to disk", e);
        }
    }
}
