package com.droppoint.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.droppoint.core.AssignmentRanker;
import com.droppoint.core.EtaCalculator;
import com.droppoint.core.GeoPoint;
import com.droppoint.repo.AppUser;
import com.droppoint.repo.AppUser.AppUserRepository;
import com.droppoint.repo.Attendance.AttendanceRepository;
import com.droppoint.repo.Customer;
import com.droppoint.repo.Customer.CustomerRepository;
import com.droppoint.repo.DeliveryOrder;
import com.droppoint.repo.DeliveryOrder.DeliveryOrderRepository;
import com.droppoint.repo.LocationPing;
import com.droppoint.repo.LocationPing.LocationPingRepository;

@Service
public class OrderService {

    private static final double WORKLOAD_WEIGHT_KM = 2.5;
    private static final int MAX_ORDERS_PER_AGENT = 6;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");
    private static final SecureRandom RANDOM = new SecureRandom();

    public record OrderView(Long id, String orderNo, String customerName, String address,
                            int cylinderCount, String status, Long agentId, String agentName,
                            String deliveryCode, Instant bookedAt,
                            Double travelMinutes, Double handoverMinutes, Double etaMinutes,
                            Instant etaAt, String etaSource, String etaExplanation) { }

    private final DeliveryOrderRepository orders;
    private final CustomerRepository customers;
    private final AppUserRepository users;
    private final AttendanceRepository attendances;
    private final LocationPingRepository pings;
    private final DistanceClient distances;

    public OrderService(DeliveryOrderRepository orders, CustomerRepository customers,
                        AppUserRepository users, AttendanceRepository attendances,
                        LocationPingRepository pings, DistanceClient distances) {
        this.orders = orders;
        this.customers = customers;
        this.users = users;
        this.attendances = attendances;
        this.pings = pings;
        this.distances = distances;
    }

    @Transactional
    public OrderView create(Long customerId, int cylinderCount) {
        if (cylinderCount < 1 || cylinderCount > 20) {
            throw new IllegalArgumentException("Cylinder count must be between 1 and 20");
        }
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new NoSuchElementException("Customer " + customerId + " not found"));
        if (!customer.isActive()) {
            throw new IllegalArgumentException("Customer " + customerId + " is not active");
        }

        Instant now = Instant.now();
        String orderNo = "DP" + LocalDate.ofInstant(now, BUSINESS_ZONE).toString().replace("-", "")
                + "-" + (1000 + RANDOM.nextInt(9000));
        String code = String.valueOf(1000 + RANDOM.nextInt(9000));

        DeliveryOrder saved = orders.save(new DeliveryOrder(orderNo, customer.getId(),
                customer.getBranchId(), (short) cylinderCount, code, now));

        return toView(saved, customer, null);
    }

    /** Ranks on-duty agents for this order, best first. */
    @Transactional(readOnly = true)
    public List<AssignmentRanker.Ranked> suggestAgents(Long orderId) {
        DeliveryOrder order = orders.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order " + orderId + " not found"));
        Customer customer = customers.findById(order.getCustomerId())
                .orElseThrow(() -> new NoSuchElementException("Customer not found"));

        GeoPoint destination = new GeoPoint(customer.getLatitude(), customer.getLongitude());
        LocalDate today = LocalDate.ofInstant(Instant.now(), BUSINESS_ZONE);

        List<AssignmentRanker.Candidate> candidates = new ArrayList<>();
        for (AppUser user : users.findAll()) {
            if (user.getRole() != AppUser.Role.AGENT || !user.isActive()
                    || !order.getBranchId().equals(user.getBranchId())) {
                continue;
            }
            var duty = attendances.findByAgentIdAndWorkDate(user.getId(), today);
            if (duty.isEmpty() || duty.get().getCheckOutAt() != null) {
                continue;   // not on duty
            }
            LocationPing last = pings.findTopByAgentIdOrderByRecordedAtDesc(user.getId());
            if (last == null) {
                continue;   // no known position yet
            }
            int active = (int) orders.countByAssignedAgentIdAndStatus(user.getId(), DeliveryOrder.Status.ASSIGNED);
            candidates.add(new AssignmentRanker.Candidate(user.getId(), user.getFullName(),
                    new GeoPoint(last.getLatitude(), last.getLongitude()), active));
        }

        return new AssignmentRanker(WORKLOAD_WEIGHT_KM, MAX_ORDERS_PER_AGENT).rank(destination, candidates);
    }

    @Transactional
    public OrderView assign(Long orderId, Long agentId) {
        DeliveryOrder order = orders.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order " + orderId + " not found"));
        AppUser agent = users.findById(agentId)
                .orElseThrow(() -> new NoSuchElementException("Agent " + agentId + " not found"));

        if (agent.getRole() != AppUser.Role.AGENT || !agent.isActive()) {
            throw new IllegalArgumentException("User " + agentId + " is not an active agent");
        }
        if (!order.getBranchId().equals(agent.getBranchId())) {
            throw new IllegalArgumentException("Agent " + agentId + " belongs to a different branch");
        }

        order.assignTo(agentId, Instant.now());   // saved by dirty checking

        Customer customer = customers.findById(order.getCustomerId()).orElseThrow();
        applyEta(order, customer, agentId);
        return toView(order, customer, agent.getFullName());
    }

    /** Assigns the order to the best-ranked agent. */
    @Transactional
    public OrderView autoAssign(Long orderId) {
        List<AssignmentRanker.Ranked> ranked = suggestAgents(orderId);
        if (ranked.isEmpty()) {
            throw new IllegalArgumentException("No agent is available for this order right now");
        }
        return assign(orderId, ranked.get(0).agentId());
    }

    @Transactional(readOnly = true)
    public List<OrderView> openOrders(Long branchId) {
        List<OrderView> out = new ArrayList<>();
        for (DeliveryOrder o : orders.findByBranchIdAndStatusInOrderByBookedAtDesc(branchId,
                List.of(DeliveryOrder.Status.BOOKED, DeliveryOrder.Status.ASSIGNED))) {
            Customer customer = customers.findById(o.getCustomerId()).orElseThrow();
            String agentName = o.getAssignedAgentId() == null ? null
                    : users.findById(o.getAssignedAgentId()).map(AppUser::getFullName).orElse(null);
            out.add(toView(o, customer, agentName));
        }
        return out;
    }

    @Transactional
    public OrderView refreshEta(Long orderId) {
        DeliveryOrder order = orders.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order " + orderId + " not found"));
        if (order.getAssignedAgentId() == null) {
            throw new IllegalArgumentException("Order " + orderId + " has no agent assigned");
        }
        Customer customer = customers.findById(order.getCustomerId()).orElseThrow();
        applyEta(order, customer, order.getAssignedAgentId());
        String agentName = users.findById(order.getAssignedAgentId())
                .map(AppUser::getFullName).orElse(null);
        return toView(order, customer, agentName);
    }

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
}
