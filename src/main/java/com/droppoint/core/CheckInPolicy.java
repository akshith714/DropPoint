package com.droppoint.core;

/**
 * Decides whether a delivery agent may check in at a branch.
 * Checks, in order: fake location, GPS accuracy, distance from the branch.
 */
public final class CheckInPolicy {

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
