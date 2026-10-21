package io.github.limuqy.mc.hassium.compat;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
#if MC_VER < MC_1_21_2
import net.minecraft.network.protocol.game.*;
#endif

/** Known semantic packet shapes only. Unknowns are never guessed from their encoded first byte. */
public final class SleepPacketCompat {
    private SleepPacketCompat() {}
    public static net.minecraft.world.level.ChunkPos column(Packet<?> p) {
        BlockPos position=position(p);
        if(position!=null) return new net.minecraft.world.level.ChunkPos(position);
#if MC_VER < MC_1_21_2
        if(p instanceof ClientboundSectionBlocksUpdatePacket a) {
            net.minecraft.world.level.ChunkPos[] result={null};
            a.runUpdates((pos,state)->{ if(result[0]==null) result[0]=new net.minecraft.world.level.ChunkPos(pos); });
            return result[0];
        }
#endif
        return null;
    }
    public static boolean worldUpdate(Packet<?> p) {
#if MC_VER < MC_1_21_2
        return p instanceof ClientboundMoveEntityPacket || p instanceof ClientboundTeleportEntityPacket
                || p instanceof ClientboundRotateHeadPacket || p instanceof ClientboundSetEntityMotionPacket
                || p instanceof ClientboundAddEntityPacket || p instanceof ClientboundBundlePacket
                || p instanceof ClientboundSetEntityDataPacket || p instanceof ClientboundSetEquipmentPacket
                || p instanceof ClientboundSectionBlocksUpdatePacket || position(p)!=null;
#else
        return false;
#endif
    }
    public static Integer entityId(Packet<?> p, ServerLevel level) {
#if MC_VER < MC_1_21_2
        if (p instanceof ClientboundMoveEntityPacket a) { Entity e=a.getEntity(level); return e==null ? -1 : e.getId(); }
        if (p instanceof ClientboundTeleportEntityPacket a) return a.getId();
        if (p instanceof ClientboundRotateHeadPacket a) { Entity e=a.getEntity(level); return e==null ? -1:e.getId(); }
        if (p instanceof ClientboundSetEntityMotionPacket a) return a.getId();
        if (p instanceof ClientboundAnimatePacket a) return a.getId();
        if (p instanceof ClientboundHurtAnimationPacket a) return a.id();
        if (p instanceof ClientboundSetEntityLinkPacket a) return a.getSourceId();
        if (p instanceof ClientboundTakeItemEntityPacket a) return a.getItemId();
        if (p instanceof ClientboundAddEntityPacket a) return a.getId();
        if (p instanceof ClientboundAddExperienceOrbPacket a) return a.getId();
#if MC_VER < MC_1_21_1
        if (p instanceof ClientboundAddPlayerPacket a) return a.getEntityId();
#endif
        if (p instanceof ClientboundSetEntityDataPacket a) return a.id();
        if (p instanceof ClientboundSetEquipmentPacket a) return a.getEntity();
        if (p instanceof ClientboundUpdateAttributesPacket a) return a.getEntityId();
        if (p instanceof ClientboundUpdateMobEffectPacket a) return a.getEntityId();
        if (p instanceof ClientboundRemoveMobEffectPacket a) { Entity e=a.getEntity(level); return e==null ? -1:e.getId(); }
        if (p instanceof ClientboundEntityEventPacket a) { Entity e=a.getEntity(level); return e==null ? -1:e.getId(); }
        if (p instanceof ClientboundDamageEventPacket a) return a.entityId();
        if (p instanceof ClientboundSetPassengersPacket a) return a.getVehicle();
        if (p instanceof ClientboundSoundEntityPacket a) return a.getId();
#endif
        return null;
    }
    public static BlockPos position(Packet<?> p) {
#if MC_VER < MC_1_21_2
        if (p instanceof ClientboundBlockUpdatePacket a) return a.getPos();
        if (p instanceof ClientboundBlockEntityDataPacket a) return a.getPos();
        if (p instanceof ClientboundBlockEventPacket a) return a.getPos();
        if (p instanceof ClientboundBlockDestructionPacket a) return a.getPos();
        if (p instanceof ClientboundLevelEventPacket a) return a.getPos();
        if (p instanceof ClientboundLevelParticlesPacket a) return BlockPos.containing(a.getX(),a.getY(),a.getZ());
        if (p instanceof ClientboundSoundPacket a) return BlockPos.containing(a.getX(),a.getY(),a.getZ());
        if (p instanceof ClientboundLevelChunkWithLightPacket a) return new BlockPos(a.getX()*16,0,a.getZ()*16);
        if (p instanceof ClientboundLightUpdatePacket a) return new BlockPos(a.getX()*16,0,a.getZ()*16);
#endif
        return null;
    }
    public static boolean spawn(Packet<?> p) {
#if MC_VER < MC_1_21_2
#if MC_VER < MC_1_21_1
        if (p instanceof ClientboundAddPlayerPacket) return true;
#endif
        return p instanceof ClientboundAddEntityPacket || p instanceof ClientboundAddExperienceOrbPacket;
#else
        return false;
#endif
    }
    public static Iterable<? extends Packet<?>> children(Packet<?> p) {
#if MC_VER < MC_1_21_2
        if (p instanceof ClientboundBundlePacket a) return a.subPackets();
#endif
        return null;
    }
    public static Packet<?> bundle(java.util.List<Packet<?>> children) {
#if MC_VER < MC_1_21_2
        @SuppressWarnings({"rawtypes","unchecked"}) Packet<?> p = new ClientboundBundlePacket((Iterable)children); return p;
#else
        throw new UnsupportedOperationException();
#endif
    }
    public static Packet<?> remove(int... ids) {
#if MC_VER < MC_1_21_2
        return new ClientboundRemoveEntitiesPacket(ids);
#else
        throw new UnsupportedOperationException();
#endif
    }
    public static int[] removed(Packet<?> p) {
#if MC_VER < MC_1_21_2
        if (p instanceof ClientboundRemoveEntitiesPacket a) return a.getEntityIds().toIntArray();
#endif
        return null;
    }
    public static boolean sectionAllowed(Packet<?> p, java.util.function.Predicate<BlockPos> allowed) {
#if MC_VER < MC_1_21_2
        if (p instanceof ClientboundSectionBlocksUpdatePacket a) {
            boolean[] any = {false}; a.runUpdates((pos,state)-> {if (allowed.test(pos)) any[0]=true;}); return any[0];
        }
#endif
        return true;
    }
}
