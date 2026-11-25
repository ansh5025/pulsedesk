# PulseDesk

[![CI](https://github.com/ansh5025/pulsedesk/actions/workflows/ci.yml/badge.svg)](https://github.com/ansh5025/pulsedesk/actions/workflows/ci.yml)

Multi-tenant incident, SLA and service-health platform. It watches your services, opens an incident when one goes down, assigns it to the right person and escalates when deadlines slip.

**Live demo:** https://pulsedesk.anshrai.in. It runs on demand, so it may be stopped. Sign in with organization `demo`, password `Demo@12345`.

**Stack:** Java 21 · Spring Boot 3.3 · PostgreSQL · Redis · RabbitMQ · React 18 + TypeScript · Docker · Azure Container Apps (Bicep) · GitHub Actions

## Features

- Organizations with isolated data, four roles (admin, manager, team lead, developer), teams and invitations
- HTTP health checks with failure thresholds that open incidents automatically and resolve them on recovery
- Incident workflow with priority-based response and resolution deadlines, escalation and in-app notifications
- Analytics (MTTA, MTTR, SLA compliance) cached in Redis, and login rate limiting
- Email delivery through RabbitMQ with retries
- JWT authentication, SSRF protection on health-check URLs, Flyway migrations, Testcontainers integration tests

See [docs/PROJECT_MEMORY.md](docs/PROJECT_MEMORY.md) for design decisions and progress.

## Run locally

1. `docker compose up -d` (Postgres, Redis, RabbitMQ, Mailpit)
2. Configuration lives in `backend/src/main/resources/application.yml`; every setting reads an environment variable with a local default.

3. Set `PULSEDESK_JWT_SECRET` (at least 32 characters), then `cd backend; mvn spring-boot:run`.
4. Swagger UI: http://localhost:8080/swagger-ui.html

## Phase 1 API

- `POST /api/v1/auth/register` {organizationName, organizationSlug, fullName, password}: creates org + ORG_ADMIN (`firstname@slug`)
- `POST /api/v1/auth/login` {organizationSlug, username, password}
- `GET /api/v1/auth/me`
- `GET /api/v1/organizations/current`, `GET /api/v1/organizations/current/members` (ORG_ADMIN, MANAGER)

## Phase 2 API

- `GET/POST /api/v1/teams`; `PUT /teams/{id}/lead`; `POST /teams/{id}/members`; `DELETE /teams/{id}/members/{membershipId}`
- `POST/GET /api/v1/invitations` (ORG_ADMIN, MANAGER); `POST /api/v1/invitations/accept` {token, password} (public)
- `PATCH /api/v1/employees/{membershipId}/role`; `DELETE /api/v1/employees/{membershipId}` (deactivates)

## Phase 3 API (services and health monitoring)

- `GET /api/v1/services`, `GET /api/v1/services/{id}`; `POST /api/v1/services`, `DELETE /api/v1/services/{id}` (ORG_ADMIN, MANAGER)
- `PUT /api/v1/services/{id}/health-check` {url, intervalSeconds, timeoutMs, failureThreshold, expectedStatus, enabled}
- `POST /api/v1/services/{id}/health-check/run` (check now); `GET /api/v1/services/{id}/health-check/results?limit=`
- Config: `app.monitoring.enabled`, `app.monitoring.poll-interval-ms`, `app.monitoring.allow-private-targets` (dev/test only; SSRF guard otherwise blocks private addresses).

## Phase 4 API (incidents)

- `POST /api/v1/incidents` (ORG_ADMIN, MANAGER, TEAM_LEAD) {title, description, priority, serviceId, teamId, assigneeId}
- `GET /api/v1/incidents?status=&serviceId=&limit=`; `GET /api/v1/incidents/{id}` (includes SLA snapshot and breach flags)
- `PUT /api/v1/incidents/{id}/status` {status} (managers, assignee, or the team's lead; only managers can close)
- `PUT /api/v1/incidents/{id}/assignee` {assigneeId} (managers; team leads within their team)
- `POST/GET /api/v1/incidents/{id}/comments`; `GET /api/v1/incidents/{id}/history`
- A service going DOWN opens one HIGH incident automatically (assigned to the team lead, else a manager, else an admin); recovery resolves it.
- If that incident is manually resolved while the service is still DOWN, the next failed health check creates a new incident. Repeated failures do not duplicate an active health-check incident. This follows the service's configured check interval (the demo uses 3600 seconds); use Check now for an immediate check.

## Phase 5 API (SLA escalation and notifications)

- A scanner (`app.sla.enabled`, `app.sla.scan-interval-ms`) escalates SLA breaches once each. Incident views now include `escalationLevel`, `responseEscalatedAt`, `resolutionEscalatedAt`.
- Unacknowledged past the response deadline: notifies the assignee, team lead and managers. Active past the resolution deadline: also notifies org admins.
- `GET /api/v1/notifications?unreadOnly=&limit=`, `GET /api/v1/notifications/unread-count`, `PUT /api/v1/notifications/{id}/read`, `PUT /api/v1/notifications/read-all` (your own inbox only). Assigning an incident also notifies the assignee.

## Phase 6 API (analytics, Redis)

- `GET /api/v1/analytics/summary?days=30` (ORG_ADMIN, MANAGER): incident counts by status/priority/source, MTTA/MTTR (minutes), SLA compliance percentages, top services, service health counts. Cached in Redis for 60 s per org (`app.cache.analytics-ttl-seconds`).
- Login is rate limited via Redis: 5 failed attempts per account and 20 per IP in 15 minutes return 429 (`app.rate-limit.*`). Redis outages fail open.
- Run Redis with `docker compose up -d` (service `redis`); the app uses `spring.data.redis.host/port` (defaults localhost:6379).

## Phase 6b (RabbitMQ email delivery)

- Optional `email` on `POST /api/v1/auth/register` and `POST /api/v1/invitations` (stored in `users.email` / `invitations.email`; contact only, never used to log in). Accepting an invitation copies its email onto the new user.
- Off by default. Enable with `app.messaging.enabled=true` (`PULSEDESK_MESSAGING_ENABLED`) plus `spring.rabbitmq.host/port/username/password` and `spring.mail.host/port` (+ credentials). Other settings: `app.messaging.from`, `app.messaging.invite-base-url` (link in invite mails, `?token=` is appended), `app.messaging.max-attempts` (3), `app.messaging.retry-backoff-ms` (1000, doubles).
- Flow: after a transaction commits, invitation and in-app notification events become `EmailMessage`s published to exchange `pulsedesk.email` -> durable queue `pulsedesk.email.send`; `EmailConsumer` sends via SMTP. After the retries are exhausted the message is dead-lettered to `pulsedesk.email.dead`. Delivery is at-least-once (a rare duplicate is possible).
- A RabbitMQ outage is logged and swallowed; the API request and in-app notification are unaffected. Users without an email get no mail.
- Dev: `docker compose up -d` starts RabbitMQ (UI :15672, guest/guest) and Mailpit (SMTP :1025, inbox UI :8025). Use `spring.mail.host=localhost`, `spring.mail.port=1025`.
- Add these keys to your `application.yml` (it is not in the repo, see above). Mail/Rabbit health indicators can mark `/actuator/health` DOWN when disabled infra is unreachable; set `management.health.rabbit.enabled=false` and `management.health.mail.enabled=false` if you don't run them.

## Frontend (React + Vite + TypeScript)

```
cd frontend
npm install
npm run dev      # http://localhost:3000, proxies /api to the backend on :8080
npm run build    # type-check + production build
```

Pages: sign in / register / accept invite, Overview (managers), Incidents (list, report, detail with status, assignee, comments, history), Services (health checks, run now, recent results), People (roles, teams, invitations), Inbox.
Invitation links look like `http://localhost:3000/accept-invite?token=...`, which matches the backend's default `app.messaging.invite-base-url`.
`GET /organizations/current/members` is readable by every member so names show instead of ids.
If a team lead or developer signs in but workspace loading returns 403, restart the backend to load the current directory permissions, then click Retry. The error screen distinguishes API permission errors from connection failures.

## Deploy to Azure (on demand, for demos)

Runs on Azure Container Apps and costs roughly $5–10/month while stopped (database storage plus the registry). Nothing is billed for compute while it's off.

| Piece | Azure resource |
|---|---|
| Frontend (nginx, public) | Container App `pulsedesk-web`; proxies `/api` to the backend |
| Backend + Redis sidecar (internal only) | Container App `pulsedesk-api`, one replica |
| Database | PostgreSQL Flexible Server, Burstable B1ms, 32 GB |
| Images | Azure Container Registry, Basic |
| Safety net | Container Apps job `pulsedesk-autostop` stops everything nightly at 01:00 IST |

Needs Azure CLI, Docker Desktop, and Owner rights on the subscription (the auto-stop job gets a role assignment).

```powershell
az login
.\scripts\azure\deploy.ps1                       # first run ~15 min; prints the public URL
.\scripts\seed-demo.ps1 -Base https://<url>/api/v1 # once, to load the demo org
.\scripts\azure\demo-up.ps1                      # before an interview (~5-10 min)
.\scripts\azure\demo-down.ps1                    # afterwards
.\scripts\azure\custom-domain.ps1                # once: serve it at pulsedesk.anshrai.in with free HTTPS
```

- Re-run `deploy.ps1` to ship new code. It regenerates the JWT secret and database password, so everyone signs in again.
- Email is off in Azure (`PULSEDESK_MESSAGING_ENABLED=false`); there's no RabbitMQ there.
- The database accepts connections only from Azure services, with TLS required.
- To run the same containers locally: set `PULSEDESK_JWT_SECRET`, then `docker compose -f docker-compose.yml -f docker-compose.app.yml up -d --build` and open http://localhost:8081.

## Tests

`cd backend; mvn test` (needs Docker running; uses Testcontainers).
