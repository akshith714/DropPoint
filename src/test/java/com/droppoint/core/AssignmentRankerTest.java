package com.droppoint.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class AssignmentRankerTest {

    private static final GeoPoint DESTINATION = new GeoPoint(17.3689, 78.5247);
    private final AssignmentRanker ranker = new AssignmentRanker(2.5, 6);

    private static GeoPoint kmNorthOf(GeoPoint p, double km) {
        return new GeoPoint(p.latitude() + km * 1000 / 111_194.93, p.longitude());
    }

    @Test
    void nearestAgentWinsWhenWorkloadIsEqual() {
        var near = new AssignmentRanker.Candidate(1L, "Near", kmNorthOf(DESTINATION, 1), 0);
        var far = new AssignmentRanker.Candidate(2L, "Far", kmNorthOf(DESTINATION, 5), 0);

        List<AssignmentRanker.Ranked> result = ranker.rank(DESTINATION, List.of(far, near));

        assertEquals(1L, result.get(0).agentId());
        assertEquals(1.0, result.get(0).distanceKm(), 0.05);
    }

    @Test
    void busyNearAgentLosesToFreeFartherAgent() {
        var busy = new AssignmentRanker.Candidate(1L, "Busy", kmNorthOf(DESTINATION, 1), 3);   // 1 + 7.5 = 8.5
        var free = new AssignmentRanker.Candidate(2L, "Free", kmNorthOf(DESTINATION, 4), 0);   // 4 + 0   = 4.0

        List<AssignmentRanker.Ranked> result = ranker.rank(DESTINATION, List.of(busy, free));

        assertEquals(2L, result.get(0).agentId());
        assertEquals(4.0, result.get(0).score(), 0.05);
        assertEquals(8.5, result.get(1).score(), 0.05);
    }

    @Test
    void agentsAtTheOrderLimitAreExcluded() {
        var full = new AssignmentRanker.Candidate(1L, "Full", kmNorthOf(DESTINATION, 0.5), 6);
        var open = new AssignmentRanker.Candidate(2L, "Open", kmNorthOf(DESTINATION, 9), 5);

        List<AssignmentRanker.Ranked> result = ranker.rank(DESTINATION, List.of(full, open));

        assertEquals(1, result.size());
        assertEquals(2L, result.get(0).agentId());
    }

    @Test
    void tiesAreBrokenDeterministically() {
        var a = new AssignmentRanker.Candidate(7L, "A", kmNorthOf(DESTINATION, 2), 1);
        var b = new AssignmentRanker.Candidate(3L, "B", kmNorthOf(DESTINATION, 2), 1);

        List<AssignmentRanker.Ranked> first = ranker.rank(DESTINATION, List.of(a, b));
        List<AssignmentRanker.Ranked> second = ranker.rank(DESTINATION, List.of(b, a));

        assertEquals(3L, first.get(0).agentId());
        assertEquals(first.get(0).agentId(), second.get(0).agentId());
    }

    @Test
    void emptyCandidateListGivesEmptyResult() {
        assertTrue(ranker.rank(DESTINATION, List.of()).isEmpty());
    }

    @Test
    void explanationDescribesDistanceAndLoad() {
        var one = new AssignmentRanker.Candidate(1L, "One", kmNorthOf(DESTINATION, 2), 1);
        assertEquals("2.0 km away, 1 active order", ranker.rank(DESTINATION, List.of(one)).get(0).explanation());
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AssignmentRanker(-1, 6));
        assertThrows(IllegalArgumentException.class, () -> new AssignmentRanker(Double.NaN, 6));
        assertThrows(IllegalArgumentException.class, () -> new AssignmentRanker(2.5, 0));
    }

    @Test
    void invalidInputsAreRejected() {
        var ok = new AssignmentRanker.Candidate(1L, "Ok", DESTINATION, 0);
        assertThrows(IllegalArgumentException.class, () -> ranker.rank(null, List.of(ok)));
        assertThrows(IllegalArgumentException.class, () -> ranker.rank(DESTINATION, null));
        assertThrows(IllegalArgumentException.class, () -> ranker.rank(DESTINATION,
                List.of(new AssignmentRanker.Candidate(2L, "NoPos", null, 0))));
        assertThrows(IllegalArgumentException.class, () -> ranker.rank(DESTINATION,
                List.of(new AssignmentRanker.Candidate(3L, "Negative", DESTINATION, -1))));
    }
}
