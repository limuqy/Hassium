package io.github.limuqy.mc.hassium.compat;

import javax.annotation.Nullable;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
#if MC_VER >= MC_1_21_2
import net.minecraft.world.entity.PositionMoveRotation;
#endif

/**
 * 实体同步包跨版本 getter 归一化：七类包在影子端应用的版本差异全部收敛于此，
 * 业务代码（ShadowSeedServer）零散 {@code #if MC_VER}。
 * <p>
 * 分界（docs/version-segments.md 白名单）：
 * <ul>
 *   <li>{@code MC_1_21_1}：Motion getter int（8000 量化原值）→ double（getter 已除 8000）</li>
 *   <li>{@code MC_1_21_2}：Teleport 重构 record + Relative（绝对坐标必须
 *       {@code PositionMoveRotation.calculateAbsolute}）；MoveEntity/RotateHead 旋转
 *       getter byte（1/256）→ float（Mth.unpackDegrees）；EntityType.create 加
 *       EntitySpawnReason.LOAD（后者在 ShadowSeedServer 内按同一分界处理）</li>
 *   <li>{@code MC_1_21_5}：MoveEntity 旋转 getter 旧名 getyRot/getxRot（float）→
 *       新名 getYRot/getXRot（float）</li>
 *   <li>{@code MC_1_21_9}：Motion 位移三元组 → Vec3（readLpVec3，1/4096）</li>
 * </ul>
 */
public final class EntityPacketCompat {

    private EntityPacketCompat() {
    }

    /** MoveEntity：yaw 旋转（角度制 float）。段 A–D byte*360/256 / 段 E 旧名 float / 段 F+ 新名 float。 */
    public static float moveYRot(ClientboundMoveEntityPacket packet) {
#if MC_VER < MC_1_21_2
        return packet.getyRot() * 360.0F / 256.0F;
#elif MC_VER < MC_1_21_5
        return packet.getyRot();
#else
        return packet.getYRot();
#endif
    }

    /** MoveEntity：pitch 旋转（角度制 float）。分段同 {@link #moveYRot}。 */
    public static float moveXRot(ClientboundMoveEntityPacket packet) {
#if MC_VER < MC_1_21_2
        return packet.getxRot() * 360.0F / 256.0F;
#elif MC_VER < MC_1_21_5
        return packet.getxRot();
#else
        return packet.getXRot();
#endif
    }

    /** RotateHead：yHeadRot（角度制 float）。段 A–D byte / 段 E+ float。 */
    public static float headYRot(ClientboundRotateHeadPacket packet) {
#if MC_VER < MC_1_21_2
        return packet.getYHeadRot() * 360.0F / 256.0F;
#else
        return packet.getYHeadRot();
#endif
    }

    /** TeleportEntity：实体 id（段 A–D getId() / 段 E+ record id()）。 */
    public static int teleportId(ClientboundTeleportEntityPacket packet) {
#if MC_VER < MC_1_21_2
        return packet.getId();
#else
        return packet.id();
#endif
    }

    /**
     * SetEntityMotion：绝对速度 Vec3。段 A–C int（8000 量化原值，自除）；
     * 段 D–G getter 已除 8000 直用；段 H+ Vec3（readLpVec3，1/4096）。
     */
    public static Vec3 motionVec(ClientboundSetEntityMotionPacket packet) {
#if MC_VER < MC_1_21_1
        return new Vec3(packet.getXa() / 8000.0, packet.getYa() / 8000.0, packet.getZa() / 8000.0);
#elif MC_VER < MC_1_21_9
        return new Vec3(packet.getXa(), packet.getYa(), packet.getZa());
#else
        return packet.getMovement();
#endif
    }

    /**
     * Teleport 归一化结果：绝对位置/速度/旋转/落地标志。
     * {@code deltaMovement == null} 表示包内无运动字段（段 A–D），调用方不动实体的速度。
     */
    public record TeleportState(Vec3 position, @Nullable Vec3 deltaMovement, float yRot, float xRot, boolean onGround) {
    }

    /**
     * Teleport 绝对状态：段 A–D 包字段直取（getX/Y/Z + byte 旋转）；段 E+ 按
     * {@code PositionMoveRotation.calculateAbsolute(prev, change, relatives)} 计算
     * （prev = 实体当前位置/旋转/已知运动），相对位以实体当前状态为基准。
     */
    public static TeleportState teleportState(Entity entity, ClientboundTeleportEntityPacket packet) {
#if MC_VER < MC_1_21_2
        return new TeleportState(
                new Vec3(packet.getX(), packet.getY(), packet.getZ()),
                null,
                packet.getyRot() * 360.0F / 256.0F,
                packet.getxRot() * 360.0F / 256.0F,
                packet.isOnGround());
#else
        PositionMoveRotation abs = PositionMoveRotation.calculateAbsolute(
                PositionMoveRotation.of(entity), packet.change(), packet.relatives());
        return new TeleportState(abs.position(), abs.deltaMovement(), abs.yRot(), abs.xRot(), packet.onGround());
#endif
    }
}
