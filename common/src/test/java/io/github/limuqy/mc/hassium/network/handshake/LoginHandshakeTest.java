package io.github.limuqy.mc.hassium.network.handshake;

import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录期能力握手线格式 L0 单测（编解码纯函数；无 MC 实例依赖）。
 */
class LoginHandshakeTest {

    @Test
    void helloAnswerRoundTrips() {
        LoginHandshake.HelloAnswer original = new LoginHandshake.HelloAnswer(0x1F3, "2.0.0+build.1");
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            original.encode(buf);
            LoginHandshake.HelloAnswer decoded = LoginHandshake.HelloAnswer.decode(buf);
            assertEquals(original.clientCaps(), decoded.clientCaps());
            assertEquals(original.modVersion(), decoded.modVersion());
        } finally {
            buf.release();
        }
    }

    @Test
    void playInitPayloadRoundTripsWithStem() {
        byte[] stem = "level-stem-nbt-bytes".getBytes(StandardCharsets.UTF_8);
        LoginHandshake.PlayInitPayload original =
                new LoginHandshake.PlayInitPayload(0b1011, 1234567890123456789L, stem, true);
        LoginHandshake.PlayInitPayload decoded = encodeDecode(original);
        assertEquals(original.negotiatedCaps(), decoded.negotiatedCaps());
        assertEquals(original.worldSeed(), decoded.worldSeed());
        assertTrue(java.util.Arrays.equals(stem, decoded.stemNbt()));
        assertTrue(decoded.seedGenEnabled());
    }

    @Test
    void playInitPayloadRoundTripsWithoutStem() {
        LoginHandshake.PlayInitPayload original =
                new LoginHandshake.PlayInitPayload(0, 0L, null, false);
        LoginHandshake.PlayInitPayload decoded = encodeDecode(original);
        assertEquals(0, decoded.negotiatedCaps());
        assertEquals(0L, decoded.worldSeed());
        assertEquals(null, decoded.stemNbt());
        assertFalse(decoded.seedGenEnabled());
    }

    @Test
    void playInitPayloadDecodeToleratesMissingTrailingFlag() {
        // 旧服务端不带 seedGenEnabled 尾位（append-only 演进）：flag 缺失按 false 处理
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            buf.writeVarInt(0b101);
            buf.writeLong(42L);
            buf.writeVarInt(0);
            LoginHandshake.PlayInitPayload decoded = LoginHandshake.PlayInitPayload.decode(buf);
            assertEquals(0b101, decoded.negotiatedCaps());
            assertEquals(42L, decoded.worldSeed());
            assertFalse(decoded.seedGenEnabled());
        } finally {
            buf.release();
        }
    }

    @Test
    void protocolVersionBoundaries() {
        assertTrue(LoginHandshake.isProtocolVersionAccepted(1, 1));
        assertTrue(LoginHandshake.isProtocolVersionAccepted(1, 7));
        assertFalse(LoginHandshake.isProtocolVersionAccepted(0, 7));
        assertFalse(LoginHandshake.isProtocolVersionAccepted(8, 7));
    }

    @Test
    void modVersionValidationBlocksLogInjection() {
        assertTrue(LoginHandshake.isValidModVersion("2.0.0"));
        assertTrue(LoginHandshake.isValidModVersion("2.0.0-beta.1+mc1.20.1"));
        assertFalse(LoginHandshake.isValidModVersion(null));
        assertFalse(LoginHandshake.isValidModVersion(""));
        assertFalse(LoginHandshake.isValidModVersion("2.0.0\nINFO spoofed"));
        assertFalse(LoginHandshake.isValidModVersion("2.0.0 path/../traversal"));
        assertFalse(LoginHandshake.isValidModVersion("x".repeat(65)));
    }

    @Test
    void describeCapsListsNegotiatedBitsStably() {
        int caps = LoginCaps.AGGREGATION | LoginCaps.SEED_GEN | LoginCaps.SHADOW_PULL;
        assertEquals("[agg,seed,pull]", LoginHandshake.describeCaps(caps));
        assertEquals("[]", LoginHandshake.describeCaps(0));
    }

    @Test
    void negotiateIntersectsAndHasQueriesBits() {
        int server = LoginCaps.AGGREGATION | LoginCaps.SECTION_DELTA;
        int client = LoginCaps.SECTION_DELTA | LoginCaps.SHADOW_PULL;
        int negotiated = LoginCaps.negotiate(server, client);
        assertEquals(LoginCaps.SECTION_DELTA, negotiated);
        assertTrue(LoginCaps.has(negotiated, LoginCaps.SECTION_DELTA));
        assertFalse(LoginCaps.has(negotiated, LoginCaps.AGGREGATION));
    }

    private static LoginHandshake.PlayInitPayload encodeDecode(LoginHandshake.PlayInitPayload payload) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            payload.encode(buf);
            return LoginHandshake.PlayInitPayload.decode(buf);
        } finally {
            buf.release();
        }
    }
}
