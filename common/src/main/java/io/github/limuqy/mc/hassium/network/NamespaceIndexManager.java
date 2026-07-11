package io.github.limuqy.mc.hassium.network;

import io.github.limuqy.mc.hassium.compat.ResourceLocationCompat;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
#if MC_VER < MC_1_21_11
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 包类型索引管理器
 * <p>
 * 借鉴 NEB 的紧凑包头优化，用短的 VarInt 索引替换长的 ResourceLocation 字符串。
 * <p>
 * 两级索引结构：
 * - 第一级：namespace 索引（如 "minecraft" -> 1）
 * - 第二级：path 索引（如 "commands" -> 1）
 * <p>
 * 索引从 1 开始，0 保留为"未索引"标记。
 * <p>
 * 典型效果：
 * - "minecraft:commands" (约 20 字节) -> VarInt(1) + VarInt(1) (2 字节)
 * - 节省 90% 的包头大小
 */
public class NamespaceIndexManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/NamespaceIndex");

    /**
     * 未索引标记
     */
    public static final int ILLEGAL = 0;

    /**
     * namespace -> 索引
     */
    private final Map<String, Integer> namespaceToIndex = new ConcurrentHashMap<>();

    /**
     * 索引 -> namespace
     */
    private final List<String> namespaces = new ArrayList<>();

    /**
     * namespace索引 -> (path -> 索引)
     */
    private final Map<Integer, Map<String, Integer>> pathToIndex = new ConcurrentHashMap<>();

    /**
     * namespace索引 -> (索引 -> path)
     */
    private final Map<Integer, List<String>> paths = new ConcurrentHashMap<>();

    /**
     * 下一个 namespace 索引
     */
    private int nextNamespaceIndex = 1; // 从 1 开始

    /**
     * 每个 namespace 的下一个 path 索引
     */
    private final Map<String, Integer> nextPathIndex = new ConcurrentHashMap<>();

    /**
     * 原版包类 -> 标识符映射
     */
    private final Map<Class<?>,
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
> vanillaClassToIdentifier = new HashMap<>();

    /**
     * 标识符 -> 原版包 ID 映射 (S2C)
     */
    private final Map<
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
, Integer> vanillaIdS2C = new HashMap<>();

    /**
     * 标识符 -> 原版包 ID 映射 (C2S)
     */
    private final Map<
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
, Integer> vanillaIdC2S = new HashMap<>();

    /**
     * 注册一个包类型
     *
     * @param identifier ResourceLocation 格式的标识符（如 "minecraft:commands"）
     */
    public synchronized void register(String identifier) {
        String[] parts = identifier.split(":", 2);
        if (parts.length != 2) {
            LOGGER.warn("Invalid identifier format: {}", identifier);
            return;
        }

        String namespace = parts[0];
        String path = parts[1];

        // 注册 namespace
        if (!namespaceToIndex.containsKey(namespace)) {
            int nsIndex = nextNamespaceIndex++;
            namespaceToIndex.put(namespace, nsIndex);
            namespaces.add(namespace);
            pathToIndex.put(nsIndex, new ConcurrentHashMap<>());
            paths.put(nsIndex, new ArrayList<>());
            nextPathIndex.put(namespace, 1); // 从 1 开始
        }

        // 注册 path
        int nsIndex = namespaceToIndex.get(namespace);
        Map<String, Integer> nsPathToIndex = pathToIndex.get(nsIndex);
        if (!nsPathToIndex.containsKey(path)) {
            int pathIndex = nextPathIndex.get(namespace);
            nsPathToIndex.put(path, pathIndex);
            paths.get(nsIndex).add(path);
            nextPathIndex.put(namespace, pathIndex + 1);
        }
    }

    /**
     * 批量注册包类型
     */
    public void registerAll(Collection<String> identifiers) {
        // 按 namespace 字典序、再按 path 字典序排序，确保确定性顺序
        List<String> sorted = new ArrayList<>(identifiers);
        sorted.sort((a, b) -> {
            String[] partsA = a.split(":", 2);
            String[] partsB = b.split(":", 2);
            int nsCompare = partsA[0].compareTo(partsB[0]);
            if (nsCompare != 0) return nsCompare;
            return partsA[1].compareTo(partsB[1]);
        });

        for (String identifier : sorted) {
            register(identifier);
        }
    }

    /**
     * 初始化原版包枚举
     * <p>
     * 从 ConnectionProtocol.PLAY 运行时提取所有原版包类，
     * 类名转 snake_case 作为 path，分配确定性标识符。
     */
    public void initVanillaPackets() {
        initVanillaForSide(PacketFlow.CLIENTBOUND);
        initVanillaForSide(PacketFlow.SERVERBOUND);
        LOGGER.info("Initialized vanilla packets: {} types", vanillaClassToIdentifier.size());
    }

    @SuppressWarnings("unchecked")
    private void initVanillaForSide(PacketFlow side) {
#if MC_VER < MC_1_20_5
        var map = (Int2ObjectMap<Class<? extends Packet<?>>>) (Int2ObjectMap<?>)
                ConnectionProtocol.PLAY.getPacketsByIds(side);

        // 按 ID 排序确保确定性顺序
        var entries = new ArrayList<>(map.int2ObjectEntrySet());
        entries.sort(Comparator.comparingInt(Int2ObjectMap.Entry::getIntKey));

        for (var entry : entries) {
            Class<?> clazz = entry.getValue();
            int packetId = entry.getIntKey();

            // 如果已经从另一侧映射过，只记录 ID
            if (vanillaClassToIdentifier.containsKey(clazz)) {
                ResourceLocation existingId = vanillaClassToIdentifier.get(clazz);
                if (side == PacketFlow.CLIENTBOUND) {
                    vanillaIdS2C.put(existingId, packetId);
                } else {
                    vanillaIdC2S.put(existingId, packetId);
                }
                continue;
            }

            // 类名转 snake_case
            String path = toSnakeCase(clazz.getSimpleName());
            ResourceLocation id = ResourceLocationCompat.create("minecraft", path);

            // 注册到索引
            register(id.toString());
            vanillaClassToIdentifier.put(clazz, id);

            if (side == PacketFlow.CLIENTBOUND) {
                vanillaIdS2C.put(id, packetId);
            } else {
                vanillaIdC2S.put(id, packetId);
            }
        }
#else
        // 1.20.5+: getPacketsByIds removed, skip vanilla packet initialization
        return;
#endif
    }

    /**
     * CamelCase 转 snake_case
     * 例如：ClientboundLevelChunkWithLightPacket -> clientbound_level_chunk_with_light_packet
     */
    private static String toSnakeCase(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    char prev = name.charAt(i - 1);
                    if (!Character.isUpperCase(prev) && prev != '_') {
                        sb.append('_');
                    } else if (Character.isUpperCase(prev) && i + 1 < name.length()
                            && Character.isLowerCase(name.charAt(i + 1))) {
                        sb.append('_');
                    }
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 获取原版包类的标识符
     *
     * @param packetClass 包类
     * @return 标识符，如果不是原版包返回 null
     */
    public
#if MC_VER < MC_1_21_11
    ResourceLocation
#else
    Identifier
#endif
    getVanillaIdentifier(Class<?> packetClass) {
        return vanillaClassToIdentifier.get(packetClass);
    }

    /**
     * 获取原版包的网络 ID
     *
     * @param type 标识符
     * @param side 网络方向
     * @return 包 ID，如果不是原版包返回 null
     */
    public Integer getVanillaPacketId(
#if MC_VER < MC_1_21_11
ResourceLocation
#else
Identifier
#endif
 type, PacketFlow side) {
        return side == PacketFlow.CLIENTBOUND ? vanillaIdS2C.get(type) : vanillaIdC2S.get(type);
    }

    /**
     * 获取包类型的索引
     *
     * @param identifier ResourceLocation 格式的标识符
     * @return 索引数组 [namespaceIndex, pathIndex]，如果未注册则返回 null
     */
    public int[] getIndex(String identifier) {
        String[] parts = identifier.split(":", 2);
        if (parts.length != 2) {
            return null;
        }

        String namespace = parts[0];
        String path = parts[1];

        Integer nsIndex = namespaceToIndex.get(namespace);
        if (nsIndex == null) {
            return null;
        }

        Map<String, Integer> nsPathToIndex = pathToIndex.get(nsIndex);
        if (nsPathToIndex == null) {
            return null;
        }

        Integer pathIndex = nsPathToIndex.get(path);
        if (pathIndex == null) {
            return null;
        }

        return new int[]{nsIndex, pathIndex};
    }

    /**
     * 根据索引获取包类型标识符
     *
     * @param namespaceIndex namespace 索引
     * @param pathIndex      path 索引
     * @return ResourceLocation 格式的标识符，如果索引无效则返回 null
     */
    public String getIdentifier(int namespaceIndex, int pathIndex) {
        if (namespaceIndex == ILLEGAL || pathIndex == ILLEGAL) {
            return null;
        }

        // 索引从 1 开始，所以需要检查 1 <= namespaceIndex <= namespaces.size()
        if (namespaceIndex < 1 || namespaceIndex > namespaces.size()) {
            return null;
        }

        String namespace = namespaces.get(namespaceIndex - 1); // 索引从 1 开始
        List<String> nsPaths = paths.get(namespaceIndex);
        if (nsPaths == null || pathIndex < 1 || pathIndex > nsPaths.size()) {
            return null;
        }

        String path = nsPaths.get(pathIndex - 1); // 索引从 1 开始
        return namespace + ":" + path;
    }

    /**
     * 检查是否包含指定的包类型
     */
    public boolean contains(String identifier) {
        return getIndex(identifier) != null;
    }

    /**
     * 获取所有注册的包类型
     */
    public Set<String> getAllIdentifiers() {
        Set<String> result = new HashSet<>();
        for (Map.Entry<Integer, Map<String, Integer>> entry : pathToIndex.entrySet()) {
            int nsIndex = entry.getKey();
            String namespace = namespaces.get(nsIndex - 1);
            for (String path : entry.getValue().keySet()) {
                result.add(namespace + ":" + path);
            }
        }
        return result;
    }

    /**
     * 获取注册的包类型数量
     */
    public int size() {
        int count = 0;
        for (Map<String, Integer> paths : pathToIndex.values()) {
            count += paths.size();
        }
        return count;
    }

    /**
     * 清空所有索引
     */
    public synchronized void clear() {
        namespaceToIndex.clear();
        namespaces.clear();
        pathToIndex.clear();
        paths.clear();
        nextNamespaceIndex = 1;
        nextPathIndex.clear();
        vanillaClassToIdentifier.clear();
        vanillaIdS2C.clear();
        vanillaIdC2S.clear();
    }

    /**
     * 序列化索引表为字节数组（用于握手同步）
     * <p>
     * 格式：
     * [namespaceCount:VarInt]
     * [namespace1Length:VarInt] [namespace1Bytes] [path1Count:VarInt] [path1Length:VarInt] [path1Bytes] ...
     * ...
     */
    public byte[] serialize() {
        // 使用 ByteArrayOutputStream 动态构建，避免缓冲区溢出
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(4096);

        // 写入 namespace 数量
        writeVarIntToStream(baos, namespaces.size());

        // 序列化每个 namespace
        for (int i = 1; i < nextNamespaceIndex; i++) {
            String namespace = namespaces.get(i - 1);
            List<String> nsPaths = paths.get(i);

            byte[] namespaceBytes = namespace.getBytes(java.nio.charset.StandardCharsets.UTF_8);

            // 写入 namespace
            writeVarIntToStream(baos, namespaceBytes.length);
            baos.write(namespaceBytes, 0, namespaceBytes.length);

            // 写入 path 数量
            writeVarIntToStream(baos, nsPaths.size());

            // 写入每个 path
            for (String path : nsPaths) {
                byte[] pathBytes = path.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                writeVarIntToStream(baos, pathBytes.length);
                baos.write(pathBytes, 0, pathBytes.length);
            }
        }

        return baos.toByteArray();
    }

    /**
     * 写入 VarInt 到 OutputStream
     */
    private static void writeVarIntToStream(java.io.ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    /**
     * 从字节数组反序列化索引表
     */
    public void deserialize(byte[] data) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data);

        // 读取 namespace 数量
        int namespaceCount = readVarIntBuf(buf);

        for (int i = 0; i < namespaceCount; i++) {
            // 读取 namespace
            int nsLength = readVarIntBuf(buf);
            byte[] nsBytes = new byte[nsLength];
            buf.get(nsBytes);
            String namespace = new String(nsBytes, java.nio.charset.StandardCharsets.UTF_8);

            // 注册 namespace
            if (!namespaceToIndex.containsKey(namespace)) {
                int nsIndex = nextNamespaceIndex++;
                namespaceToIndex.put(namespace, nsIndex);
                namespaces.add(namespace);
                pathToIndex.put(nsIndex, new ConcurrentHashMap<>());
                paths.put(nsIndex, new ArrayList<>());
                nextPathIndex.put(namespace, 1);
            }

            int nsIndex = namespaceToIndex.get(namespace);

            // 读取 path 数量
            int pathCount = readVarIntBuf(buf);

            // 读取每个 path
            for (int j = 0; j < pathCount; j++) {
                int pathLength = readVarIntBuf(buf);
                byte[] pathBytes = new byte[pathLength];
                buf.get(pathBytes);
                String path = new String(pathBytes, java.nio.charset.StandardCharsets.UTF_8);

                // 注册 path
                Map<String, Integer> nsPathToIndex = pathToIndex.get(nsIndex);
                if (!nsPathToIndex.containsKey(path)) {
                    int pathIndex = nextPathIndex.get(namespace);
                    nsPathToIndex.put(path, pathIndex);
                    paths.get(nsIndex).add(path);
                    nextPathIndex.put(namespace, pathIndex + 1);
                }
            }
        }

        LOGGER.info("Deserialized namespace index: {} namespaces, {} total types",
                namespaceCount, size());
    }

    /**
     * 写入 VarInt 到 ByteBuffer
     */
    private static void writeVarIntBuf(java.nio.ByteBuffer buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.put((byte) value);
    }

    /**
     * 从 ByteBuffer 读取 VarInt
     */
    private static int readVarIntBuf(java.nio.ByteBuffer buf) {
        int value = 0;
        int shift = 0;
        byte b;
        do {
            b = buf.get();
            value |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return value;
    }

    @Override
    public String toString() {
        return "NamespaceIndexManager{" +
                "namespaces=" + namespaces.size() +
                ", types=" + size() +
                '}';
    }
}
