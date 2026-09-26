package com.droppoint.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.droppoint.core.EtaCalculator;
import com.droppoint.core.GeoPoint;
import com.droppoint.repo.AppUser;
import com.droppoint.repo.AppUser.AppUserRepository;
import com.droppoint.repo.Attendance;
import com.droppoint.repo.Attendance.AttendanceRepository;
import com.droppoint.repo.Customer;
import com.droppoint.repo.Customer.CustomerRepository;
import com.droppoint.repo.DeliveryOrder;
import com.droppoint.repo.DeliveryOrder.DeliveryOrderRepository;
import com.droppoint.repo.DeliveryProof;
import com.droppoint.repo.DeliveryProof.DeliveryProofRepository;

/**
 * Generates a believable operating history so later analytics have something to find.
 * Every pattern here is planted on purpose: a habitually late agent, deliveries that
 * run late in the evening and at gated addresses, and five churn stories.
 */
@Service
public class SeedService {

    private static final Logger log = LoggerFactory.getLogger(SeedService.class);

    private static final int DAYS_OF_HISTORY = 60;
    private static final int NEW_CUSTOMERS = 40;
    private static final int DOMESTIC_CUSTOMERS = 30;
    private static final List<Long> AGENT_IDS = List.of(3L, 4L, 5L, 6L);
    private static final Long BRANCH_ID = 1L;
    private static final double BRANCH_LAT = 17.3457;
    private static final double BRANCH_LON = 78.5522;
    private static final double LATE_RATE = 0.12;
    private static final double FAILURE_RATE = 0.035;
    private static final long RANDOM_SEED = 20260923L;

    /** The agent who is habitually late, so "who checks in late?" has an answer. */
    private static final Long HABITUALLY_LATE_AGENT = 5L;

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter ORDER_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final double METRES_PER_DEGREE_LAT = 111_194.93;

    private static final String[] DOMESTIC_NAMES = {
        "Anil Verma", "Sunitha Rao", "Kiran Kumar", "Padma Latha", "Ramesh Naidu",
        "Sridevi Reddy", "Mohan Rao", "Jyothi Prasad", "Vijay Chandra", "Kavitha Sharma",
        "Srinivas Goud", "Rekha Bai", "Naresh Babu", "Swapna Devi", "Prakash Rao",
        "Lalitha Kumari", "Ganesh Reddy", "Bhavani Devi", "Satish Chandra", "Meena Kumari",
        "Raju Yadav", "Sarita Singh", "Vamsi Krishna", "Deepa Nair", "Harish Chandra",
        "Usha Rani", "Kishore Kumar", "Radhika Menon", "Mallesh Goud", "Aruna Devi"
    };

    private static final String[] COMMERCIAL_NAMES = {
        "Sri Sai Tiffins", "Hotel Blue Moon", "Green Leaf Mess", "Annapurna Bhavan",
        "Café Konark", "Sangeetha Mess", "New Udupi Hotel", "Balaji Catering",
        "Sri Lakshmi Tiffin Centre", "Royal Biryani House"
    };

    private static final String[] STREETS = {
        "Kothapet main road", "Dilsukhnagar, near metro", "Nagole X roads",
        "Vanasthalipuram phase 1", "L.B. Nagar circle", "Chaitanyapuri colony",
        "Sagar road, Karmanghat", "Hastinapuram main road", "Saroornagar tank bund",
        "Champapet cross roads"
    };

    public record SeedSummary(int customers, int orders, int delivered, int failed,
                              int attendanceDays, int churnCases, String note) { }

    private final CustomerRepository customers;
    private final DeliveryOrderRepository orders;
    private final AttendanceRepository attendances;
    private final DeliveryProofRepository proofs;
    private final AppUserRepository users;
    private final JdbcTemplate jdbc;

    public SeedService(CustomerRepository customers, DeliveryOrderRepository orders,
                       AttendanceRepository attendances, DeliveryProofRepository proofs,
                       AppUserRepository users, JdbcTemplate jdbc) {
        this.customers = customers;
        this.orders = orders;
        this.attendances = attendances;
        this.proofs = proofs;
        this.users = users;
        this.jdbc = jdbc;
    }

