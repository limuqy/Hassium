package io.github.limuqy.mc.hassium.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.world.level.ChunkPos;

/**
 * 压缩 .mca 映像：只挂 header + 各槽压缩载荷，不解压整柱。
 * 供尚未注入的柱 {@code probeHash}/{@code readChunk}；同一 {@code r.x.z} 只 load 一次。
 */
public final class RegionCache {

    static final int SECTOR_SIZE = 4096;
    static final int SLOTS = 1024;
    private static final Pattern REGION_FILE_NAME =
            Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private RegionCache() {}

    public static long regionKey(int chunkX, int chunkZ) {
        return ChunkPos.asLong(Math.floorDiv(chunkX, 32), Math.floorDiv(chunkZ, 32));
    }

    public static Path regionFile(Path regionDir, int chunkX, int chunkZ) {
        int rx = Math.floorDiv(chunkX, 32);
        int rz = Math.floorDiv(chunkZ, 32);
        return regionDir.resolve("r." + rx + "." + rz + ".mca");
    }

    public static Path regionFileByKey(Path regionDir, long regionKey) {
        int rx = (int) regionKey;
        int rz = (int) (regionKey >> 32);
        return regionDir.resolve("r." + rx + "." + rz + ".mca");
    }

    public static int localIndex(int chunkX, int chunkZ) {
        return (chunkX & 31) + (chunkZ & 31) * 32;
    }

    public static int regionXOf(long regionKey) {
        return (int) regionKey;
    }

    public static int regionZOf(long regionKey) {
        return (int) (regionKey >> 32);
    }

    /**
     * 从 {@code r.X.Z.mca} 解析 region 键（{@link ChunkPos#asLong(int, int)}）；
     * 文件名不符返回 {@code null}。只认名字，不打开文件。
     */
    public static Long regionKeyFromFileName(String name) {
        if (name == null) {
            return null;
        }
        Matcher matcher = REGION_FILE_NAME.matcher(name);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return ChunkPos.asLong(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 标准 Anvil 头：1024 个 big-endian int，高 24 位扇区偏移、低 8 位扇区数。
     * 勿按 {@code header[i], header[i+1024], header[i+2048]} 三平面读——那会把
     * 几乎所有槽判空，R2 Bloom 上报失败后服务端当 ROUND1 直推。
     */
    public static int locationAt(byte[] header, int index) {
        if (header == null || index < 0 || index >= SLOTS || header.length < (index + 1) * 4) {
            return 0;
        }
        int o = index * 4;
        return ((header[o] & 0xFF) << 24)
                | ((header[o + 1] & 0xFF) << 16)
                | ((header[o + 2] & 0xFF) << 8)
                | (header[o + 3] & 0xFF);
    }

    /** 一个 region 文件的压缩映像（无未压缩 NBT 槽）。 */
    public static final class Image {
        private final byte[][] payloads = new byte[SLOTS][];
        private final Long[] hashes = new Long[SLOTS];
        private final int[] timestamps = new int[SLOTS];
        private boolean fileDirty;

        public static Image empty() {
            return new Image();
        }

        public static Image load(Path file) throws IOException {
            if (file == null || !Files.isRegularFile(file) || Files.size(file) < SECTOR_SIZE) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(file);
            Image image = new Image();
            ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
            for (int i = 0; i < SLOTS; i++) {
                int loc = buf.getInt(i * 4);
                if (loc == 0) {
                    continue;
                }
                int sectorOffset = loc >>> 8;
                int numSectors = loc & 0xFF;
                if (numSectors == 0) {
                    continue;
                }
                int fileOffset = sectorOffset * SECTOR_SIZE;
                if (fileOffset + 5 > bytes.length) {
                    continue;
                }
                int length = buf.getInt(fileOffset);
                byte type = bytes[fileOffset + 4];
                if (type != HassiumType126Codec.COMPRESSION_TYPE || length <= 1) {
                    continue;
                }
                int dataLen = length - 1;
                if (fileOffset + 5 + dataLen > bytes.length) {
                    dataLen = Math.max(0, bytes.length - fileOffset - 5);
                }
                byte[] payload = new byte[dataLen];
                System.arraycopy(bytes, fileOffset + 5, payload, 0, dataLen);
                image.payloads[i] = payload;
                image.hashes[i] = HassiumType126Codec.probeHash(payload);
                if (bytes.length >= SECTOR_SIZE * 2) {
                    image.timestamps[i] = buf.getInt(SECTOR_SIZE + i * 4);
                }
            }
            return image;
        }

        public synchronized boolean isEmptySlot(int index) {
            return index < 0 || index >= SLOTS || payloads[index] == null;
        }

        public synchronized Long probeHash(int index) {
            if (isEmptySlot(index)) {
                return null;
            }
            return hashes[index];
        }

        public synchronized boolean hasSlot(int index) {
            return !isEmptySlot(index);
        }

        /**
         * 解压该槽。调用方负责 inject；映像不保留 NBT。
         */
        public synchronized byte[] readDecompressed(int index, AtomicInteger decompressCount) throws IOException {
            if (isEmptySlot(index)) {
                return null;
            }
            if (decompressCount != null) {
                decompressCount.incrementAndGet();
            }
            HassiumType126Codec.Decoded decoded = HassiumType126Codec.decode(payloads[index]);
            if (decoded.contentHash() != null) {
                hashes[index] = decoded.contentHash();
            }
            return decoded.nbt();
        }

        public synchronized void writePayload(int index, byte[] payloadAfterType, Long hash) {
            payloads[index] = payloadAfterType;
            hashes[index] = hash;
            timestamps[index] = (int) (System.currentTimeMillis() / 1000L);
            fileDirty = true;
        }

        public synchronized void clearSlot(int index) {
            payloads[index] = null;
            hashes[index] = null;
            timestamps[index] = 0;
            fileDirty = true;
        }

        public synchronized boolean hasAnySlot() {
            for (byte[] payload : payloads) {
                if (payload != null) {
                    return true;
                }
            }
            return false;
        }

        /** 内存槽已改、尚未 {@link #save}。 */
        public synchronized boolean isFileDirty() {
            return fileDirty;
        }

        public synchronized void save(Path file) throws IOException {
            if (!fileDirty) {
                return;
            }
            Files.createDirectories(file.getParent());
            int cursor = 2;
            int[] locations = new int[SLOTS];
            for (int i = 0; i < SLOTS; i++) {
                byte[] payload = payloads[i];
                if (payload == null) {
                    continue;
                }
                int size = 5 + payload.length;
                int sectors = (size + SECTOR_SIZE - 1) / SECTOR_SIZE;
                if (sectors > 255) {
                    sectors = 255;
                }
                locations[i] = (cursor << 8) | sectors;
                cursor += sectors;
            }
            byte[] out = new byte[cursor * SECTOR_SIZE];
            ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
            for (int i = 0; i < SLOTS; i++) {
                buf.putInt(i * 4, locations[i]);
                buf.putInt(SECTOR_SIZE + i * 4, timestamps[i]);
            }
            for (int i = 0; i < SLOTS; i++) {
                byte[] payload = payloads[i];
                if (payload == null) {
                    continue;
                }
                int fileOffset = (locations[i] >>> 8) * SECTOR_SIZE;
                buf.putInt(fileOffset, 1 + payload.length);
                out[fileOffset + 4] = HassiumType126Codec.COMPRESSION_TYPE;
                System.arraycopy(payload, 0, out, fileOffset + 5, payload.length);
            }
            Files.write(file, out);
            fileDirty = false;
        }
    }
}
