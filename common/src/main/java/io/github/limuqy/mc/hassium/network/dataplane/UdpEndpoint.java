package io.github.limuqy.mc.hassium.network.dataplane;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * UDP 数据面端点配置与本地身份 {@code (record}-like immutable value object)。
 *
 * <p>此类不持有 socket、不打开任何实际网络资源——仅承载可靠会话所需的「本地端身份」与
 * 「传输配置」。真正发送由 {@link ReliableDatagramSession.DatagramSink} 注入。
 * 这样保留 Task 3 把它接到 {@code DataPlaneServer} 与真实 UDP socket 的自由。
 *
 * <p>{@link Role} 决定本端 seal 时的 {@link UdpFrameCodec.Direction}：
 * {@code SERVER} 永远以 {@link UdpFrameCodec.Direction#SERVER_TO_CLIENT} seal；
 * {@code CLIENT} 永远以 {@link UdpFrameCodec.Direction#CLIENT_TO_SERVER} seal。
 * 接收方向则由对端决定，{@link ReliableDatagramSession#receive} 兼容两个方向的解封。
 *
 * <p>常量取值：
 * <ul>
 *   <li>{@code mtu}：默认 1200（避开常见 VPN/PPPoE 的 1400 安全边界，留足 KCP 头 + UDP 头余量）。</li>
 *   <li>{@code sndWindow}/{@code rcvWindow}：默认 128，KCP 应用于数据面 bulk 流的适度窗口。</li>
 *   <li>{@code maxQueuedAppBytes}：默认 1 MiB，应用层排队字节上限；到达/超过即非可写（拒绝新入队）。</li>
 *   <li>{@code maxReassemblyBytes}：默认 8 MiB，单个 KCP 消息重组上限（防恶意 / 损坏的超长帧）。</li>
 *   <li>{@code hardRttMs}：默认 1000ms，{@link ReliableDatagramSession#isHealthy()} 的硬 SRTT 门限。</li>
 * </ul>
 */
public final class UdpEndpoint {

    /** 本端角色——决定 seal 方向；不参与 wire。 */
    public enum Role {
        SERVER(UdpFrameCodec.Direction.SERVER_TO_CLIENT),
        CLIENT(UdpFrameCodec.Direction.CLIENT_TO_SERVER);

        private final UdpFrameCodec.Direction sealDirection;
        Role(UdpFrameCodec.Direction sealDirection) { this.sealDirection = sealDirection; }

        /** 该角色发出 KCP 应用消息时 {@link UdpFrameCodec#seal} 使用的方向。 */
        public UdpFrameCodec.Direction sealDirection() { return sealDirection; }
    }

    private final Role role;
    private final InetSocketAddress localAddress;

    private final int mtu;
    private final int sndWindow;
    private final int rcvWindow;
    private final int maxQueuedAppBytes;
    private final int maxReassemblyBytes;
    private final long hardRttMs;

    private UdpEndpoint(Builder b) {
        this.role = Objects.requireNonNull(b.role, "role");
        this.localAddress = b.localAddress != null ? b.localAddress
                : new InetSocketAddress(0);
        this.mtu = requirePositive(b.mtu, "mtu");
        this.sndWindow = requirePositive(b.sndWindow, "sndWindow");
        this.rcvWindow = requirePositive(b.rcvWindow, "rcvWindow");
        this.maxQueuedAppBytes = requireNonNegative(b.maxQueuedAppBytes, "maxQueuedAppBytes");
        this.maxReassemblyBytes = requirePositive(b.maxReassemblyBytes, "maxReassemblyBytes");
        this.hardRttMs = requirePositive(b.hardRttMs, "hardRttMs");
    }

    public Role role() { return role; }
    public InetSocketAddress localAddress() { return localAddress; }
    public int mtu() { return mtu; }
    public int sndWindow() { return sndWindow; }
    public int rcvWindow() { return rcvWindow; }
    public int maxQueuedAppBytes() { return maxQueuedAppBytes; }
    public int maxReassemblyBytes() { return maxReassemblyBytes; }
    public long hardRttMs() { return hardRttMs; }

    /** 复制一个新的 {@link UdpEndpoint}，只改变 {@link Role}。 */
    public UdpEndpoint toRole(Role newRole) {
        Builder b = new Builder();
        b.role = newRole;
        b.localAddress = this.localAddress;
        b.mtu = this.mtu;
        b.sndWindow = this.sndWindow;
        b.rcvWindow = this.rcvWindow;
        b.maxQueuedAppBytes = this.maxQueuedAppBytes;
        b.maxReassemblyBytes = this.maxReassemblyBytes;
        b.hardRttMs = this.hardRttMs;
        return b.build();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Role role;
        private InetSocketAddress localAddress;
        private int mtu = 1200;
        private int sndWindow = 128;
        private int rcvWindow = 128;
        private int maxQueuedAppBytes = 1 << 20;
        private int maxReassemblyBytes = 8 << 20;
        private long hardRttMs = 1000L;

        private Builder() {}

        public Builder role(Role role) { this.role = role; return this; }
        public Builder localAddress(InetSocketAddress addr) { this.localAddress = addr; return this; }
        public Builder mtu(int mtu) { this.mtu = mtu; return this; }
        public Builder sndWindow(int sndWindow) { this.sndWindow = sndWindow; return this; }
        public Builder rcvWindow(int rcvWindow) { this.rcvWindow = rcvWindow; return this; }
        public Builder maxQueuedAppBytes(int max) { this.maxQueuedAppBytes = max; return this; }
        public Builder maxReassemblyBytes(int max) { this.maxReassemblyBytes = max; return this; }
        public Builder hardRttMs(long hardRttMs) { this.hardRttMs = hardRttMs; return this; }

        public UdpEndpoint build() { return new UdpEndpoint(this); }
    }

    private static int requirePositive(int v, String name) {
        if (v <= 0) throw new IllegalArgumentException("Invalid UdpEndpoint " + name + ": " + v);
        return v;
    }

    private static int requireNonNegative(int v, String name) {
        if (v < 0) throw new IllegalArgumentException("Invalid UdpEndpoint " + name + ": " + v);
        return v;
    }

    private static long requirePositive(long v, String name) {
        if (v <= 0) throw new IllegalArgumentException("Invalid UdpEndpoint " + name + ": " + v);
        return v;
    }
}
