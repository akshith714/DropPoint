package com.droppoint.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GeoPointTest {

    private static final double ONE_DEGREE_METRES = 111_194.93;

    // ---- validation ----

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

    // ---- distance ----

    @Test
    void samePointIsZeroMetres() {
        GeoPoint p = new GeoPoint(17.3616, 78.4747);
        assertEquals(0.0, p.distanceTo(p), 0.001);
    }

    @Test
    void oneDegreeOfLatitudeIsAbout111Km() {
        assertEquals(ONE_DEGREE_METRES, new GeoPoint(0, 0).distanceTo(new GeoPoint(1, 0)), 1.0);
    }

    @Test
    void oneDegreeOfLongitudeAtEquatorIsAbout111Km() {
        assertEquals(ONE_DEGREE_METRES, new GeoPoint(0, 0).distanceTo(new GeoPoint(0, 1)), 1.0);
    }

    @Test
    void oneDegreeOfLongitudeAt60NorthIsAboutHalf() {
        assertEquals(55_597.0, new GeoPoint(60, 0).distanceTo(new GeoPoint(60, 1)), 1.0);
    }

    @Test
    void charminarToLbNagarIsAbout8Point4Km() {
        double metres = new GeoPoint(17.3616, 78.4747).distanceTo(new GeoPoint(17.3457, 78.5522));
        assertTrue(metres > 8_300 && metres < 8_500, "Expected about 8.4 km but was " + metres);
    }

    @Test
    void distanceIsTheSameInBothDirections() {
        GeoPoint a = new GeoPoint(17.3616, 78.4747);
        GeoPoint b = new GeoPoint(17.3457, 78.5522);
        assertEquals(a.distanceTo(b), b.distanceTo(a), 0.001);
    }

    @Test
    void missingOtherPointIsRejected() {
        GeoPoint p = new GeoPoint(17.3616, 78.4747);
        assertThrows(IllegalArgumentException.class, () -> p.distanceTo(null));
    }
}
