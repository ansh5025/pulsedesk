package com.pulsedesk.incident;

import com.pulsedesk.membership.OrganizationMember;
import com.pulsedesk.membership.OrganizationMemberRepository;
import com.pulsedesk.membership.Role;
import com.pulsedesk.notification.NotificationService;
import com.pulsedesk.team.Team;
import com.pulsedesk.team.TeamRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Escalates incidents that miss their SLA deadlines: each breach is escalated exactly once. */
@Service
public class SlaEscalationService {

    public static final String RESPONSE_BREACH = "SLA_RESPONSE_BREACH";
    public static final String RESOLUTION_BREACH = "SLA_RESOLUTION_BREACH";

    private static final Logger log = LoggerFactory.getLogger(SlaEscalationService.class);
    private static final int BATCH = 100;

    private final IncidentRepository incidents;
    private final IncidentHistoryRepository history;
    private final OrganizationMemberRepository members;
    private final TeamRepository teams;
    private final NotificationService notifications;
    private final TransactionTemplate tx;

    public SlaEscalationService(IncidentRepository incidents, IncidentHistoryRepository history,
                                OrganizationMemberRepository members, TeamRepository teams,
                                NotificationService notifications, PlatformTransactionManager txManager) {
        this.incidents = incidents;
        this.history = history;
        this.members = members;
        this.teams = teams;
        this.notifications = notifications;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Returns the number of escalations performed. */
    public int scan(Instant now) {
        int escalated = 0;
        for (Incident i : incidents.findResponseBreached(now, PageRequest.of(0, BATCH))) {
            escalated += escalate(i.getId(), now, true);
        }
        for (Incident i : incidents.findResolutionBreached(now, PageRequest.of(0, BATCH))) {
            escalated += escalate(i.getId(), now, false);
        }
        return escalated;
    }

    private int escalate(UUID incidentId, Instant now, boolean response) {
        try {
            Integer done = tx.execute(status -> {
                int claimed = response ? incidents.claimResponseEscalation(incidentId, now)
                        : incidents.claimResolutionEscalation(incidentId, now);
                if (claimed == 0) {
                    return 0;
                }
                Incident incident = incidents.findById(incidentId).orElseThrow();
                String type = response ? RESPONSE_BREACH : RESOLUTION_BREACH;
                String what = response ? "response" : "resolution";
                notifications.notifyAll(incident.getOrganizationId(), recipients(incident, !response),
                        incident.getId(), type, "SLA " + what + " breached: " + incident.getTitle(),
                        "Priority " + incident.getPriority() + " incident missed its " + what + " deadline.");
                history.save(new IncidentHistory(incident.getOrganizationId(), incident.getId(), null, "ESCALATED",
                        null, type, "Escalation level " + incident.getEscalationLevel()));
                return 1;
            });
            return done == null ? 0 : done;
        } catch (RuntimeException e) {
            log.error("SLA escalation failed for incident {}", incidentId, e);
            return 0;
        }
    }

    private Set<UUID> recipients(Incident incident, boolean includeAdmins) {
        UUID orgId = incident.getOrganizationId();
        List<OrganizationMember> active = members.findByOrganizationIdAndActiveTrue(orgId);
        Set<UUID> ids = new HashSet<>();
        for (OrganizationMember m : active) {
            if (m.getRole() == Role.MANAGER || (includeAdmins && m.getRole() == Role.ORG_ADMIN)
                    || m.getId().equals(incident.getAssigneeId())) {
                ids.add(m.getId());
            }
        }
        if (incident.getTeamId() != null) {
            teams.findByIdAndOrganizationId(incident.getTeamId(), orgId).map(Team::getTeamLeadId)
                    .filter(lead -> active.stream().anyMatch(m -> m.getId().equals(lead)))
                    .ifPresent(ids::add);
        }
        if (ids.isEmpty()) {
            active.stream().filter(m -> m.getRole() == Role.ORG_ADMIN).forEach(m -> ids.add(m.getId()));
        }
        return ids;
    }
}
