package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.compat.LevelHeightCompat;
import io.github.limuqy.mc.hassium.compat.LightAccessCompat;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个 16×16 区块柱的光照相关静态快照（主线程捕获，不可变，可跨线程）。
 * <p>
 * 后台 BFS 不触碰 level/BlockState；本快照预取：
 * <ul>
 *   <li>{@code lightBlock}：每格原始 getLightBlock（0–15）；</li>
 *   <li>{@code emitters}：发光源 sparse 表（packed {@code (emission << 20) | index}）；</li>
 *   <li>{@code shapeCells/shapeFaces}：{@code canOcclude && useShapeForLightOcclusion} 格的
 *       6 方向遮挡面 {@link VoxelShape} 引用（VoxelShape 不可变、可跨线程；静态形状方块
 *       的 6 面来自 BlockStateBase 缓存，跨格共享同一实例）；</li>
 *   <li>{@code sourceY}：每列天空注入 y（官方 ChunkSkyLightSources 语义：从顶向下第一个
 *       「下格 lightBlock != 0 或形状遮挡」边缘上方的格；无则 {@link LightFloodFill#NEG_INF}）。</li>
 * </ul>
 */
public final class LightColumnSnapshot {

    /** 柱内格索引：{@code yLocal * 256 + z * 16 + x}（yLocal ∈ [0, height)）。 */
    private final int chunkX;
    private final int chunkZ;
    private final int minY;
    private final int height;
    private final byte[] lightBlock;
    private final int[] emitters;
    private final int[] sourceY;
    private final int[] shapeCells;
    private final VoxelShape[][] shapeFaces;

    private LightColumnSnapshot(int chunkX, int chunkZ, int minY, int height,
                                byte[] lightBlock, int[] emitters, int[] sourceY,
                                int[] shapeCells, VoxelShape[][] shapeFaces) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minY = minY;
        this.height = height;
        this.lightBlock = lightBlock;
        this.emitters = emitters;
        this.sourceY = sourceY;
        this.shapeCells = shapeCells;
        this.shapeFaces = shapeFaces;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public int getMinY() {
        return minY;
    }

    public int getHeight() {
        return height;
    }

    public byte[] getLightBlock() {
        return lightBlock;
    }

    public int[] getEmitters() {
        return emitters;
    }

    public int[] getSourceY() {
        return sourceY;
    }

    public int[] getShapeCells() {
        return shapeCells;
    }

    public VoxelShape[][] getShapeFaces() {
        return shapeFaces;
    }

    /** 空柱占位（未加载邻居；全透明、无光源、无注入；缺块官方语义 = 无任何填充/种子）。 */
    public static LightColumnSnapshot empty(int minY, int height) {
        int[] noColumn = new int[256];
        java.util.Arrays.fill(noColumn, LightFloodFill.NO_COLUMN);
        return new LightColumnSnapshot(0, 0, minY, height,
                new byte[256 * height], new int[0], noColumn, new int[0], new VoxelShape[0][]);
    }

    /**
     * 主线程捕获柱快照（输入一致性：全部格数据同一时刻读取）。
     */
    public static LightColumnSnapshot capture(ClientLevel level, int chunkX, int chunkZ) {
        int minY = LevelHeightCompat.getMinBlockY(level);
        int height = level.getHeight();
        byte[] lb = new byte[256 * height];
        IntArrayList emitters = new IntArrayList();
        IntArrayList shapeCells = new IntArrayList();
        List<VoxelShape[]> shapeFaces = new ArrayList<>();
        int[] sy = new int[256];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        VoxelShape empty = Shapes.empty();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int sourceY = LightFloodFill.NEG_INF;
                // 上一个格的 DOWN 面（虚拟空气 = empty）
                VoxelShape aboveDown = empty;
                int baseX = chunkX * 16 + x;
                int baseZ = chunkZ * 16 + z;
                for (int yLocal = height - 1; yLocal >= 0; yLocal--) {
                    int y = minY + yLocal;
                    int cell = yLocal * 256 + z * 16 + x;
                    pos.set(baseX, y, baseZ);
                    BlockState state = level.getBlockState(pos);
                    int lbv = LightAccessCompat.getLightBlock(state, level, pos);
                    lb[cell] = (byte) lbv;

                    int emission = state.getLightEmission();
                    if (emission > 0) {
                        emitters.add((emission << 20) | cell);
                    }

                    VoxelShape[] faces = null;
                    if (state.canOcclude() && state.useShapeForLightOcclusion()) {
                        faces = new VoxelShape[6];
                        for (Direction d : Direction.values()) {
                            faces[d.ordinal()] = LightAccessCompat.getFaceOcclusionShape(state, level, pos, d);
                        }
                        shapeCells.add(cell);
                        shapeFaces.add(faces);
                    }

                    // 天空注入边缘：下格 lightBlock != 0 或（上格 DOWN 面 ∩ 下格 UP 面）
                    if (sourceY == LightFloodFill.NEG_INF) {
                        boolean edge;
                        if (lbv != 0) {
                            edge = true;
                        } else {
                            VoxelShape belowUp = faces == null ? empty : faces[Direction.UP.ordinal()];
                            edge = !aboveDown.isEmpty() && !belowUp.isEmpty()
                                    && Shapes.faceShapeOccludes(aboveDown, belowUp);
                        }
                        if (edge) {
                            sourceY = yLocal + 1;
                        }
                    }
                    aboveDown = faces == null ? empty : faces[Direction.DOWN.ordinal()];
                }
                sy[z * 16 + x] = sourceY;
            }
        }

        return new LightColumnSnapshot(chunkX, chunkZ, minY, height, lb,
                emitters.toIntArray(), sy, shapeCells.toIntArray(),
                shapeFaces.toArray(new VoxelShape[0][]));
    }
}
