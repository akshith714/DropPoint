package com.droppoint.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The business rules, one nested class each. They hold no state beyond their
 * own settings, touch no database and know nothing about Spring, which is what
 * makes them the part of the system worth unit testing.
 */
public final class Rules {

    private Rules() {
        // namespace only
    }

    /**
     * Decides whether a delivery agent may check in at a branch.
     * Checks, in order: fake location, GPS accuracy, distance from the branch.
     */
    public static final class CheckInPolicy {

        public enum Reason { ALLOWED, MOCK_LOCATION, LOW_ACCURACY, TOO_FAR }

        public record Result(boolean allowed, Reason reason, double distanceMetres) { }

        private final double radiusMetres;
        private final double maxAccuracyMetres;

        public CheckInPolicy(double radiusMetres, double maxAccuracyMetres) {
            if (!(radiusMetres > 0)) {
                throw new IllegalArgumentException("Radius must be positive, was " + radiusMetres);
            }
            if (!(maxAccuracyMetres > 0)) {
                throw new IllegalArgumentException("Max accuracy must be positive, was " + maxAccuracyMetres);
            }
            this.radiusMetres = radiusMetres;
            this.maxAccuracyMetres = maxAccuracyMetres;
        }

        public Result evaluate(GeoPoint branch, GeoPoint agent, double accuracyMetres, boolean mockLocation) {
            if (branch == null || agent == null) {
                throw new IllegalArgumentException("Branch and agent locations are required");
            }
            if (!(accuracyMetres >= 0)) {
                throw new IllegalArgumentException("Accuracy must be zero or more, was " + accuracyMetres);
            }

            double distance = branch.distanceTo(agent);

            if (mockLocation) {
                return new Result(false, Reason.MOCK_LOCATION, distance);
            }
            if (accuracyMetres > maxAccuracyMetres) {
                return new Result(false, Reason.LOW_ACCURACY, distance);
            }
            if (distance > radiusMetres) {
                return new Result(false, Reason.TOO_FAR, distance);
            }
            return new Result(true, Reason.ALLOWED, distance);
        }
    }

    /**
     * Ranks available agents for one delivery.
     * Score = distance in km + (active orders x workload weight). Lower is better.
     */
    public static final class AssignmentRanker {

        /** An agent who could take the delivery. */
        public record Candidate(Long agentId, String agentName, GeoPoint position, int activeOrders) { }

        /** One agent's score for this delivery. */
        public record Ranked(Long agentId, String agentName, double distanceKm, int activeOrders,
                             double score, String explanation) { }

        private final double workloadWeightKm;
        private final int maxOrdersPerAgent;

        public AssignmentRanker(double workloadWeightKm, int maxOrdersPerAgent) {
            if (!(workloadWeightKm >= 0)) {
                throw new IllegalArgumentException("Workload weight must be zero or more, was " + workloadWeightKm);
            }
            if (maxOrdersPerAgent < 1) {
                throw new IllegalArgumentException("Max orders must be at least 1, was " + maxOrdersPerAgent);
            }
            this.workloadWeightKm = workloadWeightKm;
            this.maxOrdersPerAgent = maxOrdersPerAgent;
        }

        /** Best first. Agents at their order limit are left out. */
        public List<Ranked> rank(GeoPoint destination, List<Candidate> candidates) {
            if (destination == null) {
                throw new IllegalArgumentException("Destination is required");
            }
            if (candidates == null) {
                throw new IllegalArgumentException("Candidates are required");
            }

            List<Ranked> ranked = new ArrayList<>();
            for (Candidate c : candidates) {
                if (c.position() == null) {
                    throw new IllegalArgumentException("Candidate " + c.agentId() + " has no position");
                }
                if (c.activeOrders() < 0) {
                    throw new IllegalArgumentException("Candidate " + c.agentId() + " has negative orders");
                }
                if (c.activeOrders() >= maxOrdersPerAgent) {
                    continue;   // already full
                }

                double distanceKm = round1(destination.distanceTo(c.position()) / 1000.0);
                double score = round1(distanceKm + c.activeOrders() * workloadWeightKm);
                String explanation = distanceKm + " km away, " + c.activeOrders() + " active order"
                        + (c.activeOrders() == 1 ? "" : "s");

                ranked.add(new Ranked(c.agentId(), c.agentName(), distanceKm, c.activeOrders(), score, explanation));
            }

            ranked.sort(Comparator.comparingDouble(Ranked::score)
                    .thenComparingDouble(Ranked::distanceKm)
                    .thenComparing(Ranked::agentId));
            return ranked;
        }
    }

    /**
     * Turns travel time into a realistic delivery time by adding the handover
     * minutes Google cannot know about: building type, floor, lift, cylinder count.
     */
    public static final class EtaCalculator {

        /** Everything about the drop that affects how long the handover takes. */
        public record DropContext(BuildingType buildingType, int floorNo, boolean hasLift, int cylinderCount) { }

        public enum BuildingType { INDEPENDENT, APARTMENT, GATED, COMMERCIAL }

        /** The breakdown, so the app can explain itself. */
        public record Estimate(double travelMinutes, double handoverMinutes, double totalMinutes,
                               String explanation) { }

