package com.droppoint.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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

    @Nested
    class Planning {

        private static Rules.DayPlan.AreaLoad area(long id, String name, int orders) {
            return new Rules.DayPlan.AreaLoad(id, name, orders, orders);
        }

        private static Rules.DayPlan.AgentSlot agent(long id, String name, int cap) {
            return new Rules.DayPlan.AgentSlot(id, name, cap, null);
        }

        @Test
        void sixAgentsAndSixAreasGetOneEach() {
            List<Rules.DayPlan.AreaLoad> areas = List.of(
                    area(1, "LB Nagar", 20), area(2, "Kothapet", 22), area(3, "Nagole", 18),
                    area(4, "Sainagar", 15), area(5, "Vanasthalipuram", 25), area(6, "Hayathnagar", 12));
            List<Rules.DayPlan.AgentSlot> agents = List.of(
                    agent(1, "A", 40), agent(2, "B", 40), agent(3, "C", 40),
                    agent(4, "D", 40), agent(5, "E", 40), agent(6, "F", 40));

            var result = Rules.DayPlan.plan(areas, agents);

            assertEquals(6, result.assignments().size());
            assertEquals(6, result.assignments().stream()
                    .map(Rules.DayPlan.Assignment::agentId).distinct().count(),
                    "each agent should get exactly one area");
            assertTrue(result.leftovers().isEmpty());
        }

        @Test
        void fourAgentsCoverSixAreas() {
            List<Rules.DayPlan.AreaLoad> areas = List.of(
                    area(1, "LB Nagar", 20), area(2, "Kothapet", 22), area(3, "Nagole", 18),
                    area(4, "Sainagar", 15), area(5, "Vanasthalipuram", 25), area(6, "Hayathnagar", 12));
            List<Rules.DayPlan.AgentSlot> agents = List.of(
                    agent(1, "A", 40), agent(2, "B", 40), agent(3, "C", 40), agent(4, "D", 40));

            var result = Rules.DayPlan.plan(areas, agents);

            assertTrue(result.leftovers().isEmpty(), "112 orders fit in 160 capacity");
            assertEquals(112, result.assignments().stream()
                    .mapToInt(Rules.DayPlan.Assignment::orders).sum());
            assertTrue(result.notes().stream().anyMatch(n -> n.contains("covers 2 areas")),
                    "someone must be doing two areas: " + result.notes());
        }

        @Test
        void areaBiggerThanOneAgentIsSplit() {
            var result = Rules.DayPlan.plan(
                    List.of(area(1, "Kothapet", 58)),
                    List.of(agent(1, "A", 40), agent(2, "B", 40)));

            assertEquals(2, result.assignments().size());
            assertEquals(58, result.assignments().stream()
                    .mapToInt(Rules.DayPlan.Assignment::orders).sum());
            assertTrue(result.assignments().stream().allMatch(Rules.DayPlan.Assignment::partial));
            assertTrue(result.notes().stream().anyMatch(n -> n.contains("split across 2")));
        }

        @Test
        void theAgentWhoCoveredAnAreaLastKeepsIt() {
            var areas = List.of(area(7, "Kothapet", 20));
            var agents = List.of(
                    agent(1, "Free", 40),                                        // more spare
                    new Rules.DayPlan.AgentSlot(2L, "Regular", 30, 7L));         // covered it last

            var result = Rules.DayPlan.plan(areas, agents);

            assertEquals(2L, result.assignments().get(0).agentId());
        }

        @Test
        void theRegularAgentLosesTheAreaWhenTooFull() {
            var areas = List.of(area(7, "Kothapet", 20));
            var agents = List.of(
                    agent(1, "Free", 40),
                    new Rules.DayPlan.AgentSlot(2L, "Regular", 5, 7L));   // not enough room

            var result = Rules.DayPlan.plan(areas, agents);

            assertEquals(1L, result.assignments().get(0).agentId());
        }

        @Test
        void ordersBeyondCapacityCarryOver() {
            var result = Rules.DayPlan.plan(
                    List.of(area(1, "Kothapet", 60), area(2, "Nagole", 30)),
                    List.of(agent(1, "A", 40), agent(2, "B", 40)));

            assertEquals(10, result.leftovers().stream()
                    .mapToInt(Rules.DayPlan.Leftover::orders).sum());
            assertTrue(result.notes().stream().anyMatch(n -> n.contains("carry over")));
        }

        @Test
        void noAgentsMeansEverythingCarriesOver() {
            var result = Rules.DayPlan.plan(List.of(area(1, "Kothapet", 12)), List.of());

            assertTrue(result.assignments().isEmpty());
            assertEquals(12, result.leftovers().get(0).orders());
            assertTrue(result.leftovers().get(0).reason().contains("no agents"));
        }

        @Test
        void samePlanEveryTimeForTheSameInput() {
            var areas = List.of(area(1, "LB Nagar", 20), area(2, "Kothapet", 20), area(3, "Nagole", 20));
            var agents = List.of(agent(1, "A", 40), agent(2, "B", 40));

            var first = Rules.DayPlan.plan(areas, agents);
            var second = Rules.DayPlan.plan(areas, agents);

            assertEquals(first.assignments(), second.assignments());
        }

        @Test
        void invalidInputsAreRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> Rules.DayPlan.plan(null, List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> Rules.DayPlan.plan(List.of(area(1, "X", -1)), List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> Rules.DayPlan.plan(List.of(), List.of(agent(1, "A", -5))));
        }
    }
}
