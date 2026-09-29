package com.droppoint.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.droppoint.core.GeoPoint;
import com.droppoint.core.Rules.EtaCalculator;
import com.droppoint.core.Rules.ProofPolicy;
import com.droppoint.repo.Deliveries.Area;
import com.droppoint.repo.Deliveries.Area.AreaRepository;
import com.droppoint.repo.Workforce.AppUser;
import com.droppoint.repo.Workforce.AppUser.AppUserRepository;
import com.droppoint.repo.Deliveries.Customer;
import com.droppoint.repo.Deliveries.Customer.CustomerRepository;
import com.droppoint.repo.Deliveries.DeliveryOrder;
import com.droppoint.repo.Deliveries.DeliveryOrder.DeliveryOrderRepository;
import com.droppoint.repo.Workforce.LocationPing;
import com.droppoint.repo.Workforce.LocationPing.LocationPingRepository;

/**
 * The life of an order: booking, assigning an agent, the ETA that follows from
 * the assignment, and the proof of delivery that closes it.
 * Travel time comes from the nested DistanceClient.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** Beyond this distance from the address a delivery is flagged, not blocked. */
    private static final double MAX_PROOF_DISTANCE_M = 150;

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The values the orders.failure_reason check constraint accepts. */
    private static final List<String> FAILURE_REASONS =
            List.of("NOBODY_HOME", "WRONG_ADDRESS", "CUSTOMER_REFUSED", "NO_ACCESS", "OTHER");

    public record OrderView(Long id, String orderNo, String customerName, String address,
                            int cylinderCount, String status, Long agentId, String agentName,
                            String deliveryCode, Instant bookedAt,
                            Double travelMinutes, Double handoverMinutes, Double etaMinutes,
                            Instant etaAt, String etaSource, String etaExplanation) { }

    public record DeliveryResult(boolean accepted, String status, String message,
                                 long distanceMetres, Double lateMinutes, String orderStatus) { }

    public record AgentOrderView(Long id, Long orderId, String orderNo, String customerName, String address,
                                 String phone, String customerPhone, int cylinderCount, String deliveryCode,
                                 Double etaMinutes, Instant etaAt, String etaExplanation,
                                 double latitude, double longitude, String status, String areaName) { }

    private final DeliveryOrderRepository orders;
    private final CustomerRepository customers;
    private final AppUserRepository users;
    private final LocationPingRepository pings;
    private final AreaRepository areas;
    private final DistanceClient distances;

    public OrderService(DeliveryOrderRepository orders, CustomerRepository customers,
                        AppUserRepository users, LocationPingRepository pings,
                        AreaRepository areas,
                        @Value("${droppoint.google.api-key:}") String googleApiKey) {
        this.orders = orders;
        this.customers = customers;
        this.users = users;
        this.pings = pings;
        this.areas = areas;
        this.distances = new DistanceClient(googleApiKey);
    }

    // ---- delivery ----

    @Transactional
    public DeliveryResult deliver(Long orderId, Long agentId, double latitude, double longitude,
                                  String enteredCode) {
        DeliveryOrder order = orders.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order " + orderId + " not found"));
        if (order.getStatus() != DeliveryOrder.Status.ASSIGNED) {
            return new DeliveryResult(false, "NOT_DELIVERABLE",
                    "This order is " + order.getStatus() + ".", 0, null, order.getStatus().name());
        }

        Customer customer = customers.findById(order.getCustomerId()).orElseThrow();
        GeoPoint customerAt = new GeoPoint(customer.getLatitude(), customer.getLongitude());
        GeoPoint agentAt = new GeoPoint(latitude, longitude);

        ProofPolicy.Result result = new ProofPolicy(MAX_PROOF_DISTANCE_M).evaluate(
                customerAt, agentAt, order.getAssignedAgentId(), agentId,
                order.getDeliveryCode(), enteredCode);

        long distance = Math.round(result.distanceMetres());

        if (!result.accepted()) {
            return new DeliveryResult(false, result.rejection().name(), result.note(),
                    distance, null, order.getStatus().name());
        }

        Instant now = Instant.now();
        Double lateMinutes = order.getEtaAt() == null ? null
                : Math.round(Duration.between(order.getEtaAt(), now).toSeconds() / 6.0) / 10.0;

        order.markDelivered(now);

        return new DeliveryResult(true, result.verdict().name(), result.note(),
                distance, lateMinutes, order.getStatus().name());
    }

    @Transactional
    public DeliveryResult fail(Long orderId, Long agentId, String reason) {
        DeliveryOrder order = orders.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order " + orderId + " not found"));
        if (!agentId.equals(order.getAssignedAgentId())) {
            throw new IllegalArgumentException("This order is assigned to another agent");
        }
        String code = reason == null ? "" : reason.trim().toUpperCase().replace(' ', '_');
        if (!FAILURE_REASONS.contains(code)) {
            throw new IllegalArgumentException("Failure reason must be one of " + FAILURE_REASONS);
        }
        order.markFailed(code, Instant.now());
        return new DeliveryResult(true, "FAILED", "Marked as not delivered: " + code,
                0, null, order.getStatus().name());
    }

    /** Today's work list for one agent: assigned orders with everything needed at the door. */
    @Transactional(readOnly = true)
    public List<AgentOrderView> myOrders(Long agentId) {
        List<AgentOrderView> out = new ArrayList<>();
        Instant startOfToday = LocalDate.now(BUSINESS_ZONE).atStartOfDay(BUSINESS_ZONE).toInstant();

        List<DeliveryOrder> assignedOrders = orders.findByAssignedAgentId(agentId);
        for (DeliveryOrder o : assignedOrders) {
            boolean isAssigned = o.getStatus() == DeliveryOrder.Status.ASSIGNED;
            boolean isCompletedToday = (o.getStatus() == DeliveryOrder.Status.DELIVERED || o.getStatus() == DeliveryOrder.Status.FAILED)
                    && o.getDeliveredAt() != null && o.getDeliveredAt().isAfter(startOfToday);

            if (isAssigned || isCompletedToday) {
                Customer c = customers.findById(o.getCustomerId()).orElse(null);
                if (c == null) continue;

                String areaName = "";
                if (c.getAreaId() != null) {
                    areaName = areas.findById(c.getAreaId()).map(Area::getName).orElse("");
                }

                out.add(new AgentOrderView(
                        o.getId(),
                        o.getId(),
                        o.getOrderNo(),
                        c.getName(),
                        c.getAddressLine(),
                        c.getPhone(),
                        c.getPhone(),
                        o.getCylinderCount(),
                        o.getDeliveryCode(),
                        o.getEtaMinutes(),
                        o.getEtaAt(),
                        o.getEtaExplanation(),
                        c.getLatitude(),
                        c.getLongitude(),
                        o.getStatus().name(),
                        areaName
                ));
            }
        }
        out.sort(Comparator.comparing((AgentOrderView v) -> "ASSIGNED".equals(v.status()) ? 0 : 1));
        return out;
    }

    // ---- shared ----

    /** Works out the ETA from the agent's current position to the customer. */
    private void applyEta(DeliveryOrder order, Customer customer, Long agentId) {
        LocationPing last = pings.findTopByAgentIdOrderByRecordedAtDesc(agentId);
        if (last == null) {
            return;   // no position yet, no ETA
        }
        GeoPoint agentAt = new GeoPoint(last.getLatitude(), last.getLongitude());
        GeoPoint destination = new GeoPoint(customer.getLatitude(), customer.getLongitude());

        DistanceClient.Travel travel = distances.travelBetween(agentAt, destination);

        EtaCalculator.Estimate estimate = EtaCalculator.estimate(travel.minutes(),
                new EtaCalculator.DropContext(
                        EtaCalculator.BuildingType.valueOf(customer.getBuildingType().name()),
                        customer.getFloorNo(), customer.isHasLift(), order.getCylinderCount()));

        order.setEta(estimate.travelMinutes(), estimate.handoverMinutes(), estimate.totalMinutes(),
                Instant.now().plusSeconds(Math.round(estimate.totalMinutes() * 60)),
                travel.source(), estimate.explanation());
    }

    private static OrderView toView(DeliveryOrder o, Customer c, String agentName) {
        return new OrderView(o.getId(), o.getOrderNo(), c.getName(), c.getAddressLine(),
                o.getCylinderCount(), o.getStatus().name(), o.getAssignedAgentId(), agentName,
                o.getDeliveryCode(), o.getBookedAt(),
                o.getTravelMinutes(), o.getHandoverMinutes(), o.getEtaMinutes(),
                o.getEtaAt(), o.getEtaSource(), o.getEtaExplanation());
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    /**
     * Travel time between two points. Uses the Google Distance Matrix API when a key
     * is configured, and falls back to a straight-line estimate when it is not.
     */
    static final class DistanceClient {

        /** Roads are longer than straight lines; typical Indian city factor. */
        private static final double ROAD_FACTOR = 1.35;
        /** Average city speed including traffic and signals. */
        private static final double CITY_SPEED_KMPH = 22.0;

        record Travel(double minutes, double km, String source) { }

        private final String apiKey;
        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build();

        DistanceClient(String apiKey) {
            this.apiKey = apiKey;
        }

        Travel travelBetween(GeoPoint from, GeoPoint to) {
            if (from == null || to == null) {
                throw new IllegalArgumentException("Both points are required");
            }
            if (apiKey != null && !apiKey.isBlank()) {
                try {
                    return callGoogle(from, to);
                } catch (Exception e) {
                    log.warn("Google Distance Matrix failed ({}), using estimate", e.getMessage());
                }
            }
            return estimate(from, to);
        }

        private Travel estimate(GeoPoint from, GeoPoint to) {
            double km = from.distanceTo(to) / 1000.0 * ROAD_FACTOR;
            double minutes = km / CITY_SPEED_KMPH * 60;
            return new Travel(round1(minutes), round1(km), "ESTIMATE");
        }

        private Travel callGoogle(GeoPoint from, GeoPoint to) throws Exception {
            String url = "https://maps.googleapis.com/maps/api/distancematrix/json"
                    + "?origins=" + from.latitude() + "," + from.longitude()
                    + "&destinations=" + to.latitude() + "," + to.longitude()
                    + "&mode=driving&departure_time=now&key=" + apiKey;

            HttpResponse<String> res = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            String body = res.body();
            if (res.statusCode() != 200 || !body.contains("\"status\" : \"OK\"")) {
                throw new IllegalStateException("HTTP " + res.statusCode());
            }
            double seconds = readNumber(body, "duration_in_traffic", "duration");
            double metres = readNumber(body, "distance", null);
            return new Travel(round1(seconds / 60.0), round1(metres / 1000.0), "GOOGLE");
        }

        /** Reads the "value" field that follows the given key in the JSON response. */
        private static double readNumber(String json, String key, String fallbackKey) {
            int at = json.indexOf("\"" + key + "\"");
            if (at < 0 && fallbackKey != null) {
                at = json.indexOf("\"" + fallbackKey + "\"");
            }
            if (at < 0) {
                throw new IllegalStateException("Missing " + key + " in response");
            }
            int valueAt = json.indexOf("\"value\"", at);
            int colon = json.indexOf(':', valueAt);
            int end = colon + 1;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == ' ')) {
                end++;
            }
            return Double.parseDouble(json.substring(colon + 1, end).trim());
        }
    }
}
