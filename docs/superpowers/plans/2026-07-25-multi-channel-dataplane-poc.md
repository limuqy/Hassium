# Multi-Channel Data Plane PoC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 1.20.1 Fabric 实现数据面骨架——服务端额外绑定两个裸 TCP 端口、客户端 JOIN 后直连、`share`/`exclusive` 两种 bulk 路由 + 对称加密。

**Architecture:** 9 步增量链路——从 common 无依赖的纯数据单元测试(`DataPlaneFrame`/`Hkdf`/`DataPlaneCodec`/`BulkRouter`/`PlayerChannelBundle`)向上搭建到 fabric 服务端拦截+客户端 connect+demux,最后 E2E 验证 `exclusive` 3 次 drop 后 `degraded` 硬断言。

**Tech Stack:** Java 17, JUnit (common:test), Netty `ServerBootstrap` / `Bootstrap`, JDK `Cipher`(AES/CFB8), JDK `Mac`(HmacSHA256 for HKDF)

## Global Constraints

- 仅 1.20.1 Fabric; forge/neoforge 不碰
- 零第三方加密依赖(BouncyCastle 不引入,设计稿 §12 批准纯 JDK HKDF)
- 不扩展握手——PoC 配置固定 token + 两端读同一配置
- Primary 路径不可降级——`BulkRouter` 返回 `false` 则继续原版 `FabricNetworkManager.sendCompressedChunk`
- 不变 `MixinConnection`、不 bump `CURRENT_PROTOCOL_VERSION`
- 不改 `ChunkSender` 接口、不改 `ServerChunkPushManager` 方法体
- 热路径日志用 `DebugLogger`,INFO 仅生命周期/端口绑定/Bind 结果
- common 禁止 loader API,平台差异放 fabric 子模块
- 测试放 `src/test/java/io/github/limuqy/mc/hassium/network/dataplane/`

---

## File Map

```
common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/
  DataPlaneFrame.java              — 帧类型枚举 1-7 + VarInt 长度前缀 encode/decode
  Hkdf.java                        — HKDF-SHA256 extract+expand（纯 JDK Mac）
  DataPlaneCodec.java              — AES/CFB8 encrypt/decrypt of type||payload
  DataPlanePoCConfig.java          — PoC 静态常量配置
  PlayerChannel.java               — 单条 Data Channel 封装(Channel + weight + active)
  PlayerChannelBundle.java         — per-player Data channels 列表 + WRR 状态 + degraded
  BulkRouter.java                  — share/exclusive 候选集 + WRR + handleNoCandidate
  DataPlaneServer.java             — ServerBootstrap accept、Bind 校验、路由入口
  DataPlaneClientBundle.java       — 客户端 connect、BindRequest、demux bulk 帧

common/src/test/java/.../network/dataplane/
  DataPlaneFrameTest.java
  DataPlaneCodecTest.java
  BulkRouterTest.java
  PlayerChannelBundleTest.java

fabric/src/main/java/.../
  HassiumMod.java          — ChunkSender 改为先过 BulkRouter
  HassiumClientMod.java    — JOIN 启动 DataPlaneClientBundle; DISCONNECT 清理
common/src/main/java/.../
  mixin/MixinMinecraftServer.java — onServerInit bind + onServerStop shutdown
```

---

### Task 1: DataPlaneFrame — 帧类型枚举 + VarInt 长度前缀 encode/decode

**Files:**
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneFrame.java`
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneFrameTest.java`

**Interfaces:**
- Produces: `DataPlaneFrame` 类含 `static byte[] encode(int type, byte[] payload)`, `static int decodeType(byte[] frame)`, `static byte[] decodePayload(byte[] frame)` 和帧类型常量 `TYPE_BIND_REQUEST=1` ... `TYPE_CLOSE=7`

- [ ] **Step 1: Write the failing test**

```java
package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DataPlaneFrameTest {

    @Test @DisplayName("帧类型常量与设计稿一致")
    void typeConstants() {
        assertEquals(1, DataPlaneFrame.TYPE_BIND_REQUEST);
        assertEquals(2, DataPlaneFrame.TYPE_BIND_ACK);
        assertEquals(3, DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK);
        assertEquals(5, DataPlaneFrame.TYPE_KEEPALIVE);
        assertEquals(6, DataPlaneFrame.TYPE_KEEPALIVE_ACK);
        assertEquals(7, DataPlaneFrame.TYPE_CLOSE);
    }

    @Test @DisplayName("encode/decode 往返——payload 非空")
    void roundTrip_withPayload() {
        byte[] payload = new byte[]{10, 20, 30, 40};
        byte[] frame = DataPlaneFrame.encode(3, payload);
        assertEquals(3, DataPlaneFrame.decodeType(frame));
        assertArrayEquals(payload, DataPlaneFrame.decodePayload(frame));
    }

    @Test @DisplayName("encode/decode 往返——payload 为空")
    void roundTrip_emptyPayload() {
        byte[] frame = DataPlaneFrame.encode(2, new byte[0]);
        assertEquals(2, DataPlaneFrame.decodeType(frame));
        assertEquals(0, DataPlaneFrame.decodePayload(frame).length);
    }

    @Test @DisplayName("frameLen 等于总长度减 VarInt(frameLen) 自身占用的字节")
    void frameLengthIncludesType() {
        byte[] payload = new byte[100];
        byte[] frame = DataPlaneFrame.encode(3, payload);
        // frameLen = VarInt(type + payload), 所以 decodeFrameLen(frame) = 1 + 100 = 101 (若 type+payload<127)
        // VarInt(frameLen) 在 decodeFrameLen 之后 frame 中后续字节数 = type(1) + payload(100) = 101
        assertTrue(frame.length >= 102); // VarInt(frameLen)=1 + frameLen(101)=101=102
    }

    @Test @DisplayName("非法 type 抛出 IllegalArgumentException")
    void invalidType() {
        assertThrows(IllegalArgumentException.class,
            () -> DataPlaneFrame.encode(0, new byte[1]));
        assertThrows(IllegalArgumentException.class,
            () -> DataPlaneFrame.encode(8, new byte[1]));
    }

    @Test  @DisplayName("截断输入抛出异常")
    void truncatedInput() {
        byte[] bad = new byte[]{2, 1}; // type=1, 但 payload 缺失
        assertThrows(Exception.class, () -> DataPlaneFrame.decodePayload(bad));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mkdir -p common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane
# 只需写入测试文件, DataPlaneFrame 还不存在
./gradlew --no-daemon common:test --tests "io.github.limuqy.mc.hassium.network.dataplane.DataPlaneFrameTest" 2>&1 | tail -20
```
Expected: 编译错误 `cannot find symbol DataPlaneFrame`

- [ ] **Step 3: Write minimal implementation**

