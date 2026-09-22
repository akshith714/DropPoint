package com.droppoint.api;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.droppoint.service.CheckInService;

@RestController
@RequestMapping("/api")
public class ApiController {

    public record CheckInRequest(double latitude, double longitude, double accuracyMetres, boolean mockLocation) { }

    private final CheckInService checkIns;

    public ApiController(CheckInService checkIns) {
        this.checkIns = checkIns;
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
