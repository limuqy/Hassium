package io.github.limuqy.mc.hassium.compression;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * zstd 字典训练器样本约束回归（经验边界：1.5.5-7 fastCover 对样本数有下限，
 * 6 个大样本被拒（"nb of samples too low"）、30+ 个样本可训）。
 * <p>
 * {@code DictionaryCorpus}/{@code DictionaryManager} 的最低数据集口径
 * （重训练样本数 ≥ 32）以此钉死：语料攒批/保留策略变更时，本用例保证
 * 「最低数据集档位」仍然可训。
 */
class DictTrainerMinimumCorpusTest {

    private static final int DICT_SIZE = 64 * 1024;

    private void train(int sampleCount, int sampleSize) {
        var trainer = ZstdRuntimeBridge.newDictTrainer(sampleCount * sampleSize, DICT_SIZE);
        Random rnd = new Random(42);
        for (int i = 0; i < sampleCount; i++) {
            byte[] sample = new byte[sampleSize];
            rnd.nextBytes(sample);
            trainer.addSample(sample);
        }
        byte[] dict = trainer.trainSamples();
        assertNotNull(dict);
        assertTrue(dict.length > 0);
    }

    @Test
    void minimumCorpusProfileTrains() {
        // 冒烟/最低数据集档位：~33 个 8KB 样本（总 264KB ≥ 256KB）必须可训
        train(33, 8 * 1024);
    }

    @Test
    void refusesTinySampleCounts() {
        // 6 个大样本（总量同样 ~264KB）被训练器拒绝——样本数下限独立于总字节
        assertThrows(Exception.class, () -> train(6, 44 * 1024));
    }
}