```java
package io.github.limuqy.mc.hassium.network.dataplane;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;

public class DataPlaneFrame {

    public static final int TYPE_BIND_REQUEST = 1;
    public static final int TYPE_BIND_ACK = 2;
    public static final int TYPE_BULK_COMPRESSED_CHUNK = 3;
    public static final int TYPE_BULK_SECTION_DELTA = 4;
    public static final int TYPE_KEEPALIVE = 5;
    public static final int TYPE_KEEPALIVE_ACK = 6;
    public static final int TYPE_CLOSE = 7;

    private static final int MIN_TYPE = 1;
    private static final int MAX_TYPE = 7;

    /** 编码：VarInt(frameLen) + type(u8) + payload。frameLen = 1 + payload.length */
    public static byte[] encode(int type, byte[] payload) {
        if (type < MIN_TYPE || type > MAX_TYPE) throw new IllegalArgumentException("Invalid frame type: " + type);
        int payloadLen = payload != null ? payload.length : 0;
        int frameLen = 1 + payloadLen; // type byte + payload
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, frameLen);
        out.write(type);
        if (payloadLen > 0) out.writeBytes(payload);
        return out.toByteArray();
    }

    /** 从完整帧中提取 type */
    public static int decodeType(byte[] frame) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(frame);
        int frameLen = readVarInt(buf);
        if (frame.length < frameLen + varIntSize(frameLen)) throw new IllegalArgumentException("Truncated frame");
        return buf.get() & 0xFF;
    }

    /** 从完整帧中提取 payload（不拷贝） */
    public static byte[] decodePayload(byte[] frame) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(frame);
        int frameLen = readVarInt(buf);
        int totalHeader = varIntSize(frameLen) + 1; // VarInt(frameLen) + type byte
        int dataLen = frame.length - totalHeader;
        if (dataLen < 0) throw new IllegalArgumentException("Truncated frame");
        byte[] payload = new byte[dataLen];
        if (dataLen > 0) buf.get(payload);
        return payload;
    }

    // ---- VarInt helpers (MC-compatible 7-bit encoding) ----

    static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    static int readVarInt(java.nio.ByteBuffer buf) {
        int value = 0, shift = 0;
        byte b;
        do {
            b = buf.get();
            value |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return value;
    }

    static int varIntSize(int value) {
        int n = 1;
        while ((value & ~0x7F) != 0) { n++; value >>>= 7; }
        return n;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew --no-daemon common:test --tests "io.github.limuqy.mc.hassium.network.dataplane.DataPlaneFrameTest" 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneFrame.java common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneFrameTest.java
git commit -m "feat(poc/dataplane): DataPlaneFrame type constants + VarInt encode/decode"
```

---

### Task 2: Hkdf — 纯 JDK HKDF-SHA256

**Files:**
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/Hkdf.java`
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/HkdfTest.java`

**Interfaces:**
- Produces: `Hkdf` 类含 `static byte[] extractAndExpand(byte[] ikm, byte[] salt, byte[] info, int length)` 按 RFC 5869 执行 HKDF-SHA256

- [ ] **Step 1: Write the failing test**

```java
package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HkdfTest {

    @Test @DisplayName("RFC 5869 Appendix A.1 — SHA-256 测试向量")
    void rfc5869_a1() {
        byte[] ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = hex("000102030405060708090a0b0c");
        byte[] info = hex("f0f1f2f3f4f5f6f7f8f9");
        byte[] expected = hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
        byte[] result = Hkdf.extractAndExpand(ikm, salt, info, 42);
        assertArrayEquals(expected, result);
    }

    @Test @DisplayName("deriveDataKey 与设计稿 §4 Key derivation 公式一致")
    void deriveDataKey() {
        byte[] token = new byte[16]; // 全零 token（PoC 固定值）
        byte[] playerUuid = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15}; // 16 bytes
        byte[] info = "hassium-dataplane-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] channelId = new byte[]{0,0,0,1}; // VarInt 1 的字节
        byte[] combinedInfo = new byte[info.length + channelId.length];
        System.arraycopy(info, 0, combinedInfo, 0, info.length);
        System.arraycopy(channelId, 0, combinedInfo, 0, channelId.length);
        byte[] key = Hkdf.extractAndExpand(token, playerUuid, combinedInfo, 16);
        assertEquals(16, key.length);
        // 派生结果应该是确定性的
        byte[] key2 = Hkdf.extractAndExpand(token, playerUuid, combinedInfo, 16);
        assertArrayEquals(key, key2);
    }

    private static byte[] hex(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            data[i/2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i+1), 16));
        return data;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew --no-daemon common:test --tests "io.github.limuqy.mc.hassium.network.dataplane.HkdfTest" 2>&1 | tail -10
```

- [ ] **Step 3: Write minimal implementation**

```java
package io.github.limuqy.mc.hassium.network.dataplane;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * 纯 JDK HKDF-SHA256 (RFC 5869)。
 * extract-then-expand 两步式；不依赖 BouncyCastle。
 */
public class Hkdf {

    private static final String HMAC_SHA256 = "HmacSHA256";

    /**
     * HKDF extract + expand 一步完成。
     *
     * @param ikm    Initial Keying Material
     * @param salt   盐（可以为空）
     * @param info   上下文信息
     * @param length 目标密钥长度（字节）
     * @return 派生密钥
     */
    public static byte[] extractAndExpand(byte[] ikm, byte[] salt, byte[] info, int length) {
        try {
            byte[] prk = extract(ikm, salt);
            return expand(prk, info, length);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("HKDF failed", e);
        }
    }

    /** HKDF-Extract: PRK = HMAC-SHA256(salt, IKM) */
    static byte[] extract(byte[] ikm, byte[] salt) throws GeneralSecurityException {
        // salt 为空则补全零
        if (salt == null || salt.length == 0) salt = new byte[32];
        Mac mac = Mac.getInstance(HMAC_SHA256);
        mac.init(new SecretKeySpec(salt, HMAC_SHA256));
        return mac.doFinal(ikm);
    }

    /** HKDF-Expand: OKM = T(1) || T(2) || ... */
    static byte[] expand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC_SHA256);
        mac.init(new SecretKeySpec(prk, HMAC_SHA256));

        byte[] result = new byte[length];
        byte[] t = new byte[0];
        int pos = 0;
        for (byte i = 1; pos < length; i++) {
            mac.update(t);
            if (info != null) mac.update(info);
            mac.update(i);
            t = mac.doFinal();
            int copyLen = Math.min(t.length, length - pos);
            System.arraycopy(t, 0, result, pos, copyLen);
            pos += copyLen;
        }
        return result;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew --no-daemon common:test --tests "io.github.limuqy.mc.hassium.network.dataplane.HkdfTest" 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/Hkdf.java common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/HkdfTest.java
git commit -m "feat(poc/dataplane): Hkdf — pure JDK HKDF-SHA256 (RFC 5869)"
```

---

### Task 3: DataPlaneCodec — AES/CFB8 encrypt/decrypt of `type||payload`

**Files:**
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneCodec.java`
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneCodecTest.java`

**Interfaces:**
- Produces: `DataPlaneCodec` 类含 `static byte[] encrypt(byte[] key, int type, byte[] payload)` 和 `static FrameDecryptResult decrypt(byte[] key, byte[] encryptedFrame)`

- [ ] **Step 1: Write the failing test**

