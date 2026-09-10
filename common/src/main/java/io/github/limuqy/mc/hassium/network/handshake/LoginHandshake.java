package io.github.limuqy.mc.hassium.network.handshake;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 登录期能力握手线格式（直连拓扑；语义借鉴 dev 分支 {@code contract/LoginHandshake}
 * 的「原版路径回退、不依赖超时」设计）。
 *
 * <p>双载体协商，同一套能力位语义（{@link LoginCaps}）：
 * <ul>
 *   <li><b>1.20.1</b>：vanilla login custom query（{@code hassium:login_hello}）——
 *       服务端 {@code handleHello} HEAD 发 query，客户端应答 {@link HelloAnswer}；
 *       1.20.1 协议原样保留 query 应答体（{@code getData()}），两端可读。</li>
 *   <li><b>1.20.2+</b>：配置阶段 {@code PreHandshakePayload}（C2S，loader 注册）——
 *       原版 codec 对未知 login query 应答体逐字节丢弃（DiscardedQueryAnswerPayload），
 *       登录期无法携带能力体；配置阶段在认证完成后、ServerPlayer 创建前，
 *       效果等同登录期握手（玩家进世界前协商必然完成）。</li>
 * </ul>
 * 服务端协商结果按玩家 UUID 登记（{@code PlayerCompressionTracker}），Play 期
 * {@code ServerHandshakeActivation} 消费并下发 {@link PlayInitPayload}（协商结果 +
 * SeedGen 种子），客户端据此安装 ZSTD 并回 compression_ready（时序与 1.1.2 期一致）。
 */
public final class LoginHandshake {

    private LoginHandshake() {
    }

    /** HELLO query 通道 path（1.20.1 login query；namespace 恒为 mod id）。 */
    public static final String HELLO_CHANNEL = "login_hello";

    /**
     * 服务端使用的 transactionId。原版服务端登录期不发 query；取离群常量避免与
     * Velocity modern forwarding 等同连接期其他 login query 撞号。
     */
    public static final int TRANSACTION_ID = 4242;

    /**
     * 客户端 HELLO 应答（仅 1.20.1 login query 载体；1.20.2+ 走 PreHandshakePayload）。
     *
     * @param clientCaps 客户端声明能力位
     * @param modVersion 模组版本（服务端做格式校验，防日志注入）
     */
    public record HelloAnswer(int clientCaps, String modVersion) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(clientCaps);
            buf.writeUtf(modVersion, 128);
        }

        public static HelloAnswer decode(FriendlyByteBuf buf) {
            return new HelloAnswer(buf.readVarInt(), buf.readUtf(128));
        }
    }

    /**
     * Play 期激活（S2C 自定义 payload 体；三端注册 {@code hassium:play_init_s2c}）。
     * 客户端收到后：协商位入 {@code ClientLoginNegotiation}；globalCompression 协商
     * → 安装 ZSTD + 回 compression_ready；seedGen 协商 → 影子端种子初始化。
     *
     * @param negotiatedCaps 服务端按位与后的协商能力位
     * @param worldSeed      主世界种子；seedGenEnabled=false 时为 0（避免关功能仍泄露种子）
     * @param stemNbt        LevelStem NBT（可空）
     * @param seedGenEnabled 服务端 SeedGen 开关
     */
    public record PlayInitPayload(int negotiatedCaps, long worldSeed, byte[] stemNbt,
                                  boolean seedGenEnabled) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(negotiatedCaps);
            buf.writeLong(worldSeed);
            buf.writeVarInt(stemNbt != null ? stemNbt.length : 0);
            if (stemNbt != null) {
                buf.writeBytes(stemNbt);
            }
            buf.writeBoolean(seedGenEnabled);
        }

        public static PlayInitPayload decode(FriendlyByteBuf buf) {
            int caps = buf.readVarInt();
            long worldSeed = buf.readLong();
            int stemLen = buf.readVarInt();
            byte[] stemNbt = null;
            if (stemLen > 0 && stemLen <= buf.readableBytes()) {
                stemNbt = new byte[stemLen];
                buf.readBytes(stemNbt);
            }
            boolean enabled = buf.readableBytes() >= 1 && buf.readBoolean();
            return new PlayInitPayload(caps, worldSeed, stemNbt, enabled);
        }
    }

    /** 协议版本校验：须落在 {@code [1, CURRENT]}（允许更旧客户端）。 */
    public static boolean isProtocolVersionAccepted(int protocolVersion, int currentVersion) {
        return protocolVersion >= 1 && protocolVersion <= currentVersion;
    }

    /**
     * mod 版本格式校验（T2-72 同款：非空、长度 ≤64、仅 {@code [0-9A-Za-z._+-]}），防日志注入。
     */
    public static boolean isValidModVersion(String version) {
        if (version == null || version.isEmpty() || version.length() > 64) {
            return false;
        }
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || c == '.' || c == '_' || c == '-' || c == '+';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** 协商位摘要（日志稳定可读）。 */
    public static String describeCaps(int caps) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        first = appendBit(sb, first, caps, LoginCaps.AGGREGATION, "agg");
        first = appendBit(sb, first, caps, LoginCaps.SECTION_DELTA, "delta");
        first = appendBit(sb, first, caps, LoginCaps.SEED_GEN, "seed");
        first = appendBit(sb, first, caps, LoginCaps.LIGHT_STRIP, "light");
        appendBit(sb, first, caps, LoginCaps.SHADOW_PULL, "pull");
        return sb.append(']').toString();
    }

    private static boolean appendBit(StringBuilder sb, boolean first, int caps, int bit, String name) {
        if ((caps & bit) == 0) {
            return first;
        }
        if (!first) {
            sb.append(',');
        }
        sb.append(name);
        return false;
    }
}
