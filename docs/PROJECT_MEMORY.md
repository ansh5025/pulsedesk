# PulseDesk - Project Memory

Read this first in every session. Update it after every meaningful decision or milestone.

## Meta
- Owner: Java backend job-seeker; has Copilot Business + Azure education credits (~Rs 2,674 left, set budget alerts).
- Source plan: `C:\Users\anshr\Downloads\Java Backend Project Plan.pdf` (originally named OpsForge; read OpsForge/com.opsforge as PulseDesk/com.pulsedesk).
- Repo: `C:\Users\anshr\projects\pulsedesk` (workspace; added to `Desktop\SAAS PROJECT.code-workspace`). This file is the master copy at `docs/PROJECT_MEMORY.md`; session copy is in session-state files/.
- Current status: Phase 1 core DONE (5 Testcontainers tests pass). Docker Desktop must be running for tests. No git repo yet (not initialised).
- BLOCKER: org content policy excludes `application.yml`, so I could not create `backend/src/main/resources/application.yml`. User must add it (content in README). Do not work around it.

## Naming
- Product: PulseDesk | repo/folder: `pulsedesk` | package: `com.pulsedesk`
- Maven: `com.pulsedesk:pulsedesk-backend` | DB/compose project: `pulsedesk` | env prefix: `PULSEDESK_`

## Product
Multi-tenant incident, SLA and service-health management SaaS (PagerDuty + Jira Service Management + monitoring). Self-service.

## Stack
Java 21, Spring Boot 3.x, Maven, Spring Security + JWT (jjwt 0.12.6, BCrypt 12), Spring Data JPA, PostgreSQL, Flyway (`ddl-auto=validate`), Redis, RabbitMQ, Spring Scheduler, Swagger/OpenAPI, JUnit5/Mockito/Testcontainers, Docker, GitHub Actions, React+TS+Tailwind (later), Azure (Static Web Apps, Container Apps, Postgres, Blob, Key Vault, App Insights). Local Docker first for Postgres/Redis/RabbitMQ.

## Architecture
Modular monolith under `com.pulsedesk`. Modules: auth, user, organization, membership, team, service, monitoring, incident, sla, invitation, audit, common (exception/response/security/config). Each: controller/service/repository/entity/dto/mapper. DTOs in APIs, never entities.

## Final decisions
1. No super admin. Self-registration creates org + ORG_ADMIN.
2. Roles: ORG_ADMIN, MANAGER, TEAM_LEAD, DEVELOPER (VIEWER dropped unless needed).
3. One user = exactly one org (UNIQUE user_id on organization_members).
4. Developer in exactly one team (nullable team_id on organization_members; no team_members table).
5. One Team Lead per team (teams.team_lead_id); a Manager oversees many teams.
6. Login = org slug + username (`amit@techkart`) + password.
7. Username auto-generated `name@orgslug`, numeric suffix on collision; unique per org (UNIQUE(organization_id, username)); retry on DataIntegrityViolationException.
8. DB-enforced tenant integrity: organization_id on all tenant tables, UNIQUE(id, organization_id) on parents, composite FKs.
9. Circular FK teams.team_lead_id <-> members.team_id: create tables first, add FKs after, both nullable.
10. Auto incidents (health-check failure) -> assigned to Manager, who assigns a developer. Manual incidents by Manager/Team Lead.
11. Incident dedup (one active incident per failure), consecutive-failure threshold, auto-resolve on recovery.
12. SLA deadlines snapshotted on incident. Ack targets P1 5m, P2 15m, P3 1h, P4 4h. Escalate to manager.
13. Status: OPEN/ACKNOWLEDGED/INVESTIGATING/RESOLVED/CLOSED. Priority P1-P4. Source MANUAL/AUTOMATIC.
14. Deleting employee = deactivate membership, keep history.
15. Org id comes from the JWT, never request params. JWT claims: sub, orgId, membershipId, role, iss, exp; 15 min expiry; secret >= 32 bytes from `JWT_SECRET` env.
16. Health checks on virtual threads with timeouts and per-service intervals.
17. Open idea: merge users + organization_members since 1:1 (undecided).

## Tables
users, organizations, organization_members, teams, services, health_check_configs, health_check_results, incidents, incident_comments, incident_history, sla_policies, invitations, audit_logs; later: notifications, escalation policies, postmortems.

## Roadmap
1. Auth + org (Flyway V1, register, login, JWT filter, principal, exception handler, /auth/me, tests)
2. Employees, teams, invitations
3. Services + monitoring
4. Incidents
5. SLA + escalation
6. Redis, RabbitMQ, analytics, React, Azure
Deploy after Phase 4 before Phase 5/6. Postmortems/escalation policies/analytics are later.