    // ---- public API ----

    @Transactional
    public SeedSummary generate(boolean force) {
        Integer runs = jdbc.queryForObject("SELECT count(*) FROM seed_run", Integer.class);
        if (runs != null && runs > 0) {
            if (!force) {
                throw new IllegalStateException(
                        "Demo history already generated. Clear it first, or pass force=true.");
            }
            clear();   // force means replace, not duplicate: order numbers are unique
        }
        for (Long agentId : AGENT_IDS) {
            AppUser agent = users.findById(agentId).orElseThrow(() -> new IllegalStateException(
                    "Agent " + agentId + " is missing; migration V7 adds the extra agents"));
            if (agent.getRole() != AppUser.Role.AGENT) {
                throw new IllegalStateException("User " + agentId + " is not an agent");
            }
        }

        Random rnd = new Random(RANDOM_SEED);
        LocalDate today = LocalDate.now(BUSINESS_ZONE);

        List<Customer> people = createCustomers(rnd);
        Map<LocalDate, List<Long>> onDuty = new HashMap<>();
        int attendanceDays = createAttendance(rnd, today, onDuty);

        Set<String> usedOrderNos = new HashSet<>();
        Counters counters = new Counters();

        // churn cases own their history, so the ordinary cycle skips them
        Set<Integer> churnIndices = Set.of(0, 1, 2, 3, DOMESTIC_CUSTOMERS);

        for (int i = 0; i < people.size(); i++) {
            if (churnIndices.contains(i)) {
                continue;
            }
            Customer c = people.get(i);
            boolean commercial = c.getCustomerType() == Customer.CustomerType.COMMERCIAL;
            int cycle = commercial ? 4 + rnd.nextInt(7) : 25 + rnd.nextInt(16);

            LocalDate day = today.minusDays(DAYS_OF_HISTORY).plusDays(rnd.nextInt(cycle));
            while (day.isBefore(today)) {
                writeOrder(c, day, cylindersFor(commercial, rnd), rnd, today, onDuty,
                        usedOrderNos, counters, false, true);
                day = day.plusDays(Math.max(1, cycle - 3 + rnd.nextInt(9)));
            }
        }

        int churnCases = createChurnCases(people, rnd, today, onDuty, usedOrderNos, counters);

        String note = "Seeded " + people.size() + " customers, " + counters.orders + " orders ("
                + counters.delivered + " delivered, " + counters.failed + " failed, "
                + counters.booked + " still booked), " + attendanceDays + " attendance days, "
                + churnCases + " churn cases. Agent " + HABITUALLY_LATE_AGENT
                + " checks in late on most days; evening and gated drops run later.";

        jdbc.update("INSERT INTO seed_run (summary) VALUES (?)", note);
        log.info(note);

        return new SeedSummary(people.size(), counters.orders, counters.delivered,
                counters.failed, attendanceDays, churnCases, note);
    }

    /** Removes generated rows only; hand-made test data is left alone. */
    @Transactional
    public void clear() {
        List<DeliveryOrder> seededOrders = orders.findBySeededTrue();
        if (!seededOrders.isEmpty()) {
            proofs.deleteByOrderIdIn(seededOrders.stream().map(DeliveryOrder::getId).toList());
            orders.deleteAll(seededOrders);
        }
        customers.deleteAll(customers.findBySeededTrue());
        attendances.deleteAll(attendances.findByWorkDateBefore(LocalDate.now(BUSINESS_ZONE)));
        jdbc.update("DELETE FROM seed_run");
    }

    // ---- step 1: customers ----

