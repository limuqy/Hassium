package io.github.limuqy.mc.hassium.protocol.egress;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EgressFrameCodecTest {
    @Test void trainedDictionaryEpochIsRequiredAndMissingDictionaryIsFatal() {
        byte[] bytes = new byte[4096]; new Random(77).nextBytes(bytes);
        var dictionary = new EgressFrameCodec.Dictionary(7, bytes);
        var packets = List.of(new EgressFrameCodec.PacketBytes("mod:state", bytes));
        try (var sender = new EgressFrameCodec(3, false, false); var receiver = new EgressFrameCodec(3)) {
            var frame = sender.encode(packets, 1, dictionary);
            assertEquals(EgressFrameCodec.TRAINED, frame.mode());
            assertThrows(IllegalArgumentException.class, () -> receiver.decode(frame.bytes(), id -> null));
            verify(packets, receiver.decode(frame.bytes(), id -> id == 7 ? bytes : null));
        }
    }

    @Test void reservedDictionaryMagicInOpaqueHistoryCannotBreakLosslessTransport() {
        byte[] packet = new byte[65540]; new Random(77).nextBytes(packet);
        // The serialized logical batch has a four-byte header, so its last 65536 bytes start here.
        packet[4] = 0x37; packet[5] = (byte)0xa4; packet[6] = 0x30; packet[7] = (byte)0xec;
        try (var sender = new EgressFrameCodec(3, true, false); var receiver = new EgressFrameCodec(3)) {
            var packets = List.of(new EgressFrameCodec.PacketBytes("mod:opaque", packet));
            verify(packets, receiver.decode(sender.encode(packets, 1, null).bytes(), null));
            verify(packets, receiver.decode(sender.encode(packets, 1, null).bytes(), null));
        }
    }

    @Test void packetCountBoundMatchesEncoderAndReceiver() {
        var packet = new EgressFrameCodec.PacketBytes("mod:small", new byte[]{1});
        try (var sender = new EgressFrameCodec(3); var receiver = new EgressFrameCodec(3)) {
            var batch = Collections.nCopies(EgressFrameCodec.MAX_PACKETS, packet);
            verify(batch, receiver.decode(sender.encode(batch, 1, null).bytes(), null));
            assertThrows(IllegalArgumentException.class, () -> sender.encode(Collections.nCopies(EgressFrameCodec.MAX_PACKETS + 1, packet), 1, null));
        }
    }
    private static EgressFrameCodec.PacketBytes packet(String key, byte[] bytes) { return new EgressFrameCodec.PacketBytes(key, bytes); }
    private static void verify(List<EgressFrameCodec.PacketBytes> expected, List<byte[]> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < actual.size(); i++) assertArrayEquals(expected.get(i).bytes(), actual.get(i), "packet " + i);
    }
    @Test void randomOpaquePayloadsPreserveEveryByteAndOrderAcrossFramesAndEpochs() {
        Random random = new Random(123);
        try (var encoder = new EgressFrameCodec(3); var decoder = new EgressFrameCodec(3)) {
            for (int i = 0; i < 150; i++) {
                List<EgressFrameCodec.PacketBytes> packets = new ArrayList<>();
                for (int j = 0; j < 10; j++) {
                    byte[] bytes = new byte[1 + random.nextInt(6000)]; random.nextBytes(bytes);
                    packets.add(packet("unknown_mod:multi_structure", bytes));
                }
                var frame = encoder.encode(packets, i < 75 ? 1 : 2, null);
                verify(packets, decoder.decode(frame.bytes(), null));
            }
        }
    }
    @Test void historyCandidateBeatsIndependentCompressionAndFinalChoiceNeverExceedsBaseline() {
        Random random = new Random(31); byte[] constant = new byte[4000]; random.nextBytes(constant);
        long independentBytes = 0, historyBytes = 0;
        try (var baseline = new EgressFrameCodec(3, false, false); var encoder = new EgressFrameCodec(3); var decoder = new EgressFrameCodec(3)) {
            for (int i = 0; i < 100; i++) {
                byte[] changed = constant.clone(); changed[990] = (byte)i;
                var packets = List.of(packet("mod:state", changed));
                var expected = baseline.encode(packets, 1, null); var actual = encoder.encode(packets, 1, null);
                assertTrue(actual.bytes().length <= expected.bytes().length);
                independentBytes += expected.bytes().length; historyBytes += actual.bytes().length;
                verify(packets, decoder.decode(actual.bytes(), null));
            }
        }
        assertTrue(historyBytes < independentBytes / 4);
    }
    @Test void automaticTemplatesAreActuallySelectedAndReferencedAcrossFrames() {
        Random random = new Random(42); byte[] base = new byte[3500]; random.nextBytes(base);
        boolean mapped = false, partial = false, exact = false;
        try (var encoder = new EgressFrameCodec(3, false, true); var decoder = new EgressFrameCodec(3)) {
            for (int i = 0; i < 15; i++) {
                byte[] changed = base.clone(); changed[200] = (byte)i;
                var packets = new ArrayList<EgressFrameCodec.PacketBytes>();
                for (int j = 0; j < 250; j++) packets.add(packet("mod:state", changed));
                packets.add(packet("mod:other", base)); packets.add(packet("mod:other", base));
                var frame = encoder.encode(packets, 1, null);
                mapped |= frame.mapped(); partial |= frame.templateReferences() > 0; exact |= frame.exactReferences() > 0;
                verify(packets, decoder.decode(frame.bytes(), null));
            }
        }
        assertTrue(mapped, "mapped candidate selected");
        assertTrue(partial, "variable template used");
        assertTrue(exact, "exact reference used");
    }
    @Test void missingTemplateIsRejectedWithoutCommittingState() {
        TemplateDictionary receiver = new TemplateDictionary();
        var bad = new EgressBytes.Writer().integer(1).integer(0).octet(1).integer(1).toByteArray();
        assertThrows(IllegalArgumentException.class, () -> receiver.decode(bad));
        var good = new EgressBytes.Writer().integer(1).integer(0).octet(0).integer(2).bytes(new byte[]{3,4}).toByteArray();
        assertArrayEquals(new byte[]{1,2,3,4}, receiver.decode(good).bytes());
    }
    @Test void corruptionAndSequenceErrorsCannotAdvanceReceiverState() {
        try (var encoder = new EgressFrameCodec(3); var decoder = new EgressFrameCodec(3)) {
            var packets = List.of(packet("mod:event", new byte[]{1,2,3}));
            var first = encoder.encode(packets, 1, null); var second = encoder.encode(packets, 1, null);
            assertThrows(IllegalArgumentException.class, () -> decoder.decode(second.bytes(), null));
            byte[] corrupted = first.bytes().clone(); corrupted[corrupted.length - 1] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> decoder.decode(corrupted, null));
            verify(packets, decoder.decode(first.bytes(), null));
            assertThrows(IllegalArgumentException.class, () -> decoder.decode(first.bytes(), null));
            verify(packets, decoder.decode(second.bytes(), null));
        }
    }
    @Test void independentFirstFrameAfterResetAndNewConnection() {
        try (var encoder = new EgressFrameCodec(3); var decoder = new EgressFrameCodec(3)) {
            byte[] bytes = new byte[2048]; new Random(4).nextBytes(bytes);
            var packets = List.of(packet("mod:state", bytes));
            verify(packets, decoder.decode(encoder.encode(packets, 1, null).bytes(), null));
            verify(packets, decoder.decode(encoder.encode(packets, 2, null).bytes(), null));
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(packets, 1, null));
            try (var newConnection = new EgressFrameCodec(3)) {
                verify(packets, newConnection.decode(encoder.encode(packets, 3, null).bytes(), null));
            }
        }
    }
    @Test void definitionReuseAndOverlappingSlotsAreRejected() {
        byte[] base = new byte[20];
        var bad = new EgressBytes.Writer().integer(1).integer(1).octet(1).integer(1).integer(base.length).bytes(base)
                .integer(2).integer(2).integer(4).integer(4).integer(3).octet(1).integer(1).toByteArray();
        assertThrows(IllegalArgumentException.class, () -> new TemplateDictionary().decode(bad));
        var good = new EgressBytes.Writer().integer(1).integer(1).octet(1).integer(1).integer(base.length).bytes(base)
                .integer(0).octet(1).integer(1).toByteArray();
        var table = new TemplateDictionary(); table.decode(good).commit();
        assertThrows(IllegalArgumentException.class, () -> table.decode(good));
    }
    @Test void cancelledMappingTransactionNeverCreatesReceiverDependencies() {
        var table = new TemplateDictionary(); byte[] bytes = new byte[512]; new Random(1).nextBytes(bytes);
        var packets = List.of(packet("mod:state", bytes),packet("mod:state",bytes));
        table.prepare(packets); // deliberately not committed
        var selected = table.prepare(packets);
        var receiver = new TemplateDictionary();
        assertEquals(2, EgressFrameCodec.splitLogical(receiver.decode(selected.bytes()).bytes()).size());
    }
    @Test void oversizedFramesAndImpossibleLogicalLengthsFailBeforeAllocating() {
        try (var receiver = new EgressFrameCodec(3)) {
            assertThrows(IllegalArgumentException.class, () -> receiver.decode(new byte[EgressFrameCodec.MAX_FRAME_BYTES+1], null));
            byte[] frame = new EgressBytes.Writer().integer(3).number(1).number(1).octet(0)
                    .integer(Integer.MAX_VALUE).integer(1).fixedInt(0).octet(0).toByteArray();
            assertThrows(IllegalArgumentException.class, () -> receiver.decode(frame, null));
        }
    }
}
