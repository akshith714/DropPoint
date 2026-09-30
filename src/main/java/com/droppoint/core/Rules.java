package com.droppoint.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class Rules {
    private Rules() {
    }

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

    public static final class ProofPolicy {
        public enum Verdict { VERIFIED, SUSPICIOUS }

        public enum Rejection { NOT_ASSIGNED_AGENT, WRONG_CODE }

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

    public static final class DayPlan {
        public record AreaLoad(Long areaId, String areaName, int orders, int cylinders) { }

        public record AgentSlot(Long agentId, String agentName, int capacity, Long lastCoveredAreaId) { }

        public record Assignment(Long agentId, String agentName, Long areaId, String areaName,
                                 int orders, boolean partial) { }

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

            Map<Long, Integer> left = new LinkedHashMap<>();
            Map<Long, AgentSlot> byId = new LinkedHashMap<>();
            for (AgentSlot s : agents) {
                left.put(s.agentId(), s.capacity());
                byId.put(s.agentId(), s);
            }

            int totalCapacity = left.values().stream().mapToInt(Integer::intValue).sum();
            int totalOrders = areas.stream().mapToInt(AreaLoad::orders).sum();

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
}
