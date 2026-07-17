package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.cache.client.CacheSaveQueue;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

/**
 * Minecraft 主类 Mixin
 * 用于在客户端初始化时加载配置
 */
@Mixin(Minecraft.class)
public class MixinMinecraft {
    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/MixinMinecraft");

    /**
     * 在 Minecraft 实例创建后初始化 Hassium 配置
     */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        LOGGER.info("Hassium: Initializing client-side configuration");
        try {
            // 获取 Minecraft 配置目录
            Minecraft mc = (Minecraft) (Object) this;
            Path configDir = mc.gameDirectory.toPath().resolve("config").resolve(Constants.MOD_ID);

            // 初始化配置服务
            HassiumConfigService configService = HassiumConfigService.getInstance();
            configService.setConfigDir(configDir);
            configService.loadConfig();

            LOGGER.info("Hassium: Configuration loaded successfully from {}", configDir);
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to load configuration", e);
        }
    }

    /**
     * 在 Minecraft 关闭时保存配置
     */
    @Inject(method = "close", at = @At("HEAD"), remap = false)
    private void onClose(CallbackInfo ci) {
        LOGGER.info("Hassium: Saving configuration before shutdown");
        try {
            HassiumConfigService.getInstance().saveConfig();
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to save configuration", e);
        }
    }

    /**
     * 在 level 切换前刷新缓存保存队列
     * <p>
     * 维度切换和断开连接都会调用 setLevel()，但不会逐个 unload 区块。
     * 必须在旧 level 被替换前保存所有待处理的区块。
     * <p>
     * 1.20.5–1.21.8：{@code setLevel(ClientLevel, ReceivingLevelScreen.Reason)}；
     * 1.21.9+：Reason 参数移除，恢复为单参数。
     */
#if MC_VER < MC_1_20_5
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void hassium$onSetLevel(ClientLevel newLevel, CallbackInfo ci) {
        hassium$flushCacheSaveQueue();
    }
#elif MC_VER < MC_1_21_9
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void hassium$onSetLevel(ClientLevel newLevel,
                                     net.minecraft.client.gui.screens.ReceivingLevelScreen.Reason reason,
                                     CallbackInfo ci) {
        hassium$flushCacheSaveQueue();
    }
#else
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void hassium$onSetLevel(ClientLevel newLevel, CallbackInfo ci) {
        hassium$flushCacheSaveQueue();
    }
#endif

    @Unique
    private void hassium$flushCacheSaveQueue() {
        try {
            CacheSaveQueue.getInstance().flush();
        } catch (Exception e) {
            LOGGER.error("Hassium: Failed to flush cache save queue on level change", e);
        }
    }
}
