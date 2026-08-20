package io.github.limuqy.mc.hassium.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigCommentsTest {
    @Test
    void bilingualJoinsZhThenEnOnSeparateLines() {
        assertEquals("中文\nEnglish", ConfigComments.bilingual("中文", "English"));
        assertEquals("中文", ConfigComments.bilingual("中文", ""));
        assertEquals("English", ConfigComments.bilingual("", "English"));
        assertArrayEquals(new String[]{"中文", "English"}, ConfigComments.lines("中文\nEnglish"));
    }

    @Test
    void schemaSaveWritesBilingualTomlComments(@TempDir Path root) throws Exception {
        Path file = root.resolve("hassium/hassium-client.toml");
        Files.createDirectories(file.getParent());
        // 借 FabricTomlConfigIO 的 schema 写出路径：写 CLIENT 默认值并断言备注分行
        try (com.electronwill.nightconfig.core.file.CommentedFileConfig cfg =
                     com.electronwill.nightconfig.core.file.CommentedFileConfig.builder(file).sync().build()) {
            for (ConfigEntry<?> entry : ConfigSchema.clientEntries()) {
                if (entry.path().equals("net.enabled")) {
                    cfg.setComment(entry.path(), entry.comment());
                    cfg.set(entry.path(), entry.defaultValue());
                }
            }
            cfg.save();
        }
        String text = Files.readString(file);
        assertTrue(text.contains("是否启用客户端网络核心"), text);
        assertTrue(text.contains("Enable client network core"), text);
        assertTrue(text.indexOf("是否启用客户端网络核心") < text.indexOf("Enable client network core"), text);
    }
}
