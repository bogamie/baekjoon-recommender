# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

BaekjoonRec — a web service that analyzes Baekjoon Online Judge users' solving data (via solved.ac API) and recommends personalized problems. Spring Boot backend + React frontend + PostgreSQL, all containerized with Docker Compose.

## Build & Run Commands

### Full Stack (Docker)
```bash
docker compose up              # Start all services (postgres:5432, backend:8080, frontend:3000)
docker compose up -d --build   # Rebuild and start in background
docker compose down            # Stop all
```

### Backend (from `backend/`)
```bash
./gradlew bootRun              # Dev server on :8080 (requires postgres running)
./gradlew build -x test        # Build JAR without tests
./gradlew test                 # Run tests
```

### Frontend (from `frontend/`)
```bash
npm run dev                    # Vite dev server on :5173 (proxies /api → localhost:8080)
npm run build                  # TypeScript check + production build
```

## Architecture

### Backend — `com.baekjoonrec`

Spring Boot 3.4.4, Java 17, Gradle Kotlin DSL. Schema managed by `init.sql` (JPA `ddl-auto=none`).

| Package | Role |
|---------|------|
| `auth` | JWT auth (jjwt 0.12.6), Spring Security filter chain, signup 4-step flow, email verification |
| `user` | User profile CRUD, solved.ac handle linking |
| `solvedac` | RestClient-based solved.ac API client with 1s rate limit, problem sync service (24h cache) |
| `analysis` | User activity classification (NEWCOMER/ACTIVE/RETURNING_EARLY/RETURNING_MID), per-tag proficiency (UNEXPLORED→BEGINNER→INTERMEDIATE→ADVANCED), dormancy detection |
| `problem` | Problem/ProblemTag/UserSolved entities (cached from solved.ac) |
| `common` | GlobalExceptionHandler (`{"error","message"}` format), MailService interface, ConsoleMailService (dev: logs verification codes) |

**Auth flow**: check-email → check-username → send-code → verify-code → signup → JWT tokens (access 30min, refresh 7d stored in DB).

**Security**: Permit `/api/auth/**`, authenticate everything else. CORS allows `localhost:3000`.

### Frontend — React 18 + Vite + TypeScript

| Directory | Role |
|-----------|------|
| `api/` | Axios client with Bearer interceptor, auto-refresh on 401, request queuing |
| `contexts/` | AuthContext — token storage (localStorage), mount-time validation |
| `pages/` | LoginPage, SignupPage (4-step form), DashboardPage (tag stats table) |
| `components/` | ProtectedRoute, Layout |

**Routing**: `/` → dashboard (auth) or login, `/login`, `/signup`, `/dashboard` (protected).

**Styling**: CSS Modules, extreme minimalism — no UI library, Pretendard font, single accent color (#2563EB).

### API Endpoints

```
POST /api/auth/check-email, check-username, send-code, verify-code, signup, login, refresh
GET  /api/users/me | PUT /api/users/me/solvedac | POST /api/users/me/sync
GET  /api/dashboard/summary | GET /api/dashboard/tags?sort={field}&order={asc|desc}
```

### Database

PostgreSQL 15. Tables: `users`, `email_verification`, `refresh_tokens`, `problems`, `problem_tags`, `user_solved`, `user_analysis`, `user_tag_stats`, `recommendations`. Schema in `init.sql`.

## Key Configuration

```yaml
# Backend env vars (set in docker-compose.yml or shell)
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/baekjoonrec
SPRING_DATASOURCE_USERNAME=app
SPRING_DATASOURCE_PASSWORD=localdev
JWT_SECRET=dev-secret-key-change-in-production

# Frontend
VITE_API_URL=http://localhost:8080   # Docker; dev uses vite proxy
```

## Development Workflow

1. Start postgres: `docker compose up postgres -d`
2. Backend: `cd backend && ./gradlew bootRun`
3. Frontend: `cd frontend && npm run dev`
4. Frontend dev server (5173) proxies `/api` to backend (8080)

## Not Yet Implemented

- Recommendation engine (Task #6 in the spec) — the `recommendations` table exists but no recommendation logic yet
- Email sending — dev mode only logs codes to console via `ConsoleMailService`
