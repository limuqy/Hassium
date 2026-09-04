package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.compat.ReflectionCompat;
import io.github.limuqy.mc.hassium.config.HassiumConfigService;
import io.github.limuqy.mc.hassium.network.handshake.LoginCaps;
import io.github.limuqy.mc.hassium.network.handshake.LoginHandshake;
import io.github.limuqy.mc.hassium.network.handshake.LoginHandshakeManager;
import io.github.limuqy.mc.hassium.server.RuntimeServerContext;
import io.github.limuqy.mc.hassium.utils.DebugLogger;
import io.github.limuqy.mc.hassium.utils.DebugLogger.LogType;
import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 登录期能力握手——服务端 1.20.1 login query 载体（1.20.2+ 走配置阶段
 * PreHandshakePayload，见各 loader 注册；原版 codec 对未知 login query 应答体
 * 逐字节丢弃，登录期无法携带能力体）。
 * <p>
 * {@code handleHello} HEAD（收到 Login Start 后、EncryptionRequest/离线验证之前，
 * 明文，在线/离线统一插入点）发送 {@code hassium:login_hello} query；vanilla 客户端
 * 恒回空应答 → 原版路径，不依赖超时。应答在 {@code handleCustomQueryPacket} HEAD
 * 解析（仅消费本模组 transactionId）。
 * <p>
 * 仅专用服 + master.enabled 时发送（单人/局域网/影子端不发）。
 * <p>
 * <b>T2-91 接受兜底（slow_login 根因）</b>：vanilla 在 Netty IO 线程写非 volatile 的
 * {@code state=READY_TO_ACCEPT}，server 线程在 {@code tick()} 读取并执行 accept。冒烟
 * 实证（seedgen1/2/3/5/6 复现、classic 与带逐 tick 日志探针的 seedgen8 不复现）：C2
 * 对该热路径做 LICM 后，server 线程的 state 读永远命中陈旧 HELLO（JMM 允许对普通
 * 字段做单线程假设），600 tick 后 {@code slow_login} 踢人；探针的反射读与日志锁恰好
 * 屏障化后即恢复正常——纯可见性竞争，非状态机逻辑问题。修复：应答处理完成后向
 * server 线程投递一次受 state 守卫的 {@code handleAcceptedLogin} 兜底
 * （BlockableEventLoop 队列出队自带 happens-before，与 vanilla tick 在同一线程严格
 * 串行，双重 {@code state==READY_TO_ACCEPT} 守卫 + 一次性标记保证恰好执行一次）。
 */
#if MC_VER < MC_1_21_1
@Mixin(net.minecraft.server.network.ServerLoginPacketListenerImpl.class)
public abstract class MixinServerLoginPacketListenerImpl {

    @Shadow
    public abstract void handleAcceptedLogin();

    @Unique
    private static volatile java.lang.reflect.Field hassium$stateField;

    @Unique
    private volatile boolean hassium$acceptFallbackScheduled;

    @Unique
    private volatile boolean hassium$accepted;

    @Inject(method = "handleHello(Lnet/minecraft/network/protocol/login/ServerboundHelloPacket;)V",
            at = @At("HEAD"))
    private void hassium$onLoginHello(ServerboundHelloPacket packet, CallbackInfo ci) {
        if (!RuntimeServerContext.isDedicatedServerContext()
                || !HassiumConfigService.getInstance().isMasterEnabled()) {
            return;
        }
        Connection connection = (Connection) ReflectionCompat.getFieldByTypeOrNull(this, Connection.class, true);
        if (connection == null || !connection.isConnected()) {
            return;
        }
        int serverCaps = LoginCaps.buildServerCaps();
        LoginHandshakeManager.onQuerySent(connection, serverCaps);
        // 1.20.1 query 体可读，但客户端不消费服务端声明位（协商结果由 Play 期
        // play_init_s2c 下发），body 留空最小化；空体不影响 vanilla 客户端应答行为。
        FriendlyByteBuf body = new FriendlyByteBuf(Unpooled.buffer());
        try {
            connection.send(new net.minecraft.network.protocol.login.ClientboundCustomQueryPacket(
                    LoginHandshake.TRANSACTION_ID,
                    new net.minecraft.resources.ResourceLocation(Constants.MOD_ID, LoginHandshake.HELLO_CHANNEL),
                    body));
            DebugLogger.info(LogType.NETWORK, "[LOGIN_HELLO] query sent (serverCaps=0x{})",
                    Integer.toHexString(serverCaps));
        } catch (Exception e) {
            body.release();
            Constants.LOG.warn("Hassium: Failed to send login hello query", e);
        }
    }

    @Inject(method = "handleCustomQueryPacket(Lnet/minecraft/network/protocol/login/ServerboundCustomQueryPacket;)V",
            at = @At("HEAD"), cancellable = true)
    private void hassium$onLoginQueryAnswer(
            net.minecraft.network.protocol.login.ServerboundCustomQueryPacket packet, CallbackInfo ci) {
        if (packet.getTransactionId() != LoginHandshake.TRANSACTION_ID) {
            return;
        }
        Connection connection = (Connection) ReflectionCompat.getFieldByTypeOrNull(this, Connection.class, true);
        if (connection != null) {
            LoginHandshakeManager.handleAnswer(this, connection, packet.getData());
            hassium$scheduleAcceptFallback();
        }
        ci.cancel();
    }

    /**
     * T2-91 接受兜底：把 accept 推到 server 线程执行（见类注释）。handleAnswer 的
     * PENDING_QUERY_CAPS.remove 语义保证每个连接只应答一次，本方法天然单次调度。
     */
    @Unique
    private void hassium$scheduleAcceptFallback() {
        if (hassium$acceptFallbackScheduled) {
            return;
        }
        hassium$acceptFallbackScheduled = true;
        Object serverObj = ReflectionCompat.getFieldByTypeOrNull(
                this, net.minecraft.server.MinecraftServer.class, true);
        if (!(serverObj instanceof net.minecraft.server.MinecraftServer server)) {
            Constants.LOG.warn("Hassium: login accept fallback skipped (server unavailable)");
            return;
        }
        server.submit(() -> {
            if (hassium$accepted) {
                return;
            }
            try {
                Object state = hassium$readState();
                // vanilla tick（fresh 读）可能已先 accept（state=ACCEPTED）；stale 读恒为
                // HELLO 也不会走到这里——两个方向都不会双发。
                if (state != null && "READY_TO_ACCEPT".contentEquals(state.toString())) {
                    hassium$accepted = true;
                    handleAcceptedLogin();
                    DebugLogger.info(LogType.NETWORK, "[LOGIN_HELLO] accept fallback executed");
                }
            } catch (Exception e) {
                Constants.LOG.warn("Hassium: login accept fallback failed", e);
            }
        });
    }

    @Unique
    private Object hassium$readState() {
        try {
            java.lang.reflect.Field f = hassium$stateField;
            if (f == null) {
                f = this.getClass().getDeclaredField("state");
                f.setAccessible(true);
                hassium$stateField = f;
            }
            return f.get(this);
        } catch (Exception e) {
            return null;
        }
    }
}
#else
/**
 * 1.20.2+ 无 login query 载体（原版 codec 丢弃未知应答体；协商走配置阶段 payload）。
 * 保留空壳 mixin 注册位，避免 mixins.json 分版本维护。
 */
@Mixin(net.minecraft.server.network.ServerLoginPacketListenerImpl.class)
public class MixinServerLoginPacketListenerImpl {
}
#endif
