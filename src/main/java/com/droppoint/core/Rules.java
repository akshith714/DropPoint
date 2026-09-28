package com.droppoint.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    /**
     * Splits a day's areas across the agents who are available, respecting each
     * agent's daily cap. No distance and no coordinates: an agent covers whole
     * areas. Whoever covered an area most recently keeps it when they have room.
     */
    public static final class DayPlan {

        /** All the orders waiting in one area today. */
        public record AreaLoad(Long areaId, String areaName, int orders, int cylinders) { }

        /** An agent who is in today, and how many more stops they can take. */
        public record AgentSlot(Long agentId, String agentName, int capacity, Long lastCoveredAreaId) { }

        /** One agent taking some or all of one area. */
        public record Assignment(Long agentId, String agentName, Long areaId, String areaName,
                                 int orders, boolean partial) { }

        /** Orders that nobody could take today. */
        public record Leftover(Long areaId, String areaName, int orders, String reason) { }

        public record Result(List<Assignment> assignments, List<Leftover> leftovers,
                             List<String> notes, int totalCapacity, int totalOrders) { }

        private DayPlan() { }

        public static Result plan(List<AreaLoad> areas, List<AgentSlot> agents) {
            if (areas == null || agents == null) {
                throw new IllegalArgumentException("Areas and agents are required");
            }
            for (AreaLoad a : areas) {
                if (a.orders() < 0) {
                    throw new IllegalArgumentException("Area " + a.areaName() + " has negative orders");
                }
            }
            for (AgentSlot s : agents) {
                if (s.capacity() < 0) {
                    throw new IllegalArgumentException("Agent " + s.agentName() + " has negative capacity");
                }
            }

            // remaining capacity per agent, in a stable order
            Map<Long, Integer> left = new LinkedHashMap<>();
            Map<Long, AgentSlot> byId = new LinkedHashMap<>();
            for (AgentSlot s : agents) {
                left.put(s.agentId(), s.capacity());
                byId.put(s.agentId(), s);
            }

            int totalCapacity = left.values().stream().mapToInt(Integer::intValue).sum();
            int totalOrders = areas.stream().mapToInt(AreaLoad::orders).sum();

            // biggest areas first, name as tie-break so the plan is repeatable
            List<AreaLoad> queue = new ArrayList<>(areas);
            queue.sort(Comparator.comparingInt(AreaLoad::orders).reversed()
                    .thenComparing(AreaLoad::areaName));

            List<Assignment> assignments = new ArrayList<>();
            List<Leftover> leftovers = new ArrayList<>();
            List<String> notes = new ArrayList<>();

            for (AreaLoad area : queue) {
                int remaining = area.orders();
                if (remaining == 0) {
                    continue;
                }
                int pieces = 0;

                while (remaining > 0) {
                    Long chosen = pick(area.areaId(), remaining, left, byId);
                    if (chosen == null) {
                        leftovers.add(new Leftover(area.areaId(), area.areaName(), remaining,
                                totalCapacity == 0 ? "no agents available today"
                                                   : "every agent is at their daily limit"));
                        break;
                    }
                    int take = Math.min(remaining, left.get(chosen));
                    boolean partial = take < area.orders();
                    assignments.add(new Assignment(chosen, byId.get(chosen).agentName(),
                            area.areaId(), area.areaName(), take, partial));
                    left.put(chosen, left.get(chosen) - take);
                    remaining -= take;
                    pieces++;
                }

                if (pieces > 1) {
                    notes.add(area.areaName() + " is too big for one agent, so it is split across "
                            + pieces + " agents.");
                }
            }

            // how many areas each agent ended up with
            Map<Long, Long> areasPerAgent = assignments.stream().collect(
                    Collectors.groupingBy(Assignment::agentId,
                            Collectors.mapping(Assignment::areaId, Collectors.collectingAndThen(
                                    Collectors.toSet(), s -> (long) s.size()))));
            areasPerAgent.forEach((agentId, count) -> {
                if (count > 1) {
                    notes.add(byId.get(agentId).agentName() + " covers " + count + " areas today.");
                }
            });

            int carried = leftovers.stream().mapToInt(Leftover::orders).sum();
            if (carried > 0) {
                notes.add(carried + " orders do not fit today and will carry over. Capacity is "
                        + totalCapacity + " stops for " + totalOrders + " orders.");
            }

            return new Result(assignments, leftovers, notes, totalCapacity, totalOrders);
        }

        /**
         * Prefers the agent who covered this area last and can take all of it,
         * otherwise the agent with the most spare capacity.
         */
        private static Long pick(Long areaId, int needed, Map<Long, Integer> left,
                                 Map<Long, AgentSlot> byId) {
            Long sticky = null;
            for (AgentSlot s : byId.values()) {
                if (areaId.equals(s.lastCoveredAreaId()) && left.get(s.agentId()) >= needed) {
                    sticky = s.agentId();
                    break;
                }
            }
            if (sticky != null) {
                return sticky;
            }
            Long best = null;
            int bestLeft = 0;
            for (Map.Entry<Long, Integer> e : left.entrySet()) {
                if (e.getValue() > bestLeft) {
                    best = e.getKey();
                    bestLeft = e.getValue();
                }
            }
            return best;
        }
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