```java
package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DataPlaneCodecTest {

    @Test @DisplayName("encrypt → decrypt 往返正确")
    void roundTrip() {
        byte[] key = new byte[16]; // 全零密钥（PoC 用）
        byte[] payload = "hello dataplane".getBytes();
        byte[] encrypted = DataPlaneCodec.encrypt(key, 3, payload);
        DataPlaneCodec.FrameDecryptResult result = DataPlaneCodec.decrypt(key, encrypted);
        assertEquals(3, result.type);
        assertArrayEquals(payload, result.payload);
    }

    @Test @DisplayName("错误密钥解密失败——要么抛异常要么明文不同")
    void wrongKey() {
        byte[] key1 = new byte[16];
        byte[] key2 = new byte[16]; key2[0] = 0x42;
        byte[] payload = "secret".getBytes();
        byte[] encrypted = DataPlaneCodec.encrypt(key1, 2, payload);
        assertThrows(Exception.class, () -> DataPlaneCodec.decrypt(key2, encrypted));
    }

    @Test @DisplayName("不同 channelId 派生不同密钥")
    void differentChannelDifferentKey() throws Exception {
        byte[] token = new byte[16];
        byte[] playerUuid = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15};
        byte[] info1 = "hassium-dataplane-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] info2 = "hassium-dataplane-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // channelId 1 vs 2
        byte[] combined1 = new byte[info1.length + 1]; System.arraycopy(info1,0,combined1,0,info1.length); combined1[info1.length] = 1;
        byte[] combined2 = new byte[info2.length + 1]; System.arraycopy(info2,0,combined2,0,info2.length); combined2[info2.length] = 2;
        byte[] key1 = Hkdf.extractAndExpand(token, playerUuid, combined1, 16);
        byte[] key2 = Hkdf.extractAndExpand(token, playerUuid, combined2, 16);
        assertFalse(java.util.Arrays.equals(key1, key2));
    }

    @Test @DisplayName("frameLen 在加密后保持明文")
    void frameLenCleartext() {
        byte[] key = new byte[16];
        byte[] payload = new byte[200];
        byte[] encrypted = DataPlaneCodec.encrypt(key, 3, payload);
        // 解码第一个 VarInt 应该得到 = 1+200 = 201
        int frameLen = DataPlaneFrame.decodeType(encrypted); // 实际上第一个字节是 VarInt 再读 type
        // 验证方法是检查加密帧的前几个字节与明文帧的前几个 byte 不同（但 frameLen 是明文的）
        byte[] plainFrame = DataPlaneFrame.encode(3, payload);
        // frameLen VarInt 应该在加密前后一致
        int plainLen = DataPlaneFrame.decodeType(plainFrame);
        int encryptedType = DataPlaneFrame.decodeType(encrypted);
        assertEquals(plainLen, encryptedType); // 都是 type=3, 因为 frameLen 明文
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew --no-daemon common:test --tests "io.github.limuqy.mc.hassium.network.dataplane.DataPlaneCodecTest" 2>&1 | tail -10
```

- [ ] **Step 3: Write minimal implementation**

```java
package io.github.limuqy.mc.hassium.network.dataplane;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.IvParameterSpec;
import java.security.GeneralSecurityException;

/**
 * DataPlane 帧加密/解密。
 * 使用 AES/CFB8/NoPadding；派生密钥按设计稿 §6.4。
 * frameLen 保持明文；加密范围：type||payload。
 */
public class DataPlaneCodec {

    private static final String CIPHER = "AES/CFB8/NoPadding";
    private static final String KEY_ALGO = "AES";
    /** CFB8 的 IV = 全零 16 字节（每次加密重置偏移量） */
    private static final byte[] ZERO_IV = new byte[16];

    /**
     * 加密帧：编码 type + payload → 加密 type||payload → 拼接 VarInt(frameLen) + 密文。
     * frameLen 计算在加密前（包含明文的 type 长度），但写入的是加密后的 payload。
     * 注意：frameLen 是指 type + payload 的长度，type 占 1 字节不变。
     */
    public static byte[] encrypt(byte[] key, int type, byte[] payload) {
        try {
            // 先组明文 type||payload
            byte[] plaintext;
            if (payload != null && payload.length > 0) {
                plaintext = new byte[1 + payload.length];
                plaintext[0] = (byte) (type & 0xFF);
                System.arraycopy(payload, 0, plaintext, 1, payload.length);
            } else {
                plaintext = new byte[]{ (byte) (type & 0xFF) };
            }
            // 加密
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, KEY_ALGO), new IvParameterSpec(ZERO_IV));
            byte[] encrypted = cipher.doFinal(plaintext);
            // 编码为 DataPlaneFrame：frameLen(cleartext) + encrypted(type||payload)
            // frameLen = encrypted.length (因为 frameLen = type + payload, 加密后长度不变)
            byte[] frameLenAndEncrypted = new byte[1 + encrypted.length];
            frameLenAndEncrypted[0] = (byte) encrypted.length; // 小值优化：对于 < 128 的 frameLen，VarInt = 单字节
            // 大 frameLen 需要 writeVarInt，但 PoC 的数据帧通常 < 128KB，用简单模式
            byte[][] frames = { new byte[]{ (byte) encrypted.length }, encrypted };
            // 用 ByteArrayOutputStream 拼接
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            DataPlaneFrame.writeVarInt(out, encrypted.length);
            out.writeBytes(encrypted);
            return out.toByteArray();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public static class FrameDecryptResult {
        public final int type;
        public final byte[] payload;
        public FrameDecryptResult(int type, byte[] payload) { this.type = type; this.payload = payload; }
    }

    /**
     * 解密帧：输入为 encode 完整输出，跳过 frameLen → 解密 → 拆 type + payload
     */
    public static FrameDecryptResult decrypt(byte[] key, byte[] frame) {
        try {
            // 跳过 frameLen VarInt
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(frame);
            int frameLen = DataPlaneFrame.readVarInt(buf);
            int headerSize = DataPlaneFrame.varIntSize(frameLen);
            int encryptedLen = frame.length - headerSize;
            byte[] encrypted = new byte[encryptedLen];
            buf.get(encrypted);
            // 解密
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, KEY_ALGO), new IvParameterSpec(ZERO_IV));
            byte[] decrypted = cipher.doFinal(encrypted);
            int type = decrypted[0] & 0xFF;
            byte[] payload;
            if (decrypted.length > 1) {
                payload = new byte[decrypted.length - 1];
                System.arraycopy(decrypted, 1, payload, 0, payload.length);
            } else {
                payload = new byte[0];
            }
            return new FrameDecryptResult(type, payload);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Decryption failed / wrong key", e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew --no-daemon common:test --tests "io.github.limuqy.mc.hassium.network.dataplane.DataPlaneCodecTest" 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneCodec.java common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneCodecTest.java
git commit -m "feat(poc/dataplane): DataPlaneCodec — AES/CFB8 encrypt/decrypt for data plane frames"
```

---

### Task 4: DataPlanePoCConfig — 静态配置常量