## Known gaps to cover in Phase 1
JwtAuthenticationFilter, custom principal, global @RestControllerAdvice, registration uniqueness race, /auth/me, /organizations/current(+/members), refresh tokens, login rate limiting, integration tests.

## Progress log
- 2026-11-20: Reviewed plan, chose name PulseDesk, created this memory file. No code yet.
- 2026-11-20: Phase 1 built in `backend/` (Spring Boot 3.3.5, no Lombok, entities use plain UUID FKs). Done: Flyway V1 (organizations, users, organization_members; single-org via UNIQUE user_id; no team_id yet, added in Phase 2), register/login/me, JwtService, JwtAuthenticationFilter, AuthenticatedUser principal, SecurityConfig (stateless, JSON 401/403), GlobalExceptionHandler (ProblemDetail), GET /organizations/current and /members (ORG_ADMIN/MANAGER), docker-compose Postgres, AuthFlowIntegrationTest.
  Decisions: kept users and organization_members separate (open question 1 resolved: keep separate). username globally unique (contains slug). Username = firstname@slug, numeric suffix on collision. Registration slug conflict -> 409; DB race -> 409 via handler.
- 2026-11-20: Phase 2 DONE (7 tests pass). V2 migration: teams (UNIQUE(id,org), UNIQUE(org,name), team_lead_id UNIQUE, composite FK lead->members), organization_members.team_id with composite FK (tenant-safe), invitations (SHA-256 token hash, 7-day expiry, single use). Features: team create/list/assign lead/add+remove developer; invitations create/list/accept (token returned once, no email yet; accept is public and creates user `firstname@slug`); employees PATCH role / DELETE (deactivate). Rules: MembershipService.canManage (ORG_ADMIN manages all but ORG_ADMIN; MANAGER manages TEAM_LEAD/DEVELOPER; nobody acts on self); TEAM_LEAD manages only the team they lead; only DEVELOPER can be invited into a team; demoting/deactivating a lead clears teams.team_lead_id; other-org ids return 404.
  Known gaps: JWT stays valid up to 15 min after deactivation (filter doesn't re-check DB); no email delivery for invites; no team delete/rename; no refresh tokens/rate limiting.
  Next: Phase 3 services + monitoring (services, health_check_configs/results, scheduler on virtual threads, consecutive-failure threshold). refresh tokens, login rate limiting, membership-active check per request, email/invite flows (Phase 2).
- 2026-11-20: Phase 3 DONE (29 tests pass). V3 migration: services, health_check_configs (1 per service), health_check_results. Package `monitoring/` (HealthProbe via java.net.http, redirects never followed; TargetValidator SSRF guard; HealthCheckRecorder state machine; HealthCheckRunner on virtual threads; HealthCheckScheduler polls `findDue()` every 10s, purges results >7 days) and `service/` (catalog + controller). DOWN when consecutive_failures >= threshold; recovers on first success; ServiceDownEvent / ServiceRecoveredEvent published only on transitions (Phase 4 incidents listen; consider @TransactionalEventListener). Limits: interval 30-3600s, timeout 1-30s, threshold 1-10. SSRF: blocks loopback/private/link-local/CGNAT/ULA, validated at configure and probe time; `app.monitoring.allow-private-targets` only for tests/dev. Gaps: DNS-rebinding TOCTOU, scheduler single-instance only (no SKIP LOCKED), application.yml still blocked by content policy (content in README).
  Next: Phase 4 incidents (auto-create on ServiceDownEvent, dedup, auto-resolve on recovery, manual incidents, comments/history, SLA snapshot).
- 2026-11-20: Phase 4 DONE (31 tests pass). V4 migration: incidents (composite tenant FKs, partial unique index `uq_incident_active_health_check` = one unresolved HEALTH_CHECK incident per service), incident_comments, incident_history. Package `incident/`. HealthIncidentListener (@TransactionalEventListener AFTER_COMMIT) calls IncidentService.openForServiceDown / resolveForRecovery, both REQUIRES_NEW (needed: joining the committed tx would silently not persist). Auto incident: priority HIGH, assignee = service team's lead, else oldest MANAGER, else oldest ORG_ADMIN; dedup by active-incident check + DB index (race -> swallow). Recovery auto-resolves (history actor null). Status machine: OPEN->ACK/IN_PROGRESS/RESOLVED, ACK->IN_PROGRESS/RESOLVED, IN_PROGRESS->RESOLVED, RESOLVED->IN_PROGRESS(reopen)/CLOSED, CLOSED terminal (409 otherwise). Permissions: create manual = ORG_ADMIN/MANAGER/TEAM_LEAD; status change = managers, assignee, or the lead of the incident's team; CLOSE = managers only; assign = managers, or team lead within own team members; everyone in the org can read and comment (not on CLOSED). Team lead's manual incident defaults to their led team. SLA snapshot fixed at creation (response/resolution minutes: CRITICAL 15/240, HIGH 60/480, MEDIUM 240/1440, LOW 480/4320); breach flags computed on read (acknowledgedAt/resolvedAt or now vs due). Priority is immutable for now.
  Gaps: no priority change/SLA recompute, no escalation or breach notifications (Phase 5), no pagination cursor, developers see all org incidents, test contexts without app.monitoring.enabled=false log scheduler noise at shutdown.
  Next: deploy (Azure) or Phase 5 SLA breach scanner + escalation, notifications.
- 2026-11-20: Phase 5 DONE (32 tests pass). V5 migration: incidents.escalation_level/response_escalated_at/resolution_escalated_at, notifications table (composite FKs). `SlaEscalationService.scan(Instant now)` (now is a parameter so tests simulate time) + `SlaScheduler` (`app.sla.enabled` default true, `app.sla.scan-interval-ms` default 30000). Each breach escalates exactly once via an atomic claim UPDATE (`... where response_escalated_at is null`, works across instances); each incident handled in its own TransactionTemplate so one failure doesn't block others. Response breach = still OPEN and never acknowledged past response due; recipients = assignee + team lead + all active MANAGERs (falls back to ORG_ADMINs if nobody). Resolution breach = any active incident past resolution due; recipients also include ORG_ADMINs. Writes an ESCALATED history entry (actor null). In-app notifications: INCIDENT_ASSIGNED (not sent to the actor themselves), SLA_RESPONSE_BREACH, SLA_RESOLUTION_BREACH. API /notifications (list, unreadOnly, unread-count, PUT /{id}/read, PUT /read-all) scoped to the caller's membership; others get 404.
  Gaps: no email/Slack/RabbitMQ delivery yet (notifications are DB rows), no pre-breach warnings, no configurable SLA policies (hardcoded per priority), no priority change, no pause-on-hold, breach flags in views are computed on read.
  Next: Phase 6 (Redis cache, RabbitMQ for notification delivery, analytics, React frontend, Azure deploy) or deploy first.
- 2026-11-20: Phase 6a DONE (34 tests pass; user chose Analytics + Redis now, RabbitMQ/email + React + Azure deploy deferred). Added spring-boot-starter-data-redis, redis in docker-compose.
  Analytics: `GET /api/v1/analytics/summary?days=30` (1-365, ORG_ADMIN/MANAGER) via NamedParameterJdbcTemplate (`analytics/AnalyticsService`): totals, byStatus/byPriority/bySource (zero-filled), MTTA/MTTR minutes (null when no data), SLA compliance (response evaluated = acknowledged OR deadline passed; met = acknowledged <= due; same for resolution), top 5 services by incidents, serviceHealth counts of enabled checks on active services. Reopened incidents lose resolved_at so they drop out of MTTR until resolved again.
  Redis: `common/cache/RedisCache` JSON read-through, FAIL-OPEN on any Redis error (falls back to loader). Analytics cached 60s per org+days (`app.cache.analytics-ttl-seconds`, 0 disables); key `pulsedesk:analytics:{org}:{days}`. Not cached: service status list (cheap DB read, changes constantly).
  Login rate limiting: `auth/LoginRateLimiter` fixed-window counters in Redis, per account (`slug:username`, `app.rate-limit.login-max-attempts`=5) and per IP (`login-max-attempts-per-ip`=20), window `login-window-seconds`=900; only 401s count; success resets the account counter; 429 when blocked even with the right password. Fails open if Redis is down. Trade-off: an attacker can lock out a known username for 15 min. `getRemoteAddr()` is used, so behind a proxy/Azure you must enable forwarded-header handling (`server.forward-headers-strategy=framework`) or all users share one IP counter.
  Tests: AnalyticsIntegrationTest and LoginRateLimitIntegrationTest use Testcontainers `redis:7-alpine` with `@ServiceConnection(name="redis")`. Tests without a Redis container still pass (fail-open) but log warnings; surefire may print a harmless "kill fork JVM" line.
  Still to do: RabbitMQ + email delivery of notifications, React frontend, Azure deploy (needs `application.yml` incl. `spring.data.redis.host/port`), refresh tokens, configurable SLA policies.
- 2026-11-20: Phase 6b DONE (37 tests pass). RabbitMQ + email. V6: nullable `users.email`, `invitations.email` (optional `email` on register + invite; accept copies it to the user). Package `messaging/`: `MessagingProperties` (`app.messaging.enabled` default FALSE, from, invite-base-url, max-attempts 3, retry-backoff-ms 1000), `RabbitConfig` (only when enabled: direct exchange `pulsedesk.email` -> durable queue `pulsedesk.email.send` with DLX `pulsedesk.email.dlx` -> `pulsedesk.email.dead`; JSON converter; listener factory with stateless retry + exponential backoff then RejectAndDontRequeue), `EmailDispatcher` (publishes; swallows AmqpException so outages never fail requests), `EmailEventListener` (@TransactionalEventListener AFTER_COMMIT for `NotificationsCreatedEvent` -> looks up active recipients' emails via JDBC, and `InvitationCreatedEvent` -> invite link `<invite-base-url>?token=...`), `EmailConsumer` (@RabbitListener -> JavaMailSender; drops with a warning if spring.mail.host unset; SMTP failure -> retry -> DLQ). `NotificationService.notifyAll` now publishes `NotificationsCreatedEvent`; `InvitationService.create` publishes `InvitationCreatedEvent` (only if email given). Delivery is at-least-once, no idempotency table. Compose adds rabbitmq (management UI 15672) and mailpit (SMTP 1025, UI 8025).
  Tests: EmailDeliveryIntegrationTest (Testcontainers rabbitmq:3.13-alpine + GreenMail SMTP 2.1.0; asserts invite mail with token link, notification mail, no mail for user without email) and EmailDispatcherTest (outage swallowed, disabled/blank not queued). Cached messaging context logs harmless "Failed to check/redeclare auto-delete queue(s)" after its container stops.
  Gaps: no per-user email preferences/unsubscribe, no email verification (address is unverified contact info), no DLQ replay tool, plain-text mail only. application.yml still blocked: add spring.rabbitmq.*, spring.mail.*, app.messaging.* from README.
  Still to do: React frontend, Azure deploy, refresh tokens, configurable SLA policies.

## Open questions
- Merge users + organization_members? Keep VIEWER role?

## Phase 7 - React frontend
- frontend/ (Vite, React 18, TS, react-router). Dev port 3000, /api proxied to :8080. No UI libs; custom 'printed ops log' CSS (paper/ink, serif headings, ruled tables, colour only for severity/health).
- Pages: Auth, Overview, Incidents, IncidentDetail, Services, People, Inbox. Verified against live backend with Playwright.
- Backend change: members endpoint open to all authenticated members.
- frontend/postcss.config.cjs is empty on purpose (a stray config in the home dir broke vite).
- Next: Azure deploy, refresh tokens, configurable SLA policies.

## Phase 8 - Azure deployment (on-demand demo)
- backend/Dockerfile (maven build -> temurin 21 JRE alpine, non-root), frontend/Dockerfile (node build -> nginx; nginx.conf.template proxies /api and /healthz to BACKEND_URL).
- infra/main.bicep: Container Apps env + Log Analytics, ACR Basic (admin creds), Postgres Flexible B1ms (public, AllowAzureServices, sslmode=require), pulsedesk-api (internal, 1 replica, redis sidecar), pulsedesk-web (external), pulsedesk-autostop scheduled job (Contributor on RG) stopping apps + DB nightly 19:30 UTC.
- Secrets (JWT, PG password) are newGuid() param defaults stored as Container App secrets; rotated each deploy.
- scripts/azure: deploy.ps1 (two-pass: base -> docker build/push -> apps), demo-up.ps1, demo-down.ps1, common.ps1. Container app start/stop via az rest (no CLI command).
- SERVER_FORWARD_HEADERS_STRATEGY=native so login rate limit sees real client IP behind proxies.
- docker-compose.app.yml runs both images locally on :8081 (verified end-to-end). Not yet deployed to Azure: user must az login.

## Phase 9 - GitHub + custom domain
- Public repo https://github.com/ansh5025/pulsedesk (homepage https://pulsedesk.anshrai.in). CI (.github/workflows/ci.yml): backend mvn test (Testcontainers), frontend build, docker image builds; all green.
- Testcontainers pinned to 1.21.4 via testcontainers.version: Boot 3.3's 1.19 requests Docker API 1.32, rejected by Docker 29 on GitHub runners.
- scripts/azure/custom-domain.ps1 binds pulsedesk.anshrai.in (CNAME pulsedesk -> web fqdn, TXT asuid.pulsedesk -> verification id) with a free managed cert and updates PULSEDESK_INVITE_URL. deploy.ps1 re-passes existing binding (customDomain/customDomainCertificateId params) so redeploys keep it.
- Azure not deployed yet: user must az login and run deploy.ps1, then custom-domain.ps1.
