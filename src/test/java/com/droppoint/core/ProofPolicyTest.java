package com.droppoint.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProofPolicyTest {

    private static final GeoPoint CUSTOMER = new GeoPoint(17.3712, 78.5580);
    private final ProofPolicy policy = new ProofPolicy(150);

    private static GeoPoint metresNorthOf(GeoPoint p, double metres) {
        return new GeoPoint(p.latitude() + metres / 111_194.93, p.longitude());
    }

    @Test
    void correctCodeAtTheDoorIsVerified() {
        var r = policy.evaluate(CUSTOMER, CUSTOMER, 3L, 3L, "4821", "4821");
        assertTrue(r.accepted());
        assertEquals(ProofPolicy.Verdict.VERIFIED, r.verdict());
    }

    @Test
    void justInsideTheRadiusIsVerified() {
        var r = policy.evaluate(CUSTOMER, metresNorthOf(CUSTOMER, 149), 3L, 3L, "4821", "4821");
        assertEquals(ProofPolicy.Verdict.VERIFIED, r.verdict());
    }

    @Test
    void farFromTheAddressIsAcceptedButFlagged() {
        var r = policy.evaluate(CUSTOMER, metresNorthOf(CUSTOMER, 3000), 3L, 3L, "4821", "4821");
        assertTrue(r.accepted(), "a distant delivery is still recorded");
        assertEquals(ProofPolicy.Verdict.SUSPICIOUS, r.verdict());
        assertTrue(r.note().contains("3000"), r.note());
    }

    @Test
    void wrongCodeBlocksTheDelivery() {
        var r = policy.evaluate(CUSTOMER, CUSTOMER, 3L, 3L, "4821", "1234");
        assertFalse(r.accepted());
        assertEquals(ProofPolicy.Rejection.WRONG_CODE, r.rejection());
    }

    @Test
    void missingCodeBlocksTheDelivery() {
        assertFalse(policy.evaluate(CUSTOMER, CUSTOMER, 3L, 3L, "4821", null).accepted());
        assertFalse(policy.evaluate(CUSTOMER, CUSTOMER, 3L, 3L, "4821", "").accepted());
    }

    @Test
    void surroundingSpacesInTheCodeAreForgiven() {
        assertTrue(policy.evaluate(CUSTOMER, CUSTOMER, 3L, 3L, "4821", " 4821 ").accepted());
    }

    @Test
    void anotherAgentCannotCloseTheOrder() {
        var r = policy.evaluate(CUSTOMER, CUSTOMER, 3L, 4L, "4821", "4821");
        assertFalse(r.accepted());
        assertEquals(ProofPolicy.Rejection.NOT_ASSIGNED_AGENT, r.rejection());
    }

    @Test
    void wrongAgentIsReportedBeforeWrongCode() {
        var r = policy.evaluate(CUSTOMER, CUSTOMER, 3L, 4L, "4821", "0000");
        assertEquals(ProofPolicy.Rejection.NOT_ASSIGNED_AGENT, r.rejection());
    }

    @Test
    void distanceIsAlwaysReportedEvenWhenBlocked() {
        var r = policy.evaluate(CUSTOMER, metresNorthOf(CUSTOMER, 500), 3L, 3L, "4821", "0000");
        assertFalse(r.accepted());
        assertEquals(500.0, r.distanceMetres(), 1.0);
    }

    @Test
    void invalidSettingsAndInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ProofPolicy(0));
        assertThrows(IllegalArgumentException.class, () -> new ProofPolicy(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> policy.evaluate(null, CUSTOMER, 3L, 3L, "4821", "4821"));
        assertThrows(IllegalArgumentException.class,
                () -> policy.evaluate(CUSTOMER, CUSTOMER, null, 3L, "4821", "4821"));
        assertThrows(IllegalArgumentException.class,
                () -> policy.evaluate(CUSTOMER, CUSTOMER, 3L, 3L, "", "4821"));
    }
}
