package io.github.limuqy.mc.hassium.compat.mods;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 外部 Mod 存在性检测（结构性，纯 {@code Class.forName}，不依赖任何 loader API）。
 * <p>
 * 本包（{@code compat.mods}）是三方兼容层：删除本包 + 三端
 * {@code hassium.modcompat.mixins.json} 登记即可整体回退，不触碰业务代码。
 * <p>
 * 探测目标均为对方**稳定类**（非内部实现细节），任何失败一律保守返回 false；
 * 检测结果静态缓存，热路径零反射开销。
 */
public final class ModCompatFlags {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/ModCompat");

    /** C2ME 主 jar（c2me-base 全局执行器；0.2.0 / 0.4.0 均存在）。 */
    private static final boolean C2ME = present("com.ishland.c2me.base.common.GlobalExecutors");

    /** C2ME 自实现的区块 IO（{@code ioSystem.replaceImpl}），写路径绕过 vanilla 流。 */
    private static final boolean C2ME_REPLACED_CHUNK_IO =
            present("com.ishland.c2me.rewrites.chunkio.common.C2MEStorageThread");

    /** C2ME OpenCL 世界生成加速（可选模块，1.21+）。 */
    private static final boolean C2ME_OPENCL =
            present("com.ishland.c2me.opts.accel.opencl.ModuleEntryPoint");

    /** Starlight / ScalableLux 共同实现的光照引擎标记接口。 */
    private static final boolean STARLIGHT_FAMILY =
            present("ca.spottedleaf.starlight.common.light.StarLightLightingProvider");

    private static volatile boolean announced;

    private ModCompatFlags() {
    }

    public static boolean c2me() {
        return C2ME;
    }

    /**
     * C2ME 是否接管了区块 IO 实现。
     * <p>
     * 注意：该模块默认开启（{@code ioSystem.replaceImpl} 默认值取决于
     * {@code globalExecutorParallelism >= 2}，多核机器为 true），因此不能当边缘情况忽略。
     */
    public static boolean c2meChunkIoReplaced() {
        return C2ME_REPLACED_CHUNK_IO;
    }

    public static boolean c2meOpenCl() {
        return C2ME_OPENCL;
    }

    /** Starlight 或 ScalableLux（两者互斥，{@code provides: starlight}）。 */
    public static boolean starlightFamily() {
        return STARLIGHT_FAMILY;
    }

    /** 是否检测到任何被本兼容层识别并接管的外部 Mod。 */
    public static boolean any() {
        return C2ME || STARLIGHT_FAMILY;
    }

    /** 首次进入被接管路径时输出一行，便于日志审计定位兼容层是否生效。 */
    public static void announceOnce() {
        if (announced) {
            return;
        }
        announced = true;
        if (!any() && !C2ME_OPENCL) {
            return;
        }
        LOGGER.info(
                "Hassium: third-party mods detected c2me={} c2meChunkIoReplaced={} opencl={} starlightFamily={}",
                C2ME, C2ME_REPLACED_CHUNK_IO, C2ME_OPENCL, STARLIGHT_FAMILY);
    }

    private static boolean present(String className) {
        try {
            Class.forName(className, false, ModCompatFlags.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