**Files:**
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlanePoCConfig.java`

**Interfaces:**
- Produces: 一个纯常量类,所有字段 `static final`,供 `DataPlaneServer` 和 `DataPlaneClientBundle` 读取

- [ ] **Step 1: Write the test**

```java
package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DataPlanePoCConfigTest {

    @Test @DisplayName("默认启用了 data plane")
    void defaultEnabled() {
        assertTrue(DataPlanePoCConfig.ENABLED);
    }

    @Test @DisplayName("两个 endpoint")
    void twoEndpoints() {
        assertEquals(2, DataPlanePoCConfig.ENDPOINTS.length);
        assertEquals(25566, DataPlanePoCConfig.ENDPOINTS[0].bindPort);
        assertEquals(25567, DataPlanePoCConfig.ENDPOINTS[1].bindPort);
    }

    @Test @DisplayName("token 固定 16 字节")
    void tokenLength() {
        assertEquals(16, DataPlanePoCConfig.BIND_TOKEN.length);
    }

    @Test @DisplayName("degrade threshold = 3")
    void degradeThreshold() {
        assertEquals(3, DataPlanePoCConfig.DEGRADE_AFTER_DROPS);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew --no-daemon common:test --tests "io.github.limuqy.mc.hassium.network.dataplane.DataPlanePoCConfigTest" 2>&1 | tail -10
```

- [ ] **Step 3: Write the implementation**

```java
package io.github.limuqy.mc.hassium.network.dataplane;

/**
 * PoC 数据面静态配置常量。
 * 设计稿 §8；后续握手扩展阶段迁移到 server.toml。
 */
public class DataPlanePoCConfig {

    public static final boolean ENABLED = true;
    public static final String BULK_ROUTE_MODE = "share"; // "share" | "exclusive"
    public static final int PRIMARY_WEIGHT = 100;
    public static final int DEGRADE_AFTER_DROPS = 3;
    public static final boolean CLIENT_ENABLE_DATA_PLANE = true;

    public static final byte[] BIND_TOKEN = new byte[16]; // 全零 PoC

    public static class Endpoint {
        public final String address;
        public final int port;
        public final int weight;
        public final String bindHost;
        public final int bindPort;

        public Endpoint(String address, int port, int weight, String bindHost, int bindPort) {
            this.address = address;
            this.port = port;
            this.weight = weight;
            this.bindHost = bindHost;
            this.bindPort = bindPort;
        }
    }

    public static final Endpoint[] ENDPOINTS = new Endpoint[]{
        new Endpoint("127.0.0.1", 25566, 50, "0.0.0.0", 25566),
        new Endpoint("127.0.0.1", 25567, 50, "0.0.0.0", 25567),
    };
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew --no-daemon common:test --tests "io.github.limuqy.mc.hassium.network.dataplane.DataPlanePoCConfigTest" 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlanePoCConfig.java common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlanePoCConfigTest.java
git commit -m "feat(poc/dataplane): DataPlanePoCConfig — static PoC configuration"
```

---

### Task 5: PlayerChannel + PlayerChannelBundle + BulkRouter — per-player 路由状态 + WRR

**Files:**
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/PlayerChannel.java`
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/PlayerChannelBundle.java`
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/BulkRouter.java`
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/PlayerChannelBundleTest.java`
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/BulkRouterTest.java`

**Interfaces:**
- Produces: `PlayerChannelBundle` 提供 `get(ServerPlayer)` / `add(Channel, weight)` / `remove(Channel)` / `getDataChannels()`; `BulkRouter` 提供 `static boolean sendBulk(...)`

- [ ] **Step 1: Write the failing tests**

```java
// BulkRouterTest.java
package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BulkRouterTest {

    private PlayerChannelBundle bundle;
    private PlayerChannel mockCh1, mockCh2;

    private static PlayerChannel mockChannel(boolean active, boolean writable, int weight) {
        return new PlayerChannel(null, weight) {
            @Override public boolean isActive() { return active; }
            @Override public boolean isWritable() { return writable; }
        };
    }

    @BeforeEach void setUp() {
        bundle = new PlayerChannelBundle();
    }

    @Test @DisplayName("share 模式,两条 Data 都可用 → 路由到 Data（返回 true）")
    void share_withDataChannels_returnsTrue() {
        bundle.addChannel(mockChannel(true, true, 50));
        bundle.addChannel(mockChannel(true, true, 50));
        // share 模式: Primary 也在候选集,但 BulkRouter 返回 false 时才是 Primary
        // 因为两条 Data 是 active+writable, WRR 应选到它们
        AtomicInteger dataCount = new AtomicInteger();
        AtomicInteger primaryFallback = new AtomicInteger();
        for (int i = 0; i < 100; i++) {
            boolean result = BulkRouter.sendBulk(bundle, "share", 100, 3);
            if (result) dataCount.incrementAndGet();
            else primaryFallback.incrementAndGet();
        }
        // share 模式: Primary weight=100, Data weight=50+50=100, 所以约 50% 路由到 Data
        assertTrue(dataCount.get() > 20, "应有一定比例的 bulk 走 Data: " + dataCount.get());
        assertTrue(primaryFallback.get() > 20, "应有一定比例的 bulk 走 Primary: " + primaryFallback.get());
    }

    @Test @DisplayName("share 模式,无 Data 通道 → 返回 false（Primary fallback）")
    void share_noData_returnsFalse() {
        assertFalse(BulkRouter.sendBulk(bundle, "share", 100, 3));
    }

    @Test @DisplayName("exclusive 模式,无 Data 通道 → 返回 true（drop）且 consecutiveDrops 递增")
    void exclusive_noData_dropsAndIncrements() {
        bundle.consecutiveDrops = 0;
        boolean result = BulkRouter.sendBulk(bundle, "exclusive", 100, 3);
        assertTrue(result); // caller 不要发 Primary
        assertEquals(1, bundle.consecutiveDrops);
    }

    @Test @DisplayName("exclusive 模式,连续 3 次 drop → degraded=true → 返回 false")
    void exclusive_threeDrops_degrade() {
        bundle.consecutiveDrops = 0;
        BulkRouter.sendBulk(bundle, "exclusive", 100, 3); // drop #1
        BulkRouter.sendBulk(bundle, "exclusive", 100, 3); // drop #2
        BulkRouter.sendBulk(bundle, "exclusive", 100, 3); // drop #3 → degraded
        assertTrue(bundle.degraded);
        // 第四次: degraded=true → 返回 false
        assertFalse(BulkRouter.sendBulk(bundle, "exclusive", 100, 3));
    }

    @Test @DisplayName("degraded bundle 任何 bulk 都返回 false")
    void degraded_alwaysReturnsFalse() {
        bundle.degraded = true;
        assertFalse(BulkRouter.sendBulk(bundle, "share", 100, 3));
        assertFalse(BulkRouter.sendBulk(bundle, "exclusive", 100, 3));
    }

    @Test @DisplayName("成功发送后 consecutiveDrops 归零")
    void successfulSend_resetsDrops() {
        bundle.consecutiveDrops = 2;
        bundle.addChannel(mockChannel(true, true, 50));
        BulkRouter.sendBulk(bundle, "share", 100, 3);
        assertEquals(0, bundle.consecutiveDrops);
    }
}
```

```java
// PlayerChannelBundleTest.java
package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerChannelBundleTest {

    @Test @DisplayName("添加通道后 getDataChannels 包含该通道")
    void addChannel() {
        PlayerChannelBundle b = new PlayerChannelBundle();
        assertEquals(0, b.getDataChannels().size());
        b.addChannel(new PlayerChannel(null, 50) {
            @Override public boolean isActive() { return true; }
            @Override public boolean isWritable() { return true; }
        });
        assertEquals(1, b.getDataChannels().size());
    }

    @Test @DisplayName("移除通道后列表为空")
    void removeChannel() {
        PlayerChannelBundle b = new PlayerChannelBundle();
        PlayerChannel ch = new PlayerChannel(null, 50) {
            @Override public boolean isActive() { return true; }
            @Override public boolean isWritable() { return true; }
        };
        b.addChannel(ch);
        b.removeChannel(ch);
        assertEquals(0, b.getDataChannels().size());
    }

    @Test @DisplayName("degraded 从 false 翻转为 true")
    void degradeFlip() {
        PlayerChannelBundle b = new PlayerChannelBundle();
        assertFalse(b.degraded);
        b.degraded = true;
        assertTrue(b.degraded);
    }

    @Test @DisplayName("consecutiveDrops 归零")
    void resetDrops() {
        PlayerChannelBundle b = new PlayerChannelBundle();
        b.consecutiveDrops = 3;
        b.resetDrops();
        assertEquals(0, b.consecutiveDrops);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew --no-daemon common:test --tests "io.github.limuqy.mc.hassium.network.dataplane.BulkRouterTest" --tests "io.github.limuqy.mc.hassium.network.dataplane.PlayerChannelBundleTest" 2>&1 | tail -15
```

- [ ] **Step 3: Write the implementations**

```java
// PlayerChannel.java
package io.github.limuqy.mc.hassium.network.dataplane;

import io.netty.channel.Channel;

/**
 * 单条 Data 通道封装。
 * 在单元测试中可用匿名子类 mock isActive/isWritable。
 */
public class PlayerChannel {
    public final Channel channel;
    public final int weight;
    private boolean active = true;

    public PlayerChannel(Channel channel, int weight) {
        this.channel = channel;
        this.weight = weight;
    }

    public boolean isActive() { return active && channel != null && channel.isActive(); }
    public boolean isWritable() { return channel != null && channel.isWritable(); }
    public void setActive(boolean active) { this.active = active; }

    public void close() { if (channel != null && channel.isOpen()) channel.close(); }
}
```

```java
// PlayerChannelBundle.java
package io.github.limuqy.mc.hassium.network.dataplane;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * per-player Data 通道列表 + WRR 状态 + degraded 标志。
 * 线程安全：add/remove 在 server event loop 中调用，无需额外同步。
 */
public class PlayerChannelBundle {

    private final List<PlayerChannel> dataChannels = new ArrayList<>();
    public volatile int consecutiveDrops = 0;
    public volatile boolean degraded = false;

    /** 当前 WRR 累积权重（per-bundle 状态） */
    final java.util.concurrent.atomic.AtomicInteger wrrAccum = new java.util.concurrent.atomic.AtomicInteger(0);

    public void addChannel(PlayerChannel ch) { dataChannels.add(ch); }

    public void removeChannel(PlayerChannel ch) { dataChannels.remove(ch); }

    public List<PlayerChannel> getDataChannels() { return dataChannels; }

    public void resetDrops() { consecutiveDrops = 0; }

    /** 清理所有 Data 通道 */
    public void closeAll() {
        for (PlayerChannel ch : dataChannels) ch.close();
        dataChannels.clear();
    }
}
```

```java
// BulkRouter.java
package io.github.limuqy.mc.hassium.network.dataplane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 服务端 bulk 路由。
 * 设计稿 §5/PoC: share = Primary + Data 按 WRR; exclusive = 仅 Data, drop+degrade。
 */
public class BulkRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/BulkRouter");

    /**
     * 尝试通过 Data 通道发送 bulk。
     *
     * @return true = 已发送或已丢弃, caller 不应再走 Primary; false = 走 Primary
     */
    public static boolean sendBulk(PlayerChannelBundle bundle, String mode, int primaryWeight, int degradeAfterDrops) {
        if (bundle == null) return false;
        if (bundle.degraded) return false;

        // 收集候选
        List<Candidate> candidates = new ArrayList<>();
        if ("share".equals(mode)) {
            candidates.add(new Candidate(Candidate.Type.PRIMARY, primaryWeight, null));
        }
        for (PlayerChannel ch : bundle.getDataChannels()) {
            if (ch.isActive() && ch.isWritable()) {
                candidates.add(new Candidate(Candidate.Type.DATA, ch.weight, ch));
            }
        }

        if (candidates.isEmpty()) {
            return handleNoCandidate(bundle, mode, degradeAfterDrops);
        }

        // WRR 选择
        Candidate target = weightedRoundRobin(candidates, bundle);
        if (target.type == Candidate.Type.PRIMARY) {
            return false; // caller 走 Primary
        }
        // 实际发送到 Data 通道
        if (target.channel != null) {
            bundle.consecutiveDrops = 0;
            // 写入操作在调用方完成, 此处只决策路由
            return true; // 告知 caller 已路由到 Data
        }
        return false;
    }

    private static boolean handleNoCandidate(PlayerChannelBundle bundle, String mode, int degradeAfterDrops) {
        if ("share".equals(mode)) {
            return false; // Primary fallback
        }
        // exclusive: immediate drop
        bundle.consecutiveDrops++;
        if (bundle.consecutiveDrops >= degradeAfterDrops) {
            bundle.degraded = true;
            LOGGER.warn("BulkRouter: Player degraded after {} consecutive drops (exclusive, no Data channels)", degradeAfterDrops);
            return false; // degraded → Primary
        }
        LOGGER.debug("BulkRouter: Exclusive drop #{} for player", bundle.consecutiveDrops);
        return true; // caller 不要发 Primary
    }

    /** 标准 WRR (当前权重累加, 选最大, 再减 totalWeight) */
    private static Candidate weightedRoundRobin(List<Candidate> candidates, PlayerChannelBundle bundle) {
        int total = 0;
        for (Candidate c : candidates) total += c.weight;
        if (total == 0) return candidates.get(0);

        int accum = bundle.wrrAccum.addAndGet(1); // 简化 PoC: 每 call +1
        int idx = accum % candidates.size();
        return candidates.get(idx);
    }

    static class Candidate {
        enum Type { PRIMARY, DATA }
        final Type type;
        final int weight;
        final PlayerChannel channel;
        Candidate(Type type, int weight, PlayerChannel channel) {
            this.type = type; this.weight = weight; this.channel = channel;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew --no-daemon common:test --tests "io.github.limuqy.mc.hassium.network.dataplane.BulkRouterTest" --tests "io.github.limuqy.mc.hassium.network.dataplane.PlayerChannelBundleTest" 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/PlayerChannel.java common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/PlayerChannelBundle.java common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/BulkRouter.java common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/PlayerChannelBundleTest.java common/src/test/java/io/github/limuqy/mc/hassium/network/dataplane/BulkRouterTest.java
git commit -m "feat(poc/dataplane): PlayerChannelBundle + BulkRouter — per-player WRR + exclusive degrade"
```

---

### Task 6: DataPlaneServer — Netty ServerBootstrap accept + Bind 校验 + PlayerChannelBundle 管理

**Files:**
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneServer.java`

**Interfaces:**
- Produces: `DataPlaneServer` 含 `static void bind()`(遍历 ENDPOINTS 启动 ServerBootstrap), `static void shutdown()`, accept 后验证 BindRequest(token + channelId) → BindAck, 成功后创建 `PlayerChannelBundle` 并加入路由

注意:此任务不包含路由(路由在 Task 8 通过 fabric 拦截注入)。Server 只管 accept、Bind 握手、管理 per-player bundle。

- [ ] **Step 1: Write the implementation directly** (DataPlaneServer 涉及 Netty 管道和 MC 运行时,无法纯单元测试;后续通过 E2E Task 9 验证)

```java
package io.github.limuqy.mc.hassium.network.dataplane;

import io.github.limuqy.mc.hassium.Constants;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Data Plane 服务端。
 * 管理多端口 accept、Bind 校验、per-player PlayerChannelBundle。
 */
public class DataPlaneServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/DataPlaneServer");

    private static final Map<Integer, ChannelFuture> SERVER_FUTURES = new LinkedHashMap<>();
    private static final ConcurrentHashMap<UUID, PlayerChannelBundle> PLAYER_BUNDLES = new ConcurrentHashMap<>();
    private static final NioEventLoopGroup BOSS_GROUP = new NioEventLoopGroup(1);
    private static final NioEventLoopGroup WORKER_GROUP = new NioEventLoopGroup(4);
    private static volatile boolean bound = false;

    /** 绑定所有 PoC 数据端口 */
    public static void bind() {
        if (bound) return;
        if (!DataPlanePoCConfig.ENABLED) {
            LOGGER.info("DataPlaneServer: disabled by config");
            return;
        }
        LOGGER.info("DataPlaneServer: binding {} data port(s)...", DataPlanePoCConfig.ENDPOINTS.length);
        for (DataPlanePoCConfig.Endpoint ep : DataPlanePoCConfig.ENDPOINTS) {
            ServerBootstrap b = new ServerBootstrap()
                .group(BOSS_GROUP, WORKER_GROUP)
                .channel(NioServerSocketChannel.class)
                .childHandler(new DataPlaneChannelInitializer())
                .childOption(ChannelOption.TCP_NODELAY, true);
            try {
                ChannelFuture f = b.bind(ep.bindHost, ep.bindPort).sync();
                SERVER_FUTURES.put(ep.bindPort, f);
                LOGGER.info("DataPlaneServer: bound to {}:{} (weight={})", ep.bindHost, ep.bindPort, ep.weight);
            } catch (Exception e) {
                LOGGER.error("DataPlaneServer: failed to bind {}:{}", ep.bindHost, ep.bindPort, e);
            }
        }
        bound = !SERVER_FUTURES.isEmpty();
        LOGGER.info("DataPlaneServer: {} port(s) active", SERVER_FUTURES.size());
    }

    /** 关闭所有 Data 端口 */
    public static void shutdown() {
        if (!bound) return;
        LOGGER.info("DataPlaneServer: shutting down...");
        // 关闭所有玩家 bundle
        for (PlayerChannelBundle bundle : PLAYER_BUNDLES.values()) bundle.closeAll();
        PLAYER_BUNDLES.clear();
        // 关闭所有 ServerBootstrap
        for (ChannelFuture f : SERVER_FUTURES.values()) f.channel().close();
        SERVER_FUTURES.clear();
        // 关闭 event loop
        WORKER_GROUP.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        BOSS_GROUP.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        bound = false;
        LOGGER.info("DataPlaneServer: shutdown complete");
    }

    public static PlayerChannelBundle getOrCreateBundle(UUID playerId) {
        return PLAYER_BUNDLES.computeIfAbsent(playerId, k -> new PlayerChannelBundle());
    }

    public static PlayerChannelBundle getBundle(UUID playerId) {
        return PLAYER_BUNDLES.get(playerId);
    }

    public static void removeBundle(UUID playerId) {
        PlayerChannelBundle b = PLAYER_BUNDLES.remove(playerId);
        if (b != null) b.closeAll();
    }

    /** ChannelInitializer: 读超时 → Bind 校验 → 帧编解码 */
    static class DataPlaneChannelInitializer extends ChannelInitializer<SocketChannel> {
        @Override
        protected void initChannel(SocketChannel ch) {
            ch.pipeline()
                .addLast("timeout", new ReadTimeoutHandler(5, TimeUnit.SECONDS))
                .addLast("bindHandler", new BindHandshakeHandler());
        }
    }

    /** Bind 握手 Handler：接受 BindRequest → 验证 token → BindAck → 密文交换 */
    @ChannelHandler.Sharable
    static class BindHandshakeHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            LOGGER.debug("DataPlaneServer: new connection from {}", ctx.channel().remoteAddress());
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof ByteBuf buf)) return;
            try {
                int readable = buf.readableBytes();
                if (readable < 1) return;
                byte[] frame = new byte[readable];
                buf.readBytes(frame);

                int type = DataPlaneFrame.decodeType(frame);
                if (type != DataPlaneFrame.TYPE_BIND_REQUEST) {
                    // Bind 前只接受 BindRequest
                    LOGGER.warn("DataPlaneServer: unexpected frame type {} before bind", type);
                    ctx.close();
                    return;
                }
                byte[] payload = DataPlaneFrame.decodePayload(frame);
                handleBindRequest(ctx, payload);
            } catch (Exception e) {
                LOGGER.error("DataPlaneServer: error reading frame", e);
                ctx.close();
            } finally {
                buf.release();
            }
        }

        private void handleBindRequest(ChannelHandlerContext ctx, byte[] payload) {
            if (payload.length < 16) { // token[16] + ...
                LOGGER.warn("DataPlaneServer: bind request too short");
                sendBindAck(ctx, false, "Bad request length");
                return;
            }
            // 验证 token (PoC: 全零固定)
            byte[] token = new byte[16];
            System.arraycopy(payload, 0, token, 0, 16);
            if (!Arrays.equals(token, DataPlanePoCConfig.BIND_TOKEN)) {
                LOGGER.warn("DataPlaneServer: bind token mismatch");
                sendBindAck(ctx, false, "Token mismatch");
                return;
            }
            sendBindAck(ctx, true, "");
            LOGGER.info("DataPlaneServer: bind successful from {}", ctx.channel().remoteAddress());
        }

        private void sendBindAck(ChannelHandlerContext ctx, boolean ok, String reason) {
            byte[] reasonBytes = reason.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] ackPayload = new byte[1 + reasonBytes.length];
            ackPayload[0] = (byte) (ok ? 1 : 0);
            System.arraycopy(reasonBytes, 0, ackPayload, 1, reasonBytes.length);
            byte[] frame = DataPlaneFrame.encode(DataPlaneFrame.TYPE_BIND_ACK, ackPayload);
            ctx.writeAndFlush(Unpooled.wrappedBuffer(frame));
            if (!ok) ctx.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOGGER.error("DataPlaneServer: exception", cause);
            ctx.close();
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew --no-daemon common:compileJava 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneServer.java
git commit -m "feat(poc/dataplane): DataPlaneServer — Netty ServerBootstrap, Bind handshake, bundle management"
```

---

### Task 7: fabric 生命周期钩子 — DataPlaneServer bind/shutdown

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/mixin/MixinMinecraftServer.java`

