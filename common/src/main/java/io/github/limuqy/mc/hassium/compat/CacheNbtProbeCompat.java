package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.Constants;
import net.minecraft.server.MinecraftServer;

/** Dev-only R1/R2 wall fixture; does not alter the cache protocol or production synchronization. */
public final class CacheNbtProbeCompat {
    private CacheNbtProbeCompat() {}
    /** Read-stage evidence before any R2 authority response can replace the cached NBT. */
    public static void disk(net.minecraft.world.level.chunk.LevelChunk chunk, net.minecraft.nbt.CompoundTag root) {
#if MC_VER < MC_1_21_2
        if(!"cachenbt".equals(System.getProperty("hassium.smokeScenario")) || chunk.getPos().z!=0
                || (chunk.getPos().x!=0 && chunk.getPos().x!=2)) return;
        Constants.LOG.info("HassiumSmokeTest:CACHE_NBT disk pos={} stored={}",chunk.getPos(),root.get("block_entities"));
        for(int i=0;i<XS.length;i++) if((XS[i]>>4)==chunk.getPos().x) {
            var be=chunk.getBlockEntity(pos(i));
            Constants.LOG.info("HassiumSmokeTest:CACHE_NBT loaded pos={} text={} waxed={}",pos(i),
                    be instanceof net.minecraft.world.level.block.entity.SignBlockEntity s?s.getFrontText().getMessage(0,false).getString():"MISSING",
                    be instanceof net.minecraft.world.level.block.entity.SignBlockEntity s && s.isWaxed());
        }
#endif
    }
#if MC_VER < MC_1_21_2
    private static boolean prepared, mutated;
    private static final int[] XS={8,10,40,42,12,14,44,46};
    private static final long[] baseline=new long[2];
    private static net.minecraft.core.BlockPos pos(int i) { return new net.minecraft.core.BlockPos(XS[i],68,3); }
    private static long hash(net.minecraft.server.level.ServerLevel level,int x) {
        return io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil.combineSectionHashes(
                io.github.limuqy.mc.hassium.cache.ChunkContentHashUtil.computeSectionHashes(level.getChunk(x,0)));
    }
    private static boolean enabled() {
        return io.github.limuqy.mc.hassium.server.ServerSmokeTest.isEnabled()
                && "cachenbt".equals(System.getProperty("hassium.serverSmokeScenario"));
    }
#endif
    public static void tick(MinecraftServer server) {
#if MC_VER < MC_1_21_2
        if(!enabled() || prepared || server.getPlayerList().getPlayers().stream().noneMatch(p->p.getTags().contains("cache_nbt_setup"))) return;
        prepared=true;
        var level=server.overworld();
        // Same stone wall as the existing offline R2 fixture, built in R1 so NBT-only
        // columns can retain exactly the same block hash across the reconnect.
        for(int x=-160;x<160;x++) for(int z=2;z<4;z++) for(int y=64;y<68;y++)
            level.setBlock(new net.minecraft.core.BlockPos(x,y,z),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),3);
        for(int i=0;i<XS.length;i++) {
            level.setBlock(pos(i),(i%2==0?net.minecraft.world.level.block.Blocks.OAK_SIGN:net.minecraft.world.level.block.Blocks.BIRCH_SIGN).defaultBlockState(),3);
            var sign=(net.minecraft.world.level.block.entity.SignBlockEntity)level.getBlockEntity(pos(i));
            sign.setText(sign.getFrontText().setMessage(0,net.minecraft.network.chat.Component.literal("R1_"+i)),true);
            sign.setWaxed(false); sign.setChanged();
            level.sendBlockUpdated(pos(i),sign.getBlockState(),sign.getBlockState(),3);
        }
        baseline[0]=hash(level,2); baseline[1]=hash(level,0);
        Constants.LOG.info("HassiumSmokeTest:CACHE_NBT server R1 prepared signs={} controlHash={} nbtOnlyHash={}",XS.length,Long.toHexString(baseline[0]),Long.toHexString(baseline[1]));
#endif
    }
    public static void offline(MinecraftServer server) {
#if MC_VER < MC_1_21_2
        if(!enabled() || !prepared || mutated) return;
        mutated=true;
        var level=server.overworld();
        // R1 had forty seconds to settle vanilla block updates. Compare immediately
        // around the offline mutation; the old generic R2 wall is not repainted here.
        baseline[0]=hash(level,2); baseline[1]=hash(level,0);
        level.setBlock(new net.minecraft.core.BlockPos(44,66,3),net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(),3);
        for(int i=0;i<XS.length;i++) {
            if(i==4) continue; // Exact R1 NBT survives the reconnect unchanged.
            if(i==6 || i==7) {
                level.setBlock(pos(i),(i==6?net.minecraft.world.level.block.Blocks.AIR:net.minecraft.world.level.block.Blocks.CHEST).defaultBlockState(),3);
                continue;
            }
            var sign=(net.minecraft.world.level.block.entity.SignBlockEntity)level.getBlockEntity(pos(i));
            sign.setText(sign.getFrontText().setMessage(0,net.minecraft.network.chat.Component.literal(i==5?"":"R2_"+i)),true);
            sign.setWaxed(true); sign.setChanged();
            level.sendBlockUpdated(pos(i),sign.getBlockState(),sign.getBlockState(),3);
            Constants.LOG.info("HassiumSmokeTest:CACHE_NBT server R2 pos={} text={} waxed={} players={}",pos(i),sign.getFrontText().getMessage(0,false).getString(),sign.isWaxed(),server.getPlayerList().getPlayerCount());
        }
        long control=hash(level,2), nbtOnly=hash(level,0);
        Constants.LOG.info("HassiumSmokeTest:CACHE_NBT offline controlChanged={} nbtOnlyUnchanged={} controlBefore={} controlAfter={} nbtBefore={} nbtAfter={}",
                control!=baseline[0],nbtOnly==baseline[1],Long.toHexString(baseline[0]),Long.toHexString(control),Long.toHexString(baseline[1]),Long.toHexString(nbtOnly));
        if(control==baseline[0] || nbtOnly!=baseline[1]) throw new IllegalStateException("Cache NBT fixture hash isolation failed");
#endif
    }
    public static void assertClient(int round) {
        Client.assertClient(round);
    }
    /** Dedicated-server verification must never resolve ClientLevel while loading the fixture. */
    private static final class Client {
    private static void assertClient(int round) {
#if MC_VER < MC_1_21_2
        var mc=net.minecraft.client.Minecraft.getInstance();
        int expectedRound=Math.min(round,2);
        if(round==0) {
            var shadow=io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry.getInstance().get();
            var dim=LevelCompat.getDimensionId(mc.level);
            var tag=new net.minecraft.nbt.CompoundTag(); tag.putBoolean("is_waxed",false);
            ShadowPoiGate.runExclusive(shadow,()->{
                var chunk=shadow.injectedChunk(dim,0,0);
                BlockEntityCompat.loadFromTag(chunk.getBlockEntity(pos(0)),tag,shadow.level(dim).registryAccess());
            });
            BlockEntityCompat.loadFromTag(mc.level.getBlockEntity(pos(0)),tag,mc.level.registryAccess());
            var column=new net.minecraft.world.level.ChunkPos(0,0);
            io.github.limuqy.mc.hassium.protocol.ShadowPullClient.clearCompareRequested(dim,column);
            io.github.limuqy.mc.hassium.protocol.ShadowPullClient.requestNbtRepairFull(dim,java.util.List.of(column));
            Constants.LOG.info("HassiumSmokeTest:CACHE_NBT force_full sameBlockHash=true poisonedWax=false");
            return;
        }
        int stale=0;
        for(int i=0;i<XS.length;i++) {
            var be=mc.level.getBlockEntity(pos(i));
            String text=be instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign?sign.getFrontText().getMessage(0,false).getString():"MISSING";
            boolean waxed=be instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign && sign.isWaxed();
            String expected=expectedRound==2 && i==4?"R1_4":expectedRound==2 && i==5?"":"R"+expectedRound+"_"+i;
            boolean correct=text.equals(expected) && waxed==(expectedRound==2 && i!=4);
            if(expectedRound==2 && i==6) correct=be==null && mc.level.getBlockState(pos(i)).isAir();
            if(expectedRound==2 && i==7) correct=be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity;
            Constants.LOG.info("HassiumSmokeTest:CACHE_NBT client R{} case={} pos={} expected={} actual={} waxed={} correct={}",round,i,pos(i),expected,text,waxed,correct);
            if(!correct) stale++;
        }
        boolean wall=mc.level.getBlockState(new net.minecraft.core.BlockPos(44,66,3)).is(expectedRound==2?net.minecraft.world.level.block.Blocks.GOLD_BLOCK:net.minecraft.world.level.block.Blocks.STONE);
        Constants.LOG.info("HassiumSmokeTest:CACHE_NBT client R{} wallCorrect={} staleSigns={}",round,wall,stale);
        if(stale!=0 || !wall) throw new IllegalStateException("CACHE_NBT R"+round+" staleSigns="+stale+" wallCorrect="+wall);
#else
        throw new IllegalStateException("Cache NBT fixture targets 1.20.1 / 1.21.1");
#endif
    }
    }
}
