package io.github.limuqy.mc.hassium.compression;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * ZSTD 运行时桥：以隔离 ClassLoader 加载 zstd-jni，杜绝模块层 split-package 崩溃。
 * <p>
 * 背景：生产 jar 若把 zstd-jni class shade 进 mod jar，ModLauncher 构建模块层时
 * hassium 模块与其它 mod 携带的真 zstd-jni 模块（AMN=com.github.luben.zstd_jni）
 * 都导出 com.github.luben.zstd.util 等包 → {@code ResolutionException} 启动即崩
 * （与 1.21+ lz4 的 "Modules hassium and org.lz4.java export package net.jpountz.util" 同指纹，
 * 已有真实玩家整合包触发）。
 * <p>
 * 方案（参照 BandwidthOptimizer 的 ZstdRuntimeBridge）：
 * <ol>
 *   <li>构建期 zstd-jni 零编译期依赖（编译器阻止直接引用回归），整 jar 由构建脚本嵌入
 *       {@code META-INF/hassium/libs/zstd-jni.jar}（class 不混入 mod jar）；</li>
 *   <li>运行期把嵌入 jar 解到<b>进程独立</b>的 {@code Files.createTempDirectory} 目录，
 *       用 parent=platformClassLoader 的隔离 URLClassLoader 加载 —— zstd 类不进游戏模块层，
 *       split-package 结构上不可能；</li>
 *   <li>API 面全部经反射绑定（本类封装），仓库内不出现任何 com.github.luben.zstd 类型。</li>
 * </ol>
 * <p>
 * <b>回退策略</b>：嵌入加载失败时重试一次（覆盖瞬时 FS 错误）；再失败即抛
 * {@link IllegalStateException}（cause=首次失败，suppressed=重试失败），<b>默认不回退到
 * 外部 zstd</b>——静默使用整合包中来源/版本不明的 zstd 会破坏隔离保证。开发/测试可显式
 * 开启 {@code -Dhassium.zstdExternalFallback=true}（开启后绑定外部实现会打 WARN 并记录来源）。
 * <p>
 * <b>JNI 边界</b>：zstd-jni 的 {@code Native} 会读全局属性 {@code ZstdNativePath}/
 * {@code ZstdTempFolder}，命中时 native 可能来自共享路径而非嵌入 jar（跨 ClassLoader
 * 重复加载同一 native 库是 JNI 未定义行为）。本桥不改写这两个属性（全局副作用），但会
 * 检测并在日志中告警。
 * <p>
 * <b>临时目录</b>：每进程一个独立目录，只管理自己的文件，不扫描/清理其它实例的目录
 * （跨进程生命周期不可靠）。正常退出不做删除（JVM 关闭钩子里关 ClassLoader 会破坏
 * shutdown 阶段的存档压缩路径），代价是每次运行残留约 7 MB 临时目录，与 zstd-jni 自身
 * 残留 native 临时文件的行为同级。跨启动缓存（内容哈希寻址）刻意不做。
 * <p>
 * 线程安全：静态 API 与各包装类仅做反射转发；ctx/dict 句柄的共享语义与 zstd-jni
 * 原生对象一致，由调用方约束（见 ZstdDictionaryCompressionCodec 的 synchronized 注记）。
 */
