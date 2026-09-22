package com.droppoint.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.droppoint.core.EtaCalculator.BuildingType;
import com.droppoint.core.EtaCalculator.DropContext;
import com.droppoint.core.EtaCalculator.Estimate;

class EtaCalculatorTest {

    @Test
    void groundFloorIndependentHouseIsTheFastestHandover() {
        Estimate e = EtaCalculator.estimate(12, new DropContext(BuildingType.INDEPENDENT, 0, false, 1));
        assertEquals(3.0, e.handoverMinutes(), 0.01);
        assertEquals(15.0, e.totalMinutes(), 0.01);
    }

    @Test
    void stairsCostMoreThanALift() {
        DropContext stairs = new DropContext(BuildingType.APARTMENT, 4, false, 1);
        DropContext lift = new DropContext(BuildingType.APARTMENT, 4, true, 1);

        assertEquals(5.0 + 4 * 1.5, EtaCalculator.estimate(10, stairs).handoverMinutes(), 0.01);
        assertEquals(5.0 + 4 * 0.4, EtaCalculator.estimate(10, lift).handoverMinutes(), 0.01);
        assertTrue(EtaCalculator.estimate(10, stairs).totalMinutes()
                 > EtaCalculator.estimate(10, lift).totalMinutes());
    }

    @Test
    void extraCylindersAddTime() {
        DropContext one = new DropContext(BuildingType.INDEPENDENT, 0, false, 1);
        DropContext three = new DropContext(BuildingType.INDEPENDENT, 0, false, 3);
        assertEquals(3.0, EtaCalculator.estimate(5, one).handoverMinutes(), 0.01);
        assertEquals(3.0 + 2 * 1.5, EtaCalculator.estimate(5, three).handoverMinutes(), 0.01);
    }

    @Test
    void buildingTypesAreOrderedFromFastestToSlowest() {
        double independent = handoverFor(BuildingType.INDEPENDENT);
        double apartment = handoverFor(BuildingType.APARTMENT);
        double gated = handoverFor(BuildingType.GATED);
        double commercial = handoverFor(BuildingType.COMMERCIAL);
        assertTrue(independent < apartment && apartment < gated && gated < commercial,
                "Expected independent < apartment < gated < commercial");
    }

    private static double handoverFor(BuildingType type) {
        return EtaCalculator.estimate(10, new DropContext(type, 0, false, 1)).handoverMinutes();
    }

    @Test
    void hardDropTakesFarLongerThanTravelAlone() {
        // Priya: gated, floor 5, no lift, 2 cylinders
        Estimate e = EtaCalculator.estimate(14, new DropContext(BuildingType.GATED, 5, false, 2));
        assertEquals(7.0 + 7.5 + 1.5, e.handoverMinutes(), 0.01);
        assertEquals(30.0, e.totalMinutes(), 0.01);
    }

    @Test
    void zeroTravelTimeIsAllowed() {
        Estimate e = EtaCalculator.estimate(0, new DropContext(BuildingType.INDEPENDENT, 0, false, 1));
        assertEquals(3.0, e.totalMinutes(), 0.01);
    }

    @Test
    void explanationMentionsFloorAndCylinders() {
        String text = EtaCalculator.estimate(10,
                new DropContext(BuildingType.GATED, 5, false, 2)).explanation();
        assertTrue(text.contains("gated"), text);
        assertTrue(text.contains("floor 5"), text);
        assertTrue(text.contains("no lift"), text);
        assertTrue(text.contains("2 cylinders"), text);
    }

    @Test
    void invalidInputsAreRejected() {
        DropContext ok = new DropContext(BuildingType.APARTMENT, 1, false, 1);
        assertThrows(IllegalArgumentException.class, () -> EtaCalculator.estimate(-1, ok));
        assertThrows(IllegalArgumentException.class, () -> EtaCalculator.estimate(Double.NaN, ok));
        assertThrows(IllegalArgumentException.class, () -> EtaCalculator.estimate(10, null));
        assertThrows(IllegalArgumentException.class, () -> EtaCalculator.estimate(10,
                new DropContext(null, 1, false, 1)));
        assertThrows(IllegalArgumentException.class, () -> EtaCalculator.estimate(10,
                new DropContext(BuildingType.APARTMENT, -1, false, 1)));
        assertThrows(IllegalArgumentException.class, () -> EtaCalculator.estimate(10,
                new DropContext(BuildingType.APARTMENT, 1, false, 0)));
    }
}
