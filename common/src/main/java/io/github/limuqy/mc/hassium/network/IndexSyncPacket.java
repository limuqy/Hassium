package io.github.limuqy.mc.hassium.network;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 索引同步包
 * <p>
 * 用于在握手阶段同步包类型索引表。
 * 服务端将索引表序列化后发送给客户端，客户端反序列化后建立本地索引。
 */
public class IndexSyncPacket {

    /**
     * 索引表数据
     */
    private final byte[] indexData;

    /**
     * 从索引管理器创建同步包
     */
    public IndexSyncPacket(NamespaceIndexManager indexManager) {
        this.indexData = indexManager.serialize();
    }

    /**
     * 从字节数组创建同步包
     */
    public IndexSyncPacket(byte[] indexData) {
        this.indexData = indexData;
    }

    /**
     * 获取索引数据
     */
    public byte[] getIndexData() {
        return indexData;
    }

    /**
     * 序列化为字节数组
     */
    public byte[] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(4 + indexData.length);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // 写入数据长度
        buffer.putInt(indexData.length);

        // 写入数据
        buffer.put(indexData);

        return buffer.array();
    }

    /**
     * 从字节数组反序列化
     */
    public static IndexSyncPacket decode(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // 读取数据长度
        int dataLength = buffer.getInt();

        // 读取数据
        byte[] indexData = new byte[dataLength];
        buffer.get(indexData);

        return new IndexSyncPacket(indexData);
    }

    /**
     * 应用索引数据到索引管理器
     */
    public void applyTo(NamespaceIndexManager indexManager) {
        indexManager.deserialize(indexData);
    }
}
