package com.droppoint.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranks available agents for one delivery.
 * Score = distance in km + (active orders x workload weight). Lower is better.
 */
public final class AssignmentRanker {

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

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
