package com.droppoint.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.droppoint.core.Rules.AssignmentRanker;
import com.droppoint.core.Rules.CheckInPolicy;
import com.droppoint.core.Rules.EtaCalculator;
import com.droppoint.core.Rules.EtaCalculator.BuildingType;
import com.droppoint.core.Rules.EtaCalculator.DropContext;
import com.droppoint.core.Rules.EtaCalculator.Estimate;
import com.droppoint.core.Rules.ProofPolicy;

class RulesTest {

    /** One degree of latitude is about 111,195 m, which is close enough to place a test point. */
    private static GeoPoint metresNorthOf(GeoPoint p, double metres) {
        return new GeoPoint(p.latitude() + metres / 111_194.93, p.longitude());
    }

    private static GeoPoint kmNorthOf(GeoPoint p, double km) {
        return metresNorthOf(p, km * 1000);
    }

    @Nested
    class CheckIn {

        private static final GeoPoint BRANCH = new GeoPoint(17.3457, 78.5522);
        private final CheckInPolicy policy = new CheckInPolicy(200, 100);

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

    @Nested
    class Assignment {

        private static final GeoPoint DESTINATION = new GeoPoint(17.3689, 78.5247);
        private final AssignmentRanker ranker = new AssignmentRanker(2.5, 6);

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

    @Nested
    class Eta {

        @Test
        void groundFloorIndependentHouseIsTheFastestHandover() {
            Estimate e = EtaCalculator.estimate(12, new DropContext(BuildingType.INDEPENDENT, 0, false, 1));
            assertEquals(3.0, e.handoverMinutes(), 0.01);
            assertEquals(15.0, e.totalMinutes(), 0.01);
        }

        @Test
        void stairsCostMoreThanALift() {
            DropContext stairs = new DropContext(BuildingType.APARTMENT, 4, false, 1);
            DropContext lift = new DropContext(BuildingType.APARTMENT, 4, true, 1);

            assertEquals(5.0 + 4 * 1.5, EtaCalculator.estimate(10, stairs).handoverMinutes(), 0.01);
            assertEquals(5.0 + 4 * 0.4, EtaCalculator.estimate(10, lift).handoverMinutes(), 0.01);
            assertTrue(EtaCalculator.estimate(10, stairs).totalMinutes()
                     > EtaCalculator.estimate(10, lift).totalMinutes());
        }

        @Test
        void extraCylindersAddTime() {
            DropContext one = new DropContext(BuildingType.INDEPENDENT, 0, false, 1);
            DropContext three = new DropContext(BuildingType.INDEPENDENT, 0, false, 3);
            assertEquals(3.0, EtaCalculator.estimate(5, one).handoverMinutes(), 0.01);
            assertEquals(3.0 + 2 * 1.5, EtaCalculator.estimate(5, three).handoverMinutes(), 0.01);
        }

        @Test
        void buildingTypesAreOrderedFromFastestToSlowest() {
            double independent = handoverFor(BuildingType.INDEPENDENT);
            double apartment = handoverFor(BuildingType.APARTMENT);
            double gated = handoverFor(BuildingType.GATED);
            double commercial = handoverFor(BuildingType.COMMERCIAL);
            assertTrue(independent < apartment && apartment < gated && gated < commercial,
                    "Expected independent < apartment < gated < commercial");
        }

        private double handoverFor(BuildingType type) {
            return EtaCalculator.estimate(10, new DropContext(type, 0, false, 1)).handoverMinutes();
        }

        @Test
        void hardDropTakesFarLongerThanTravelAlone() {
            // Priya: gated, floor 5, no lift, 2 cylinders
            Estimate e = EtaCalculator.estimate(14, new DropContext(BuildingType.GATED, 5, false, 2));
            assertEquals(7.0 + 7.5 + 1.5, e.handoverMinutes(), 0.01);
            assertEquals(30.0, e.totalMinutes(), 0.01);
        }

        @Test
        void zeroTravelTimeIsAllowed() {
            Estimate e = EtaCalculator.estimate(0, new DropContext(BuildingType.INDEPENDENT, 0, false, 1));
            assertEquals(3.0, e.totalMinutes(), 0.01);
        }

        @Test
        void explanationMentionsFloorAndCylinders() {
            String text = EtaCalculator.estimate(10,
                    new DropContext(BuildingType.GATED, 5, false, 2)).explanation();
            assertTrue(text.contains("gated"), text);
            assertTrue(text.contains("floor 5"), text);
            assertTrue(text.contains("no lift"), text);
            assertTrue(text.contains("2 cylinders"), text);
        }

        @Test
        void invalidInputsAreRejected() {
            DropContext ok = new DropContext(BuildingType.APARTMENT, 1, false, 1);
            assertThrows(IllegalArgumentException.class, () -> EtaCalculator.estimate(-1, ok));
            assertThrows(IllegalArgumentException.class, () -> EtaCalculator.estimate(Double.NaN, ok));
            assertThrows(IllegalArgumentException.class, () -> EtaCalculator.estimate(10, null));
            assertThrows(IllegalArgumentException.class, () -> EtaCalculator.estimate(10,
                    new DropContext(null, 1, false, 1)));
            assertThrows(IllegalArgumentException.class, () -> EtaCalculator.estimate(10,
                    new DropContext(BuildingType.APARTMENT, -1, false, 1)));
            assertThrows(IllegalArgumentException.class, () -> EtaCalculator.estimate(10,
                    new DropContext(BuildingType.APARTMENT, 1, false, 0)));
        }
    }

    @Nested
    class Proof {

        private static final GeoPoint CUSTOMER = new GeoPoint(17.3712, 78.5580);
        private final ProofPolicy policy = new ProofPolicy(150);

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
}
