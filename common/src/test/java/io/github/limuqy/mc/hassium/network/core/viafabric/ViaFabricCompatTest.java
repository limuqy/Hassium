package io.github.limuqy.mc.hassium.network.core.viafabric;

import io.github.limuqy.mc.hassium.network.core.NetworkCore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ViaFabric 兼容（T9）：检测两态（classpath 探测 / 平台 mod 列表）+ 取包处接入降级路径
 * + NetworkCore 翻译器缝（注入器之前应用、异常退回原包）。
 *
 * <p>ViaFabric 不在测试 classpath，桥构建在测试环境必然失败（无 Minecraft 客户端连接）——
 * 正好覆盖「接入异常 → 退回直接注入不崩」的验收路径；转换成功路径为骨架，冒烟见 T9-TASK.md。
 */
class ViaFabricCompatTest {

    private final ViaFabricCompat compat = ViaFabricCompat.INSTANCE;

    @AfterEach
    void restoreDefaultProbes() {
        // 恢复默认探测器，避免污染其他测试（NetworkCore 单例跨测试共享）
        ViaFabricCompat.classTester = ViaFabricCompat::defaultClassPresent;
        ViaFabricCompat.modTester = ViaFabricCompat::defaultModLoaded;
        compat.onLogin();
    }

    private static Packet<ServerGamePacketListener> fakePacket() {
        return new Packet<>() {
#if MC_VER < MC_1_21_1
            @Override
            public void write(FriendlyByteBuf buffer) {
            }
#else
            @Override
            public net.minecraft.network.protocol.PacketType<? extends Packet<ServerGamePacketListener>> type() {
                return null;
            }
#endif
            @Override
            public void handle(ServerGamePacketListener handler) {
            }
        };
    }

    private void probeAbsent() {
        ViaFabricCompat.classTester = name -> false;
        ViaFabricCompat.modTester = id -> false;
    }

    @Test
    void detectionFalseWhenNothingInstalled() {
        probeAbsent();
        compat.onLogin();
        assertFalse(compat.isActive(), "无 ViaFabric 时检测应为 false");
    }

    @Test
    void detectionTrueViaClasspathProbe() {
        ViaFabricCompat.classTester = name ->
                ViaFabricCompat.PROBE_CLASSES[1].equals(name); // com.viaversion.viafabric.ViaFabric
        ViaFabricCompat.modTester = id -> false;
        compat.onLogin();
        assertTrue(compat.isActive(), "classpath 命中 ViaFabric 前端类应检测为 true");
    }

    @Test
    void detectionTrueViaModList() {
        ViaFabricCompat.classTester = name -> false;
        ViaFabricCompat.modTester = id -> "viafabric".equals(id);
        compat.onLogin();
        assertTrue(compat.isActive(), "mod 列表命中 viafabric 应检测为 true");
    }

    @Test
    void detectionCachedUntilNextLogin() {
        ViaFabricCompat.classTester = name -> true;
        ViaFabricCompat.modTester = id -> false;
        compat.onLogin();
        assertTrue(compat.isActive());

        // 探测结果缓存：卸载（探测器翻转为缺席）后 isActive 仍为 true
        probeAbsent();
        assertTrue(compat.isActive(), "检测结果应缓存到下次登录");

        // 下次登录重探测 → false
        compat.onLogin();
        assertFalse(compat.isActive(), "onLogin 应重探测");
    }

    @Test
    void inactiveTranslationPassesThroughOriginalPacket() {
        probeAbsent();
        compat.onLogin();
        Packet<ServerGamePacketListener> packet = fakePacket();
        assertSame(packet, compat.translateForInjection(packet), "缺席时翻译器应透传原包");
    }

    @Test
    void activeButBridgeUnavailableDegradesToDirectInjection() {
        // 检测命中，但桥构建必然失败（测试环境无 Minecraft 客户端连接 → tryBuild 返回 null）
        ViaFabricCompat.classTester = name -> true;
        ViaFabricCompat.modTester = id -> false;
        compat.onLogin();
        assertTrue(compat.isActive());

        Packet<ServerGamePacketListener> packet = fakePacket();
        // 首次调用尝试建桥失败 → 原包直进（不抛异常）
        assertSame(packet, compat.translateForInjection(packet), "桥不可用时应退回原包直接注入");
        // 会话内降级：后续调用不再重建桥，仍然原包直进
        assertSame(packet, compat.translateForInjection(packet));
    }

    @Test
    void translatorAppliedBeforeInjectorsWithFailureFallback() {
        NetworkCore core = NetworkCore.getInstance();
        core.onDisconnect();

        Packet<ServerGamePacketListener> original = fakePacket();
        Packet<ServerGamePacketListener> translated = fakePacket();
        AtomicInteger seen = new AtomicInteger();
        AtomicReference<Object> lastSeen = new AtomicReference<>();
        java.util.function.Consumer<Packet<?>> injector = p -> {
            seen.incrementAndGet();
            lastSeen.set(p);
        };
        core.registerS2CInjector(injector);

        // 正常翻译：注入器收到转换后包
        core.setS2CTranslator(p -> p == original ? translated : p);
        core.dispatchS2C(original);
        assertEquals(1, seen.get());
        assertSame(translated, lastSeen.get(), "注入器应收到转换后包");

        // 翻译器抛异常：注入器收到原包，不崩
        core.setS2CTranslator(p -> {
            throw new IllegalStateException("viafabric boom");
        });
        core.dispatchS2C(original);
        assertEquals(2, seen.get());
        assertSame(original, lastSeen.get(), "翻译器异常应退回原包直接注入");

        // 翻译器返回 null：原包直进
        core.setS2CTranslator(p -> null);
        core.dispatchS2C(original);
        assertEquals(3, seen.get());
        assertSame(original, lastSeen.get(), "翻译器返回 null 应跳过翻译");

        core.unregisterS2CInjector(injector);
        core.onDisconnect();
    }

    @Test
    void onLoginWiresTranslator() {
        NetworkCore core = NetworkCore.getInstance();
        core.onDisconnect();
        probeAbsent();
        compat.onLogin();
        assertNotNull(core.s2cTranslator(), "onLogin 后应接线翻译器（缺席时透传）");

        Packet<ServerGamePacketListener> packet = fakePacket();
        AtomicInteger seen = new AtomicInteger();
        java.util.function.Consumer<Packet<?>> injector = p -> seen.incrementAndGet();
        core.registerS2CInjector(injector);
        core.dispatchS2C(packet);
        assertEquals(1, seen.get(), "缺席时经翻译器透传后注入器仍收到包");
        core.unregisterS2CInjector(injector);
        core.onDisconnect();
    }
}
