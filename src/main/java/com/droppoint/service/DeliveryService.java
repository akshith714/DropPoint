package com.droppoint.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.droppoint.core.GeoPoint;
import com.droppoint.core.ProofPolicy;
import com.droppoint.repo.Customer;
import com.droppoint.repo.Customer.CustomerRepository;
import com.droppoint.repo.DeliveryOrder;
import com.droppoint.repo.DeliveryOrder.DeliveryOrderRepository;
import com.droppoint.repo.DeliveryProof;
import com.droppoint.repo.DeliveryProof.DeliveryProofRepository;

@Service
public class DeliveryService {

    /** Beyond this distance from the address a delivery is flagged, not blocked. */
    private static final double MAX_PROOF_DISTANCE_M = 150;

    public record DeliveryResult(boolean accepted, String status, String message,
                                 long distanceMetres, Double lateMinutes, String orderStatus) { }

    public record AgentOrderView(Long orderId, String orderNo, String customerName, String address,
                                 String phone, int cylinderCount, String deliveryCode,
                                 Double etaMinutes, Instant etaAt, String etaExplanation,
                                 double latitude, double longitude) { }

    private final DeliveryOrderRepository orders;
    private final CustomerRepository customers;
    private final DeliveryProofRepository proofs;

    public DeliveryService(DeliveryOrderRepository orders, CustomerRepository customers,
                           DeliveryProofRepository proofs) {
        this.orders = orders;
        this.customers = customers;
        this.proofs = proofs;
    }

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
        proofs.save(new DeliveryProof(order.getId(), agentId, now, latitude, longitude,
                result.distanceMetres(), true, result.verdict().name(), lateMinutes));

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
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A failure reason is required");
        }
        order.markFailed(reason.trim().toUpperCase(), Instant.now());
        return new DeliveryResult(true, "FAILED", "Marked as not delivered: " + reason,
                0, null, order.getStatus().name());
    }

    /** Today's work list for one agent: assigned orders with everything needed at the door. */
    @Transactional(readOnly = true)
    public List<AgentOrderView> myOrders(Long agentId) {
        List<AgentOrderView> out = new ArrayList<>();
        for (DeliveryOrder o : orders.findByAssignedAgentIdAndStatusOrderByAssignedAtAsc(
                agentId, DeliveryOrder.Status.ASSIGNED)) {
            Customer c = customers.findById(o.getCustomerId()).orElseThrow();
            out.add(new AgentOrderView(o.getId(), o.getOrderNo(), c.getName(), c.getAddressLine(),
                    c.getPhone(), o.getCylinderCount(), o.getDeliveryCode(),
                    o.getEtaMinutes(), o.getEtaAt(), o.getEtaExplanation(),
                    c.getLatitude(), c.getLongitude()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<DeliveryProof> suspiciousDeliveries() {
        return proofs.findByVerdictOrderByRecordedAtDesc(ProofPolicy.Verdict.SUSPICIOUS.name());
    }
}
