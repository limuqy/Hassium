package io.github.limuqy.mc.hassium.network.core.viafabric;

import io.github.limuqy.mc.hassium.network.core.NetworkCore;
import io.github.limuqy.mc.hassium.platform.Services;
import net.minecraft.network.protocol.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ViaFabric 兼容门面（T9，REQ A8）：检测 ViaFabric 系列（Fabric 前端 ViaFabric /
 * Forge 前端 ViaForge / 增强前端 ViaFabricPlus / 核心引擎 ViaVersion），并在检测到时把
 * S2C 注入点改挂 ViaFabric 取包处——先经 {@link ViaDecodeBridge} 协议转换再进 T5 注入器。
 *
 * <p><b>检测</b>（双通道，任一命中即视为存在）：
 * <ul>
 *   <li>classpath 探测：{@code com.viaversion.viaversion.api.Via}（核心引擎，ViaVersion 全家桶必有）、
 *       {@code com.viaversion.viafabric.ViaFabric}、{@code com.viaversion.viaforge.ViaForge}、
 *       {@code com.viaversion.viafabricplus.ViaFabricPlus}（前端类名）；</li>
 *   <li>平台 mod 列表：{@link Services#PLATFORM}（FabricLoader / Forge ModList / NeoForge ModList）
 *       {@code isModLoaded("viafabric"/"viaforge"/"viafabricplus"/"viaversion")}。</li>
 * </ul>
 * classpath 探测在部分平台（NeoForge 模块层）可能看不到他模类，由 mod 列表通道兜底；
 * 平台 API 不可用（如单测环境）视为未装——直接注入，安全。
 *
 * <p><b>接入</b>：{@link #onLogin()} 在每次登录时重探测并接线
 * {@link NetworkCore#setS2CTranslator}（{@link NetworkCore#onLogin()} 调用）；翻译器
 * {@link #translateForInjection} 全失败安全——ViaFabric 缺席/桥不可用/转换异常一律返回原包，
 * 注入器照常收到 Packet，不崩。
 *
 * <p>检测结果 bool 供注入层查询：{@link #isActive()}。
 */
public final class ViaFabricCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ViaFabricCompat");

    public static final ViaFabricCompat INSTANCE = new ViaFabricCompat();

    /** classpath 探测候选类（任一存在即命中；核心引擎类必随任何前端打包）。 */
    static final String[] PROBE_CLASSES = {
            "com.viaversion.viaversion.api.Via",
            "com.viaversion.viafabric.ViaFabric",
            "com.viaversion.viaforge.ViaForge",
            "com.viaversion.viafabricplus.ViaFabricPlus",
    };

    /** 平台 mod 列表探测候选 id（任一已装即命中）。 */
    static final String[] PROBE_MOD_IDS = {
            "viaversion", "viafabric", "viaforge", "viafabricplus",
    };

    // ---- 测试缝：单测可注入假探测器覆盖默认实现（默认实现见下） ----

    @FunctionalInterface
    interface ClassPresenceTester {
        boolean test(String className);
    }

    @FunctionalInterface
    interface ModLoadedTester {
        boolean test(String modId);
    }

    static volatile ClassPresenceTester classTester = ViaFabricCompat::defaultClassPresent;
    static volatile ModLoadedTester modTester = ViaFabricCompat::defaultModLoaded;

    // ---- 状态（volatile，跨线程：网关 event loop / 主线程 / 单测） ----

    private volatile Boolean cachedActive;
    private volatile ViaDecodeBridge bridge;
    private volatile boolean bridgeFailed;

    private ViaFabricCompat() {
    }

    /** 检测结果（惰性缓存）：ViaFabric 系列已装返回 true。注入层查询口。 */
    public boolean isActive() {
        Boolean cached = cachedActive;
        if (cached == null) {
            synchronized (this) {
                cached = cachedActive;
                if (cached == null) {
                    cached = detect();
                    cachedActive = cached;
                }
            }
        }
        return cached;
    }

    /**
     * 每次登录（{@link NetworkCore#onLogin()} 调用）：重探测 + 重置转换桥（旧会话的
     * UserConnection/Connection 已失效），并接线翻译器。翻译器常挂，缺席时内部透传。
     */
    public void onLogin() {
        synchronized (this) {
            cachedActive = null;
            bridge = null;
            bridgeFailed = false;
        }
        NetworkCore.getInstance().setS2CTranslator(this::translateForInjection);
        LOGGER.info("Hassium: ViaFabric compat wired (active={})", isActive());
    }

    /**
     * 入站 S2C 注入前转换（T9 取包处接入）：Packet → 当前版本线字节 → ViaFabric decode 链
     * → 转换后字节 → Packet。任何失败/缺席返回原包（直接注入），永不抛出。
     */
    public Packet<?> translateForInjection(Packet<?> packet) {
        if (!isActive()) {
            return packet;
        }
        ViaDecodeBridge b = bridge;
        if (b == null && !bridgeFailed) {
            synchronized (this) {
                b = bridge;
                if (b == null && !bridgeFailed) {
                    b = ViaDecodeBridge.tryBuild();
                    bridge = b;
                    if (b == null) {
                        bridgeFailed = true;
                        LOGGER.warn("Hassium: ViaFabric bridge unavailable, direct injection for this session");
                    }
                }
            }
        }
        if (b == null) {
            return packet;
        }
        try {
            Packet<?> translated = b.translatePacket(packet);
            return translated != null ? translated : packet;
        } catch (Throwable t) {
            LOGGER.error("Hassium: ViaFabric translate failed for {}, direct injection fallback",
                    packet.getClass().getSimpleName(), t);
            return packet;
        }
    }

    // ---- 内部 ----

    private boolean detect() {
        try {
            for (String className : PROBE_CLASSES) {
                try {
                    if (classTester.test(className)) {
                        LOGGER.info("Hassium: ViaFabric detected via classpath ({})", className);
                        return true;
                    }
                } catch (Throwable ignored) {
                    // 单个探测失败不致命，继续
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("Hassium: ViaFabric classpath probe failed", t);
        }
        try {
            for (String modId : PROBE_MOD_IDS) {
                try {
                    if (modTester.test(modId)) {
                        LOGGER.info("Hassium: ViaFabric detected via mod list ({})", modId);
                        return true;
                    }
                } catch (Throwable ignored) {
                    // 单个 mod 查询失败不致命，继续
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("Hassium: ViaFabric mod-list probe failed", t);
        }
        return false;
    }

    /** 默认 classpath 探测器（mod 类加载器；Fabric/Forge 生产环境可见全部 mod 类）。包级可见供测试缝引用。 */
    static boolean defaultClassPresent(String className) {
        try {
            Class.forName(className, false, ViaFabricCompat.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Throwable t) {
            // 类存在但初始化被拒（如 NeoForge 模块层不可见）→ 走 mod 列表通道
            LOGGER.debug("Hassium: class probe {} error: {}", className, t.toString());
            return false;
        }
    }

    /** 默认平台 mod 列表探测器（FabricLoader / Forge ModList / NeoForge ModList）。包级可见供测试缝引用。 */
    static boolean defaultModLoaded(String modId) {
        return Services.PLATFORM.isModLoaded(modId);
    }
}
