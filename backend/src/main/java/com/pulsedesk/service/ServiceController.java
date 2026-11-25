package com.pulsedesk.service;

import com.pulsedesk.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/services")
public class ServiceController {

    public record CreateServiceRequest(@NotBlank @Size(max = 100) String name,
                                       @Size(max = 500) String description, UUID teamId) {
    }

    public record HealthCheckRequest(
            @NotBlank @Size(max = 500) String url,
            @Min(100) @Max(599) Integer expectedStatus,
            @Min(30) @Max(3600) Integer intervalSeconds,
            @Min(1000) @Max(30000) Integer timeoutMs,
            @Min(1) @Max(10) Integer failureThreshold,
            Boolean enabled) {
    }

    private static final String MANAGERS = "hasAnyRole('ORG_ADMIN','MANAGER')";

    private final ServiceCatalogService catalog;

    public ServiceController(ServiceCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<ServiceCatalogService.ServiceView> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return catalog.list(caller);
    }

    @GetMapping("/{serviceId}")
    public ServiceCatalogService.ServiceView get(@AuthenticationPrincipal AuthenticatedUser caller,
                                                 @PathVariable UUID serviceId) {
        return catalog.get(caller, serviceId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGERS)
    public ServiceCatalogService.ServiceView create(@AuthenticationPrincipal AuthenticatedUser caller,
                                                    @Valid @RequestBody CreateServiceRequest request) {
        return catalog.create(caller, request.name(), request.description(), request.teamId());
    }

    @DeleteMapping("/{serviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(MANAGERS)
    public void deactivate(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID serviceId) {
        catalog.deactivate(caller, serviceId);
    }

    @PutMapping("/{serviceId}/health-check")
    @PreAuthorize(MANAGERS)
    public ServiceCatalogService.ServiceView configure(@AuthenticationPrincipal AuthenticatedUser caller,
                                                       @PathVariable UUID serviceId,
                                                       @Valid @RequestBody HealthCheckRequest r) {
        return catalog.configureHealthCheck(caller, serviceId, new ServiceCatalogService.HealthCheckSettings(
                r.url(),
                r.expectedStatus() == null ? 200 : r.expectedStatus(),
                r.intervalSeconds() == null ? 60 : r.intervalSeconds(),
                r.timeoutMs() == null ? 5000 : r.timeoutMs(),
                r.failureThreshold() == null ? 3 : r.failureThreshold(),
                r.enabled() == null || r.enabled()));
    }

    @PostMapping("/{serviceId}/health-check/run")
    @PreAuthorize(MANAGERS)
    public ServiceCatalogService.ServiceView runNow(@AuthenticationPrincipal AuthenticatedUser caller,
                                                    @PathVariable UUID serviceId) {
        return catalog.runCheckNow(caller, serviceId);
    }

    @GetMapping("/{serviceId}/health-check/results")
    public List<ServiceCatalogService.ResultView> results(@AuthenticationPrincipal AuthenticatedUser caller,
                                                          @PathVariable UUID serviceId,
                                                          @RequestParam(defaultValue = "50") int limit) {
        return catalog.results(caller, serviceId, limit);
    }
}
