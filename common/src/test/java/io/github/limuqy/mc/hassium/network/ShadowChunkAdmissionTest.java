package io.github.limuqy.mc.hassium.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowChunkAdmissionTest {
    @Test
    @DisplayName("pull and fallback share one admission key")
    void preventsDuplicateAdmission() {
        ShadowChunkAdmission admission = new ShadowChunkAdmission();
        admission.advanceEpoch(2L);
        ShadowChunkAdmission.Key key = new ShadowChunkAdmission.Key("minecraft:overworld", 1, 2, 2L);
        assertEquals(ShadowChunkAdmission.Decision.ADMITTED,
                admission.begin(key, ShadowChunkAdmission.Path.PULL));
        assertEquals(ShadowChunkAdmission.Decision.DUPLICATE,
                admission.begin(key, ShadowChunkAdmission.Path.LEGACY_FALLBACK));
        assertTrue(admission.isActive(key));
        assertTrue(admission.finish(key));
        assertFalse(admission.isActive(key));
    }

    @Test
    @DisplayName("epoch transition cancels old admissions")
    void cancelsOnEpochTransition() {
        ShadowChunkAdmission admission = new ShadowChunkAdmission();
        admission.advanceEpoch(1L);
        ShadowChunkAdmission.Key oldKey = new ShadowChunkAdmission.Key("minecraft:overworld", 0, 0, 1L);
        admission.begin(oldKey, ShadowChunkAdmission.Path.PULL);
        admission.advanceEpoch(2L);
        assertEquals(0, admission.activeCount());
        assertEquals(ShadowChunkAdmission.Decision.STALE,
                admission.begin(oldKey, ShadowChunkAdmission.Path.LEGACY_FALLBACK));
    }
}