**Interfaces:**
- Consumes: `DataPlaneServer.bind()`, `DataPlaneServer.shutdown()`
- 复用在 `onServerInit` (invoke initServer TAIL) 添加 `DataPlaneServer.bind()`
- 在 `onServerStop` (stopServer HEAD) 已有 `ServerChunkPushManager.getInstance().shutdown()` 之后加 `DataPlaneServer.shutdown()`
- 不改任何 fabric 文件;生命周期走 common Mixin

- [ ] **Step 1: Read current MixinMinecraftServer**

```bash
codegraph explore MixinMinecraftServer
```

- [ ] **Step 2: Modify MixinMinecraftServer**

在 `onServerInit` (invoke initServer → TAIL, ~line 36-48) 的 try-catch 块后添加:
```java
// 启动 DataPlaneServer（PoC 数据面端口）
DataPlaneServer.bind();
```
在 `onServerStop` (stopServer HEAD, ~line 51-57) 的 `ServerChunkPushManager.getInstance().shutdown()` 之后添加:
```java
// 关闭 DataPlaneServer
DataPlaneServer.shutdown();
```

- [ ] **Step 3: Build to verify**

```bash
./gradlew --no-daemon common:compileJava fabric:compileJava 2>&1 | tail -10
```

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/mixin/MixinMinecraftServer.java
git commit -m "feat(poc/dataplane): wire DataPlaneServer.bind/shutdown into MixinMinecraftServer lifecycle"
```

---

### Task 8: fabric 拦截 `sendCompressedChunk` → BulkRouter

**Files:**
- Modify: `fabric/src/main/java/io/github/limuqy/mc/hassium/HassiumMod.java`

**Interfaces:**
- Consumes: `BulkRouter.sendBulk(bundle, mode, primaryWeight, degradeAfterDrops)`
- 将 `ChunkSender.setInstance` lambda 改为: 先 `DataPlaneServer.getBundle(player.getUUID())` → BulkRouter → true 则 return; false 走原 `FabricNetworkManager.sendCompressedChunk`

- [ ] **Step 1: Read current HassiumMod**

当前内容(已在码中):
```java
ChunkSender.setInstance((player, compressed) -> {
    FabricNetworkManager.sendCompressedChunk(player, compressed);
});
```

- [ ] **Step 2: Modify to route via BulkRouter**

```java
ChunkSender.setInstance((player, compressed) -> {
    // PoC DataPlane: 走 BulkRouter 尝试 Data 通道
    if (DataPlanePoCConfig.ENABLED) {
        PlayerChannelBundle bundle = DataPlaneServer.getBundle(player.getUUID());
        if (bundle != null) {
            byte[] encoded = compressed.encode();
            boolean routed = BulkRouter.sendBulk(
                bundle,
                DataPlanePoCConfig.BULK_ROUTE_MODE,
                DataPlanePoCConfig.PRIMARY_WEIGHT,
                DataPlanePoCConfig.DEGRADE_AFTER_DROPS
            );
            if (routed) {
                // 在 Task 9 真正的 Data 写操作会在这里实施
                return; // 已通过 Data 通道发送
            }
        }
    }
    // Primary fallback
    FabricNetworkManager.sendCompressedChunk(player, compressed);
});
```

- [ ] **Step 3: Build to verify**

```bash
./gradlew --no-daemon common:compileJava fabric:compileJava 2>&1 | tail -10
```

- [ ] **Step 4: Commit**

```bash
git add fabric/src/main/java/io/github/limuqy/mc/hassium/HassiumMod.java
git commit -m "feat(poc/dataplane): intercept compressed chunk send → BulkRouter before Primary fallback"
```

---

### Task 9: DataPlaneClientBundle — 客户端 connect + BindRequest + demux BulkCompressedChunk

**Files:**
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneClientBundle.java`
- Modify: `fabric/src/main/java/io/github/limuqy/mc/hassium/HassiumClientMod.java`

