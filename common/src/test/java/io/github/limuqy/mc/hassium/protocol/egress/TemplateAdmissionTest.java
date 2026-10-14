package io.github.limuqy.mc.hassium.protocol.egress;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TemplateAdmissionTest {
    private static EgressFrameCodec.PacketBytes packet(String key, byte[] bytes) {
        return new EgressFrameCodec.PacketBytes(key, bytes);
    }
    @Test void learningSurvivesRejectedFramesAndUnionsPreviouslyUnseenVariableSlots() {
        byte[] base = new byte[512]; new Random(19).nextBytes(base);
        var sender = new TemplateDictionary(); var receiver = new TemplateDictionary();
        for (int i = 0; i < 3; i++) {
            byte[] changed = base.clone(); changed[100] = (byte)i;
            if (i == 2) changed[300] ^= 1;
            assertNull(sender.prepare(List.of(packet("mod:state", changed))), "one occurrence cannot repay a definition");
        }
        var batch = new ArrayList<EgressFrameCodec.PacketBytes>();
        for (int i = 0; i < 12; i++) {
            byte[] changed = base.clone(); changed[100] = (byte)(i + 3); changed[300] = (byte)(i * 17);
            batch.add(packet("mod:state", changed));
        }
        var tx = sender.prepare(batch); assertNotNull(tx); assertEquals(batch.size(), tx.partial);
        var decoded = receiver.decode(tx.bytes());
        var restored = EgressFrameCodec.splitLogical(decoded.bytes());
        for (int i = 0; i < batch.size(); i++) assertArrayEquals(batch.get(i).bytes(), restored.get(i));
        tx.commit(); decoded.commit();
        byte[] later = base.clone(); later[100] = 77; later[300] = 66;
        var reuse = sender.prepare(List.of(packet("mod:state", later)));
        assertNotNull(reuse); assertEquals(0, reuse.definitions); assertEquals(1, reuse.partial);
        assertArrayEquals(later, EgressFrameCodec.splitLogical(receiver.decode(reuse.bytes()).bytes()).getFirst());
    }
    @Test void unrelatedStructuresSharingAChannelNeverShareUncheckedConstants() {
        Random random = new Random(117); byte[] first = new byte[512], second = new byte[512];
        random.nextBytes(first); random.nextBytes(second);
        var sender = new TemplateDictionary(); var receiver = new TemplateDictionary();
        var packets = new ArrayList<EgressFrameCodec.PacketBytes>();
        for (int i = 0; i < 20; i++) {
            byte[] bytes = (i % 2 == 0 ? first : second).clone(); bytes[90] = (byte)i;
            packets.add(packet("mod:multiplexed", bytes));
        }
        var tx = sender.prepare(packets); assertNotNull(tx);
        var restored = EgressFrameCodec.splitLogical(receiver.decode(tx.bytes()).bytes());
        for (int i = 0; i < packets.size(); i++) assertArrayEquals(packets.get(i).bytes(), restored.get(i));
    }
    @Test void singleUseDefinitionsAreNotAdmittedEvenAfterManyObservations() {
        byte[] bytes = new byte[4096]; new Random(4).nextBytes(bytes);
        var table = new TemplateDictionary();
        for (int i = 0; i < 100; i++) assertNull(table.prepare(List.of(packet("mod:occasional", bytes))));
    }
    @Test void sampleStorageIsBoundedUnderUniqueLargePayloadsAndClearedAtSessionEnd() {
        var table = new TemplateDictionary(); Random random = new Random(31);
        for (int i = 0; i < 300; i++) {
            byte[] bytes = new byte[4096]; random.nextBytes(bytes);
            assertNull(table.prepare(List.of(packet("mod:" + i, bytes))));
            assertTrue(table.learningStats().models() <= TemplateDictionary.MAX_SEEDS);
            assertTrue(table.learningStats().bytes() <= TemplateDictionary.MAX_SEED_BYTES);
        }
        table.clear(); assertEquals(new TemplateDictionary.LearningStats(0, 0), table.learningStats());
    }
    @Test void repeatedLossesBackOffAndAnUnseenChannelImmediatelyWakesTemplates() {
        byte[] bytes = new byte[3500]; new Random(42).nextBytes(bytes);
        try (var encoder = new EgressFrameCodec(3, false, true); var decoder = new EgressFrameCodec(3)) {
            for (int i = 0; i < 160; i++) {
                var encoded = encoder.encode(List.of(packet("mod:occasional", bytes)), 1, null);
                assertArrayEquals(bytes, decoder.decode(encoded.bytes(), null).getFirst());
            }
            assertTrue(encoder.templateStats().attempts() < 50);
            assertTrue(encoder.templateStats().skips() > 100);
            var packets = Collections.nCopies(250, packet("mod:now_frequent", bytes));
            var encoded = encoder.encode(packets, 1, null);
            assertTrue(encoded.mapped(), "new channel must wake the probe during backoff");
            var restored = decoder.decode(encoded.bytes(), null); assertEquals(250, restored.size());
            for (byte[] actual : restored) assertArrayEquals(bytes, actual);
        }
    }
}
