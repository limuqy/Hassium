package io.github.limuqy.mc.hassium.compat.mods;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * C2ME OpenCL 世界生成加速（{@code c2me-opts-accel-opencl}）× Hassium 影子端兼容。
 * <p>
 * <b>问题</b>：C2ME 把 OpenCL 全局上下文的初始化挂在 {@code MinecraftServer.runServer()}
 * 的 HEAD（{@code MixinMinecraftServer.preRunServer}：新建 {@code CLServerGlobalContext} →
 * {@code OpenCLDeviceLocator.enumerateAll()} → 逐设备 {@code openDevice}），并把上下文存进
 * 该实例的 {@code @Unique} 字段 {@code c2me$clContext}；而 {@code ChunkMap.<init>} 的
 * postInit 处理器（{@code MixinThreadedAnvilChunkStorage.postInit}）经
 * {@code MinecraftServerExtension.c2me$getCLContext()} 读该字段，为 {@code null} 时
 * 直接 {@code throw new IllegalStateException}。
 * <p>
 * Hassium 影子端 {@code initServer} **不走 {@code runServer}**
 * （见 {@code SeedGenLevelCompat.assembleShadowServer}），故客户端侧上下文恒为
 * {@code null} → 影子端创建必然抛错 → 影子链路整体降级。此时真服 {@code pull_mode} 已压制
 * 原版整柱，客户端又无独立拉取通道 → 空 {@code ClientChunkCache}。
 * <p>
 * <b>接管方式</b>：按 C2ME 自身语义（逐条对应 {@code preRunServer} /
 * {@code postStopServer} 字节码）为影子实例补齐「建上下文 → 枚举设备 → openDevice」，
 * 并在影子端关停时 {@code closeAllDevices}（避免每次重连泄漏 GPU 上下文）。
 * <p>
 * <b>失败兜底</b>：全程反射 + 不抛异常。任一环节不可用（类/字段/方法缺失、枚举失败、
 * 无设备）即回滚字段并记日志，影子端沿用原有失败路径——不引入新的失败模式。
 * 无 OpenCL 时 C2ME 自己也会抛，故这里不改变语义。
 */
public final class C2meOpenClCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ModCompat");

    private static final String CLASS_GLOBAL_CONTEXT =
            "com.ishland.c2me.opts.accel.opencl.common.gen.CLServerGlobalContext";
    private static final String CLASS_DEVICE_LOCATOR =
            "com.ishland.c2me.opts.accel.opencl.common.enumeration.OpenCLDeviceLocator";
    private static final String CLASS_DEVICE_METADATA =
            "com.ishland.c2me.opts.accel.opencl.common.enumeration.OpenCLDeviceMetadata";

    /**
     * C2ME mixin 在 {@code MinecraftServer} 上声明的 {@code @Unique} 字段（每实例一份上下文）。
     * <p>
     * 注意：{@code $} 是 Manifold 字符串模板的插值符，故字段名由 char 拼接构造，
     * 不能直接写成字符串字面量。
     */
    private static final char DOLLAR = '$';
    private static final String FIELD_CL_CONTEXT = "c2me" + DOLLAR + "clContext";

    private C2meOpenClCompat() {
    }

    /**
     * 影子端 {@code initServer()} 之前调用：为影子实例补齐 OpenCL 全局上下文。
     * <p>
     * 必须早于 {@code MinecraftServer.createLevels}（{@code ChunkMap.<init>} 会读该字段）。
     * 幂等：字段非空时直接返回（C2ME 自己已初始化过）。
     */
    public static void armFor(MinecraftServer server) {
        if (server == null || !ModCompatFlags.c2meOpenCl()) {
            return;
        }
        try {
            Field field = MinecraftServer.class.getDeclaredField(FIELD_CL_CONTEXT);
            field.setAccessible(true);
            if (field.get(server) != null) {
                return;
            }
            Class<?> contextClass = Class.forName(CLASS_GLOBAL_CONTEXT);
            Class<?> locatorClass = Class.forName(CLASS_DEVICE_LOCATOR);
            Class<?> metadataClass = Class.forName(CLASS_DEVICE_METADATA);

            Object context = contextClass.getDeclaredConstructor().newInstance();
            field.set(server, context);
            try {
                // 与 C2ME 一致：只要枚举到设备就逐个 openDevice（返回值不参与判定）
                boolean openedAnyDevice = false;
                Method openDevice = contextClass.getMethod("openDevice", metadataClass);
                for (Object metadata : (List<?>) locatorClass.getMethod("enumerateAll").invoke(null)) {
                    openDevice.invoke(context, metadata);
                    openedAnyDevice = true;
                }
                if (!openedAnyDevice) {
                    // C2ME 在无设备且未开 fallback 时抛「No OpenCL devices found」；
                    // 这里只回滚字段——随后的 ChunkMap postInit 会以同样的 ISE 结束，
                    // 失败语义不变（且不需要我们复刻对方的配置读取）。
                    field.set(server, null);
                    LOGGER.warn("Hassium: C2ME OpenCL 无可用设备，影子端沿用 C2ME 原失败路径");
                    return;
                }
                LOGGER.info("Hassium: C2ME OpenCL 上下文已为影子端初始化（影子端不走 runServer，需在此补齐）");
            } catch (Throwable t) {
                field.set(server, null);
                LOGGER.warn("Hassium: C2ME OpenCL 上下文初始化失败，影子端沿用 C2ME 原失败路径", t);
            }
        } catch (Throwable t) {
            // 反射面不匹配（C2ME 改名/换实现）：不影响其他功能，仅本次接管失效
            LOGGER.warn("Hassium: C2ME OpenCL 兼容层不可用（反射面不匹配），影子端沿用 C2ME 原失败路径", t);
        }
    }

    /**
     * 影子端关停（各维度 chunk 源已关闭）后调用：释放 OpenCL 设备，避免重复连服累积 GPU 上下文。
     * 与 C2ME {@code postStopServer} 同语义；失败仅记日志。
     */
    public static void releaseFor(MinecraftServer server) {
        if (server == null || !ModCompatFlags.c2meOpenCl()) {
            return;
        }
        try {
            Field field = MinecraftServer.class.getDeclaredField(FIELD_CL_CONTEXT);
            field.setAccessible(true);
            Object context = field.get(server);
            if (context == null) {
                return;
            }
            context.getClass().getMethod("closeAllDevices").invoke(context);
            field.set(server, null);
        } catch (Throwable t) {
            LOGGER.warn("Hassium: C2ME OpenCL 上下文释放失败（不影响存档与缓存）", t);
        }
    }
}
