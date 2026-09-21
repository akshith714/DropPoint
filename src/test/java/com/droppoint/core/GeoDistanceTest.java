package com.droppoint.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GeoDistanceTest {

    private static final double ONE_DEGREE_METRES = 111_194.93;

    @Test
    void samePointIsZeroMetres() {
        GeoPoint p = new GeoPoint(17.3616, 78.4747);
        assertEquals(0.0, GeoDistance.metresBetween(p, p), 0.001);
    }

    @Test
    void oneDegreeOfLatitudeIsAbout111Km() {
        GeoPoint a = new GeoPoint(0, 0);
        GeoPoint b = new GeoPoint(1, 0);
        assertEquals(ONE_DEGREE_METRES, GeoDistance.metresBetween(a, b), 1.0);
    }

    @Test
    void oneDegreeOfLongitudeAtEquatorIsAbout111Km() {
        GeoPoint a = new GeoPoint(0, 0);
        GeoPoint b = new GeoPoint(0, 1);
        assertEquals(ONE_DEGREE_METRES, GeoDistance.metresBetween(a, b), 1.0);
    }

    @Test
    void oneDegreeOfLongitudeAt60NorthIsAboutHalf() {
        GeoPoint a = new GeoPoint(60, 0);
        GeoPoint b = new GeoPoint(60, 1);
        assertEquals(55_597.0, GeoDistance.metresBetween(a, b), 1.0);
    }

    @Test
    void charminarToLbNagarIsAbout8Point4Km() {
        GeoPoint charminar = new GeoPoint(17.3616, 78.4747);
        GeoPoint lbNagar = new GeoPoint(17.3457, 78.5522);
        double metres = GeoDistance.metresBetween(charminar, lbNagar);
        assertTrue(metres > 8_300 && metres < 8_500, "Expected about 8.4 km but was " + metres);
    }

    @Test
    void distanceIsTheSameInBothDirections() {
        GeoPoint a = new GeoPoint(17.3616, 78.4747);
        GeoPoint b = new GeoPoint(17.3457, 78.5522);
        assertEquals(GeoDistance.metresBetween(a, b), GeoDistance.metresBetween(b, a), 0.001);
    }

    @Test
    void missingPointIsRejected() {
        GeoPoint p = new GeoPoint(17.3616, 78.4747);
        assertThrows(IllegalArgumentException.class, () -> GeoDistance.metresBetween(p, null));
        assertThrows(IllegalArgumentException.class, () -> GeoDistance.metresBetween(null, p));
    }
}
