package com.droppoint.core;

/**
 * Decides whether a delivery claim can be trusted.
 * A wrong code blocks the delivery; a bad location only flags it.
 */
public final class ProofPolicy {

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
