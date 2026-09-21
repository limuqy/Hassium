package io.github.limuqy.mc.hassium.shadow.storage;

import io.github.limuqy.mc.hassium.compression.HassiumCompression;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 半成品列（{@code Status < FEATURES}）不得冒充「有内容」。
 * <p>
 * 不变量：只有方块状态已定型的列可携带内容 hash / 可作 compare 基线。违反它会把
 * 半成品列喂成空基线（服务端逐段 hash 全不匹配 → 整柱 FULL），或把它当内容柱注入。
 */
class ShadowColumnContentTest {

    /** 原版 worldgen 写盘的中间态列（方块尚未定型）。 */
    private static final String[] PARTIAL_STATUSES = {
        "minecraft:structure_starts", "minecraft:biomes", "minecraft:carvers", "minecraft:noise",
    };

    /** 方块状态已定型的列（可作内容/基线）。 */
    private static final String[] FINAL_STATUSES = {
        "minecraft:features", "minecraft:initialize_light", "minecraft:light",
        "minecraft:spawn", "minecraft:full",
    };

    @BeforeAll
    static void init() {
        // ChunkStatus.byName 需要注册表；未 bootstrap 触碰会让 BuiltInRegistries 在测试 JVM 内永久
        // 不可用（连锁污染其它测试）。同一模式见 compat/ChunkShapeDilationTest。
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
        HassiumCompression.reset();
        HassiumCompression.initialize();
    }

    @Test
    @DisplayName("写盘判据：半成品列不给 hash，定型列保留")
    void effectiveHashWithholdsHashForPartialColumns() {
        for (String status : PARTIAL_STATUSES) {
            assertNull(ShadowColumnContent.effectiveHash(rawColumn(status), 0x1234L),
                    status + " 是半成品，不得携带内容 hash");
        }
        for (String status : FINAL_STATUSES) {
            assertEquals(0x1234L, ShadowColumnContent.effectiveHash(rawColumn(status), 0x1234L),
                    status + " 方块已定型，hash 必须保留");
        }
    }

    @Test
    @DisplayName("无 hash 时不扫 NBT（半成品/非法字节一律原样返回 null）")
    void effectiveHashShortCircuitsOnNullHash() {
        assertNull(ShadowColumnContent.effectiveHash(rawColumn("minecraft:full"), null));
        assertNull(ShadowColumnContent.effectiveHash(new byte[]{1, 2, 3}, null));
        assertNull(ShadowColumnContent.effectiveHash(null, null));
    }

    @Test
    @DisplayName("已解析列（读盘路径）判据与字节路径一致")
    void parsedColumnAgreesWithByteScan() {
        for (String status : PARTIAL_STATUSES) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Status", status);
            assertFalse(ShadowColumnContent.isContentBearing(tag), status);
        }
        for (String status : FINAL_STATUSES) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Status", status);
            assertTrue(ShadowColumnContent.isContentBearing(tag), status);
        }
        assertFalse(ShadowColumnContent.isContentBearing((CompoundTag) null), "null 列不得认作有内容");
    }

    @Test
    @DisplayName("非法/截断 NBT 不抛异常，降级为「无内容」")
    void malformedBytesDegradeToContentless() {
        assertFalse(ShadowColumnContent.isContentBearing(new byte[0]));
        assertFalse(ShadowColumnContent.isContentBearing(new byte[]{0x0A, 0x00}));
        assertFalse(ShadowColumnContent.isContentBearing(new byte[]{0x00, 0x00, 0x00}), "根非 compound");
        assertNull(ShadowColumnContent.effectiveHash(new byte[]{0x0A, 0x00, 0x01}, 7L));
        assertNull(ShadowColumnContent.effectiveHash(new byte[]{0x0A, 0x00, 0x00, 0x08}, 7L),
                "声明了字符串键但字节不足");
    }

    @Test
    @DisplayName("缺少 Status 键的列不得认作有内容")
    void columnWithoutStatusIsContentless() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("xPos", 3);
        assertFalse(ShadowColumnContent.isContentBearing(tag));
        assertNull(ShadowColumnContent.effectiveHash(nbt("xPos", 3), 5L));
    }

    /** 顶级 compound：{@code {Status: <status>}}。 */
    private static byte[] rawColumn(String status) {
        return nbt("Status", status);
    }

    /** 顶级 compound：{@code {<key>: <string|int>}}（手写 NBT 字节，不经 ChunkSerializer）。 */
    private static byte[] nbt(String key, Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x0A); // TAG_Compound
        writeU16(out, 0); // 根名空
        if (value instanceof String s) {
            out.write(0x08); // TAG_String
            writeName(out, key);
            byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
            writeU16(out, utf8.length);
            out.writeBytes(utf8);
        } else {
            out.write(0x03); // TAG_Int
            writeName(out, key);
            int v = (Integer) value;
            out.writeBytes(new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v});
        }
        out.write(0x00); // TAG_End
        return out.toByteArray();
    }

    private static void writeName(ByteArrayOutputStream out, String name) {
        byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
        writeU16(out, utf8.length);
        out.writeBytes(utf8);
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
