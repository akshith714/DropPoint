package com.droppoint.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.format.annotation.DateTimeFormat;
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

import com.droppoint.service.AgentAdminService;
import com.droppoint.service.AgentService;
import com.droppoint.service.AssistantService;
import com.droppoint.service.AuthService;
import com.droppoint.service.IntakeService;
import com.droppoint.service.OrderService;
import com.droppoint.service.PlanService;

@RestController
@RequestMapping("/api")
public class ApiController {

    public record CheckInRequest(double latitude, double longitude, double accuracyMetres, boolean mockLocation) { }

    public record PingRequest(double latitude, double longitude, double accuracyMetres, boolean simulated) { }

    public record DeliverRequest(Long agentId, double latitude, double longitude, String code) { }

    public record FailRequest(Long agentId, String reason) { }

    public record AskRequest(String question, String role, Long agentId) { }

    public record IntakeRequest(String text) { }

    public record MoveRequest(Long assignmentId, Long agentId) { }

    public record SosRequest(Double latitude, Double longitude) { }

    public record AcknowledgeRequest(Long managerId) { }

    private final AgentService agents;
    private final OrderService orders;
    private final AssistantService assistant;
    private final IntakeService intake;
    private final PlanService plans;
    private final AgentAdminService agentAdmin;
    private final AuthService auth;

    public ApiController(AgentService agents, OrderService orders,
                         AssistantService assistant,
                         IntakeService intake,
                         PlanService plans, AgentAdminService agentAdmin,
                         AuthService auth) {
        this.agents = agents;
        this.orders = orders;
        this.assistant = assistant;
        this.intake = intake;
        this.plans = plans;
        this.agentAdmin = agentAdmin;
        this.auth = auth;
    }

    // ---- endpoints ----

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("app", "DropPoint", "status", "up");
    }

    @PostMapping("/auth/login")
    public AuthService.UserView login(@RequestBody AuthService.LoginRequest r) {
        return auth.login(r.phone(), r.pin());
    }

    @GetMapping("/admin/users")
    public List<AuthService.UserView> listUsers() {
        return auth.listAllUsers();
    }

    @PostMapping("/admin/users")
    public AuthService.UserView createUser(@RequestBody AuthService.CreateUserRequest r) {
        return auth.createUser(r.name(), r.phone(), r.role(), r.pin());
    }

    @PostMapping("/admin/users/{id}/delete")
    public Map<String, String> deleteUser(@PathVariable Long id) {
        auth.deleteUser(id);
        return Map.of("status", "deleted");
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

    @PostMapping("/agents/{agentId}/sos")
    public AgentService.SosResponse triggerSos(@PathVariable Long agentId, @RequestBody SosRequest request) {
        return agents.triggerSos(agentId, request.latitude(), request.longitude());
    }

    @GetMapping("/sos/active")
    public List<AgentService.SosAlertView> activeAlerts() {
        return agents.getActiveAlerts();
    }

    @PostMapping("/sos/{alertId}/acknowledge")
    public AgentService.SosAlertView acknowledgeSos(@PathVariable Long alertId, @RequestBody AcknowledgeRequest request) {
        return agents.acknowledgeSos(alertId, request.managerId());
    }

    @GetMapping("/agents/locations")
    public List<AgentService.AgentLocationView> agentLocations() {
        return agents.getAgentLocations();
    }

    @GetMapping("/agents/{agentId}/status")
    public AgentService.AgentStatusView agentStatus(@PathVariable Long agentId) {
        return agents.getAgentStatus(agentId);
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

    @PostMapping("/assistant/ask")
    public AssistantService.Answer ask(@RequestBody AskRequest r) {
        return assistant.ask(r.question(), r.role(), r.agentId());
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

    @GetMapping("/admin/agents")
    public List<AgentAdminService.AgentItem> listAdminAgents() {
        return agentAdmin.listAgents();
    }

    @PostMapping("/admin/agents")
    public AgentAdminService.AgentItem createAgent(@RequestBody AgentAdminService.CreateAgentRequest r) {
        return agentAdmin.addAgent(r.name(), r.phone(), r.cap(), r.preferredAreaId(), r.preferredAreaIds());
    }

    @PostMapping("/admin/agents/{id}")
    public AgentAdminService.AgentItem updateAgent(@PathVariable Long id, @RequestBody AgentAdminService.UpdateAgentRequest r) {
        return agentAdmin.updateAgent(id, r.name(), r.phone(), r.cap(), r.available(), r.preferredAreaId(), r.preferredAreaIds());
    }

    @PostMapping("/admin/agents/{id}/delete")
    public Map<String, String> deleteAgent(@PathVariable Long id) {
        agentAdmin.deleteAgent(id);
        return Map.of("status", "deleted");
    }

    @GetMapping("/plan/progress")
    public PlanService.ProgressReport getProgress(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return plans.progress(date);
    }

    @GetMapping("/plan")
    public PlanService.PlanView getPlan() {
        return plans.today();
    }

    @PostMapping("/plan")
    public PlanService.PlanView makePlan() {
        return plans.plan();
    }

    @PostMapping("/plan/move")
    public PlanService.PlanView movePlan(@RequestBody MoveRequest r) {
        return plans.move(r.assignmentId(), r.agentId());
    }

    @PostMapping("/plan/confirm")
    public Map<String, Object> confirmPlan() {
        return plans.confirm();
    }

    @PostMapping("/plan/reopen")
    public Map<String, String> reopenPlan() {
        plans.reopen();
        return Map.of("status", "reopened");
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