public final class ZstdRuntimeBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/Zstd");

    /** 嵌入资源固定名（无版本号）：构建脚本嵌入时统一改名，版本只写在 gradle.properties 的 zstd_jni_version */
    private static final String EMBEDDED_LIB_RESOURCE = "META-INF/hassium/libs/zstd-jni.jar";
    private static final String EMBEDDED_LIB_FILE_NAME = "zstd-jni.jar";
    private static final String TMP_DIR_PREFIX = "hassium-zstd-";
    /** zstd-jni Native 的全局覆盖属性（只检测告警，不改写） */
    private static final String[] NATIVE_OVERRIDE_PROPERTIES = {"ZstdNativePath", "ZstdTempFolder"};
    /** 显式开发/测试选项：允许绑定外部（整合包来源）zstd；生产环境保持 false 保证隔离失败可见 */
    private static final String EXTERNAL_FALLBACK_PROPERTY = "hassium.zstdExternalFallback";

    private static final Bindings BINDINGS = loadBindings();

    private ZstdRuntimeBridge() {
    }

    /** 当前绑定是否来自本桥的嵌入隔离加载（生产路径）；外部回退时为 false */
    public static boolean isIsolated() {
        return BINDINGS.isolated;
    }

    // ------------------------------------------------------------------
    // 静态便捷 API（对应 Zstd 的静态方法）
    // ------------------------------------------------------------------

    public static byte[] compress(byte[] input, int level) {
        return (byte[]) invoke(BINDINGS.mCompressLevel, null, input, level);
    }

    public static byte[] compress(byte[] input, CompressDict dict) {
        return (byte[]) invoke(BINDINGS.mCompressDict, null, input, dict.handle);
    }

    public static byte[] compressUsingDict(byte[] input, byte[] dictionary, int level) {
        return (byte[]) invoke(BINDINGS.mCompressUsingDict, null, input, dictionary, level);
    }

    public static byte[] decompress(byte[] input, int maxSize) {
        return (byte[]) invoke(BINDINGS.mDecompressSize, null, input, maxSize);
    }

    public static byte[] decompress(byte[] input, DecompressDict dict, int maxSize) {
        return (byte[]) invoke(BINDINGS.mDecompressDictSize, null, input, dict.handle, maxSize);
    }

    public static long getFrameContentSize(byte[] input) {
        return (long) invoke(BINDINGS.mGetFrameContentSize, null, input);
    }

    public static long errDstSizeTooSmall() {
        return BINDINGS.errDstSizeTooSmall;
    }

    // ------------------------------------------------------------------
    // 句柄构造
    // ------------------------------------------------------------------

    public static CompressDict newCompressDict(byte[] dictionary, int level) {
        return new CompressDict(construct(BINDINGS.newCompressDict, dictionary, level));
    }

    public static DecompressDict newDecompressDict(byte[] dictionary) {
        return new DecompressDict(construct(BINDINGS.newDecompressDict, dictionary));
    }

    public static DictTrainer newDictTrainer(int totalSampleBytes, int dictionarySize) {
        return new DictTrainer(construct(BINDINGS.newTrainer, totalSampleBytes, dictionarySize));
    }

    public static CompressCtx newCompressCtx() {
        return new CompressCtx(construct(BINDINGS.newCompressCtx));
    }

    public static DecompressCtx newDecompressCtx() {
        return new DecompressCtx(construct(BINDINGS.newDecompressCtx));
    }

    // ------------------------------------------------------------------
    // 包装类型：隐藏反射句柄，方法名与 zstd-jni 对齐，迁移零语义差
    // ------------------------------------------------------------------

    /** 对应 ZstdDictCompress（native 句柄，用后 close） */
    public static final class CompressDict implements AutoCloseable {
        private final Object handle;

        private CompressDict(Object handle) {
            this.handle = handle;
        }

        @Override
        public void close() {
            invoke(BINDINGS.mCompressDictClose, this.handle);
        }
    }

    /** 对应 ZstdDictDecompress（native 句柄，用后 close） */
    public static final class DecompressDict implements AutoCloseable {
        private final Object handle;

        private DecompressDict(Object handle) {
            this.handle = handle;
        }

        @Override
        public void close() {
            invoke(BINDINGS.mDecompressDictClose, this.handle);
        }
    }

    /** 对应 ZstdDictTrainer */
    public static final class DictTrainer {
        private final Object handle;

        private DictTrainer(Object handle) {
            this.handle = handle;
        }

        public void addSample(byte[] sample) {
            invoke(BINDINGS.mTrainerAddSample, this.handle, sample);
        }

        public byte[] trainSamples() {
            return (byte[]) invoke(BINDINGS.mTrainerTrainSamples, this.handle);
        }
    }

    /** 对应 ZstdCompressCtx（AutoCloseable，按 try-with-resources 使用） */
    public static final class CompressCtx implements AutoCloseable {
        private final Object handle;

        private CompressCtx(Object handle) {
            this.handle = handle;
        }

        public void setLevel(int level) {
            invoke(BINDINGS.mCompressSetLevel, this.handle, level);
        }

        public void setMagicless(boolean magicless) {
            invoke(BINDINGS.mCompressSetMagicless, this.handle, magicless);
        }

        public void loadDict(byte[] dictionary) {
            invoke(BINDINGS.mCompressLoadDictBytes, this.handle, dictionary);
        }

        public byte[] compress(byte[] input) {
            return (byte[]) invoke(BINDINGS.mCompressCtxCompress, this.handle, input);
        }

        @Override
        public void close() {
            invoke(BINDINGS.mCompressCtxClose, this.handle);
        }
    }

    /** 对应 ZstdDecompressCtx（AutoCloseable，按 try-with-resources 使用） */
    public static final class DecompressCtx implements AutoCloseable {
        private final Object handle;

        private DecompressCtx(Object handle) {
            this.handle = handle;
        }

        public void setMagicless(boolean magicless) {
            invoke(BINDINGS.mDecompressSetMagicless, this.handle, magicless);
        }

        public void loadDict(byte[] dictionary) {
            invoke(BINDINGS.mDecompressLoadDictBytes, this.handle, dictionary);
        }

        public void loadDict(DecompressDict dictionary) {
            invoke(BINDINGS.mDecompressLoadDictDecompress, this.handle, dictionary.handle);
        }

        public byte[] decompress(byte[] input, int maxSize) {
            return (byte[]) invoke(BINDINGS.mDecompressCtxDecompress, this.handle, input, maxSize);
        }

        public int decompressByteArray(byte[] target, int targetOffset, int targetSize,
                                       byte[] source, int sourceOffset, int sourceSize) {
            return (int) invoke(BINDINGS.mDecompressCtxByteArray, this.handle,
                    target, targetOffset, targetSize, source, sourceOffset, sourceSize);
        }

        @Override
        public void close() {
            invoke(BINDINGS.mDecompressCtxClose, this.handle);
        }
    }

    /** zstd-jni 抛出的 ZstdException 的桥接形态，携带 native 错误码（如 dstSizeTooSmall 重试判定） */
    public static final class ZstdErrorException extends RuntimeException {
        private final long errorCode;

        ZstdErrorException(long errorCode, RuntimeException cause) {
            super(cause.getMessage(), cause);
            this.errorCode = errorCode;
        }

        public long errorCode() {
            return this.errorCode;
        }
    }

    // ------------------------------------------------------------------
    // 反射转发
    // ------------------------------------------------------------------

    private static Object construct(Constructor<?> constructor, Object... arguments) {
        try {
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException exception) {
            throw unwrap(exception);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("zstd-jni bridge construction failed", exception);
        }
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            throw unwrap(exception);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("zstd-jni bridge invocation failed: " + method.getName(), exception);
        }
    }

    private static RuntimeException unwrap(InvocationTargetException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            if (isZstdException(runtimeException)) {
                return new ZstdErrorException(readErrorCode(runtimeException), runtimeException);
            }
            return runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("zstd-jni bridge invocation failed", cause);
    }

    // 隔离 loader 里的异常类与桥不在同一类型空间，只能按类名识别
    private static boolean isZstdException(Throwable throwable) {
        return "com.github.luben.zstd.ZstdException".equals(throwable.getClass().getName());
    }

    private static long readErrorCode(Throwable zstdException) {
        try {
            Method errorCode = zstdException.getClass().getMethod("getErrorCode");
            return (long) errorCode.invoke(zstdException);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------
    // 加载链：嵌入 jar（进程独立临时目录，失败重试一次）→
    // 显式开启属性时才走的外部 zstd 回退 → 否则抛出（隔离失败必须可见）
    // ------------------------------------------------------------------

    private static Bindings loadBindings() {
        // 进程独立临时目录：createTempDirectory 保证跨 JVM/线程唯一，不与其它实例共享文件
        Throwable embeddedFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Path directory = null;
            try {
                directory = Files.createTempDirectory(TMP_DIR_PREFIX);
                Bindings bindings = bindWithURLClassLoader(writeEmbeddedJar(directory), true);
                LOGGER.info("Embedded zstd-jni loaded (isolated URLClassLoader, parent=platform): {}",
                        directory);
                warnOnNativeOverrides();
                return bindings;
            } catch (Throwable failure) {
                if (directory != null) {
                    deleteDirectoryTreeIfPossible(directory);
                }
                if (embeddedFailure == null) {
                    embeddedFailure = failure;
                } else {
                    embeddedFailure.addSuppressed(failure);
                }
            }
        }

        boolean externalFallbackEnabled = Boolean.getBoolean(EXTERNAL_FALLBACK_PROPERTY);
        if (externalFallbackEnabled) {
            LOGGER.warn("Embedded zstd-jni failed twice; external fallback ENABLED via -D{} "
                    + "(isolation defeated: source/version unknown)", EXTERNAL_FALLBACK_PROPERTY, embeddedFailure);
            Bindings external = bindExternalWithLogging();
            if (external != null) {
                return external;
            }
            IllegalStateException unavailable = new IllegalStateException(
                    "zstd-jni unavailable: embedded isolation failed and no external zstd found",
                    embeddedFailure);
            unavailable.addSuppressed(new IllegalStateException("external fallback exhausted"));
            throw unavailable;
        }

        throw new IllegalStateException(
                "zstd-jni unavailable: embedded isolation failed twice (external fallback is disabled in "
                        + "production; enable only for development with -D" + EXTERNAL_FALLBACK_PROPERTY + "=true)",
                embeddedFailure);
    }

    // 外部回退（显式开发选项）：线程上下文 loader → 自身 loader；成功/失败都记录来源
    private static Bindings bindExternalWithLogging() {
        Throwable externalFailure = null;
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader ownClassLoader = ZstdRuntimeBridge.class.getClassLoader();
        ClassLoader[] candidates = ownClassLoader != contextClassLoader
                ? new ClassLoader[]{contextClassLoader, ownClassLoader}
                : new ClassLoader[]{contextClassLoader};
        for (ClassLoader candidate : candidates) {
            try {
                Bindings bindings = bind(candidate, false);
                LOGGER.warn("External zstd-jni bound from {} {} (NOT isolated)",
                        candidate.getClass().getName(), describeLoaderName(candidate));
                warnOnNativeOverrides();
                return bindings;
            } catch (Throwable failure) {
                if (externalFailure == null) {
                    externalFailure = failure;
                } else {
                    externalFailure.addSuppressed(failure);
                }
            }
        }
        LOGGER.warn("External zstd-jni not found on any fallback loader", externalFailure);
        return null;
    }

    private static String describeLoaderName(ClassLoader loader) {
        try {
            String name = loader.getName();
            return name != null ? "\"" + name + "\"" : "(unnamed)";
        } catch (RuntimeException ignored) {
            return "(unknown)";
        }
    }

    private static Bindings bindWithURLClassLoader(Path jarPath, boolean isolated)
            throws IOException, ReflectiveOperationException {
        URLClassLoader loader = new URLClassLoader(
                new URL[]{jarPath.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        try {
            return bind(loader, isolated);
        } catch (Throwable failure) {
            try {
                loader.close();
            } catch (IOException ignored) {
            }
            throw failure;
        }
    }

    // 独立临时目录中不存在同名文件，无需复用/覆盖语义
    private static Path writeEmbeddedJar(Path directory) throws IOException {
        Path jarPath = directory.resolve(EMBEDDED_LIB_FILE_NAME);
        try (InputStream inputStream = ZstdRuntimeBridge.class.getClassLoader()
                .getResourceAsStream(EMBEDDED_LIB_RESOURCE)) {
            if (inputStream == null) {
                throw new IOException("Missing embedded zstd-jni resource: " + EMBEDDED_LIB_RESOURCE);
            }
            Files.copy(inputStream, jarPath);
        }
        return jarPath;
    }

    // 只检测告警，不改写全局属性（改写会引入跨加载器并发副作用）
    private static void warnOnNativeOverrides() {
        for (String property : NATIVE_OVERRIDE_PROPERTIES) {
            if (System.getProperty(property) != null) {
                LOGGER.warn("JVM property -D{} is set: zstd-jni native may load from a shared path instead of "
                        + "the embedded jar (cross-ClassLoader native reuse is unsafe); ignoring its value is "
                        + "not possible without a JVM restart", property);
            }
        }
    }

    private static void deleteDirectoryTreeIfPossible(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 绑定面：隔离 loader（或外部 loader）里的构造器与方法句柄。
    // 同名方法（setMagicless/loadDict/close）在两个 Ctx 类上各自绑定，
    // Method 属主类必须与调用目标一致，否则 invoke 抛 IllegalArgumentException。
    // ------------------------------------------------------------------

    private static Bindings bind(ClassLoader classLoader, boolean isolated)
            throws ReflectiveOperationException {
        Class<?> zstd = Class.forName("com.github.luben.zstd.Zstd", true, classLoader);
        Class<?> compressCtx = Class.forName("com.github.luben.zstd.ZstdCompressCtx", true, classLoader);
        Class<?> decompressCtx = Class.forName("com.github.luben.zstd.ZstdDecompressCtx", true, classLoader);
        Class<?> compressDict = Class.forName("com.github.luben.zstd.ZstdDictCompress", true, classLoader);
        Class<?> decompressDict = Class.forName("com.github.luben.zstd.ZstdDictDecompress", true, classLoader);
        Class<?> trainer = Class.forName("com.github.luben.zstd.ZstdDictTrainer", true, classLoader);

        return new Bindings(
                classLoader,
                isolated,
                zstd.getMethod("compress", byte[].class, int.class),
                zstd.getMethod("compress", byte[].class, compressDict),
                zstd.getMethod("compressUsingDict", byte[].class, byte[].class, int.class),
                zstd.getMethod("decompress", byte[].class, int.class),
                zstd.getMethod("decompress", byte[].class, decompressDict, int.class),
                zstd.getMethod("getFrameContentSize", byte[].class),
                (long) zstd.getMethod("errDstSizeTooSmall").invoke(null),
                compressCtx.getConstructor(),
                decompressCtx.getConstructor(),
                compressDict.getConstructor(byte[].class, int.class),
                decompressDict.getConstructor(byte[].class),
                trainer.getConstructor(int.class, int.class),
                compressCtx.getMethod("setLevel", int.class),
                compressCtx.getMethod("setMagicless", boolean.class),
                compressCtx.getMethod("loadDict", byte[].class),
                compressCtx.getMethod("compress", byte[].class),
                compressCtx.getMethod("close"),
                decompressCtx.getMethod("setMagicless", boolean.class),
                decompressCtx.getMethod("loadDict", byte[].class),
                decompressCtx.getMethod("loadDict", decompressDict),
                decompressCtx.getMethod("decompress", byte[].class, int.class),
                decompressCtx.getMethod("decompressByteArray",
                        byte[].class, int.class, int.class, byte[].class, int.class, int.class),
                decompressCtx.getMethod("close"),
                compressDict.getMethod("close"),
                decompressDict.getMethod("close"),
                trainer.getMethod("addSample", byte[].class),
                trainer.getMethod("trainSamples")
        );
    }

    private static final class Bindings {
        // 持有隔离 loader 的强引用：防 GC 回收 ClassLoader 导致已绑定的 native 类失效
        final ClassLoader loader;
        final boolean isolated;
        final Method mCompressLevel;
        final Method mCompressDict;
        final Method mCompressUsingDict;
        final Method mDecompressSize;
        final Method mDecompressDictSize;
        final Method mGetFrameContentSize;
        final long errDstSizeTooSmall;
        final Constructor<?> newCompressCtx;
        final Constructor<?> newDecompressCtx;
        final Constructor<?> newCompressDict;
        final Constructor<?> newDecompressDict;
        final Constructor<?> newTrainer;
        final Method mCompressSetLevel;
        final Method mCompressSetMagicless;
        final Method mCompressLoadDictBytes;
        final Method mCompressCtxCompress;
        final Method mCompressCtxClose;
        final Method mDecompressSetMagicless;
        final Method mDecompressLoadDictBytes;
        final Method mDecompressLoadDictDecompress;
        final Method mDecompressCtxDecompress;
        final Method mDecompressCtxByteArray;
        final Method mDecompressCtxClose;
        final Method mCompressDictClose;
        final Method mDecompressDictClose;
        final Method mTrainerAddSample;
        final Method mTrainerTrainSamples;

        private Bindings(
                ClassLoader loader,
                boolean isolated,
                Method mCompressLevel,
                Method mCompressDict,
                Method mCompressUsingDict,
                Method mDecompressSize,
                Method mDecompressDictSize,
                Method mGetFrameContentSize,
                long errDstSizeTooSmall,
                Constructor<?> newCompressCtx,
                Constructor<?> newDecompressCtx,
                Constructor<?> newCompressDict,
                Constructor<?> newDecompressDict,
                Constructor<?> newTrainer,
                Method mCompressSetLevel,
                Method mCompressSetMagicless,
                Method mCompressLoadDictBytes,
                Method mCompressCtxCompress,
                Method mCompressCtxClose,
                Method mDecompressSetMagicless,
                Method mDecompressLoadDictBytes,
                Method mDecompressLoadDictDecompress,
                Method mDecompressCtxDecompress,
                Method mDecompressCtxByteArray,
                Method mDecompressCtxClose,
                Method mCompressDictClose,
                Method mDecompressDictClose,
                Method mTrainerAddSample,
                Method mTrainerTrainSamples
        ) {
            this.loader = loader;
            this.isolated = isolated;
            this.mCompressLevel = mCompressLevel;
            this.mCompressDict = mCompressDict;
            this.mCompressUsingDict = mCompressUsingDict;
            this.mDecompressSize = mDecompressSize;
            this.mDecompressDictSize = mDecompressDictSize;
            this.mGetFrameContentSize = mGetFrameContentSize;
            this.errDstSizeTooSmall = errDstSizeTooSmall;
            this.newCompressCtx = newCompressCtx;
            this.newDecompressCtx = newDecompressCtx;
            this.newCompressDict = newCompressDict;
            this.newDecompressDict = newDecompressDict;
            this.newTrainer = newTrainer;
            this.mCompressSetLevel = mCompressSetLevel;
            this.mCompressSetMagicless = mCompressSetMagicless;
            this.mCompressLoadDictBytes = mCompressLoadDictBytes;
            this.mCompressCtxCompress = mCompressCtxCompress;
            this.mCompressCtxClose = mCompressCtxClose;
            this.mDecompressSetMagicless = mDecompressSetMagicless;
            this.mDecompressLoadDictBytes = mDecompressLoadDictBytes;
            this.mDecompressLoadDictDecompress = mDecompressLoadDictDecompress;
            this.mDecompressCtxDecompress = mDecompressCtxDecompress;
            this.mDecompressCtxByteArray = mDecompressCtxByteArray;
            this.mDecompressCtxClose = mDecompressCtxClose;
            this.mCompressDictClose = mCompressDictClose;
            this.mDecompressDictClose = mDecompressDictClose;
            this.mTrainerAddSample = mTrainerAddSample;
            this.mTrainerTrainSamples = mTrainerTrainSamples;
        }
    }
}
