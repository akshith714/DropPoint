package com.droppoint.api;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.droppoint.core.AssignmentRanker;
import com.droppoint.service.CheckInService;
import com.droppoint.service.OrderService;
import com.droppoint.service.TrackingService;

@RestController
@RequestMapping("/api")
public class ApiController {

    public record CheckInRequest(double latitude, double longitude, double accuracyMetres, boolean mockLocation) { }

    public record PingRequest(double latitude, double longitude, double accuracyMetres, boolean simulated) { }

    public record SimulateRequest(double fromLat, double fromLon, double toLat, double toLon,
                                  Integer steps, Integer secondsPerStep) { }

    public record CreateOrderRequest(Long customerId, Integer cylinderCount) { }

    private final CheckInService checkIns;
    private final TrackingService tracking;
    private final OrderService orders;

    public ApiController(CheckInService checkIns, TrackingService tracking, OrderService orders) {
        this.checkIns = checkIns;
        this.tracking = tracking;
        this.orders = orders;
    }

    // ---- endpoints ----

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("app", "DropPoint", "status", "up");
    }

    @PostMapping("/agents/{agentId}/check-in")
    public CheckInService.CheckInResponse checkIn(@PathVariable Long agentId, @RequestBody CheckInRequest request) {
        return checkIns.checkIn(agentId, request.latitude(), request.longitude(),
                request.accuracyMetres(), request.mockLocation());
    }

    @PostMapping("/agents/{agentId}/check-out")
    public CheckInService.CheckOutResponse checkOut(@PathVariable Long agentId) {
        return checkIns.checkOut(agentId);
    }

    @PostMapping("/agents/{agentId}/ping")
    public TrackingService.PingResponse ping(@PathVariable Long agentId, @RequestBody PingRequest request) {
        return tracking.recordPing(agentId, request.latitude(), request.longitude(),
                request.accuracyMetres(), request.simulated());
    }

    @GetMapping("/tracking/live")
    public List<TrackingService.LivePosition> live() {
        return tracking.livePositions();
    }

    @PostMapping("/agents/{agentId}/simulate-route")
    public Map<String, Object> simulate(@PathVariable Long agentId, @RequestBody SimulateRequest r) {
        int steps = r.steps() == null ? 40 : r.steps();
        int seconds = r.secondsPerStep() == null ? 5 : r.secondsPerStep();
        int created = tracking.simulateRoute(agentId, r.fromLat(), r.fromLon(), r.toLat(), r.toLon(), steps, seconds);
        return Map.of("pingsCreated", created);
    }

    @PostMapping("/orders")
    public OrderService.OrderView createOrder(@RequestBody CreateOrderRequest r) {
        return orders.create(r.customerId(), r.cylinderCount() == null ? 1 : r.cylinderCount());
    }

    @GetMapping("/orders")
    public List<OrderService.OrderView> openOrders(@RequestParam(defaultValue = "1") Long branchId) {
        return orders.openOrders(branchId);
    }

    @GetMapping("/orders/{orderId}/suggest-agents")
    public List<AssignmentRanker.Ranked> suggestAgents(@PathVariable Long orderId) {
        return orders.suggestAgents(orderId);
    }

    @PostMapping("/orders/{orderId}/assign/{agentId}")
    public OrderService.OrderView assign(@PathVariable Long orderId, @PathVariable Long agentId) {
        return orders.assign(orderId, agentId);
    }

    @PostMapping("/orders/{orderId}/auto-assign")
    public OrderService.OrderView autoAssign(@PathVariable Long orderId) {
        return orders.autoAssign(orderId);
    }

    // ---- error handling ----

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("error", String.valueOf(e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(NoSuchElementException e) {
        return Map.of("error", String.valueOf(e.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(DataIntegrityViolationException e) {
        return Map.of("error", "This request conflicts with existing data. Please try again.");
    }
}
