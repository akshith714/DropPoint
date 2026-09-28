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

import com.droppoint.repo.Deliveries.DeliveryProof;
import com.droppoint.service.AssistantService;
import com.droppoint.service.DemoService;
import com.droppoint.service.AgentService;
import com.droppoint.service.IntakeService;
import com.droppoint.service.OrderService;

@RestController
@RequestMapping("/api")
public class ApiController {

    public record CheckInRequest(double latitude, double longitude, double accuracyMetres, boolean mockLocation) { }

    public record PingRequest(double latitude, double longitude, double accuracyMetres, boolean simulated) { }

    public record CreateOrderRequest(Long customerId, Integer cylinderCount) { }

    public record DeliverRequest(Long agentId, double latitude, double longitude, String code) { }

    public record FailRequest(Long agentId, String reason) { }

    public record AskRequest(String question) { }

    public record IntakeRequest(String text) { }

    private final AgentService agents;
    private final OrderService orders;
    private final AssistantService assistant;
    private final DemoService demo;
    private final IntakeService intake;

    public ApiController(AgentService agents, OrderService orders,
                         AssistantService assistant,
                         DemoService demo, IntakeService intake) {
        this.agents = agents;
        this.orders = orders;
        this.assistant = assistant;
        this.demo = demo;
        this.intake = intake;
    }

    // ---- endpoints ----

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("app", "DropPoint", "status", "up");
    }

    @PostMapping("/agents/{agentId}/check-in")
    public AgentService.CheckInResponse checkIn(@PathVariable Long agentId, @RequestBody CheckInRequest request) {
        return agents.checkIn(agentId, request.latitude(), request.longitude(),
                request.accuracyMetres(), request.mockLocation());
    }

    @PostMapping("/agents/{agentId}/check-out")
    public AgentService.CheckOutResponse checkOut(@PathVariable Long agentId) {
        return agents.checkOut(agentId);
    }

    @PostMapping("/agents/{agentId}/ping")
    public AgentService.PingResponse ping(@PathVariable Long agentId, @RequestBody PingRequest request) {
        return agents.recordPing(agentId, request.latitude(), request.longitude(),
                request.accuracyMetres(), request.simulated());
    }

    @PostMapping("/orders")
    public OrderService.OrderView createOrder(@RequestBody CreateOrderRequest r) {
        return orders.create(r.customerId(), r.cylinderCount() == null ? 1 : r.cylinderCount());
    }

    @GetMapping("/orders")
    public List<OrderService.OrderView> openOrders(@RequestParam(defaultValue = "1") Long branchId) {
        return orders.openOrders(branchId);
    }

    @PostMapping("/orders/{orderId}/assign/{agentId}")
    public OrderService.OrderView assign(@PathVariable Long orderId, @PathVariable Long agentId) {
        return orders.assign(orderId, agentId);
    }

    @PostMapping("/orders/{orderId}/refresh-eta")
    public OrderService.OrderView refreshEta(@PathVariable Long orderId) {
        return orders.refreshEta(orderId);
    }

    @PostMapping("/orders/{orderId}/deliver")
    public OrderService.DeliveryResult deliver(@PathVariable Long orderId, @RequestBody DeliverRequest r) {
        return orders.deliver(orderId, r.agentId(), r.latitude(), r.longitude(), r.code());
    }

    @PostMapping("/orders/{orderId}/fail")
    public OrderService.DeliveryResult fail(@PathVariable Long orderId, @RequestBody FailRequest r) {
        return orders.fail(orderId, r.agentId(), r.reason());
    }

    @GetMapping("/agents/{agentId}/orders")
    public List<OrderService.AgentOrderView> myOrders(@PathVariable Long agentId) {
        return orders.myOrders(agentId);
    }

    @GetMapping("/deliveries/suspicious")
    public List<DeliveryProof> suspicious() {
        return orders.suspiciousDeliveries();
    }

    @PostMapping("/admin/seed")
    public DemoService.SeedSummary seed(@RequestParam(defaultValue = "false") boolean force) {
        return demo.generate(force);
    }

    @PostMapping("/assistant/ask")
    public AssistantService.Answer ask(@RequestBody AskRequest r) {
        return assistant.ask(r.question());
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return assistant.dashboard();
    }

    @GetMapping("/customers")
    public List<Map<String, Object>> customers() {
        return assistant.customerList();
    }

    @GetMapping("/agents")
    public List<Map<String, Object>> agents() {
        return assistant.agentList();
    }

    @GetMapping("/areas")
    public List<Map<String, Object>> areas() {
        return assistant.areaList();
    }

    @PostMapping("/intake/preview")
    public IntakeService.Preview previewIntake(@RequestBody IntakeRequest r) {
        return intake.preview(r.text());
    }

    @PostMapping("/intake/import")
    public IntakeService.ImportResult importIntake(@RequestBody IntakeRequest r) {
        return intake.importOrders(r.text());
    }

    @PostMapping("/demo/reset")
    public Map<String, Object> demoReset() {
        return demo.reset();
    }

    @GetMapping("/demo/health")
    public Map<String, Object> demoHealth() {
        return demo.healthCheck();
    }

    @PostMapping("/admin/seed/clear")
    public Map<String, String> clearSeed() {
        demo.clear();
        return Map.of("status", "cleared");
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

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflictingState(IllegalStateException e) {
        return Map.of("error", String.valueOf(e.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(DataIntegrityViolationException e) {
        return Map.of("error", "This request conflicts with existing data. Please try again.");
    }
}
