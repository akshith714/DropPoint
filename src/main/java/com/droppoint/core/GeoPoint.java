package com.droppoint.core;

public record GeoPoint(double latitude, double longitude) {
    public static final double EARTH_RADIUS_METRES = 6_371_000.0;

    public GeoPoint {
        if (Double.isNaN(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Latitude must be between -90 and 90, was " + latitude);
        }
        if (Double.isNaN(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Longitude must be between -180 and 180, was " + longitude);
        }
    }

    public double distanceTo(GeoPoint other) {
        if (other == null) {
            throw new IllegalArgumentException("Other point is required");
        }
        double lat1 = Math.toRadians(latitude);
        double lat2 = Math.toRadians(other.latitude);
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(other.longitude - longitude);

        double a = Math.pow(Math.sin(deltaLat / 2), 2)
                 + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(deltaLon / 2), 2);

        return EARTH_RADIUS_METRES * 2 * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
