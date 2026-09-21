package com.droppoint.core;

/**
 * A location on Earth in decimal degrees.
 * Latitude must be between -90 and 90, longitude between -180 and 180.
 */
public record GeoPoint(double latitude, double longitude) {

    public GeoPoint {
        if (Double.isNaN(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Latitude must be between -90 and 90, was " + latitude);
        }
        if (Double.isNaN(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Longitude must be between -180 and 180, was " + longitude);
        }
    }
}