    private List<Customer> createCustomers(Random rnd) {
        Set<String> phones = new HashSet<>();
        List<Customer> made = new ArrayList<>();

        for (int i = 0; i < NEW_CUSTOMERS; i++) {
            boolean commercial = i >= DOMESTIC_CUSTOMERS;
            String name = commercial
                    ? COMMERCIAL_NAMES[i - DOMESTIC_CUSTOMERS]
                    : DOMESTIC_NAMES[i];

            Customer.BuildingType building;
            short floor;
            boolean lift;
            if (commercial) {
                building = Customer.BuildingType.COMMERCIAL;
                floor = 0;
                lift = false;
            } else {
                double roll = rnd.nextDouble();
                if (roll < 0.40) {
                    building = Customer.BuildingType.INDEPENDENT;
                    floor = 0;
                    lift = false;
                } else if (roll < 0.80) {
                    building = Customer.BuildingType.APARTMENT;
                    floor = (short) rnd.nextInt(7);
                    lift = rnd.nextDouble() < 0.25;
                } else {
                    building = Customer.BuildingType.GATED;
                    floor = (short) rnd.nextInt(7);
                    lift = rnd.nextDouble() < 0.60;
                }
            }

            String phone;
            do {
                phone = "92" + String.format("%08d", rnd.nextInt(100_000_000));
            } while (!phones.add(phone));

            // churn case 2 has moved away, so that customer starts inactive
            boolean active = i != 1;

            made.add(customers.save(new Customer(BRANCH_ID, name, phone,
                    commercial ? Customer.CustomerType.COMMERCIAL : Customer.CustomerType.DOMESTIC,
                    STREETS[rnd.nextInt(STREETS.length)],
                    round6(17.32 + rnd.nextDouble() * 0.07),
                    round6(78.50 + rnd.nextDouble() * 0.09),
                    building, floor, lift, active, true)));
        }
        return made;
    }

    // ---- step 2: attendance ----

    private int createAttendance(Random rnd, LocalDate today, Map<LocalDate, List<Long>> onDuty) {
        GeoPoint branch = new GeoPoint(BRANCH_LAT, BRANCH_LON);
        int rows = 0;

        for (int back = DAYS_OF_HISTORY; back >= 1; back--) {
            LocalDate day = today.minusDays(back);
            boolean sunday = day.getDayOfWeek() == DayOfWeek.SUNDAY;
            int week = (int) Math.floorMod(day.toEpochDay() / 7, AGENT_IDS.size());
            List<Long> working = new ArrayList<>();

            for (int i = 0; i < AGENT_IDS.size(); i++) {
                Long agentId = AGENT_IDS.get(i);

                // two of the four rest each Sunday, rotating week by week
                if (sunday && (i == week || i == (week + 1) % AGENT_IDS.size())) {
                    continue;
                }
                // every draw happens before any skip, so the history stays reproducible
                boolean absent = rnd.nextDouble() < 0.04;
                boolean late = agentId.equals(HABITUALLY_LATE_AGENT) && rnd.nextDouble() < 0.70;
                int checkInMinute = late
                        ? 8 * 60 + 30 + 35 + rnd.nextInt(16)     // 35-50 minutes late
                        : 8 * 60 + 30 + rnd.nextInt(31) - 15;    // 08:30 give or take a quarter hour

                Instant checkInAt = atIndiaTime(day, checkInMinute);
                Instant checkOutAt = atIndiaTime(day, 19 * 60 + rnd.nextInt(81) - 40);

                double lat = round6(BRANCH_LAT + (rnd.nextDouble() - 0.5) * 0.0016);
                double lon = round6(BRANCH_LON + (rnd.nextDouble() - 0.5) * 0.0016);
                double accuracy = round1(8 + rnd.nextDouble() * 17);
                double distance = branch.distanceTo(new GeoPoint(lat, lon));

                if (absent) {
                    continue;
                }
                if (attendances.findByAgentIdAndWorkDate(agentId, day).isPresent()) {
                    working.add(agentId);   // hand-made row for that day: leave it alone
                    continue;
                }

                Attendance a = new Attendance(agentId, BRANCH_ID, day, checkInAt,
                        lat, lon, accuracy, distance);
                a.checkOut(checkOutAt);
                attendances.save(a);

                working.add(agentId);
                rows++;
            }
            onDuty.put(day, working);
        }
        return rows;
    }

