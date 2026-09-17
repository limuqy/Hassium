package io.github.limuqy.mc.hassium.server;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerChunkPushValveTest {

    @Test
    @DisplayName("FULL/DELTA budget defaults to 5; hash window is N+32")
    void budgets() {
        assertEquals(5, PullPacingValve.fullDeltaBudget(0));
        assertEquals(5, PullPacingValve.fullDeltaBudget(5));
        assertEquals(37, PullPacingValve.hashBudget(5));
        assertEquals(40, PullPacingValve.lookaheadLimit(5));
        assertEquals(32, PullPacingValve.UNCHANGED_PER_TICK);
    }

    @Test
    @DisplayName("20 ready FULL columns: only N+UNCHANGED nearest enter the hash window")
    void readyHashWindowCapsAtBudget() {
        List<PullPacingValve.Candidate> all = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            all.add(new PullPacingValve.Candidate(i, i, 0, true, false));
        }
        List<Integer> selected = PullPacingValve.selectReadyToHash(all, 0, 0, PullPacingValve.hashBudget(5));
        assertEquals(20, selected.size(), "20 < 37 so all ready may be hashed this tick");
        List<Integer> tight = PullPacingValve.selectReadyToHash(all, 0, 0, 5);
        assertEquals(5, tight.size());
        assertEquals(List.of(0, 1, 2, 3, 4), tight);
    }

    @Test
    @DisplayName("nearest-first: far ring is not hashed before the player's column")
    void nearestReadyFirst() {
        List<PullPacingValve.Candidate> all = List.of(
                new PullPacingValve.Candidate(0, 10, 0, true, false),
                new PullPacingValve.Candidate(1, 0, 0, true, false),
                new PullPacingValve.Candidate(2, 3, 0, true, false));
        assertEquals(List.of(1, 2, 0), PullPacingValve.selectReadyToHash(all, 0, 0, 5));
    }

    @Test
    @DisplayName("no baseline cannot be UNCHANGED; empty and zero hash are both no-baseline")
    void hasBaselineDetectsClientHash() {
        assertFalse(PullPacingValve.hasBaseline(0L, List.of()));
        assertFalse(PullPacingValve.hasBaseline(0L, null));
        assertTrue(PullPacingValve.hasBaseline(1L, List.of()));
        assertTrue(PullPacingValve.hasBaseline(0L, List.of(1L)));
    }

    @Test
    @DisplayName("FULL quota gone: skip hash unless the column might be UNCHANGED")
    void skipHashWhenFullQuotaGone() {
        assertTrue(PullPacingValve.skipHash(0, 32, false), "cold FULL must not hash after N");
        assertFalse(PullPacingValve.skipHash(0, 32, true), "baseline may still be UNCHANGED");
        assertFalse(PullPacingValve.skipHash(5, 32, false));
        assertFalse(PullPacingValve.skipHash(1, 0, false));
        assertTrue(PullPacingValve.skipHash(0, 0, true));
        assertTrue(PullPacingValve.skipHash(0, 0, false));
    }

    @Test
    @DisplayName("lookahead does not ticket beyond N×8; already-ticketed unready occupy the window")
    void lookaheadDoesNotTicketPastWindow() {
        int lookahead = PullPacingValve.lookaheadLimit(5);
        List<PullPacingValve.Candidate> all = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            all.add(new PullPacingValve.Candidate(i, i, 0, false, false));
        }
        List<Integer> tickets = PullPacingValve.selectLookaheadTickets(all, 0, 0, 0, lookahead);
        assertEquals(40, tickets.size());
        assertEquals(0, tickets.get(0));
        List<Integer> none = PullPacingValve.selectLookaheadTickets(all, 0, 0, lookahead, lookahead);
        assertTrue(none.isEmpty());
    }

    @Test
    @DisplayName("ready columns are never selected for lookahead tickets")
    void readyNotTicketed() {
        List<PullPacingValve.Candidate> all = List.of(
                new PullPacingValve.Candidate(0, 0, 0, true, false),
                new PullPacingValve.Candidate(1, 1, 0, false, false));
        List<Integer> tickets = PullPacingValve.selectLookaheadTickets(all, 0, 0, 0, 40);
        assertEquals(List.of(1), tickets);
    }

    @Test
    @DisplayName("already ticketed unready are skipped when filling lookahead")
    void skipAlreadyTicketed() {
        List<PullPacingValve.Candidate> all = List.of(
                new PullPacingValve.Candidate(0, 0, 0, false, true),
                new PullPacingValve.Candidate(1, 2, 0, false, false));
        List<Integer> tickets = PullPacingValve.selectLookaheadTickets(all, 0, 0, 1, 40);
        assertEquals(List.of(1), tickets);
        Set<Integer> set = new HashSet<>(tickets);
        assertFalse(set.contains(0));
    }
}
