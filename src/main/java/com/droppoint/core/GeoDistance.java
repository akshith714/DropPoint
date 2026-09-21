package com.droppoint.core;

/**
 * Great-circle distance between two points using the haversine formula.
 * Accurate to about 0.5%, which is far below phone GPS error.
 */
public final class GeoDistance {

    /** Mean radius of the Earth in metres. */
    public static final double EARTH_RADIUS_METRES = 6_371_000.0;

    private GeoDistance() {
        // utility class: no instances
    }

    /** Returns the distance between two points in metres. */
    public static double metresBetween(GeoPoint from, GeoPoint to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("Both points are required");
        }

        double lat1 = Math.toRadians(from.latitude());
        double lat2 = Math.toRadians(to.latitude());
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(to.longitude() - from.longitude());

        double a = Math.pow(Math.sin(deltaLat / 2), 2)
                 + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(deltaLon / 2), 2);

        double c = 2 * Math.asin(Math.min(1.0, Math.sqrt(a)));

        return EARTH_RADIUS_METRES * c;
    }
}