    // ---- step 3 and 4: orders ----

    /** Books one order and plays it through to its outcome. */
    private void writeOrder(Customer c, LocalDate day, int cylinders, Random rnd, LocalDate today,
                            Map<LocalDate, List<Long>> onDuty, Set<String> usedOrderNos,
                            Counters counters, boolean forceLate, boolean requireAttendance) {

        Instant bookedAt = atIndiaTime(day, 7 * 60 + rnd.nextInt(11 * 60));   // 07:00-18:00
        DeliveryOrder order = new DeliveryOrder(nextOrderNo(day, rnd, usedOrderNos), c.getId(),
                BRANCH_ID, (short) cylinders, String.format("%04d", rnd.nextInt(10_000)), bookedAt);
        order.markSeeded();
        order = orders.save(order);
        counters.orders++;

        List<Long> available = onDuty.getOrDefault(day, List.of());
        Long agentId;
        if (!available.isEmpty()) {
            agentId = available.get(rnd.nextInt(available.size()));
        } else if (requireAttendance) {
            counters.booked++;
            return;   // nobody on duty: a realistic backlog
        } else {
            agentId = AGENT_IDS.get(rnd.nextInt(AGENT_IDS.size()));
        }

        Instant assignedAt = bookedAt.plusSeconds((5 + rnd.nextInt(26)) * 60L);
        order.assignTo(agentId, assignedAt);

        EtaCalculator.Estimate eta = EtaCalculator.estimate(round1(6 + rnd.nextDouble() * 19),
                new EtaCalculator.DropContext(
                        EtaCalculator.BuildingType.valueOf(c.getBuildingType().name()),
                        c.getFloorNo(), c.isHasLift(), cylinders));
        Instant etaAt = assignedAt.plusSeconds(Math.round(eta.totalMinutes() * 60));
        order.setEta(eta.travelMinutes(), eta.handoverMinutes(), eta.totalMinutes(),
                etaAt, "ESTIMATE", eta.explanation());

        if (!forceLate && rnd.nextDouble() < FAILURE_RATE) {
            order.markFailed(failureReason(rnd), etaAt);
            counters.failed++;
            return;
        }

        int hour = etaAt.atZone(BUSINESS_ZONE).getHour();
        boolean harderToDeliver = (hour >= 18 && hour < 20)
                || c.getBuildingType() == Customer.BuildingType.GATED;
        boolean late = forceLate
                || rnd.nextDouble() < LATE_RATE * (harderToDeliver ? 2 : 1);

        double lateness;
        if (forceLate) {
            lateness = 20 + rnd.nextDouble() * 20;      // the churn story needs 20+ minutes
        } else if (late) {
            lateness = 8 + rnd.nextDouble() * 37;
        } else {
            lateness = -6 + rnd.nextDouble() * 11;
        }

        Instant deliveredAt = etaAt.plusSeconds(Math.round(lateness * 60));
        order.markDelivered(deliveredAt);
        counters.delivered++;

        writeProof(order, c, agentId, etaAt, deliveredAt, rnd);
    }

    private void writeProof(DeliveryOrder order, Customer c, Long agentId,
                            Instant etaAt, Instant deliveredAt, Random rnd) {
        boolean suspicious = rnd.nextDouble() < 0.02;
        double offsetDegrees = suspicious
                ? (800 + rnd.nextDouble() * 3200) / METRES_PER_DEGREE_LAT
                : (rnd.nextDouble() - 0.5) * 0.0006;

        double lat = round6(c.getLatitude() + offsetDegrees);
        double lon = round6(c.getLongitude() + (suspicious ? 0 : (rnd.nextDouble() - 0.5) * 0.0006));
        double distance = new GeoPoint(c.getLatitude(), c.getLongitude())
                .distanceTo(new GeoPoint(lat, lon));
        double lateMinutes = Math.round(Duration.between(etaAt, deliveredAt).toSeconds() / 6.0) / 10.0;

        proofs.save(new DeliveryProof(order.getId(), agentId, deliveredAt, lat, lon, distance,
                true, suspicious ? "SUSPICIOUS" : "VERIFIED", lateMinutes));
    }

