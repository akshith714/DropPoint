package com.droppoint.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.droppoint.repo.Deliveries.Area;
import com.droppoint.repo.Deliveries.Area.AreaRepository;
import com.droppoint.repo.Deliveries.Customer;
import com.droppoint.repo.Deliveries.Customer.CustomerRepository;
import com.droppoint.repo.Deliveries.DeliveryOrder;
import com.droppoint.repo.Deliveries.DeliveryOrder.DeliveryOrderRepository;

/**
 * Turns a pasted or uploaded list of the day's orders into customers and orders.
 * Nobody types coordinates: a location is taken from a past delivery, from
 * geocoding the address, or from the locality centre - in that order.
 */
@Service
public class IntakeService {

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final long BRANCH_ID = 1L;

    /** Widths the customer table will accept, checked here so a long line is reported, not thrown. */
    private static final int MAX_NAME = 150;
    private static final int MAX_ADDRESS = 250;

    /** Nominatim asks for no more than one request a second. */
    private static final long GEOCODE_GAP_MS = 1_100;

    /** One parsed line, before it is saved. The area is the locality's canonical name. */
    public record Row(int lineNo, String phone, String name, String area, String address,
                      int cylinders, String type, String locationSource, String problem) {
        public boolean isValid() { return problem == null; }
    }

    public record Preview(List<Row> rows, int valid, int problems,
                          int newCustomers, int knownCustomers, Map<String, Integer> byArea) { }

    public record ImportResult(String batch, int ordersCreated, int customersCreated,
                               int skipped, List<String> problems, Map<String, Integer> byArea) { }

    /** A resolved position and how much we trust it. */
    private record Located(double latitude, double longitude, String source) { }

    private final CustomerRepository customers;
    private final DeliveryOrderRepository orders;
    private final AreaRepository areas;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private long lastGeocodeAt;

    public IntakeService(CustomerRepository customers, DeliveryOrderRepository orders,
                         AreaRepository areas) {
        this.customers = customers;
        this.orders = orders;
        this.areas = areas;
    }

    /** Parses without saving, so the manager can check before committing. */
    @Transactional(readOnly = true)
    public Preview preview(String text) {
        List<Row> rows = parse(text, activeAreas());
        Map<String, Integer> byArea = new HashMap<>();
        int newOnes = 0;
        int known = 0;

        for (Row r : rows) {
            if (!r.isValid()) {
                continue;
            }
            byArea.merge(r.area(), 1, Integer::sum);
            if (customers.findFirstByPhoneOrderByIdAsc(r.phone()).isPresent()) {
                known++;
            } else {
                newOnes++;
            }
        }
        int valid = (int) rows.stream().filter(Row::isValid).count();
        return new Preview(rows, valid, rows.size() - valid, newOnes, known, byArea);
    }

    @Transactional
    public ImportResult importOrders(String text) {
        Map<String, Area> areaByName = activeAreas();
        List<Row> rows = parse(text, areaByName);
        String batch = LocalDate.now(ZONE) + "-" + UUID.randomUUID().toString().substring(0, 6);

        List<String> problems = new ArrayList<>();
        Map<String, Integer> byArea = new HashMap<>();
        Set<String> orderNos = new HashSet<>();
        int created = 0;
        int newCustomers = 0;
        int skipped = 0;

        for (Row r : rows) {
            if (!r.isValid()) {
                problems.add("Line " + r.lineNo() + ": " + r.problem());
                skipped++;
                continue;
            }
            Area area = areaByName.get(r.area().toLowerCase(Locale.ROOT));

            Optional<Customer> existing = customers.findFirstByPhoneOrderByIdAsc(r.phone());
            Customer customer;
            if (existing.isPresent()) {
                customer = existing.get();
            } else {
                Located at = locate(r.address(), area);
                customer = customers.save(new Customer(BRANCH_ID, r.name(), r.phone(),
                        Customer.CustomerType.valueOf(r.type()), r.address(),
                        at.latitude(), at.longitude(),
                        Customer.BuildingType.INDEPENDENT, (short) 0, false,
                        true, area.getId(), at.source()));
                newCustomers++;
            }

            orders.save(new DeliveryOrder(nextOrderNo(orderNos), customer.getId(), BRANCH_ID,
                    (short) r.cylinders(), fourDigits(), Instant.now(),
                    area.getId(), batch));
            created++;
            byArea.merge(area.getName(), 1, Integer::sum);
        }

        log.info("Imported batch {}: {} orders, {} new customers, {} skipped",
                batch, created, newCustomers, skipped);
        return new ImportResult(batch, created, newCustomers, skipped, problems, byArea);
    }

    /** The branch's localities, keyed by lower-case name so a typed area can be matched. */
    private Map<String, Area> activeAreas() {
        Map<String, Area> byName = new HashMap<>();
        for (Area a : areas.findByBranchIdAndActiveTrue(BRANCH_ID)) {
            byName.put(a.getName().toLowerCase(Locale.ROOT), a);
        }
        return byName;
    }

    /* ---------------- parsing ---------------- */

    /**
     * Reads every line into a row, valid or not. Preview and import both come
     * through here, so what the manager checks is what gets saved.
     */
    private List<Row> parse(String text, Map<String, Area> areaByName) {
        List<Row> rows = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return rows;
        }

