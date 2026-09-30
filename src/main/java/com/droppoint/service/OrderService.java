package com.droppoint.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.droppoint.core.Rules.ProofPolicy;
import com.droppoint.repo.Deliveries.Area;
import com.droppoint.repo.Deliveries.Area.AreaRepository;
import com.droppoint.repo.Deliveries.Customer;
import com.droppoint.repo.Deliveries.Customer.CustomerRepository;
import com.droppoint.repo.Deliveries.DeliveryOrder;
import com.droppoint.repo.Deliveries.DeliveryOrder.DeliveryOrderRepository;
import com.droppoint.repo.Workforce.AppUser;
import com.droppoint.repo.Workforce.AppUser.AppUserRepository;

@Service
public class OrderService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    private static final List<String> FAILURE_REASONS =
            List.of("NOBODY_HOME", "WRONG_ADDRESS", "CUSTOMER_REFUSED", "NO_ACCESS", "OTHER");

    public record OrderView(Long id, String orderNo, String customerName, String address,
                            int cylinderCount, String status, Long agentId, String agentName,
                            String deliveryCode, Instant bookedAt) { }

    public record DeliveryResult(boolean accepted, String status, String message, String orderStatus) { }

    public record AgentOrderView(Long id, String orderNo, String customerName, String address,
                                 String customerPhone, int cylinderCount, String status, String areaName) { }

    private final DeliveryOrderRepository orders;
    private final CustomerRepository customers;
    private final AppUserRepository users;
    private final AreaRepository areas;

    public OrderService(DeliveryOrderRepository orders, CustomerRepository customers,
                        AppUserRepository users, AreaRepository areas) {
        this.orders = orders;
        this.customers = customers;
        this.users = users;
        this.areas = areas;
    }

    @Transactional
    public DeliveryResult deliver(Long orderId, Long agentId, String enteredCode) {
        DeliveryOrder order = orders.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order " + orderId + " not found"));
        if (order.getStatus() != DeliveryOrder.Status.ASSIGNED) {
            return new DeliveryResult(false, "NOT_DELIVERABLE",
                    "This order is " + order.getStatus() + ".", order.getStatus().name());
        }

        ProofPolicy.Result result = ProofPolicy.evaluate(order.getAssignedAgentId(), agentId,
                order.getDeliveryCode(), enteredCode);
        if (!result.accepted()) {
            return new DeliveryResult(false, result.rejection().name(), result.note(), order.getStatus().name());
        }

        order.markDelivered(Instant.now());
        return new DeliveryResult(true, "DELIVERED", result.note(), order.getStatus().name());
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
        return new DeliveryResult(true, "FAILED", "Marked as not delivered: " + code, order.getStatus().name());
    }

    @Transactional(readOnly = true)
    public List<AgentOrderView> myOrders(Long agentId) {
        List<AgentOrderView> out = new ArrayList<>();
        Instant startOfToday = LocalDate.now(BUSINESS_ZONE).atStartOfDay(BUSINESS_ZONE).toInstant();

        for (DeliveryOrder o : orders.findByAssignedAgentId(agentId)) {
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

                out.add(new AgentOrderView(o.getId(), o.getOrderNo(), c.getName(), c.getAddressLine(),
                        c.getPhone(), o.getCylinderCount(), o.getStatus().name(), areaName));
            }
        }
        out.sort(Comparator.comparing((AgentOrderView v) -> "ASSIGNED".equals(v.status()) ? 0 : 1));
        return out;
    }

    @Transactional(readOnly = true)
    public List<OrderView> openOrders() {
        List<OrderView> out = new ArrayList<>();
        for (DeliveryOrder o : orders.findByBranchIdAndStatusInOrderByBookedAtDesc(1L,
                List.of(DeliveryOrder.Status.ASSIGNED))) {
            Customer c = customers.findById(o.getCustomerId()).orElse(null);
            if (c == null) continue;
            String agentName = o.getAssignedAgentId() == null ? null
                    : users.findById(o.getAssignedAgentId()).map(AppUser::getFullName).orElse(null);
            out.add(new OrderView(o.getId(), o.getOrderNo(), c.getName(), c.getAddressLine(),
                    o.getCylinderCount(), o.getStatus().name(), o.getAssignedAgentId(), agentName,
                    o.getDeliveryCode(), o.getBookedAt()));
        }
        out.sort(Comparator.comparing(OrderView::agentName, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(OrderView::orderNo));
        return out;
    }
}
