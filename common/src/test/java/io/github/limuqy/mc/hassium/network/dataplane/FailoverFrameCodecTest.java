package io.github.limuqy.mc.hassium.network.dataplane;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Task 6 failover control-frame wire contract. */
final class FailoverFrameCodecTest {

    @Test
    void requestAndPermitRoundTripWithLongEpochs() {
        byte[] request = FailoverFrameCodec.encodeRequest(0x0123_4567_89AB_CDEFL, 300);
        FailoverFrameCodec.Request decodedRequest = FailoverFrameCodec.decodeRequest(request);
        assertEquals(0x0123_4567_89AB_CDEFL, decodedRequest.connectionEpoch());
        assertEquals(300, decodedRequest.requestedEndpointId());

        byte[] permit = FailoverFrameCodec.encodePermit(9L, Long.MAX_VALUE - 3L);
        FailoverFrameCodec.Permit decodedPermit = FailoverFrameCodec.decodePermit(permit);
        assertEquals(9L, decodedPermit.connectionEpoch());
        assertEquals(Long.MAX_VALUE - 3L, decodedPermit.expiryMs());
    }

    @Test
    void rejectsTruncatedOrNegativeEndpointPayload() {
        assertThrows(IllegalArgumentException.class, () -> FailoverFrameCodec.decodeRequest(new byte[7]));
        assertThrows(IllegalArgumentException.class, () -> FailoverFrameCodec.encodeRequest(1L, -1));
        assertThrows(IllegalArgumentException.class, () -> FailoverFrameCodec.decodePermit(new byte[15]));
    }
}
