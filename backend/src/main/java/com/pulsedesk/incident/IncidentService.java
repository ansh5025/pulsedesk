package com.pulsedesk.incident;

import com.pulsedesk.common.exception.ApiException;
import com.pulsedesk.common.security.AuthenticatedUser;
import com.pulsedesk.membership.OrganizationMember;
import com.pulsedesk.membership.OrganizationMemberRepository;
import com.pulsedesk.membership.Role;
import com.pulsedesk.notification.NotificationService;
import com.pulsedesk.service.MonitoredService;
import com.pulsedesk.service.MonitoredServiceRepository;
import com.pulsedesk.team.Team;
import com.pulsedesk.team.TeamRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentService {

    public record SlaView(int responseMinutes, int resolutionMinutes, Instant responseDueAt, Instant resolutionDueAt,
                          boolean responseBreached, boolean resolutionBreached) {
    }

    public record IncidentView(UUID id, UUID serviceId, UUID teamId, String title, String description,
                               IncidentStatus status, IncidentPriority priority, IncidentSource source,
                               UUID assigneeId, UUID createdById, Instant createdAt, Instant acknowledgedAt,
                               Instant resolvedAt, Instant closedAt, SlaView sla, int escalationLevel,
                               Instant responseEscalatedAt, Instant resolutionEscalatedAt) {
    }

    public record CommentView(UUID id, UUID authorId, String body, Instant createdAt) {
    }

    public record HistoryView(UUID actorId, String type, String from, String to, String note, Instant at) {
    }

    public record CreateCommand(String title, String description, IncidentPriority priority, UUID serviceId,
                                UUID teamId, UUID assigneeId) {
    }

    private static final String AUTOMATION_NOTE_DOWN = "Opened automatically by health check";
    private static final List<IncidentStatus> ACTIVE = List.of(IncidentStatus.OPEN, IncidentStatus.ACKNOWLEDGED,
            IncidentStatus.IN_PROGRESS);

    private final IncidentRepository incidents;
    private final IncidentCommentRepository comments;
    private final IncidentHistoryRepository history;
    private final MonitoredServiceRepository services;
    private final OrganizationMemberRepository members;
    private final TeamRepository teams;
    private final NotificationService notifications;

    public IncidentService(IncidentRepository incidents, IncidentCommentRepository comments,
                           IncidentHistoryRepository history, MonitoredServiceRepository services,
                           OrganizationMemberRepository members, TeamRepository teams,
                           NotificationService notifications) {
        this.incidents = incidents;
        this.comments = comments;
        this.history = history;
        this.services = services;
        this.members = members;
        this.teams = teams;
        this.notifications = notifications;
    }

    @Transactional
    public IncidentView create(AuthenticatedUser caller, CreateCommand cmd) {
        UUID orgId = caller.organizationId();
        UUID teamId = cmd.teamId();
        if (cmd.serviceId() != null) {
            MonitoredService service = services.findByIdAndOrganizationIdAndActiveTrue(cmd.serviceId(), orgId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Service not found"));
            if (teamId == null) {
                teamId = service.getTeamId();
            }
        }
        if (teamId == null && caller.role() == Role.TEAM_LEAD) {
            teamId = teams.findByTeamLeadId(caller.membershipId()).map(Team::getId).orElse(null);
        }
        if (teamId != null && teams.findByIdAndOrganizationId(teamId, orgId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Team not found");
        }
        UUID assigneeId = null;
        if (cmd.assigneeId() != null) {
            assigneeId = requireAssignee(caller, cmd.assigneeId(), teamId).getId();
        }
        Incident incident = incidents.save(new Incident(orgId, cmd.serviceId(), teamId, cmd.title().trim(),
                cmd.description(), cmd.priority() == null ? IncidentPriority.MEDIUM : cmd.priority(),
                IncidentSource.MANUAL, assigneeId, caller.membershipId()));
        record(incident, caller.membershipId(), "CREATED", null, incident.getStatus().name(), null);
        if (assigneeId != null) {
            record(incident, caller.membershipId(), "ASSIGNED", null, assigneeId.toString(), null);
            notifyAssigned(incident, assigneeId, caller.membershipId());
        }
        return view(incident);
    }

    @Transactional(readOnly = true)
    public List<IncidentView> list(AuthenticatedUser caller, IncidentStatus status, UUID serviceId, int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 200)));
        UUID orgId = caller.organizationId();
        List<Incident> found;
        if (status != null) {
            found = incidents.findByOrganizationIdAndStatusOrderByCreatedAtDesc(orgId, status, page);
        } else if (serviceId != null) {
            found = incidents.findByOrganizationIdAndServiceIdOrderByCreatedAtDesc(orgId, serviceId, page);
        } else {
            found = incidents.findByOrganizationIdOrderByCreatedAtDesc(orgId, page);
        }
        return found.stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public IncidentView get(AuthenticatedUser caller, UUID incidentId) {
        return view(require(caller, incidentId));
    }

    @Transactional
    public IncidentView changeStatus(AuthenticatedUser caller, UUID incidentId, IncidentStatus next) {
        Incident incident = require(caller, incidentId);
        requireTriage(caller, incident);
        if (!incident.getStatus().allowedNext().contains(next)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Cannot move incident from " + incident.getStatus() + " to " + next);
        }
        if (next == IncidentStatus.CLOSED && !isManager(caller)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only managers can close incidents");
        }
        applyStatus(incident, next, caller.membershipId(), null);
        return view(incident);
    }

    @Transactional
    public IncidentView assign(AuthenticatedUser caller, UUID incidentId, UUID assigneeMembershipId) {
        Incident incident = require(caller, incidentId);
        if (!incident.getStatus().isActive()) {
            throw new ApiException(HttpStatus.CONFLICT, "Incident is already " + incident.getStatus());
        }
        if (!isManager(caller) && !leadsTeamOf(caller, incident)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Not allowed to assign this incident");
        }
        OrganizationMember assignee = requireAssignee(caller, assigneeMembershipId,
                isManager(caller) ? null : incident.getTeamId());
        String previous = incident.getAssigneeId() == null ? null : incident.getAssigneeId().toString();
        incident.setAssigneeId(assignee.getId());
        incidents.save(incident);
        record(incident, caller.membershipId(), "ASSIGNED", previous, assignee.getId().toString(), null);
        notifyAssigned(incident, assignee.getId(), caller.membershipId());
        return view(incident);
    }

    @Transactional
    public CommentView comment(AuthenticatedUser caller, UUID incidentId, String body) {
        Incident incident = require(caller, incidentId);
        if (incident.getStatus() == IncidentStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "Incident is closed");
        }
        IncidentComment saved = comments.save(new IncidentComment(caller.organizationId(), incident.getId(),
                caller.membershipId(), body.trim()));
        return new CommentView(saved.getId(), saved.getAuthorId(), saved.getBody(), saved.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<CommentView> comments(AuthenticatedUser caller, UUID incidentId) {
        Incident incident = require(caller, incidentId);
        return comments.findByIncidentIdAndOrganizationIdOrderByCreatedAt(incident.getId(), caller.organizationId())
                .stream().map(c -> new CommentView(c.getId(), c.getAuthorId(), c.getBody(), c.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<HistoryView> history(AuthenticatedUser caller, UUID incidentId) {
        Incident incident = require(caller, incidentId);
        return history.findByIncidentIdAndOrganizationIdOrderByCreatedAt(incident.getId(), caller.organizationId())
                .stream().map(h -> new HistoryView(h.getActorId(), h.getType(), h.getFromValue(), h.getToValue(),
                        h.getNote(), h.getCreatedAt()))
                .toList();
    }

    /** Ensures one active health-check incident; repeated failures while it is active are a no-op. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UUID> openForServiceDown(UUID orgId, UUID serviceId, int failures, String lastError) {
        MonitoredService service = services.findByIdAndOrganizationIdAndActiveTrue(serviceId, orgId).orElse(null);
        if (service == null || !incidents.findByOrganizationIdAndServiceIdAndSourceAndStatusIn(
                orgId, serviceId, IncidentSource.HEALTH_CHECK, ACTIVE).isEmpty()) {
            return Optional.empty();
        }
        String description = "Health check failed " + failures + " consecutive times."
                + (lastError == null ? "" : " Last error: " + lastError);
        UUID assignee = pickAssignee(orgId, service.getTeamId());
        try {
            Incident incident = incidents.saveAndFlush(new Incident(orgId, serviceId, service.getTeamId(),
                    "Service down: " + service.getName(), description, IncidentPriority.HIGH,
                    IncidentSource.HEALTH_CHECK, assignee, null));
            record(incident, null, "CREATED", null, incident.getStatus().name(), AUTOMATION_NOTE_DOWN);
            if (assignee != null) {
                record(incident, null, "ASSIGNED", null, assignee.toString(), null);
                notifyAssigned(incident, assignee, null);
            }
            return Optional.of(incident.getId());
        } catch (DataIntegrityViolationException e) {
            return Optional.empty();
        }
    }

    /** Resolves any active health-check incident for the service once it is healthy again. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int resolveForRecovery(UUID orgId, UUID serviceId) {
        List<Incident> open = incidents.findByOrganizationIdAndServiceIdAndSourceAndStatusIn(
                orgId, serviceId, IncidentSource.HEALTH_CHECK, ACTIVE);
        open.forEach(i -> applyStatus(i, IncidentStatus.RESOLVED, null, "Service recovered automatically"));
        return open.size();
    }

    private UUID pickAssignee(UUID orgId, UUID teamId) {
        if (teamId != null) {
            Optional<OrganizationMember> lead = teams.findByIdAndOrganizationId(teamId, orgId)
                    .map(Team::getTeamLeadId)
                    .flatMap(id -> members.findByIdAndOrganizationId(id, orgId))
                    .filter(OrganizationMember::isActive);
            if (lead.isPresent()) {
                return lead.get().getId();
            }
        }
        List<OrganizationMember> active = members.findByOrganizationIdAndActiveTrue(orgId);
        return firstWithRole(active, Role.MANAGER).or(() -> firstWithRole(active, Role.ORG_ADMIN))
                .map(OrganizationMember::getId).orElse(null);
    }

    private Optional<OrganizationMember> firstWithRole(List<OrganizationMember> list, Role role) {
        return list.stream().filter(m -> m.getRole() == role)
                .min(Comparator.comparing(OrganizationMember::getCreatedAt));
    }

    private void applyStatus(Incident incident, IncidentStatus next, UUID actorId, String note) {
        IncidentStatus previous = incident.getStatus();
        incident.moveTo(next, Instant.now());
        incidents.save(incident);
        record(incident, actorId, "STATUS_CHANGED", previous.name(), next.name(), note);
    }

    private OrganizationMember requireAssignee(AuthenticatedUser caller, UUID membershipId, UUID restrictToTeam) {
        OrganizationMember member = members.findByIdAndOrganizationId(membershipId, caller.organizationId())
                .filter(OrganizationMember::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Assignee not found"));
        if (restrictToTeam != null && caller.role() == Role.TEAM_LEAD
                && !restrictToTeam.equals(member.getTeamId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Team leads can only assign members of their team");
        }
        return member;
    }

    private Incident require(AuthenticatedUser caller, UUID incidentId) {
        return incidents.findByIdAndOrganizationId(incidentId, caller.organizationId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Incident not found"));
    }

    private void requireTriage(AuthenticatedUser caller, Incident incident) {
        boolean allowed = isManager(caller) || caller.membershipId().equals(incident.getAssigneeId())
                || leadsTeamOf(caller, incident);
        if (!allowed) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Not allowed to update this incident");
        }
    }

    private boolean isManager(AuthenticatedUser caller) {
        return caller.role() == Role.ORG_ADMIN || caller.role() == Role.MANAGER;
    }

    private boolean leadsTeamOf(AuthenticatedUser caller, Incident incident) {
        return caller.role() == Role.TEAM_LEAD && incident.getTeamId() != null
                && teams.findByIdAndOrganizationId(incident.getTeamId(), caller.organizationId())
                .map(t -> caller.membershipId().equals(t.getTeamLeadId())).orElse(false);
    }

    private void notifyAssigned(Incident incident, UUID assigneeId, UUID actorId) {
        if (!assigneeId.equals(actorId)) {
            notifications.notifyAll(incident.getOrganizationId(), List.of(assigneeId), incident.getId(),
                    "INCIDENT_ASSIGNED", "Assigned to you: " + incident.getTitle(),
                    "Priority " + incident.getPriority() + " incident.");
        }
    }

    private void record(Incident incident, UUID actorId, String type, String from, String to, String note) {
        history.save(new IncidentHistory(incident.getOrganizationId(), incident.getId(), actorId, type, from, to,
                note));
    }

    private IncidentView view(Incident i) {
        Instant now = Instant.now();
        Instant respondedAt = i.getAcknowledgedAt() == null ? now : i.getAcknowledgedAt();
        Instant resolvedAt = i.getResolvedAt() == null ? now : i.getResolvedAt();
        SlaView sla = new SlaView(i.getSlaResponseMinutes(), i.getSlaResolutionMinutes(), i.getSlaResponseDueAt(),
                i.getSlaResolutionDueAt(), respondedAt.isAfter(i.getSlaResponseDueAt()),
                resolvedAt.isAfter(i.getSlaResolutionDueAt()));
        return new IncidentView(i.getId(), i.getServiceId(), i.getTeamId(), i.getTitle(), i.getDescription(),
                i.getStatus(), i.getPriority(), i.getSource(), i.getAssigneeId(), i.getCreatedById(),
                i.getCreatedAt(), i.getAcknowledgedAt(), i.getResolvedAt(), i.getClosedAt(), sla,
                i.getEscalationLevel(), i.getResponseEscalatedAt(), i.getResolutionEscalatedAt());
    }
}