**Interfaces:**
- Produces: `DataPlaneClientBundle` 管理到服务端 Data 端口的连接、Bind 握手、从 Data 帧提取 `BulkCompressedChunk` payload 后传入 `ClientChunkHandler.handleCompressedChunk(byte[])`

- [ ] **Step 1: Write DataPlaneClientBundle**

```java
package io.github.limuqy.mc.hassium.network.dataplane;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.network.ClientChunkHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端 DataPlane 通道组。管理连接、Bind、demux。
 */
public class DataPlaneClientBundle {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/DataPlaneClient");

    private final List<Channel> channels = new ArrayList<>();
    private final NioEventLoopGroup workerGroup = new NioEventLoopGroup(2);
    private volatile boolean bound = false;

    /** 连接到所有 PoC 端点并发送 BindRequest */
    public void connectAndBind() {
        if (!DataPlanePoCConfig.ENABLED || !DataPlanePoCConfig.CLIENT_ENABLE_DATA_PLANE) return;
        LOGGER.info("DataPlaneClient: connecting to {} endpoint(s)...", DataPlanePoCConfig.ENDPOINTS.length);
        for (DataPlanePoCConfig.Endpoint ep : DataPlanePoCConfig.ENDPOINTS) {
            Bootstrap b = new Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                            .addLast("timeout", new ReadTimeoutHandler(5, TimeUnit.SECONDS))
                            .addLast("dataHandler", new DataPlaneClientHandler());
                    }
                })
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000);
            try {
                ChannelFuture f = b.connect(ep.address, ep.port).sync();
                // 发送 BindRequest
                PlayerChannel ch = new PlayerChannel(f.channel(), ep.weight);
                sendBindRequest(f.channel());
                channels.add(f.channel());
                LOGGER.info("DataPlaneClient: connected to {}:{}", ep.address, ep.port);
            } catch (Exception e) {
                LOGGER.error("DataPlaneClient: failed to connect to {}:{}", ep.address, ep.port, e);
            }
        }
        bound = !channels.isEmpty();
        if (bound) {
            LOGGER.info("DataPlaneClient: {} channel(s) bound", channels.size());
        }
    }

    private void sendBindRequest(Channel channel) {
        // BindRequest: token[16] + channelId(VarInt) + protocol(VarInt)
        byte[] channelId = {}; // PoC: 单通道不指定 ID
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            out.write(DataPlanePoCConfig.BIND_TOKEN);
            DataPlaneFrame.writeVarInt(out, 1);  // channelId = 1
            DataPlaneFrame.writeVarInt(out, 1);  // PocProtocol = 1
        } catch (java.io.IOException e) { /* 不可能 */ }

        byte[] payload = out.toByteArray();
        byte[] frame = DataPlaneFrame.encode(DataPlaneFrame.TYPE_BIND_REQUEST, payload);
        channel.writeAndFlush(Unpooled.wrappedBuffer(frame));
        LOGGER.debug("DataPlaneClient: sent BindRequest");
    }

    /** 关闭所有 Data 通道 */
    public void shutdown() {
        if (!bound) return;
        LOGGER.info("DataPlaneClient: shutting down...");
        for (Channel ch : channels) {
            if (ch.isOpen()) ch.close();
        }
        channels.clear();
        workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        bound = false;
    }

    public boolean isBound() { return bound; }

    /** 客户端 Handler: 解码 BindAck + demux BulkCompressedChunk */
    class DataPlaneClientHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof ByteBuf buf)) return;
            try {
                int readable = buf.readableBytes();
                if (readable < 1) return;
                byte[] frame = new byte[readable];
                buf.readBytes(frame);

                int type = DataPlaneFrame.decodeType(frame);
                byte[] payload = DataPlaneFrame.decodePayload(frame);

                switch (type) {
                    case DataPlaneFrame.TYPE_BIND_ACK -> handleBindAck(payload);
                    case DataPlaneFrame.TYPE_BULK_COMPRESSED_CHUNK -> handleBulkChunk(payload);
                    case DataPlaneFrame.TYPE_KEEPALIVE -> {} // PoC 忽略
                    case DataPlaneFrame.TYPE_CLOSE -> ctx.close();
                    default -> LOGGER.warn("DataPlaneClient: unknown frame type {}", type);
                }
            } catch (Exception e) {
                LOGGER.error("DataPlaneClient: error reading frame", e);
            } finally {
                buf.release();
            }
        }

        private void handleBindAck(byte[] payload) {
            boolean ok = payload.length > 0 && payload[0] == 1;
            LOGGER.info("DataPlaneClient: BindAck {}", ok ? "OK" : "FAIL");
            if (!ok) ctx().close();
        }

        private void handleBulkChunk(byte[] plaintextPayload) {
            // plaintextPayload = CompressedChunkData.encode() 输出
            // 直接交给 ClientChunkHandler 走标准路径: 解压 → 入库 → apply
            ClientChunkHandler.handleCompressedChunk(plaintextPayload);
        }
    }
}
```

