package com.droppoint.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GeoPointTest {

    @Test
    void acceptsExactBoundaryValues() {
        assertDoesNotThrow(() -> new GeoPoint(90, 180));
        assertDoesNotThrow(() -> new GeoPoint(-90, -180));
    }

    @Test
    void rejectsLatitudeOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> new GeoPoint(90.0001, 0));
        assertThrows(IllegalArgumentException.class, () -> new GeoPoint(-91, 0));
    }

    @Test
    void rejectsLongitudeOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> new GeoPoint(0, 180.0001));
        assertThrows(IllegalArgumentException.class, () -> new GeoPoint(0, -181));
    }

    @Test
    void rejectsNotANumber() {
        assertThrows(IllegalArgumentException.class, () -> new GeoPoint(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new GeoPoint(0, Double.NaN));
    }
}
