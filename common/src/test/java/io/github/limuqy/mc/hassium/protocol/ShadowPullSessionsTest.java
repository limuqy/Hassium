package io.github.limuqy.mc.hassium.protocol;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShadowPullSessionsTest {
    private static ShadowPullResponseS2CPacket resolve(ShadowPullHandler handler, UUID player) {
        var request = new ShadowPullRequestC2SPacket("minecraft:overworld", 0L, 0L,
                List.of(new ShadowPullRequestC2SPacket.Entry(0, 0, 3L, List.of(), 1)));
        return handler.handle(player, request, "minecraft:overworld", 0L, 0, 0, 1, true, true,
                (req, entry) -> ShadowPullResponseS2CPacket.Result.unchanged(0, 0, 3L, List.of()));
    }
    @Test void reconnectAndOtherPlayersHaveIndependentRequestIdentity() {
        var sessions = new ShadowPullSessions();
        var a = new UUID(0, 1);
        var b = new UUID(0, 2);
        var oldConnection = new Object();
        var first = sessions.handler(a, oldConnection);
        assertEquals(ShadowPullResponseS2CPacket.Kind.UNCHANGED, resolve(first, a).results().get(0).kind());
        assertEquals("duplicate", resolve(sessions.handler(a, oldConnection), a).results().get(0).error());
        assertEquals(ShadowPullResponseS2CPacket.Kind.UNCHANGED,
                resolve(sessions.handler(b, new Object()), b).results().get(0).kind());
        var next = new Object();
        assertEquals(ShadowPullResponseS2CPacket.Kind.UNCHANGED,
                resolve(sessions.handler(a, next), a).results().get(0).kind());
        assertFalse(sessions.matches(a, oldConnection));
        sessions.remove(a);
        assertFalse(sessions.matches(a, next));
        sessions.clear();
    }
}