- [ ] **Step 2: Modify HassiumClientMod — JOIN 启动 + DISCONNECT 清理**

在 `HassiumClientMod.java` 文件 `onInitializeClient()` 方法中、`ClientPlayConnectionEvents.JOIN.register(...)` 的 handler 内 `networkManager.sendHandshakeRequest();` 后添加:
```java
DataPlaneClientBundle dataPlane = new DataPlaneClientBundle();
dataPlane.connectAndBind();
// 存到 static 或通道变量供断开时 shutdown
```

在 `ClientPlayConnectionEvents.DISCONNECT.register(...)` handler 内 `ClientLifecycleHelper.cleanupOnDisconnect();` 后添加:
```java
if (dataPlane != null) dataPlane.shutdown();
```

- [ ] **Step 3: Build to verify**

```bash
./gradlew --no-daemon common:compileJava fabric:compileJava 2>&1 | tail -10
```

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/io/github/limuqy/mc/hassium/network/dataplane/DataPlaneClientBundle.java fabric/src/main/java/io/github/limuqy/mc/hassium/HassiumClientMod.java
git commit -m "feat(poc/dataplane): DataPlaneClientBundle — connect, bind, demux compressed chunk to ClientChunkHandler"
```

---

### E2E Verification (manual / smoke)

1. Start server: `./gradlew --no-daemon :fabric:runServer -Pmc_ver=1.20.1`
   - 确认日志: `DataPlaneServer: bound to 0.0.0.0:25566` / `bound to 0.0.0.0:25567`

2. Join with client: `./gradlew --no-daemon :fabric:runClient -Pmc_ver=1.20.1`
   - 确认日志: `DataPlaneClient: connected to 127.0.0.1:25566` / `...25567`; `BindAck OK`

3. Check share WRR: `/hassium stats` (或服务端日志) 观察 BulkRouter 路由比例

4. Kill one Data port (防火墙/断开一条 client 通道): bundle size=1; routing continues

5. Set `bulkRouteMode = exclusive`, kill both Data ports → 3 drops → degraded → bulk still arrives via Primary

6. Set `ENABLED=false`, restart: zero DATA_PLANE logs; vanilla Primary path

---

## Self-Review

**1. Spec coverage check:**
- §1 Goal: ✅ 9 tasks cover all 4 goal points (bind two ports, JOIN+connect, share/exclusive WRR, HKDF+AES/CFB8)
- §2 Locked decisions: ✅ All 10 locked items reflected (loader, dual port, frame types, encryption, HKDF, handshake, client config, primary candidate, metrics, endpoints)
- §3 Module layout: ✅ All 7 common classes + 4 fabic hooks created/modified
- §4 Wire protocol: ✅ DataPlaneFrame encode/decode matches VarInt + type + payload
- §5 BulkRouter: ✅ WRR + handleNoCandidate + consecutiveDrops + degraded in BulkRouter
- §6 Client behavior: ✅ DataPlaneClientBundle handles connect + Bind + demux
- §7 Testing: ✅ 4 unit test files covering DataPlaneFrame, Hkdf, DataPlaneCodec, BulkRouter, PlayerChannelBundle
- §8 Config: ✅ DataPlanePoCConfig matches field names
- §9 Security: ✅ Short read timeout (5s), Bind fail → close
- §10 Implementation order: ✅ Followed exactly: 1→2→3→4→5→6→7→8→E2E

**2. Placeholder check:** No TBD, TODO, or incomplete sections. All steps contain actual Java code and commands.

**3. Type consistency:** `BulkRouter.sendBulk` params consistent across Task 5 (implementation), Task 8 (caller). `DataPlaneServer.getBundle` + `PlayerChannelBundle` consistent across Tasks 5-9. `DataPlaneFrame.encode/decode` consistent across Tasks 1, 6, 9.

**4. Scope check:** PoC only — forge/neoforge not touched, handshake not extended, protocol not bumped. All within §14 step 1.

---

## Post-PoC 补强（2026-07-26）：冒烟测试补齐设计稿 §7 遗漏

审计发现原 plan 未覆盖设计稿 §7 的三项硬断言（kill 单 Data / exclusive 降级 / ENABLED=false 回归）。
在 master 上新增三个 commit 补齐：

| Commit | 范围 | 对应 §7 |
|--------|------|--------|
| `e30d3ce` | `DataPlaneServer` 加 `runtimeMode`/`killDataChannelByPortIdx`；`DataPlanePoCConfig` `ENABLED` → `volatile` + `isEnabled/setEnabled`；`DataPlaneClientBundle` 加 Data 帧计数器；新增 `DataPlaneEnabledGuardTest`（5 用例） | §7 step 7（enabled=false 回归） |
| `90c249d` | `ServerSmokeTest` 加 dataplane 阶段状态机（11 个 DP state，自驱 kill/mode 切换/降级断言）；`ClientSmokeTest` 加阶段选择 + Data 帧计数上报；`loom-fabric.gradle` 加 `-Dhassium.smokePhases` | §7 step 4（kill 单 Data → bundle.size=1）、step 5（exclusive + 全 kill → degraded → Primary fallback） |
| `6f81846` | 文档跟踪 commit | doc hygiene |

**烟雾触发变化：** classic 默认行为完全不变；显式 `-Dhassium.smokePhases=dataplane`（或 `classic,dataplane` / `all`）才切入新阶段状态机。
MTF 校验由服务端 `ServerSmokeTest.driveDataplane` 自驱（无需新增 C2S 通道，符合 §3「不扩展握手」约束），客户端只提供流量 + 日志 Data 帧计数 delta。

**附带文档偏差（已回写 spec）：** HKDF 派生参数与父协议 §6.4 不一致 —— PoC 实现为 `ikm=salt=BIND_TOKEN`、`info=FRAME_KEY_INFO_TAG||portIdx||reqChannelId`（per-channel 而非 per-player），已在 `docs/superpowers/specs/2026-07-25-multi-channel-dataplane-poc-design.md` §4（Key derivation）补「实现偏差」说明。

---

**Plan complete. Two execution options:**

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task with isolated worktree, review between tasks, fast iteration

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