        String[] lines = text.replace("\r", "").split("\n");
        boolean headerAllowed = true;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            // only the first line with content can be the header
            if (headerAllowed && line.toLowerCase(Locale.ROOT).startsWith("phone")) {
                headerAllowed = false;
                continue;
            }
            headerAllowed = false;

            String[] f = line.split(",", -1);
            int lineNo = i + 1;

            if (f.length < 3) {
                rows.add(bad(lineNo, "needs at least phone, name and area"));
                continue;
            }
            String phone = f[0].trim().replaceAll("[^0-9]", "");
            String name = f[1].trim();
            String typedArea = f[2].trim();
            String address = f.length > 3 && !f[3].isBlank() ? f[3].trim() : typedArea;
            String cylText = f.length > 4 ? f[4].trim() : "1";
            String type = f.length > 5 ? f[5].trim().toUpperCase(Locale.ROOT) : "DOMESTIC";

            if (phone.length() < 10)            { rows.add(bad(lineNo, "phone must be 10 digits")); continue; }
            if (name.isEmpty())                 { rows.add(bad(lineNo, "name is missing")); continue; }
            if (name.length() > MAX_NAME)       { rows.add(bad(lineNo, "name is too long")); continue; }
            if (typedArea.isEmpty())            { rows.add(bad(lineNo, "area is missing")); continue; }
            if (address.length() > MAX_ADDRESS) { rows.add(bad(lineNo, "address is too long")); continue; }

            Area area = areaByName.get(typedArea.toLowerCase(Locale.ROOT));
            if (area == null) {
                rows.add(bad(lineNo, "we do not deliver to " + typedArea));
                continue;
            }

            int cylinders;
            try {
                cylinders = cylText.isEmpty() ? 1 : Integer.parseInt(cylText);
            } catch (NumberFormatException e) {
                rows.add(bad(lineNo, cylText + " is not a cylinder count"));
                continue;
            }
            if (cylinders < 1 || cylinders > 20) {
                rows.add(bad(lineNo, "cylinders must be 1 to 20"));
                continue;
            }
            if (!type.equals("DOMESTIC") && !type.equals("COMMERCIAL")) {
                type = "DOMESTIC";
            }

            String source = customers.findFirstByPhoneOrderByIdAsc(phone)
                    .map(Customer::getLocationSource).orElse("AREA");

            rows.add(new Row(lineNo, phone, name, area.getName(), address,
                    cylinders, type, source, null));
        }
        return rows;
    }

    private static Row bad(int lineNo, String problem) {
        return new Row(lineNo, "", "", "", "", 0, "", "", problem);
    }

    /* ---------------- location ---------------- */

    /** Geocodes the address, falling back to the locality centre when that fails. */
    private Located locate(String address, Area area) {
        try {
            pauseForNominatim();
            String query = URLEncoder.encode(address + ", " + area.getName() + ", Hyderabad, India",
                    StandardCharsets.UTF_8);
            HttpResponse<String> res = http.send(
                    HttpRequest.newBuilder(URI.create(
                            "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" + query))
                            .header("User-Agent", "DropPoint/1.0")
                            .timeout(Duration.ofSeconds(6)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            String body = res.body();
            if (res.statusCode() == 200 && body.contains("\"lat\"")) {
                double lat = Double.parseDouble(between(body, "\"lat\":\"", "\""));
                double lon = Double.parseDouble(between(body, "\"lon\":\"", "\""));
                // sanity: a hit somewhere else in the world is worse than the locality centre
                if (lat > 17.0 && lat < 17.8 && lon > 78.0 && lon < 78.9) {
                    return new Located(lat, lon, "GEOCODED");
                }
            }
        } catch (Exception e) {
            log.debug("Geocoding failed for {}: {}", address, e.getMessage());
        }
        // Spread the fallbacks by up to ~400 m: thirty orders stacked on one
        // point would leave clustering with nothing to work with.
        double jitter = 0.004;
        return new Located(
                area.getLatitude() + ThreadLocalRandom.current().nextDouble(-jitter, jitter),
                area.getLongitude() + ThreadLocalRandom.current().nextDouble(-jitter, jitter),
                "AREA");
    }

    /** Keeps successive geocoding calls a second apart, as Nominatim's terms ask. */
    private void pauseForNominatim() throws InterruptedException {
        if (lastGeocodeAt > 0) {
            long waitMs = GEOCODE_GAP_MS - (System.currentTimeMillis() - lastGeocodeAt);
            if (waitMs > 0) {
                Thread.sleep(waitMs);
            }
        }
        lastGeocodeAt = System.currentTimeMillis();
    }

    private static String between(String s, String start, String end) {
        int a = s.indexOf(start) + start.length();
        return s.substring(a, s.indexOf(end, a));
    }

    /** An order number not already taken in the table or earlier in this batch. */
    private String nextOrderNo(Set<String> usedInBatch) {
        String prefix = "DP" + LocalDate.now(ZONE).toString().replace("-", "") + "-";
        for (int attempt = 0; attempt < 200; attempt++) {
            String candidate = prefix + ThreadLocalRandom.current().nextInt(1000, 10000);
            if (usedInBatch.add(candidate) && !orders.existsByOrderNo(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not find a free order number for today");
    }

    private static String fourDigits() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(1000, 10000));
    }
}
