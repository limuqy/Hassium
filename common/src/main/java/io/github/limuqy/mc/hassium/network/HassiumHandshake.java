package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.api.HassiumCapabilities;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Hassium 握手数据包
 */
public final class HassiumHandshake {

    private HassiumHandshake() {
        // 工具类，禁止实例化
    }

    /**
     * 客户端握手请求
     */
    public record ClientRequest(
            HassiumCapabilities clientCapabilities
    ) {
        /**
         * 序列化为字节数组
         */
        public byte[] encode() {
            ByteBuffer buffer = ByteBuffer.allocate(1024);
            buffer.order(ByteOrder.BIG_ENDIAN);

            // 协议版本
            buffer.putInt(clientCapabilities.protocolVersion());

            // Mod 版本
            writeString(buffer, clientCapabilities.modVersion());

            // 支持的算法数量
            buffer.putInt(clientCapabilities.supportedAlgorithms().size());

            // 支持的算法列表
            for (String algorithm : clientCapabilities.supportedAlgorithms()) {
                writeString(buffer, algorithm);
            }

            // 标志位
            buffer.put((byte) (clientCapabilities.clientCacheSupported() ? 1 : 0));
            buffer.put((byte) (clientCapabilities.chunkRevisionSupported() ? 1 : 0));
            buffer.put((byte) (clientCapabilities.scheme127Supported() ? 1 : 0));
            buffer.put((byte) (clientCapabilities.globalPacketCompressionSupported() ? 1 : 0));
            buffer.put((byte) (clientCapabilities.compactHeaderSupported() ? 1 : 0));
            // SeedGen 能力（append-only；旧服务端忽略尾字节）
            buffer.put((byte) (clientCapabilities.seedGenSupported() ? 1 : 0));

            buffer.flip();
            byte[] result = new byte[buffer.remaining()];
            buffer.get(result);
            return result;
        }

        /**
         * 从字节数组反序列化
         */
        public static ClientRequest decode(byte[] data) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            buffer.order(ByteOrder.BIG_ENDIAN);

            // 协议版本
            int protocolVersion = buffer.getInt();

            // Mod 版本
            String modVersion = readString(buffer);

            // 支持的算法数量
            int algorithmCount = buffer.getInt();

            // 支持的算法列表
            Set<String> supportedAlgorithms = new HashSet<>();
            for (int i = 0; i < algorithmCount; i++) {
                supportedAlgorithms.add(readString(buffer));
            }

            // 标志位
            boolean clientCacheSupported = buffer.get() == 1;
            boolean chunkRevisionSupported = buffer.get() == 1;
            boolean scheme127Supported = buffer.get() == 1;
            boolean globalPacketCompressionSupported = buffer.get() == 1;
            boolean compactHeaderSupported = buffer.get() == 1;
            // SeedGen 能力（append-only；旧客户端字节流无此字段）
            boolean seedGenSupported = buffer.remaining() >= 1 && buffer.get() == 1;

            HassiumCapabilities capabilities = new HassiumCapabilities(
                    modVersion,
                    protocolVersion,
                    supportedAlgorithms,
                    clientCacheSupported,
                    chunkRevisionSupported,
                    scheme127Supported,
                    globalPacketCompressionSupported,
                    compactHeaderSupported,
                    seedGenSupported
            );

            return new ClientRequest(capabilities);
        }
    }

    /**
     * 服务端握手响应
     * <p>
     * SeedGen 字段（worldSeed/levelStemNbt/seedGenEnabled）为 append-only 尾部扩展：
     * 旧客户端解码时在固定字段后忽略尾字节，新字段取默认（0/null/false）。
     */
    public record ServerResponse(
            HassiumCapabilities serverCapabilities,
            String worldId,
            boolean accepted,
            String rejectReason,
            long worldSeed,
            byte[] levelStemNbt,
            boolean seedGenEnabled
    ) {
        /**
         * 创建接受响应
         */
        public static ServerResponse accept(HassiumCapabilities serverCapabilities, String worldId,
                                            long worldSeed, byte[] levelStemNbt, boolean seedGenEnabled) {
            return new ServerResponse(serverCapabilities, worldId, true, null, worldSeed, levelStemNbt, seedGenEnabled);
        }

        /**
         * 创建拒绝响应
         */
        public static ServerResponse reject(String reason) {
            return new ServerResponse(null, null, false, reason, 0L, null, false);
        }

        /**
         * 序列化为字节数组
         */
        public byte[] encode() {
            ByteBuffer buffer = ByteBuffer.allocate(1024);
            buffer.order(ByteOrder.BIG_ENDIAN);

            // 是否接受
            buffer.put((byte) (accepted ? 1 : 0));

            if (accepted) {
                // 协议版本
                buffer.putInt(serverCapabilities.protocolVersion());

                // Mod 版本
                writeString(buffer, serverCapabilities.modVersion());

                // 世界 ID
                writeString(buffer, worldId);

                // 支持的算法数量
                buffer.putInt(serverCapabilities.supportedAlgorithms().size());

                // 支持的算法列表
                for (String algorithm : serverCapabilities.supportedAlgorithms()) {
                    writeString(buffer, algorithm);
                }

                // 标志位
                buffer.put((byte) (serverCapabilities.clientCacheSupported() ? 1 : 0));
                buffer.put((byte) (serverCapabilities.chunkRevisionSupported() ? 1 : 0));
                buffer.put((byte) (serverCapabilities.scheme127Supported() ? 1 : 0));
                buffer.put((byte) (serverCapabilities.globalPacketCompressionSupported() ? 1 : 0));
                buffer.put((byte) (serverCapabilities.compactHeaderSupported() ? 1 : 0));
                // SeedGen 尾部（append-only；旧客户端忽略尾字节）
                buffer.putLong(worldSeed);
                byte[] stem = levelStemNbt;
                buffer.putInt(stem != null ? stem.length : 0);
                if (stem != null && stem.length > 0) {
                    buffer.put(stem);
                }
                buffer.put((byte) (seedGenEnabled ? 1 : 0));
            } else {
                // 拒绝原因
                writeString(buffer, rejectReason != null ? rejectReason : "");
            }

            buffer.flip();
            byte[] result = new byte[buffer.remaining()];
            buffer.get(result);
            return result;
        }

        /**
         * 从字节数组反序列化
         */
        public static ServerResponse decode(byte[] data) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            buffer.order(ByteOrder.BIG_ENDIAN);

            // 是否接受
            boolean accepted = buffer.get() == 1;

            if (accepted) {
                // 协议版本
                int protocolVersion = buffer.getInt();

                // Mod 版本
                String modVersion = readString(buffer);

                // 世界 ID
                String worldId = readString(buffer);

                // 支持的算法数量
                int algorithmCount = buffer.getInt();

                // 支持的算法列表
                Set<String> supportedAlgorithms = new HashSet<>();
                for (int i = 0; i < algorithmCount; i++) {
                    supportedAlgorithms.add(readString(buffer));
                }

                // 标志位
                boolean clientCacheSupported = buffer.get() == 1;
                boolean chunkRevisionSupported = buffer.get() == 1;
                boolean scheme127Supported = buffer.get() == 1;
                boolean globalPacketCompressionSupported = buffer.get() == 1;
                boolean compactHeaderSupported = buffer.get() == 1;

                HassiumCapabilities capabilities = new HassiumCapabilities(
                        modVersion,
                        protocolVersion,
                        supportedAlgorithms,
                        clientCacheSupported,
                        chunkRevisionSupported,
                        scheme127Supported,
                        globalPacketCompressionSupported,
                        compactHeaderSupported,
                        false
                );

                // SeedGen 尾部（append-only；旧服务端字节流无此字段时取默认）
                long worldSeed = 0L;
                byte[] levelStemNbt = null;
                boolean seedGenEnabled = false;
                if (buffer.remaining() >= 8) {
                    worldSeed = buffer.getLong();
                    if (buffer.remaining() >= 4) {
                        int stemLen = buffer.getInt();
                        if (stemLen > 0 && stemLen <= buffer.remaining()) {
                            levelStemNbt = new byte[stemLen];
                            buffer.get(levelStemNbt);
                        }
                        if (buffer.remaining() >= 1) {
                            seedGenEnabled = buffer.get() == 1;
                        }
                    }
                }

                return new ServerResponse(capabilities, worldId, true, null, worldSeed, levelStemNbt, seedGenEnabled);
            } else {
                // 拒绝原因
                String rejectReason = readString(buffer);
                return new ServerResponse(null, null, false, rejectReason, 0L, null, false);
            }
        }
    }

    /**
     * 写入字符串
     */
    private static void writeString(ByteBuffer buffer, String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        buffer.putInt(bytes.length);
        buffer.put(bytes);
    }

    /**
     * 读取字符串
     */
    private static String readString(ByteBuffer buffer) {
        int length = buffer.getInt();
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
