package io.github.limuqy.mc.hassium.compat;

import io.github.limuqy.mc.hassium.Constants;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 加载器全局 {@code ServerLifecycleHooks.currentServer} 槽位收口。
 *
 * <p>原版集成服 / 专用服由加载器在 {@code runServer} 生命周期里写入该槽
 * （Fabric Porting Lib：{@code ServerLifecycleEvents.SERVER_STARTING}；
 * NeoForge/Forge：{@code handleServerAboutToStart}）。影子端
 * {@code ShadowSeedServer.initServer()} 直接调用、不走 {@code runServer}，
 * 槽位在多人客户端进程内恒为 null。TF 等 mod 的
 * {@code WorldUtil.getOverworldSeed()} 走 {@code Objects.requireNonNull(getCurrentServer())}
 * → 影子本地 worldgen 触发 NPE。
 *
 * <p>本类只镜像「写槽 / 清槽」一步（与 Porting Lib {@code putstatic} 同语义），
 * <b>不</b> fire {@code SERVER_STARTING} / {@code handleServerAboutToStart}——
 * 后者会连带 loadConfigs、配置目录解析与 ServerAboutToStart 事件，对影子假服
 * 副作用过大。仅在槽为空或已是本影子实例时写入；不覆盖集成服/专用服真服。
 */
public final class ServerLifecycleHooksCompat {

    private static final String[] HOOKS_CLASS_NAMES = {
            // Fabric：TF / Porting Lib 生态
            "io.github.fabricators_of_create.porting_lib.core.util.ServerLifecycleHooks",
            // NeoForge
            "net.neoforged.neoforge.server.ServerLifecycleHooks",
            // Forge
            "net.minecraftforge.server.ServerLifecycleHooks",
    };

    private static final Object RESOLVE_LOCK = new Object();
    private static List<Field> resolvedFields;
    private static boolean resolved;

    private ServerLifecycleHooksCompat() {}

    /**
     * 影子端 {@code initServer} 成功后调用：把本实例写入可用的全局 currentServer 槽。
     * 槽已被真服占用（单人集成服）时跳过。
     */
    public static void adoptCurrentServer(MinecraftServer shadow) {
        if (shadow == null) {
            return;
        }
        for (Field field : currentServerFields()) {
            try {
                Object current = field.get(null);
                if (current == null || current == shadow) {
                    field.set(null, shadow);
                    Constants.LOG.debug("Hassium: adopted shadow server as currentServer via {}",
                            field.getDeclaringClass().getName());
                }
            } catch (Throwable t) {
                Constants.LOG.debug("Hassium: adopt currentServer failed via {}",
                        field.getDeclaringClass().getName(), t);
            }
        }
    }

    /**
     * 影子端关停：若槽仍指向本实例则清空（与 NeoForge {@code expectServerStopped} 对齐）。
     * 不清集成服真服。
     */
    public static void releaseCurrentServer(MinecraftServer shadow) {
        if (shadow == null) {
            return;
        }
        for (Field field : currentServerFields()) {
            try {
                if (field.get(null) == shadow) {
                    field.set(null, null);
                    Constants.LOG.debug("Hassium: released shadow server from currentServer via {}",
                            field.getDeclaringClass().getName());
                }
            } catch (Throwable t) {
                Constants.LOG.debug("Hassium: release currentServer failed via {}",
                        field.getDeclaringClass().getName(), t);
            }
        }
    }

    private static List<Field> currentServerFields() {
        if (resolved) {
            return resolvedFields;
        }
        synchronized (RESOLVE_LOCK) {
            if (resolved) {
                return resolvedFields;
            }
            List<Field> fields = new ArrayList<>(HOOKS_CLASS_NAMES.length);
            for (String name : HOOKS_CLASS_NAMES) {
                try {
                    Class<?> hooks = Class.forName(name);
                    Field field = hooks.getDeclaredField("currentServer");
                    field.setAccessible(true);
                    fields.add(field);
                } catch (ClassNotFoundException | NoSuchFieldException ignored) {
                    // 本加载器/本会话无该 hooks 类——正常
                } catch (Throwable t) {
                    Constants.LOG.debug("Hassium: failed to resolve currentServer on {}", name, t);
                }
            }
            resolvedFields = List.copyOf(fields);
            resolved = true;
            return resolvedFields;
        }
    }
}
