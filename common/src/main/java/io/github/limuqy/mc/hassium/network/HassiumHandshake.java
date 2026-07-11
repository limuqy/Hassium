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

            HassiumCapabilities capabilities = new HassiumCapabilities(
                    modVersion,
                    protocolVersion,
                    supportedAlgorithms,
                    clientCacheSupported,
                    chunkRevisionSupported,
                    scheme127Supported,
                    globalPacketCompressionSupported,
                    compactHeaderSupported
            );

            return new ClientRequest(capabilities);
        }
    }

    /**
     * 服务端握手响应
     */
    public record ServerResponse(
            HassiumCapabilities serverCapabilities,
            String worldId,
            boolean accepted,
            String rejectReason
    ) {
        /**
         * 创建接受响应
         */
        public static ServerResponse accept(HassiumCapabilities serverCapabilities, String worldId) {
            return new ServerResponse(serverCapabilities, worldId, true, null);
        }

        /**
         * 创建拒绝响应
         */
        public static ServerResponse reject(String reason) {
            return new ServerResponse(null, null, false, reason);
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
                        compactHeaderSupported
                );

                return new ServerResponse(capabilities, worldId, true, null);
            } else {
                // 拒绝原因
                String rejectReason = readString(buffer);
                return new ServerResponse(null, null, false, rejectReason);
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