    /**
     * Five deliberate stories for churn analysis to explain. Their order history reaches
     * further back than the ordinary window, because a 60-day view cannot show a 65-day gap.
     */
    private int createChurnCases(List<Customer> people, Random rnd, LocalDate today,
                                 Map<LocalDate, List<Long>> onDuty, Set<String> usedOrderNos,
                                 Counters counters) {

        // 1. service failure: steady, then three very late drops, then silence
        Customer serviceFailure = people.get(0);
        int[] failureDays = {221, 191, 161, 131, 101, 71};
        for (int i = 0; i < failureDays.length; i++) {
            boolean lastThree = i >= failureDays.length - 3;
            writeOrder(serviceFailure, today.minusDays(failureDays[i]), 1, rnd, today, onDuty,
                    usedOrderNos, counters, lastThree, false);
        }

        // 2. moved away: regular orders, then nothing, and the account is closed
        Customer movedAway = people.get(1);
        for (int d : new int[] {215, 185, 155, 125, 95, 65}) {
            writeOrder(movedAway, today.minusDays(d), 1, rnd, today, onDuty,
                    usedOrderNos, counters, false, false);
        }

        // 3. gradual drift: gaps stretching 30, 38, 47, 60 days
        Customer drifting = people.get(2);
        for (int d : new int[] {230, 200, 162, 115, 55}) {
            writeOrder(drifting, today.minusDays(d), 1, rnd, today, onDuty,
                    usedOrderNos, counters, false, false);
        }

        // 5. healthy but overdue: the control case, a perfect 28-day cycle
        Customer control = people.get(3);
        for (int d : new int[] {230, 202, 174, 146, 118, 90, 62, 34}) {
            writeOrder(control, today.minusDays(d), 1, rnd, today, onDuty,
                    usedOrderNos, counters, false, false);
        }

        // 4. commercial slowdown: weekly at first, then monthly, and smaller each time
        Customer slowing = people.get(DOMESTIC_CUSTOMERS);
        int[] slowingDays = {180, 173, 166, 159, 152, 120, 90, 60, 35};
        int[] slowingCylinders = {3, 3, 3, 3, 3, 2, 2, 1, 1};
        for (int i = 0; i < slowingDays.length; i++) {
            writeOrder(slowing, today.minusDays(slowingDays[i]), slowingCylinders[i], rnd, today,
                    onDuty, usedOrderNos, counters, false, false);
        }

        return 5;
    }

    // ---- small helpers ----

    private static int cylindersFor(boolean commercial, Random rnd) {
        if (commercial) {
            return 1 + rnd.nextInt(4);
        }
        return rnd.nextDouble() < 0.90 ? 1 : 2;
    }

    private static String failureReason(Random rnd) {
        double roll = rnd.nextDouble();
        if (roll < 0.60) {
            return "NOBODY_HOME";
        }
        if (roll < 0.80) {
            return "NO_ACCESS";
        }
        return roll < 0.90 ? "CUSTOMER_REFUSED" : "WRONG_ADDRESS";
    }

    private static String nextOrderNo(LocalDate day, Random rnd, Set<String> used) {
        String no;
        do {
            no = "DP" + day.format(ORDER_DAY) + "-" + String.format("%04d", rnd.nextInt(10_000));
        } while (!used.add(no));
        return no;
    }

    private static Instant atIndiaTime(LocalDate day, int minuteOfDay) {
        return day.atTime(LocalTime.MIDNIGHT).plusMinutes(minuteOfDay)
                .atZone(BUSINESS_ZONE).toInstant();
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000) / 1_000_000.0;
    }

    /** Running totals for the summary. */
    private static final class Counters {
        private int orders;
        private int delivered;
        private int failed;
        private int booked;
    }
}
