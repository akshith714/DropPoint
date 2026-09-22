package com.droppoint.core;

/**
 * Turns travel time into a realistic delivery time by adding the handover
 * minutes Google cannot know about: building type, floor, lift, cylinder count.
 */
public final class EtaCalculator {

    /** Everything about the drop that affects how long the handover takes. */
    public record DropContext(BuildingType buildingType, int floorNo, boolean hasLift, int cylinderCount) { }

    public enum BuildingType { INDEPENDENT, APARTMENT, GATED, COMMERCIAL }

    /** The breakdown, so the app can explain itself. */
    public record Estimate(double travelMinutes, double handoverMinutes, double totalMinutes,
                           String explanation) { }

    /** Minutes at the door before any floor or load penalty. */
    private static final double BASE_INDEPENDENT = 3.0;
    private static final double BASE_APARTMENT = 5.0;
    private static final double BASE_GATED = 7.0;      // security desk, visitor entry
    private static final double BASE_COMMERCIAL = 8.0; // paperwork, kitchen access

    /** Extra minutes per floor. */
    private static final double PER_FLOOR_STAIRS = 1.5;
    private static final double PER_FLOOR_LIFT = 0.4;

    /** Extra minutes for each cylinder after the first. */
    private static final double PER_EXTRA_CYLINDER = 1.5;

    private EtaCalculator() {
        // utility class
    }

    public static Estimate estimate(double travelMinutes, DropContext drop) {
        if (!(travelMinutes >= 0)) {
            throw new IllegalArgumentException("Travel minutes must be zero or more, was " + travelMinutes);
        }
        if (drop == null) {
            throw new IllegalArgumentException("Drop context is required");
        }
        if (drop.buildingType() == null) {
            throw new IllegalArgumentException("Building type is required");
        }
        if (drop.floorNo() < 0 || drop.floorNo() > 100) {
            throw new IllegalArgumentException("Floor must be between 0 and 100, was " + drop.floorNo());
        }
        if (drop.cylinderCount() < 1) {
            throw new IllegalArgumentException("Cylinder count must be at least 1, was " + drop.cylinderCount());
        }

        double base = switch (drop.buildingType()) {
            case INDEPENDENT -> BASE_INDEPENDENT;
            case APARTMENT -> BASE_APARTMENT;
            case GATED -> BASE_GATED;
            case COMMERCIAL -> BASE_COMMERCIAL;
        };

        double perFloor = drop.hasLift() ? PER_FLOOR_LIFT : PER_FLOOR_STAIRS;
        double floorPenalty = drop.floorNo() * perFloor;
        double cylinderExtra = (drop.cylinderCount() - 1) * PER_EXTRA_CYLINDER;

        double handover = round1(base + floorPenalty + cylinderExtra);
        double total = round1(travelMinutes + handover);

        return new Estimate(round1(travelMinutes), handover, total,
                explain(drop, base, floorPenalty, cylinderExtra));
    }

    private static String explain(DropContext drop, double base, double floorPenalty, double cylinderExtra) {
        StringBuilder s = new StringBuilder();
        s.append(round1(base)).append(" min at a ").append(drop.buildingType().name().toLowerCase())
         .append(" address");
        if (floorPenalty > 0) {
            s.append(", +").append(round1(floorPenalty)).append(" min for floor ").append(drop.floorNo())
             .append(drop.hasLift() ? " with lift" : " with no lift");
        }
        if (cylinderExtra > 0) {
            s.append(", +").append(round1(cylinderExtra)).append(" min for ")
             .append(drop.cylinderCount()).append(" cylinders");
        }
        return s.toString();
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
