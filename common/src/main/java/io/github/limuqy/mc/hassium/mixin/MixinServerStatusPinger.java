package io.github.limuqy.mc.hassium.mixin;

import io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity;
import io.github.limuqy.mc.hassium.network.dataplane.ControlEndpoint;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
#if MC_VER < MC_1_21_11
import net.minecraft.Util;
#else
import net.minecraft.util.Util;
#endif
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.status.ClientStatusPacketListener;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.protocol.status.ServerboundStatusRequestPacket;
#if MC_VER < MC_1_20_5
import net.minecraft.network.protocol.status.ClientboundPongResponsePacket;
import net.minecraft.network.protocol.status.ServerboundPingRequestPacket;
#elif MC_VER < MC_1_21_1
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
#else
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务器列表备用状态：主地址 ping 失败（vanilla {@code onPingFailed}）时，自动按
 * priority 依次 ping 持久化候选（仅由真实握手通告写入 store），把备用节点的
 * 人数/延迟/motd 回写主条目并附加「备用」标记；候选全部失败则恢复 vanilla 红字。
 *
 * <p>候选 ping 为自实现的最小 status 协议（intention + status request + ping request），
 * 不走 vanilla {@code pingServer}（避免候选失败递归 onPingFailed 与 anonymous-listener
 * 注入脆弱性）；成功回调在 netty 线程写 ServerData —— 与 vanilla 的 status 响应同款，
 * 无新增线程风险。
 */
@Mixin(ServerStatusPinger.class)
public class MixinServerStatusPinger {

    /** 进行中的备用 ping 链：主条目 ip → 链状态（ConcurrentHashMap：THREAD_POOL 多线程 + netty 回调）。 */
    @Unique
    private static final Map<String, BackupChain> HASSIUM_BACKUP_CHAINS = new ConcurrentHashMap<>();

    @Unique
    private static final class BackupChain {
        final List<ControlEndpoint> candidates;
        final ServerData target;

        BackupChain(List<ControlEndpoint> candidates, ServerData target) {
            this.candidates = candidates;
            this.target = target;
        }
    }

