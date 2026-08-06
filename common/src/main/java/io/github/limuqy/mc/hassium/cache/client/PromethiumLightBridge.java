package io.github.limuqy.mc.hassium.cache.client;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Promethium 并行光照引擎运行时桥接（反射，零编译依赖）。
 * <p>
 * Promethium MOD 安装时经 {@link Class#forName} 发现引擎（{@code ParallelLightEngine}
 * 单例 + 三注入接口），Hassium 通过本类访问，import 面为零——引擎随 MOD 更新，Hassium
 * 无版本绑定。MOD 缺席 → {@link #isEnabled()} = false → 全链路走官方引擎（统一异步
 * 缓冲队列），优雅降级。
 * <p>
 * 线程约定与引擎接口一致：{@link #submitRecompute} / {@link #clear} 任意线程；
 * {@link #drainCompletions} / {@link #configure} 主线程。
 * <p>
 * 装配数据（配置 / 指标 / 官方引擎原语钩子）由 {@link HassiumLightBindings} 与
 * {@link HassiumLightHooks} 提供，经 {@link Proxy} 包装成引擎接口实例注入
 * （接口类运行时加载，Hassium 无法直接 implements）。
 */
public final class PromethiumLightBridge {

    private static final String ENGINE_CLS = "io.github.limuqy.mc.promethium.light.ParallelLightEngine";
    private static final String CONFIG_CLS = "io.github.limuqy.mc.promethium.light.LightEngineConfig";
    private static final String STATS_CLS = "io.github.limuqy.mc.promethium.light.LightEngineStats";
    private static final String HOOKS_CLS = "io.github.limuqy.mc.promethium.light.LightEngineHooks";

    private static volatile boolean availabilityChecked;
    private static volatile boolean available;
    private static volatile Object engine;
    private static volatile Method mSubmitRecompute;
    private static volatile Method mSubmitRecomputeExplicit;
    private static volatile Method mDrainCompletions;
    private static volatile Method mClear;
    private static volatile Method mOnChunkDataReplaced;
    private static volatile Method mConfigure;
    private static volatile Object configProxy;
    private static volatile Object statsProxy;
    private static volatile Object hooksProxy;

    private PromethiumLightBridge() {}

    /** Promethium MOD 是否可用（懒检测一次；类在 classpath 即可用）。 */
    public static boolean isAvailable() {
        if (!availabilityChecked) {
            availabilityChecked = true;
            try {
                Class.forName(ENGINE_CLS);
                available = true;
            } catch (ClassNotFoundException e) {
                available = false;
            }
        }
        return available;
    }

    /** 并行光照是否生效 = 配置开启 && Promethium MOD 可用。 */
    public static boolean isEnabled() {
        return HassiumConfigService.getInstance().isParallelLightEngineEnabled() && isAvailable();
    }

    /** 装配（主线程；幂等）。MOD 缺席静默跳过。 */
    public static void configure() {
        if (!isEnabled()) {
            return;
        }
        try {
            if (mConfigure == null) {
                mConfigure = engine().getClass().getMethod("configure",
                        Class.forName(CONFIG_CLS), Class.forName(STATS_CLS), Class.forName(HOOKS_CLS));
            }
            mConfigure.invoke(engine(), configProxy(), statsProxy(), hooksProxy());
        } catch (Throwable t) {
            Constants.LOG.warn("Hassium: Promethium light engine configure failed", t);
        }
    }

    /** 提交区块列光照重算（任意线程）。 */
    public static void submitRecompute(ChunkPos corePos, CompoundTag cachedNbt) {
        if (!isEnabled()) {
            return;
        }
        try {
            if (mSubmitRecompute == null) {
                mSubmitRecompute = engine().getClass().getMethod("submitRecompute", ChunkPos.class, CompoundTag.class);
            }
            mSubmitRecompute.invoke(engine(), corePos, cachedNbt);
        } catch (Throwable t) {
            Constants.LOG.warn("Hassium: Promethium submitRecompute failed for {}", corePos, t);
        }
    }

    /**
     * 提交区块列光照重算，显式携带变化 section 域（G1，delta 直传，任意线程）。
     * <p>
     * 反射构造 {@code SectionDomain}（record，构造器 (int, int)）——引擎随 MOD 更新，
     * Hassium 零编译依赖，桥内按全限定名发现。引擎为旧版（无 3 参方法）时
     * NoSuchMethodException → warn + 丢弃本次显式提交；发布流程保证引擎先于桥更新，
     * 该降级仅在版本失配窗口出现。
     *
     * @param minSectionY         变化 section 域下限（绝对 section y，含）
     * @param maxSectionYExclusive 变化 section 域上限（绝对 section y，不含）
     */
    public static void submitRecompute(ChunkPos corePos, CompoundTag cachedNbt,
                                       int minSectionY, int maxSectionYExclusive) {
        if (!isEnabled()) {
            return;
        }
        try {
            if (mSubmitRecomputeExplicit == null) {
                mSubmitRecomputeExplicit = engine().getClass().getMethod("submitRecompute",
                        ChunkPos.class, CompoundTag.class,
                        Class.forName("io.github.limuqy.mc.promethium.light.SectionDomain"));
            }
            Object domain = Class.forName("io.github.limuqy.mc.promethium.light.SectionDomain")
                    .getConstructor(int.class, int.class).newInstance(minSectionY, maxSectionYExclusive);
            mSubmitRecomputeExplicit.invoke(engine(), corePos, cachedNbt, domain);
        } catch (Throwable t) {
            Constants.LOG.warn("Hassium: Promethium submitRecompute(explicit) failed for {} [{}..{})",
                    corePos, minSectionY, maxSectionYExclusive, t);
        }
    }

    /** chunk apply 后失效旧快照（任意线程）。 */
    public static void onChunkDataReplaced(ClientLevel level, ChunkPos pos) {
        if (!isEnabled()) {
            return;
        }
        try {
            if (mOnChunkDataReplaced == null) {
                mOnChunkDataReplaced = engine().getClass().getMethod("onChunkDataReplaced", ClientLevel.class, ChunkPos.class);
            }
            mOnChunkDataReplaced.invoke(engine(), level, pos);
        } catch (Throwable t) {
            Constants.LOG.warn("Hassium: Promethium onChunkDataReplaced failed for {}", pos, t);
        }
    }

    /** 客户端 tick 尾帧预算落地（主线程；无引擎时零开销）。 */
    public static void drainCompletions(long deadlineNs) {
        if (!isEnabled()) {
            return;
        }
        try {
            if (mDrainCompletions == null) {
                mDrainCompletions = engine().getClass().getMethod("drainCompletions", long.class);
            }
            mDrainCompletions.invoke(engine(), deadlineNs);
        } catch (Throwable t) {
            Constants.LOG.warn("Hassium: Promethium drainCompletions failed", t);
        }
    }

    /** 断连清理（任意线程）。 */
    public static void clear() {
        if (!isEnabled()) {
            return;
        }
        try {
            if (mClear == null) {
                mClear = engine().getClass().getMethod("clear");
            }
            mClear.invoke(engine());
        } catch (Throwable t) {
            Constants.LOG.warn("Hassium: Promethium light engine clear failed", t);
        }
    }

    private static Object engine() throws Exception {
        Object e = engine;
        if (e == null) {
            synchronized (PromethiumLightBridge.class) {
                e = engine;
                if (e == null) {
                    Class<?> cls = Class.forName(ENGINE_CLS);
                    e = cls.getMethod("getInstance").invoke(null);
                    engine = e;
                }
            }
        }
        return e;
    }

    /** LightEngineConfig 接口代理 → HassiumLightBindings。 */
    private static Object configProxy() throws Exception {
        Object p = configProxy;
        if (p == null) {
            Class<?> iface = Class.forName(CONFIG_CLS);
            p = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    (proxy, m, args) -> invokeImpl(HassiumLightBindings.INSTANCE, m));
            configProxy = p;
        }
        return p;
    }

    /** LightEngineStats 接口代理 → HassiumLightBindings。 */
    private static Object statsProxy() throws Exception {
        Object p = statsProxy;
        if (p == null) {
            Class<?> iface = Class.forName(STATS_CLS);
            p = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    (proxy, m, args) -> invokeImpl(HassiumLightBindings.INSTANCE, m, args));
            statsProxy = p;
        }
        return p;
    }

    /** LightEngineHooks 接口代理 → HassiumLightHooks（原语实现留在宿主）。 */
    private static Object hooksProxy() throws Exception {
        Object p = hooksProxy;
        if (p == null) {
            Class<?> iface = Class.forName(HOOKS_CLS);
            p = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    (proxy, m, args) -> invokeImpl(HassiumLightHooks.INSTANCE, m, args));
            hooksProxy = p;
        }
        return p;
    }

    /** 反射转发：接口方法名与实现方法同名同参；Object 方法特判。 */
    private static Object invokeImpl(Object impl, Method ifaceMethod, Object... args) throws Throwable {
        String name = ifaceMethod.getName();
        if (name.equals("toString")) {
            return impl.getClass().getSimpleName() + " (Promethium bridge proxy)";
        }
        if (name.equals("hashCode")) {
            return System.identityHashCode(impl);
        }
        if (name.equals("equals")) {
            return impl == args[0];
        }
        Method m = impl.getClass().getMethod(name, ifaceMethod.getParameterTypes());
        return m.invoke(impl, args);
    }
}
