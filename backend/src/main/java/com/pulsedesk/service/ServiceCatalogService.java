package com.pulsedesk.service;

import com.pulsedesk.common.exception.ApiException;
import com.pulsedesk.common.security.AuthenticatedUser;
import com.pulsedesk.monitoring.HealthCheckConfig;
import com.pulsedesk.monitoring.HealthCheckConfigRepository;
import com.pulsedesk.monitoring.HealthCheckResult;
import com.pulsedesk.monitoring.HealthCheckResultRepository;
import com.pulsedesk.monitoring.HealthCheckRunner;
import com.pulsedesk.monitoring.HealthStatus;
import com.pulsedesk.monitoring.TargetValidator;
import com.pulsedesk.team.TeamRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServiceCatalogService {

    public record HealthView(UUID configId, String url, int expectedStatus, int intervalSeconds, int timeoutMs,
                             int failureThreshold, boolean enabled, HealthStatus status, int consecutiveFailures,
                             Instant lastCheckedAt, Instant lastStatusChangeAt) {
    }

    public record ServiceView(UUID id, String name, String description, UUID teamId, HealthView health) {
    }

    public record ResultView(Instant checkedAt, boolean success, Integer statusCode, long responseTimeMs,
                             String error) {
    }

    public record HealthCheckSettings(String url, int expectedStatus, int intervalSeconds, int timeoutMs,
                                      int failureThreshold, boolean enabled) {
    }

    private final MonitoredServiceRepository services;
    private final TeamRepository teams;
    private final HealthCheckConfigRepository configs;
    private final HealthCheckResultRepository results;
    private final HealthCheckRunner runner;
    private final TargetValidator targetValidator;

    public ServiceCatalogService(MonitoredServiceRepository services, TeamRepository teams,
                                 HealthCheckConfigRepository configs, HealthCheckResultRepository results,
                                 HealthCheckRunner runner, TargetValidator targetValidator) {
        this.services = services;
        this.teams = teams;
        this.configs = configs;
        this.results = results;
        this.runner = runner;
        this.targetValidator = targetValidator;
    }

    @Transactional
    public ServiceView create(AuthenticatedUser caller, String name, String description, UUID teamId) {
        String trimmed = name.trim();
        if (services.existsByOrganizationIdAndNameIgnoreCase(caller.organizationId(), trimmed)) {
            throw new ApiException(HttpStatus.CONFLICT, "Service name already exists");
        }
        requireTeam(caller, teamId);
        MonitoredService saved = services.save(
                new MonitoredService(caller.organizationId(), trimmed, description, teamId));
        return view(saved, null);
    }

    @Transactional(readOnly = true)
    public List<ServiceView> list(AuthenticatedUser caller) {
        List<MonitoredService> list = services.findByOrganizationIdAndActiveTrueOrderByName(caller.organizationId());
        Map<UUID, HealthCheckConfig> byService = configs
                .findByServiceIdIn(list.stream().map(MonitoredService::getId).toList()).stream()
                .collect(Collectors.toMap(HealthCheckConfig::getServiceId, Function.identity()));
        return list.stream().map(s -> view(s, byService.get(s.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public ServiceView get(AuthenticatedUser caller, UUID serviceId) {
        MonitoredService service = requireService(caller, serviceId);
        return view(service, configs.findByServiceIdAndOrganizationId(serviceId, caller.organizationId()).orElse(null));
    }

    /** Deactivates the service and stops its monitoring; history is kept. */
    @Transactional
    public void deactivate(AuthenticatedUser caller, UUID serviceId) {
        MonitoredService service = requireService(caller, serviceId);
        service.setActive(false);
        services.save(service);
        configs.findByServiceIdAndOrganizationId(serviceId, caller.organizationId()).ifPresent(c -> {
            c.setEnabled(false);
            configs.save(c);
        });
    }

    @Transactional
    public ServiceView configureHealthCheck(AuthenticatedUser caller, UUID serviceId, HealthCheckSettings s) {
        MonitoredService service = requireService(caller, serviceId);
        try {
            targetValidator.validate(s.url());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        HealthCheckConfig config = configs.findByServiceIdAndOrganizationId(serviceId, caller.organizationId())
                .orElseGet(() -> new HealthCheckConfig(caller.organizationId(), serviceId));
        config.apply(s.url().trim(), s.expectedStatus(), s.intervalSeconds(), s.timeoutMs(), s.failureThreshold(),
                s.enabled());
        return view(service, configs.save(config));
    }

    /** Runs the check immediately (outside the schedule) and returns the updated state. */
    public ServiceView runCheckNow(AuthenticatedUser caller, UUID serviceId) {
        requireService(caller, serviceId);
        HealthCheckConfig config = configs.findByServiceIdAndOrganizationId(serviceId, caller.organizationId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No health check configured"));
        runner.checkNow(config.getId());
        return get(caller, serviceId);
    }

    @Transactional(readOnly = true)
    public List<ResultView> results(AuthenticatedUser caller, UUID serviceId, int limit) {
        requireService(caller, serviceId);
        HealthCheckConfig config = configs.findByServiceIdAndOrganizationId(serviceId, caller.organizationId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No health check configured"));
        int bounded = Math.max(1, Math.min(limit, 200));
        return results.findByConfigIdAndOrganizationIdOrderByCheckedAtDesc(
                        config.getId(), caller.organizationId(), PageRequest.of(0, bounded)).stream()
                .map(this::resultView).toList();
    }

    private MonitoredService requireService(AuthenticatedUser caller, UUID serviceId) {
        return services.findByIdAndOrganizationIdAndActiveTrue(serviceId, caller.organizationId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Service not found"));
    }

    private void requireTeam(AuthenticatedUser caller, UUID teamId) {
        if (teamId != null && teams.findByIdAndOrganizationId(teamId, caller.organizationId()).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Team not found");
        }
    }

    private ServiceView view(MonitoredService s, HealthCheckConfig c) {
        HealthView health = c == null ? null : new HealthView(c.getId(), c.getUrl(), c.getExpectedStatus(),
                c.getIntervalSeconds(), c.getTimeoutMs(), c.getFailureThreshold(), c.isEnabled(), c.getStatus(),
                c.getConsecutiveFailures(), c.getLastCheckedAt(), c.getLastStatusChangeAt());
        return new ServiceView(s.getId(), s.getName(), s.getDescription(), s.getTeamId(), health);
    }

    private ResultView resultView(HealthCheckResult r) {
        return new ResultView(r.getCheckedAt(), r.isSuccess(), r.getStatusCode(), r.getResponseTimeMs(), r.getError());
    }
}
