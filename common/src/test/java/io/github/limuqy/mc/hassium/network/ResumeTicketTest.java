package io.github.limuqy.mc.hassium.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T7 — {@link ResumeTicket} 编解码 + 验签，{@link ResumeTicketValidator} epoch 防重放。
 */
class ResumeTicketTest {

    private static final UUID PLAYER = UUID.fromString("a1b2c3d4-0000-4000-8000-000000000001");

    @Test
    @DisplayName("票据往返：encode/decode 后 uuid/epoch/签名一致")
    void ticketRoundTrips() {
        byte[] key = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        byte[] sig = ResumeTicket.sign(PLAYER, 42L, key);
        ResumeTicket ticket = new ResumeTicket(PLAYER, 42L, sig);

        ResumeTicket decoded = ResumeTicket.decode(ticket.encode());

        assertEquals(PLAYER, decoded.playerId());
        assertEquals(42L, decoded.epoch());
        assertArrayEquals(sig, decoded.signature());
        assertTrue(decoded.verify(key));
    }

    @Test
    @DisplayName("正确签名验票通过；错误密钥 / 篡改 epoch / 篡改 UUID 均拒绝")
    void verifyRejectsTampering() {
        byte[] key = "shared-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ResumeTicket ticket = new ResumeTicket(PLAYER, 7L, ResumeTicket.sign(PLAYER, 7L, key));

        assertTrue(ticket.verify(key), "正确密钥应通过");
        assertFalse(ticket.verify("other-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "错误密钥应拒绝");

        // 篡改 epoch：签名仍用原 epoch 7，票面改为 8 → 验签失败
        ResumeTicket tamperedEpoch = new ResumeTicket(PLAYER, 8L, ResumeTicket.sign(PLAYER, 7L, key));
        assertFalse(tamperedEpoch.verify(key), "篡改 epoch 应拒绝");

        // 篡改 UUID：签名仍用原 uuid，票面换人 → 验签失败
        ResumeTicket tamperedPlayer = new ResumeTicket(
                UUID.fromString("99999999-0000-4000-8000-000000000001"), 7L,
                ResumeTicket.sign(PLAYER, 7L, key));
        assertFalse(tamperedPlayer.verify(key), "篡改 UUID 应拒绝");
    }

    @Test
    @DisplayName("畸形票据解码抛 IllegalArgumentException（长度不符）")
    void decodeRejectsBadLength() {
        assertThrows(IllegalArgumentException.class, () -> ResumeTicket.decode(new byte[10]));
        assertThrows(IllegalArgumentException.class, () -> ResumeTicket.decode(null));
        byte[] tooLong = new byte[ResumeTicket.ENCODED_LENGTH + 1];
        assertThrows(IllegalArgumentException.class, () -> ResumeTicket.decode(tooLong));
    }

    @Test
    @DisplayName("verifyRequest：票据玩家与连接玩家不一致拒绝（防跨玩家重放）")
    void verifyRequestRejectsPlayerMismatch() {
        byte[] key = "k".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] keyBackup = ResumeTicket.sharedKey();
        try {
            ResumeTicket.setSharedKey(key);
            ResumeTicket ticket = new ResumeTicket(PLAYER, 1L, ResumeTicket.sign(PLAYER, 1L, key));
            assertTrue(ResumeTicketValidator.verifyRequest(PLAYER, ticket.encode()).accepted());
            // 同票以另一玩家身份提交 → 拒绝
            UUID other = UUID.fromString("b2b2b2b2-0000-4000-8000-000000000002");
            assertFalse(ResumeTicketValidator.verifyRequest(other, ticket.encode()).accepted());
        } finally {
            ResumeTicket.setSharedKey(keyBackup);
        }
    }

    @Test
    @DisplayName("epoch 递增防重放：同票重放拒绝、旧 epoch 拒绝、新 epoch 通过")
    void validatorRejectsReplayAndOldEpoch() {
        byte[] key = "epoch-key".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] keyBackup = ResumeTicket.sharedKey();
        try {
            ResumeTicket.setSharedKey(key);

            // epoch 5 首次提交 → 通过
            ResumeTicket ticket5 = new ResumeTicket(PLAYER, 5L, ResumeTicket.sign(PLAYER, 5L, key));
            assertTrue(ResumeTicketValidator.verifyAndAccept(ticket5), "首个票据应通过");
            assertEquals(5L, ResumeTicketValidator.lastAcceptedEpoch(PLAYER));

            // 同票重放（epoch 相等）→ 拒绝
            assertFalse(ResumeTicketValidator.verifyAndAccept(ticket5), "同票重放应拒绝");

            // 旧 epoch（3 < 5）→ 拒绝
            ResumeTicket ticket3 = new ResumeTicket(PLAYER, 3L, ResumeTicket.sign(PLAYER, 3L, key));
            assertFalse(ResumeTicketValidator.verifyAndAccept(ticket3), "旧 epoch 重放应拒绝");

            // 新 epoch（9 > 5）→ 通过
            ResumeTicket ticket9 = new ResumeTicket(PLAYER, 9L, ResumeTicket.sign(PLAYER, 9L, key));
            assertTrue(ResumeTicketValidator.verifyAndAccept(ticket9), "递增 epoch 应通过");
            assertEquals(9L, ResumeTicketValidator.lastAcceptedEpoch(PLAYER));

            // 伪造签名（正确 epoch 但错误签名）→ 拒绝
            ResumeTicket forged = new ResumeTicket(PLAYER, 10L, ResumeTicket.sign(PLAYER, 10L, "wrong-key".getBytes()));
            assertFalse(ResumeTicketValidator.verifyAndAccept(forged), "伪造签名应拒绝");
        } finally {
            ResumeTicketValidator.clear(PLAYER);
            ResumeTicket.setSharedKey(keyBackup);
        }
    }
}
