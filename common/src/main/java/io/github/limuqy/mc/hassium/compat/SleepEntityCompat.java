package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.mixin.server.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import java.util.*;
import net.minecraft.network.protocol.game.*;
#if MC_VER < MC_1_21_2
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
#endif

/** A read-only seed of ONE observer. Never calls sendPairingData/packDirty or resets the shared tracker. */
public final class SleepEntityCompat {
    private SleepEntityCompat() {}
    public static List<Entity> observed(ServerPlayer player) {
        List<Entity> result = new ArrayList<>();
        ServerLevel level = PlayerCompat.getServerLevel(player);
        for (var entry : ((SleepChunkMapAccess)level.getChunkSource().chunkMap).hassium$sleepEntityMap().int2ObjectEntrySet()) {
            var tracker = (SleepTrackedEntityAccess)entry.getValue();
            if (tracker.hassium$sleepObservers().contains(player.connection)) {
                Entity entity = level.getEntity(entry.getIntKey());
                if (entity != null && !entity.isRemoved()) result.add(entity);
            }
        }
        return result;
    }
    public static List<Packet<?>> seed(ServerPlayer observer, Entity entity) {
        var tracked = ((SleepChunkMapAccess)PlayerCompat.getServerLevel(observer).getChunkSource().chunkMap).hassium$sleepEntityMap().get(entity.getId());
        if (!(tracked instanceof SleepTrackedEntityAccess access)) return List.of();
        var tracker = access.hassium$sleepTracker();
        var beforeBase=positionBase(tracker);
        boolean beforeDirty=entity.getEntityData().isDirty();
        int beforeAttributes=dirtyAttributes(entity);
        List<Packet<?>> packets = new ArrayList<>();
#if MC_VER < MC_1_21_1
        packets.add(entity.getAddEntityPacket());
#else
        packets.add(entity.getAddEntityPacket(tracker));
#endif
        var data = entity.getEntityData().getNonDefaultValues();
        if (data != null && !data.isEmpty()) packets.add(new ClientboundSetEntityDataPacket(entity.getId(), data));
        if (entity instanceof LivingEntity living) {
            packets.add(new ClientboundUpdateAttributesPacket(entity.getId(),living.getAttributes().getSyncableAttributes()));
            List<com.mojang.datafixers.util.Pair<net.minecraft.world.entity.EquipmentSlot,net.minecraft.world.item.ItemStack>> slots = new ArrayList<>();
            for (var slot : net.minecraft.world.entity.EquipmentSlot.values()) slots.add(com.mojang.datafixers.util.Pair.of(slot,living.getItemBySlot(slot).copy()));
            packets.add(new ClientboundSetEquipmentPacket(entity.getId(),slots));
            for (var effect : living.getActiveEffects()) {
#if MC_VER < MC_1_21_1
                packets.add(new ClientboundUpdateMobEffectPacket(entity.getId(),effect));
#else
                packets.add(new ClientboundUpdateMobEffectPacket(entity.getId(),effect,false));
#endif
            }
        }
        // R0 invariant: seed decoder base B, not the entity's newer current position.
        var base = positionBase(tracker);
#if MC_VER < MC_1_21_2
        FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.buffer());
        try {
            b.writeVarInt(entity.getId()); b.writeDouble(base.x); b.writeDouble(base.y); b.writeDouble(base.z);
            b.writeByte(net.minecraft.util.Mth.floor(entity.getYRot()*256/360));
            b.writeByte(net.minecraft.util.Mth.floor(entity.getXRot()*256/360)); b.writeBoolean(entity.onGround());
#if MC_VER < MC_1_21_1
            packets.add(new ClientboundTeleportEntityPacket(b));
#else
            packets.add(ClientboundTeleportEntityPacket.STREAM_CODEC.decode(b));
#endif
        } finally { b.release(); }
#else
        packets.add(new ClientboundEntityPositionSyncPacket(entity.getId(),
                new net.minecraft.world.entity.PositionMoveRotation(base,entity.getDeltaMovement(),entity.getYRot(),entity.getXRot()),entity.onGround()));
#endif
        packets.add(new ClientboundSetEntityMotionPacket(entity));
        packets.add(new ClientboundRotateHeadPacket(entity,(byte)net.minecraft.util.Mth.floor(entity.getYHeadRot()*256/360)));
        packets.addAll(relationships(entity));
        if(io.github.limuqy.mc.hassium.server.sleep.SleepRuntimeProbe.ENABLED
                && (!beforeBase.equals(positionBase(tracker)) || beforeDirty!=entity.getEntityData().isDirty()
                || beforeAttributes!=dirtyAttributes(entity)))
            throw new IllegalStateException("Sleep snapshot modified shared tracker/dirty state");
        return packets;
    }
    private static net.minecraft.world.phys.Vec3 positionBase(net.minecraft.server.level.ServerEntity tracker) {
#if MC_VER < MC_1_21_2
        return ((SleepVecDeltaAccess)((SleepServerEntityAccess)tracker).hassium$sleepPositionCodec()).hassium$sleepBase();
#else
        return tracker.getPositionBase();
#endif
    }
    /** Repeat after every endpoint has been spawned; initial tracker iteration order is arbitrary. */
    public static List<Packet<?>> relationships(Entity entity) {
        List<Packet<?>> packets=new ArrayList<>();
        if (!entity.getPassengers().isEmpty()) packets.add(new ClientboundSetPassengersPacket(entity));
        if (entity.isPassenger()) packets.add(new ClientboundSetPassengersPacket(entity.getVehicle()));
#if MC_VER < MC_1_21_1
        if (entity instanceof net.minecraft.world.entity.Mob mob && mob.isLeashed()) packets.add(new ClientboundSetEntityLinkPacket(mob,mob.getLeashHolder()));
#else
        if (entity instanceof net.minecraft.world.entity.Leashable leash && leash.isLeashed()) packets.add(new ClientboundSetEntityLinkPacket(entity,leash.getLeashHolder()));
#endif
        return packets;
    }
    public static double interactionRadius(ServerPlayer player) {
#if MC_VER < MC_1_21_1
        return io.github.limuqy.mc.hassium.platform.Services.PLATFORM.sleepInteractionRadius(player);
#else
        return Math.max(8,Math.max(player.blockInteractionRange(),player.entityInteractionRange())+2);
#endif
    }
    private static int dirtyAttributes(Entity entity) {
#if MC_VER < MC_1_21_1
        return entity instanceof LivingEntity l ? l.getAttributes().getDirtyAttributes().size() : 0;
#else
        return entity instanceof LivingEntity l ? l.getAttributes().getAttributesToSync().size() : 0;
#endif
    }
}