        /** Minutes at the door before any floor or load penalty. */
        private static final double BASE_INDEPENDENT = 3.0;
        private static final double BASE_APARTMENT = 5.0;
        private static final double BASE_GATED = 7.0;      // security desk, visitor entry
        private static final double BASE_COMMERCIAL = 8.0; // paperwork, kitchen access

        /** Extra minutes per floor. */
        private static final double PER_FLOOR_STAIRS = 1.5;
        private static final double PER_FLOOR_LIFT = 0.4;

        /** Extra minutes for each cylinder after the first. */
        private static final double PER_EXTRA_CYLINDER = 1.5;

        private EtaCalculator() {
            // utility class
        }

        public static Estimate estimate(double travelMinutes, DropContext drop) {
            if (!(travelMinutes >= 0)) {
                throw new IllegalArgumentException("Travel minutes must be zero or more, was " + travelMinutes);
            }
            if (drop == null) {
                throw new IllegalArgumentException("Drop context is required");
            }
            if (drop.buildingType() == null) {
                throw new IllegalArgumentException("Building type is required");
            }
            if (drop.floorNo() < 0 || drop.floorNo() > 100) {
                throw new IllegalArgumentException("Floor must be between 0 and 100, was " + drop.floorNo());
            }
            if (drop.cylinderCount() < 1) {
                throw new IllegalArgumentException("Cylinder count must be at least 1, was " + drop.cylinderCount());
            }

            double base = switch (drop.buildingType()) {
                case INDEPENDENT -> BASE_INDEPENDENT;
                case APARTMENT -> BASE_APARTMENT;
                case GATED -> BASE_GATED;
                case COMMERCIAL -> BASE_COMMERCIAL;
            };

            double perFloor = drop.hasLift() ? PER_FLOOR_LIFT : PER_FLOOR_STAIRS;
            double floorPenalty = drop.floorNo() * perFloor;
            double cylinderExtra = (drop.cylinderCount() - 1) * PER_EXTRA_CYLINDER;

            double handover = round1(base + floorPenalty + cylinderExtra);
            double total = round1(travelMinutes + handover);

            return new Estimate(round1(travelMinutes), handover, total,
                    explain(drop, base, floorPenalty, cylinderExtra));
        }

        private static String explain(DropContext drop, double base, double floorPenalty, double cylinderExtra) {
            StringBuilder s = new StringBuilder();
            s.append(round1(base)).append(" min at ")
             .append(drop.buildingType() == BuildingType.INDEPENDENT ? "an " : "a ")
             .append(drop.buildingType().name().toLowerCase()).append(" address");
            if (floorPenalty > 0) {
                s.append(", +").append(round1(floorPenalty)).append(" min for floor ").append(drop.floorNo())
                 .append(drop.hasLift() ? " with lift" : " with no lift");
            }
            if (cylinderExtra > 0) {
                s.append(", +").append(round1(cylinderExtra)).append(" min for ")
                 .append(drop.cylinderCount()).append(" cylinders");
            }
            return s.toString();
        }
    }

    /**
     * Decides whether a delivery claim can be trusted.
     * A wrong code blocks the delivery; a bad location only flags it.
     */
    public static final class ProofPolicy {

        public enum Verdict { VERIFIED, SUSPICIOUS }

        public enum Rejection { NOT_ASSIGNED_AGENT, WRONG_CODE }

        /** Either a rejection (delivery blocked) or a verdict with the distance. */
        public record Result(boolean accepted, Rejection rejection, Verdict verdict,
                             double distanceMetres, String note) { }

        private final double maxDistanceMetres;

        public ProofPolicy(double maxDistanceMetres) {
            if (!(maxDistanceMetres > 0)) {
                throw new IllegalArgumentException("Max distance must be positive, was " + maxDistanceMetres);
            }
            this.maxDistanceMetres = maxDistanceMetres;
        }

        public Result evaluate(GeoPoint customerAt, GeoPoint agentAt,
                               Long assignedAgentId, Long claimingAgentId,
                               String expectedCode, String enteredCode) {

            if (customerAt == null || agentAt == null) {
                throw new IllegalArgumentException("Both positions are required");
            }
            if (assignedAgentId == null || claimingAgentId == null) {
                throw new IllegalArgumentException("Both agent ids are required");
            }
            if (expectedCode == null || expectedCode.isBlank()) {
                throw new IllegalArgumentException("Expected code is required");
            }

            double distance = customerAt.distanceTo(agentAt);

            if (!assignedAgentId.equals(claimingAgentId)) {
                return new Result(false, Rejection.NOT_ASSIGNED_AGENT, null, distance,
                        "This order is assigned to another agent.");
            }

            boolean codeMatches = expectedCode.trim().equals(enteredCode == null ? "" : enteredCode.trim());
            if (!codeMatches) {
                return new Result(false, Rejection.WRONG_CODE, null, distance,
                        "The code does not match. Ask the customer to read it again.");
            }

            if (distance > maxDistanceMetres) {
                return new Result(true, null, Verdict.SUSPICIOUS, distance,
                        "Delivered " + Math.round(distance) + " m from the address — flagged for review.");
            }

            return new Result(true, null, Verdict.VERIFIED, distance, "Delivery verified.");
        }
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
