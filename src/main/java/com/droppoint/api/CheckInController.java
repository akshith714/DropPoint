package com.droppoint.api;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.droppoint.service.CheckInService;

@RestController
public class CheckInController {

    public record CheckInRequest(double latitude, double longitude, double accuracyMetres, boolean mockLocation) { }

    private final CheckInService service;

    public CheckInController(CheckInService service) {
        this.service = service;
    }

    @PostMapping("/api/agents/{agentId}/check-in")
    public CheckInService.CheckInResponse checkIn(@PathVariable Long agentId, @RequestBody CheckInRequest request) {
        return service.checkIn(agentId, request.latitude(), request.longitude(),
                request.accuracyMetres(), request.mockLocation());
    }
}