    /**
     * 拦截 vanilla pingServer：主地址（有持久化候选）直接改走候选 ping 链。
     * <p>
     * 1.20.1 的 {@code Connection.connectToServer} 是同步阻塞（syncUninterruptibly），
     * 主地址拒绝连接会同步抛异常 → ServerEntry 的 catch 直接显示红字，
     * {@code onPingFailed} 永不触发；因此接管点必须是 pingServer 本身。
     * 1.20.5+ 同入口（异步失败路径仍由 onPingFailed 注入兜底）。
     */
#if MC_VER < MC_1_20_5
    @Inject(method = "pingServer", at = @At("HEAD"), cancellable = true)
    private void hassium$onPingServer(ServerData serverData, Runnable refresh, CallbackInfo ci) {
#elif MC_VER < MC_1_21_11
    @Inject(method = "pingServer", at = @At("HEAD"), cancellable = true)
    private void hassium$onPingServer(ServerData serverData, Runnable iconRefresh, Runnable statusRefresh, CallbackInfo ci) {
#else
    // 1.21.11 的 pingServer 增加了 EventLoopGroupHolder 参数，mixin 描述符须对齐
    @Inject(method = "pingServer", at = @At("HEAD"), cancellable = true)
    private void hassium$onPingServer(ServerData serverData, Runnable iconRefresh, Runnable statusRefresh,
                                      net.minecraft.server.network.EventLoopGroupHolder eventLoopGroupHolder, CallbackInfo ci) {
#endif
        if (serverData == null || serverData.name.startsWith("hassium-failover:")) {
            return;
        }
        List<ControlEndpoint> candidates = ClientFailoverIdentity.findBackupFor(serverData.ip);
        if (candidates.isEmpty()) {
            return; // 普通服务器：vanilla 原样
        }
        ci.cancel();
        if (io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isDataplaneLogging()) {
            org.slf4j.LoggerFactory.getLogger("Hassium/ServerList").info(
                    "[diag] ServerStatusPinger.pingServer ip={} name={} candidates={}",
                    serverData.ip, serverData.name, candidates.size());
        }
        serverData.motd = Component.translatable("multiplayer.status.pinging");
        serverData.ping = -1L;
        serverData.playerList = List.of();
        HASSIUM_BACKUP_CHAINS.put(serverData.ip, new BackupChain(candidates, serverData));
        // 先 ping 主地址本身：健康 → 正常显示（无备用标记）；失败 → 候选链（备用标记）
        try {
            ServerAddress address = ServerAddress.parseString(serverData.ip);
            Optional<InetSocketAddress> resolved =
                    ServerNameResolver.DEFAULT.resolveAddress(address).map(ResolvedServerAddress::asInetSocketAddress);
            if (resolved.isPresent()) {
                hassium$startStatusPing(serverData, resolved.get(), PRIMARY_PING);
                return;
            }
        } catch (Exception ignored) {
            // 主地址不可解析：落候选链
        }
        hassium$pingCandidate(serverData, candidates.get(0), 0);
    }

    @Inject(method = "onPingFailed", at = @At("HEAD"), cancellable = true)
    private void hassium$onPingFailed(Component reason, ServerData serverData, CallbackInfo ci) {
        if (io.github.limuqy.mc.hassium.config.HassiumConfigService.getInstance().isDataplaneLogging()) {
            org.slf4j.LoggerFactory.getLogger("Hassium/ServerList").info(
                    "[diag] ServerStatusPinger.onPingFailed ip={} name={} reason={} candidates={}",
                    serverData == null ? "null" : serverData.ip,
                    serverData == null ? "-" : serverData.name,
                    reason.getString(),
                    serverData == null ? 0
                            : io.github.limuqy.mc.hassium.network.dataplane.ClientFailoverIdentity.findBackupFor(serverData.ip).size());
        }
        if (serverData == null || serverData.name.startsWith("hassium-failover:")) {
            return; // 合成候选：不接管
        }
        List<ControlEndpoint> candidates = ClientFailoverIdentity.findBackupFor(serverData.ip);
        if (candidates.isEmpty()) {
            return; // 普通服务器：vanilla 原样
        }
        ci.cancel();
        if (HASSIUM_BACKUP_CHAINS.containsKey(serverData.ip)) {
            return; // 链已在进行（vanilla 周期重 ping / legacy 重复触发）：不重复建链
        }
        HASSIUM_BACKUP_CHAINS.put(serverData.ip, new BackupChain(candidates, serverData));
        hassium$pingCandidate(serverData, candidates.get(0), 0);
    }

    /** 对候选发起一次最小 status ping；失败/不可达 → 推进下一候选。 */
    @Unique
    private static void hassium$pingCandidate(ServerData target, ControlEndpoint endpoint, int index) {
        try {
            ServerAddress address = ServerAddress.parseString(endpoint.host() + ":" + endpoint.port());
            Optional<InetSocketAddress> resolved =
                    ServerNameResolver.DEFAULT.resolveAddress(address).map(ResolvedServerAddress::asInetSocketAddress);
            if (resolved.isEmpty()) {
                hassium$onCandidateFailed(target, index);
                return;
            }
            hassium$startStatusPing(target, resolved.get(), index);
        } catch (Exception e) {
            hassium$onCandidateFailed(target, index);
        }
    }

    /** 主地址 ping 的标记值（backupIndex < 0 表示主地址：成功不加备用标记，失败转候选链）。 */
    @Unique
    private static final int PRIMARY_PING = -1;

    /** 对指定地址发起一次最小 status ping；成功/失败回调按 backupIndex 区分主/备语义。 */
    @Unique
    private static void hassium$startStatusPing(ServerData target, InetSocketAddress socket, int backupIndex) {
        try {
#if MC_VER < MC_1_20_2
            Connection connection = Connection.connectToServer(socket, false);
#elif MC_VER < MC_1_21_11
            Connection connection = Connection.connectToServer(socket, false, null);
#else
            // 1.21.11+ 第二参数由 boolean useEpoll 改为 EventLoopGroupHolder
            Connection connection = Connection.connectToServer(
                    socket, net.minecraft.server.network.EventLoopGroupHolder.remote(false), null);
#endif
            ClientStatusPacketListener listener = new ClientStatusPacketListener() {
                private boolean success;
                private long pingStart;

                @Override
                public void handleStatusResponse(ClientboundStatusResponsePacket packet) {
                    if (success) {
                        return;
                    }
                    success = true;
                    hassium$applyStatus(target, packet.status(), backupIndex >= 0);
                    pingStart = Util.getMillis();
                    connection.send(new ServerboundPingRequestPacket(pingStart));
                }

                @Override
                public void handlePongResponse(ClientboundPongResponsePacket packet) {
#if MC_VER < MC_1_20_5
                    target.ping = Util.getMillis() - packet.getTime();
#else
                    target.ping = Util.getMillis() - packet.time();
#endif
                    connection.disconnect(CommonComponents.EMPTY);
                    HASSIUM_BACKUP_CHAINS.remove(target.ip);
                }

#if MC_VER < MC_1_21_1
                @Override
                public void onDisconnect(Component disconnectReason) {
                    if (!success) {
                        hassium$onPingClosed(target, backupIndex);
                    }
                }
#else
                @Override
                public void onDisconnect(DisconnectionDetails details) {
                    if (!success) {
                        hassium$onPingClosed(target, backupIndex);
                    }
                }
#endif

                @Override
                public boolean isAcceptingMessages() {
                    return connection.isConnected();
                }
            };
#if MC_VER < MC_1_20_2
            connection.setListener(listener);
            connection.send(new ClientIntentionPacket(socket.getHostString(), socket.getPort(), ConnectionProtocol.STATUS));
            connection.send(new ServerboundStatusRequestPacket());
#elif MC_VER < MC_1_20_5
            // 1.20.2–1.20.4：connectToServer 已改 3 参，ClientIntentionPacket 改 (protocolVersion, host, port, intent)
            connection.setListener(listener);
            connection.send(new ClientIntentionPacket(SharedConstants.getProtocolVersion(),
                    socket.getHostString(), socket.getPort(), net.minecraft.network.protocol.handshake.ClientIntent.STATUS));
            connection.send(new ServerboundStatusRequestPacket());
#else
            connection.initiateServerboundStatusConnection(socket.getHostString(), socket.getPort(), listener);
            connection.send(ServerboundStatusRequestPacket.INSTANCE);
#endif
        } catch (Exception e) {
            hassium$onPingClosed(target, backupIndex);
        }
    }

    /** ping 连接关闭（失败）：主地址 → 转候选链；候选 → 推进下一候选。 */
    @Unique
    private static void hassium$onPingClosed(ServerData target, int backupIndex) {
        if (backupIndex >= 0) {
            hassium$onCandidateFailed(target, backupIndex);
            return;
        }
        BackupChain chain = HASSIUM_BACKUP_CHAINS.get(target.ip);
        if (chain == null || chain.candidates.isEmpty()) {
            HASSIUM_BACKUP_CHAINS.remove(target.ip);
            hassium$restoreFailure(target);
        } else {
            hassium$pingCandidate(target, chain.candidates.get(0), 0);
        }
    }

    /** 候选失败：推进下一候选；耗尽 → 恢复 vanilla 红字。 */
    @Unique
    private static void hassium$onCandidateFailed(ServerData target, int failedIndex) {
        BackupChain chain = HASSIUM_BACKUP_CHAINS.get(target.ip);
        if (chain == null) {
            return;
        }
        int next = failedIndex + 1;
        if (next >= chain.candidates.size()) {
            HASSIUM_BACKUP_CHAINS.remove(target.ip);
            hassium$restoreFailure(target);
        } else {
            hassium$pingCandidate(target, chain.candidates.get(next), next);
        }
    }

    /** 把 ping 到的 ServerStatus 复制到主条目；markBackup（备用节点）时人数行附加「备用」标记。 */
    @Unique
    private static void hassium$applyStatus(ServerData target, ServerStatus status, boolean markBackup) {
        target.motd = status.description();
        status.version().ifPresentOrElse(version -> {
            target.version = Component.literal(version.name());
            target.protocol = version.protocol();
        }, () -> {
            target.version = Component.translatable("multiplayer.status.old");
            target.protocol = 0;
        });
        status.players().ifPresentOrElse(players -> {
            Component count = hassium$formatPlayerCount(players.online(), players.max());
            target.status = markBackup
                    ? count.copy().append(Component.translatable("hassium.failover.backup_status")
                            .withStyle(ChatFormatting.YELLOW))
                    : count;
            target.players = players;
        }, () -> target.status = Component.translatable("multiplayer.status.unknown")
                .withStyle(ChatFormatting.DARK_GRAY));
#if MC_VER >= MC_1_20_5 && MC_VER < MC_1_21_6
        target.setState(target.protocol == SharedConstants.getCurrentVersion().getProtocolVersion()
                ? ServerData.State.SUCCESSFUL
                : ServerData.State.INCOMPATIBLE);
#elif MC_VER >= MC_1_21_6
        // 1.21.6+ yarn 把 WorldVersion.getProtocolVersion 改为 protocolVersion
        target.setState(target.protocol == SharedConstants.getCurrentVersion().protocolVersion()
                ? ServerData.State.SUCCESSFUL
                : ServerData.State.INCOMPATIBLE);
#endif
    }

    /** 候选全部失败：恢复 vanilla onPingFailed 的红字显示。 */
    @Unique
    private static void hassium$restoreFailure(ServerData target) {
        target.motd = Component.translatable("multiplayer.status.cannot_connect")
                .withStyle(style -> style.withColor(-65536));
        target.status = CommonComponents.EMPTY;
#if MC_VER >= MC_1_20_5
        target.setState(ServerData.State.UNREACHABLE);
#endif
    }

    /** 与 vanilla 同款人数格式（1.20.1 literal 拼接；跨版本统一实现，视觉无差）。 */
    @Unique
    private static Component hassium$formatPlayerCount(int online, int max) {
        return Component.literal(Integer.toString(online))
                .append(Component.literal("/").withStyle(ChatFormatting.DARK_GRAY))
                .append(Integer.toString(max))
                .withStyle(ChatFormatting.GRAY);
    }
}
