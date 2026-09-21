package com.droppoint.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CheckInPolicyTest {

    private static final GeoPoint BRANCH = new GeoPoint(17.3457, 78.5522);
    private final CheckInPolicy policy = new CheckInPolicy(200, 100);

    private static GeoPoint metresNorthOf(GeoPoint p, double metres) {
        return new GeoPoint(p.latitude() + metres / 111_194.93, p.longitude());
    }

    @Test
    void agentAtBranchIsAllowed() {
        CheckInPolicy.Result r = policy.evaluate(BRANCH, BRANCH, 10, false);
        assertTrue(r.allowed());
        assertEquals(CheckInPolicy.Reason.ALLOWED, r.reason());
    }

    @Test
    void agentJustInsideRadiusIsAllowed() {
        CheckInPolicy.Result r = policy.evaluate(BRANCH, metresNorthOf(BRANCH, 199), 10, false);
        assertTrue(r.allowed());
    }

    @Test
    void agentJustOutsideRadiusIsTooFar() {
        CheckInPolicy.Result r = policy.evaluate(BRANCH, metresNorthOf(BRANCH, 201), 10, false);
        assertFalse(r.allowed());
        assertEquals(CheckInPolicy.Reason.TOO_FAR, r.reason());
        assertEquals(201.0, r.distanceMetres(), 0.5);
    }

    @Test
    void agentKilometresAwayIsTooFar() {
        GeoPoint charminar = new GeoPoint(17.3616, 78.4747);
        CheckInPolicy.Result r = policy.evaluate(BRANCH, charminar, 10, false);
        assertEquals(CheckInPolicy.Reason.TOO_FAR, r.reason());
    }

    @Test
    void mockLocationIsRejectedEvenAtBranch() {
        CheckInPolicy.Result r = policy.evaluate(BRANCH, BRANCH, 10, true);
        assertFalse(r.allowed());
        assertEquals(CheckInPolicy.Reason.MOCK_LOCATION, r.reason());
    }

    @Test
    void mockLocationIsReportedBeforeDistance() {
        GeoPoint far = metresNorthOf(BRANCH, 5_000);
        CheckInPolicy.Result r = policy.evaluate(BRANCH, far, 10, true);
        assertEquals(CheckInPolicy.Reason.MOCK_LOCATION, r.reason());
        assertEquals(5_000.0, r.distanceMetres(), 1.0);
    }

    @Test
    void accuracyAtTheLimitIsAccepted() {
        CheckInPolicy.Result r = policy.evaluate(BRANCH, BRANCH, 100, false);
        assertTrue(r.allowed());
    }

    @Test
    void poorAccuracyIsRejectedEvenAtBranch() {
        CheckInPolicy.Result r = policy.evaluate(BRANCH, BRANCH, 100.5, false);
        assertFalse(r.allowed());
        assertEquals(CheckInPolicy.Reason.LOW_ACCURACY, r.reason());
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CheckInPolicy(0, 100));
        assertThrows(IllegalArgumentException.class, () -> new CheckInPolicy(200, -1));
        assertThrows(IllegalArgumentException.class, () -> new CheckInPolicy(Double.NaN, 100));
    }

    @Test
    void invalidInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> policy.evaluate(null, BRANCH, 10, false));
        assertThrows(IllegalArgumentException.class, () -> policy.evaluate(BRANCH, null, 10, false));
        assertThrows(IllegalArgumentException.class, () -> policy.evaluate(BRANCH, BRANCH, -5, false));
        assertThrows(IllegalArgumentException.class, () -> policy.evaluate(BRANCH, BRANCH, Double.NaN, false));
    }
}
