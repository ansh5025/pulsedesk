package com.pulsedesk.incident;

import com.pulsedesk.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    public record CreateIncidentRequest(@NotBlank @Size(max = 200) String title,
                                        @Size(max = 2000) String description, IncidentPriority priority,
                                        UUID serviceId, UUID teamId, UUID assigneeId) {
    }

    public record StatusRequest(@NotNull IncidentStatus status) {
    }

    public record AssignRequest(@NotNull UUID assigneeId) {
    }

    public record CommentRequest(@NotBlank @Size(max = 2000) String body) {
    }

    private final IncidentService service;

    public IncidentController(IncidentService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ORG_ADMIN','MANAGER','TEAM_LEAD')")
    public IncidentService.IncidentView create(@AuthenticationPrincipal AuthenticatedUser caller,
                                               @Valid @RequestBody CreateIncidentRequest r) {
        return service.create(caller, new IncidentService.CreateCommand(r.title(), r.description(), r.priority(),
                r.serviceId(), r.teamId(), r.assigneeId()));
    }

    @GetMapping
    public List<IncidentService.IncidentView> list(@AuthenticationPrincipal AuthenticatedUser caller,
                                                   @RequestParam(required = false) IncidentStatus status,
                                                   @RequestParam(required = false) UUID serviceId,
                                                   @RequestParam(defaultValue = "50") int limit) {
        return service.list(caller, status, serviceId, limit);
    }

    @GetMapping("/{id}")
    public IncidentService.IncidentView get(@AuthenticationPrincipal AuthenticatedUser caller,
                                            @PathVariable UUID id) {
        return service.get(caller, id);
    }

    @PutMapping("/{id}/status")
    public IncidentService.IncidentView status(@AuthenticationPrincipal AuthenticatedUser caller,
                                               @PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        return service.changeStatus(caller, id, r.status());
    }

    @PutMapping("/{id}/assignee")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','MANAGER','TEAM_LEAD')")
    public IncidentService.IncidentView assign(@AuthenticationPrincipal AuthenticatedUser caller,
                                               @PathVariable UUID id, @Valid @RequestBody AssignRequest r) {
        return service.assign(caller, id, r.assigneeId());
    }

    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public IncidentService.CommentView comment(@AuthenticationPrincipal AuthenticatedUser caller,
                                               @PathVariable UUID id, @Valid @RequestBody CommentRequest r) {
        return service.comment(caller, id, r.body());
    }

    @GetMapping("/{id}/comments")
    public List<IncidentService.CommentView> comments(@AuthenticationPrincipal AuthenticatedUser caller,
                                                      @PathVariable UUID id) {
        return service.comments(caller, id);
    }

    @GetMapping("/{id}/history")
    public List<IncidentService.HistoryView> history(@AuthenticationPrincipal AuthenticatedUser caller,
                                                     @PathVariable UUID id) {
        return service.history(caller, id);
    }
}
