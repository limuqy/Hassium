package io.github.limuqy.mc.hassium.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 缓冲 NBT 写入流，收集完整数据后通过回调触发 Hassium 压缩写入。
 */
public class HassiumChunkWriteBuffer extends ByteArrayOutputStream {

    @FunctionalInterface
    public interface WriteHandler {
        void accept(byte[] data) throws IOException;
    }

    private final WriteHandler handler;

    public HassiumChunkWriteBuffer(WriteHandler handler) {
        super(8192);
        this.handler = handler;
    }

    @Override
    public void close() throws IOException {
        super.close();
        byte[] data = this.toByteArray();
        if (data.length > 0) {
            handler.accept(data);
        }
    }
}
