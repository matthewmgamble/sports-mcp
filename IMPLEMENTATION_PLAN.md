# sports-mcp — Implementation Plan

**Status:** Approved for Sprint 0, 2026-10-12
**Source spec:** `sports-mcp-development-plan.md` (the "dev plan"). This document refines it with decisions made on 2026-10-12, upstream behaviour verified that day, and lessons from Pulseboard (`~/code/pulseboard/cloud/decision_engine/sports.py`). **Where the two conflict, this document wins.**

---

## 1. Decisions

| Topic | Decision | Notes |
|---|---|---|
| Distribution | **Open source + publicly hosted** endpoint | Anyone can add the hosted MCP URL; others can self-host the same image. |
| Hosted access | **Anonymous, per-IP rate limits**; optional API keys raise limits | No signup. OAuth is post-MVP. |
| MLB / NHL source | **Mirror Pulseboard**: MLB → `statsapi.mlb.com`, NHL → `api-web.nhle.com` | ESPN for every other league. |
| Leagues | **NFL, NHL, NBA, MLB** (fully fixture-tested) **+ NCAAF, NCAAB, EPL, La Liga, Bundesliga** (config-enabled) | FIFA World Cup entry kept in registry but disabled (tournament ended July 2026). |
| Storage | **SQLite** (WAL), Flyway migrations | Single file on a Docker volume. |
| Hosting | **New VPS / Docker host** | Docker image + Compose is the primary deploy path; Cloudflare in front. |
| Coordinates | groupId `ca.hex42labs`, artifactId `sports-mcp`, packages `ca.hex42labs.sports.*` | |
| HTTP framework | **Javalin 7.2.x** (Jetty 12.1) | *Not* Javalin 6 — see §3. |
| MCP | Official Java SDK **2.0.1** (`mcp-core` + `mcp-json-jackson2`), **stateless Streamable HTTP** at `/mcp` | All tools read-only; no server→client notifications, so stateless suits public hosting (no per-session memory). |
| Build | Single Maven module, Maven wrapper, Java 21 | Multi-module adds nothing at this size. |

Defaults I've chosen that you can veto (none block Sprint 0): **Apache-2.0 license**; default display timezone **America/New_York** with a per-request `timezone` override; **latest score kept on the game row** (no score-history table); **90-day retention** for finished games; logos returned as **ESPN CDN URLs** (no logo proxy).

## 2. Changes from the dev plan

1. **Three providers, not one.** The provider SPI from dev plan §3 becomes load-bearing on day one: a `LeagueRegistry` sends each league to its provider.
2. **Canonical team IDs are provider-independent** (§5.1), so MLB and NHL teams get the same IDs and logos as if they came from ESPN. That keeps an ESPN fallback for MLB/NHL possible later.
3. **Public hosting replaces the dev plan's localhost-by-default posture** (dev plan §9). It adds per-IP rate limiting, a separate management port, abuse controls and a legal/attribution workstream (§10).
4. **No ESPN date-range queries.** They return HTTP 400, so schedule sync fetches one day at a time (§3).
5. **Stateless MCP transport** instead of session-based Streamable HTTP.
6. **Status enum adds `SUSPENDED`**, and soccer/OT/shootout detail is captured via `periodType` (§5.2).
7. **`teamId` inputs also accept a team name** that resolves to exactly one team ("leafs", "Green Bay"), which makes the tools friendlier for agents. Ambiguous names return an error listing the candidates.
8. **`timezone` and `date` parameters** on game queries, because "today" is ambiguous for an agent.
9. **Separate `/metrics` and `/admin/*` onto a localhost-only management port.**
10. Effort grows from 18–26 to roughly **24–32 engineering days** (three adapters plus public hardening).

## 3. Upstream findings (verified 2026-10-12)

**ESPN** (`site.api.espn.com/apis/site/v2/sports/{sport}/{league}/…`)
- The default `scoreboard` returns the *current* slate. For NFL that's the whole week (15 events, week 5, with `teamsOnBye`).
- `?dates=YYYYMMDD` works for single days. **`?dates=A-B` ranges return HTTP 400** (`"Failed to get events endpoint."`). An early probe misread this as "0 events". We'll add a contract test that flags it if ranges start working again.
- NFL `?dates=2026&seasontype=2&week=6` works (14 events).
- **Games that haven't started report `score: "0"`.** We null scores based on status, not on whether the field is present.
- **Responses are gzip-encoded even without `Accept-Encoding`.** Java's `HttpClient` doesn't decompress, so the client must send `Accept-Encoding: gzip` and decode explicitly.
- `cache-control: max-age=1`, so ESPN's CDN won't absorb our traffic for us.
- `teams/{id}/schedule` works (NHL TOR: 84 events, full season).
- Competitions include `venue.fullName` and `broadcasts[].names`. Live NFL games include `situation` (possession, down & distance, red zone).
- NCAAF's default scoreboard is already FBS (same count with `groups=80`). **NCAAB's default is a featured subset** (17 vs 132 games with `groups=50&limit=400`), so always pass `groups=50`.
- **Postponed games have `status.type.state = "post"`** (same as finals). Map on `type.name` first.
- `dates=` buckets are **US Eastern** days (the 00:15Z MNF game is in the previous day's bucket).

**NHL** (`api-web.nhle.com/v1`)
- `schedule/{date}` returns a 7-day `gameWeek` (51 games), so 3 calls cover a 16-day window.
- **`score/{date}` includes `clock`, `periodDescriptor` and scores for every game on that date.** One batched call per date replaces Pulseboard's per-game play-by-play calls for live clocks.
- States: `gameState` FUT/PRE/LIVE/CRIT/FINAL/OFF; `gameScheduleState` OK/PPD/… (postponed, cancelled).
- **`/now` URLs 307-redirect** (e.g. `club-schedule-season/TOR/now` → `/20262027`), so the client must follow redirects.
- Teams: `standings/now` (32 teams, abbreviation plus localized names). It may be empty in the off-season, so we ship a seed file.

**MLB** (`statsapi.mlb.com/api/v1`)
- `schedule?sportId=1&startDate=&endDate=&hydrate=team,linescore` worked today (15 games over 10 days). Pulseboard saw **intermittent HTTP 406 on range queries**, so we use ranges for background sync with a per-day fallback on 406, and single dates for live refresh.
- `teams?sportId=1` returns 30 teams.
- **Postponed and cancelled games report `abstractGameState: "Final"`**. Check `codedGameState` (`D` postponed, `C` cancelled) first.
- The schedule's hydrated linescore lags (it's cached at the CDN). Live games need `game/{pk}/linescore` (~3 KB), as in Pulseboard.
- **Responses carry a copyright notice** pointing at `gdx.mlb.com/components/copyright.txt`. See §10.4.

**Java stack compatibility**
- MCP SDK 2.0.1's servlet transport needs `jakarta.servlet-api` 6.1, i.e. **Jetty 12**.
- Javalin 6.x uses Jetty 11 (servlet 5): **incompatible**. Javalin 7.2.3 uses Jetty 12.1.12: **compatible**.
- The SDK's `mcp` bundle pulls in Jackson 3. Use `mcp-core` + `mcp-json-jackson2` to stay on one Jackson line with Javalin.
- The old `server-servlet` artifact stopped at 0.18.4. In 2.x the servlet transports live in `mcp-core`.
- Jetty 12.1's ee10 module ships `jakarta.servlet-api` **6.0**, while the SDK compiles against 6.1 (`provided`). It only uses 6.0 APIs today, and `AppEndToEndTest` plus the MCP Inspector check cover this. Re-verify on SDK upgrades.
- `logstash-logback-encoder` 9.x pulls Jackson 3, so we pin 8.x to stay on one Jackson line.

## 4. Architecture

```text
           MCP clients (Claude, Ack, …)        REST clients
                     │  POST /mcp (stateless)        │  GET /api/v1/…
                     └──────────────┬────────────────┘
                     Javalin 7 public port (:8080)
              rate limit · CORS · error mapping · request IDs
                                    │
                            SportsService  ◄── one service layer for both APIs
               ┌────────────────────┼─────────────────────┐
         TeamCatalog          GameQueryService      ScoreRefreshCoordinator
               │                    │              (Caffeine single-flight,
               │                    │               adaptive TTL, stale fallback)
               └──────────┬─────────┴──────────┬──────────┘
                     Repositories (SQLite)   ProviderRouter (LeagueRegistry)
                                               ┌──────┼────────┐
                                           EspnProvider  NhlProvider  MlbProvider
                                               └──────┼────────┘
                                         UpstreamHttp (gzip, redirects, timeouts,
                                         per-provider rate limit + circuit breaker)

   ScheduleSyncScheduler ──► providers ──► upsert games/teams
   Management port (:9090, localhost): /metrics, /admin/resync, /health/*
```

### 4.1 Packages

```text
ca.hex42labs.sports
  App                     wiring (manual DI, like Pulseboard), lifecycle
  config                  AppConfig (env + YAML), LeagueRegistry, league definitions
  domain                  League, Team, Game, GameStatus, PeriodType, Freshness, ids
  application             SportsService, GameQueryService, TeamCatalog, TeamResolver,
                          ScoreRefreshCoordinator, FreshnessPolicy
  provider                SportsProvider SPI, ProviderBatch, ProviderException, UpstreamHttp
  provider.espn           EspnClient, EspnMapper, EspnProvider, EspnStatusMapper
  provider.nhl            NhlClient, NhlMapper, NhlProvider
  provider.mlb            MlbClient, MlbMapper, MlbProvider, MlbTeamMap
  persistence             Database (Hikari + SQLite pragmas), GameRepository,
                          TeamRepository, SyncStateRepository
  scheduler               ScheduleSyncScheduler, RetentionJob
  api.rest                RestRoutes, DTOs, ErrorMapper, OpenAPI serving
  api.mcp                 McpServerFactory, tool definitions + JSON schemas, resources
  api.http                RateLimitFilter, ClientIdentity, RequestIdFilter, CORS
  observability           Metrics (Micrometer/Prometheus), health checks
```

### 4.2 Dependencies (versions pinned in Sprint 0)

Javalin 7.2.x · MCP `mcp-core` + `mcp-json-jackson2` 2.0.1 · Jackson 2 (+ jsr310) · `org.xerial:sqlite-jdbc` · HikariCP · Flyway (SQLite support to be confirmed in the 2.x line) · Caffeine 3 · Resilience4j (`ratelimiter`, `circuitbreaker`) · Micrometer + Prometheus registry · SLF4J + Logback with a JSON encoder · Tests: JUnit 5, AssertJ, WireMock, Awaitility.

`java.net.http.HttpClient` for upstream calls: `followRedirects(NORMAL)`, connect timeout 3 s, request timeout 5 s, a `User-Agent` naming the project and repo URL, explicit gzip decoding.

## 5. Domain model

### 5.1 Canonical IDs (opaque strings to clients, but readable)

| Entity | Format | Examples |
|---|---|---|
| League | lowercase key | `nfl`, `nhl`, `nba`, `mlb`, `ncaaf`, `ncaab`, `epl`, `laliga`, `bundesliga` |
| Team (pro leagues) | `{league}:{lowercase ESPN abbreviation}` | `nfl:gb`, `nhl:tor`, `mlb:tor`, `nba:tor` |
| Team (college, soccer) | `{league}:{ESPN numeric team id}` | `ncaaf:145`, `epl:359` (abbreviations there aren't unique or stable; same rule Pulseboard uses for logos) |
| Game | `{league}:{provider event id}` | `nfl:401872994`, `nhl:2026020123`, `mlb:776001` |

MLB and NHL native team IDs map to ESPN abbreviations via static tables ported from Pulseboard (`MLB_ESPN`: id → abbreviation, including AL/NL All-Star squads; `NHL_ESPN`: `LAK→la, SJS→sj, NJD→nj, TBL→tb, UTA→utah`). `teams.provider_team_id` stores the native ID.

Logo URL convention (from Pulseboard): `https://a.espncdn.com/i/teamlogos/{slug}/500/{key}.png`. `slug` is the league (`nfl`/`nhl`/`mlb`/`nba`), `ncaa` for college, or `soccer` for clubs. `key` is the abbreviation or numeric ID per the team rule above.

### 5.2 Entities

- **League:** `id, displayName, sport, provider, enabled, displayTimezone, scheduleStrategy`.
- **Team:** `id, league, provider, providerTeamId, name, shortName, abbreviation, location, logoUrl?, aliases[]`.
- **Game:** `id, league, provider, providerEventId, season?, seasonType?, homeTeamId, awayTeamId, startsAt (UTC), status, statusDetail?, period?, periodType?, clock?, homeScore?, awayScore?, homeShootout?, awayShootout?, venue?, broadcasts[], situation? (NFL live), fetchedAt, providerUpdatedAt?`.
- **SyncState:** `league, kind (TEAMS|SCHEDULE), windowStart, windowEnd, lastSuccessAt, lastAttemptAt, error?`.
- **Response metadata:** `source` (e.g. `espn`, `nhl`, `mlb`), `asOf`, `isStale`, `staleReason?`, `attribution`.

**`GameStatus`:** `SCHEDULED, LIVE, FINAL, POSTPONED, SUSPENDED, CANCELLED, UNKNOWN`.
**`PeriodType`:** `REGULATION, OVERTIME, SHOOTOUT, EXTRA_TIME, PENALTIES, EXTRA_INNINGS`.
**Score rule:** scores are `null` unless status ∈ {LIVE, FINAL, SUSPENDED}.

### 5.3 Status mapping (confirmed against fixtures in Sprint 0/1)

| Provider | → SCHEDULED | → LIVE | → FINAL | → POSTPONED / SUSPENDED / CANCELLED |
|---|---|---|---|---|
| ESPN (`status.type.name` first, because postponed has `state=post`; `state` only for unknown names) | `STATUS_SCHEDULED`, `STATUS_DELAYED` before start | `STATUS_IN_PROGRESS`, `STATUS_HALFTIME`, `STATUS_END_PERIOD`, `STATUS_RAIN_DELAY` in-game; `state=in` | `STATUS_FINAL`, `STATUS_FULL_TIME`, `STATUS_FINAL_AET`, `STATUS_FINAL_PEN`; `state=post` | `STATUS_POSTPONED` / `STATUS_SUSPENDED` / `STATUS_CANCELED`, `STATUS_ABANDONED` |
| NHL | `gameState` FUT, PRE | LIVE, CRIT | FINAL, OFF | `gameScheduleState` PPD / SUSP / CNCL (overrides gameState) |
| MLB (`codedGameState` first: postponed/cancelled report abstract "Final") | `abstractGameState` Preview (incl. "Delayed Start") | Live (incl. "Delayed", which goes in `statusDetail`) | Final ("Final", "Game Over", "Completed Early") | `codedGameState` D (Postponed) / C (Cancelled) confirmed; T/U (Suspended) per community docs |

Unknown values map to `UNKNOWN` and increment a metric (`sports_status_unmapped_total{provider,raw}`) so schema drift shows up quickly.

## 6. Providers

### 6.1 SPI

```java
public interface SportsProvider {
    String id();                                                  // "espn" | "nhl" | "mlb"
    List<Team> fetchTeams(League league);
    ProviderBatch fetchSchedule(League league, LocalDate from, LocalDate to); // background
    ProviderBatch fetchScoreboard(League league, LocalDate bucket);           // live, batched
    Optional<Game> refreshGame(League league, Game game);         // per-game enrichment (MLB only)
}
```

`ProviderBatch` = games + teams seen + `fetchedAt` + provider "date bucket" semantics. Providers never touch the DB.

### 6.2 Per-provider strategy

| | ESPN | NHL | MLB |
|---|---|---|---|
| Teams | `/teams` (college: `?limit=1000`; confirm groups) | `standings/now`, falling back to a bundled seed JSON | `teams?sportId=1` plus seed |
| Schedule sync (D-1…D+14) | 16 single-day `scoreboard?dates=` calls per league (NCAAB: `groups=50&limit=400`) | 3 `schedule/{date}` calls (7-day weeks) | 1 range call; on 406, split into single days |
| Live batch | `scoreboard?dates=` for the active bucket(s) | `score/{date}` (scores + clock in one call) | `schedule?date=…&hydrate=linescore,team` |
| Per-game live | none needed | none needed | `game/{pk}/linescore` for LIVE games that were requested, coalesced |
| Game types | season types 1–3 (pre/reg/post) | `gameType` 1–3 | R, F, D, L, W, A; spring training excluded by default (config) |

**Date buckets / late games.** Provider "dates" are local slates (ESPN buckets confirmed US Eastern; NHL/MLB use local game dates). Ported from Pulseboard's `slate_dates`: until **03:00 in the league's display timezone**, live refresh also fetches *yesterday's* bucket, so a game that started last night and runs past midnight stays live. `startsAt` is always stored as UTC. Buckets only drive fetches, never presentation.

**Dedup:** postponed games can appear under both the original date and the makeup date with the same ID. Upsert by `(provider, provider_event_id)`. The latest `startsAt`/status wins, and makeup-date data takes precedence (Pulseboard lesson).

## 7. Persistence (SQLite)

- `sqlite-jdbc`, `journal_mode=WAL`, `busy_timeout=5000`, `foreign_keys=ON`, `synchronous=NORMAL`.
- **All writes go through one dedicated connection/executor.** Reads use a small Hikari pool. This avoids `SQLITE_BUSY` storms during sync.
- Flyway `V1__init.sql`:
  - `teams(id PK, league, provider, provider_team_id, name, short_name, abbreviation, location, logo_url, aliases_json, updated_at, UNIQUE(league, provider_team_id))`
  - `games(id PK, league, provider, provider_event_id, season, season_type, home_team_id, away_team_id, starts_at, status, status_detail, period, period_type, clock, home_score, away_score, home_shootout, away_shootout, venue, broadcasts_json, situation_json, fetched_at, provider_updated_at, created_at, updated_at, UNIQUE(provider, provider_event_id))`
  - Indexes: `(league, starts_at)`, `(home_team_id, starts_at)`, `(away_team_id, starts_at)`, `(status, starts_at)`
  - `sync_state(league, kind, window_start, window_end, last_success_at, last_attempt_at, error, PRIMARY KEY(league, kind))`
  - `raw_payloads(id, provider, url_hash, captured_at, body_gz)`: off by default, 24 h retention.
- Upserts are idempotent (`INSERT … ON CONFLICT DO UPDATE`). `fetched_at` only moves forward: a stale write never overwrites a fresher one.
- `RetentionJob`: delete FINAL/CANCELLED games older than `retention.finishedDays` (90) and raw payloads older than 24 h.

## 8. Freshness, caching and coalescing

`FreshnessPolicy` (all configurable; values from dev plan §6):

| Situation | Max age before refresh on request |
|---|---|
| LIVE | 15 s |
| SCHEDULED, starts within 1 h | 60 s |
| SCHEDULED, already past start time but not LIVE (late/delayed start) | 30 s |
| FINAL < 2 h ago | 5 min |
| FINAL older | serve stored, never refresh on request |
| Empty batch (no games) | 2 min |
| Team catalog | 24 h (background) |

Background: full D-1…D+14 sync every 6 h, near-term (next 48 h) every 30 min, catch-up on startup if `last_success_at` is stale. Jitter ±10%.

**Coordinator algorithm** (dev plan §6, made concrete):
1. Query the DB for the requested games.
2. Group them by *refresh key* (`provider:league:bucket`, or `mlb:game:{pk}` for per-game linescore) and compute each key's required freshness from its most demanding game.
3. If fresh, return immediately.
4. Otherwise do `inFlight.computeIfAbsent(key, fetch)`: a Caffeine `AsyncCache<RefreshKey, ProviderBatch>` with variable `Expiry` set from the batch contents. 100 concurrent callers share one `CompletableFuture`.
5. The fetch passes through a per-provider Resilience4j rate limiter (ESPN 3 rps / NHL 2 rps / MLB 1 rps, burst 5) and circuit breaker.
6. Upsert, then re-read.
7. Return with `asOf` = oldest `fetchedAt` among returned games.
8. On timeout, open breaker or error: return stored data with `isStale=true` and a `staleReason`. If nothing is stored, return 502/503.

`refresh=true` cuts the TTL to a **5 s floor**, still goes through coalescing, and has its own stricter client rate limit (§10.1). There is no code path that fetches a season on request.

Single replica for MVP. Redis/distributed lease remains backlog (dev plan §13.7).

## 9. Interfaces

### 9.1 MCP (`POST /mcp`, stateless Streamable HTTP)

All tools: `annotations.readOnlyHint=true, openWorldHint=true`, strict input JSON Schema, an `outputSchema`, `structuredContent` plus a short text summary (e.g. "3 live NHL games: TOR 2–1 MTL (2nd, 12:34) …"). Errors come back as `isError: true` tool results with actionable messages ("Unknown league 'nlf'. Valid: nfl, nhl, …").

| Tool | Inputs | Notes |
|---|---|---|
| `sports_list_leagues` | — | Includes provider and attribution. |
| `sports_list_teams` | `league`, `search?` | Search covers name, location, abbreviation and aliases. |
| `sports_get_games` | `league?`, `team?`, `date?` *or* `from?`/`to?`, `status?`, `timezone?`, `limit?` | `date` defaults to today in `timezone`. Range ≤ 31 days, `limit` ≤ 100 (default 25). Needs `league` or `team`. |
| `sports_get_live_scores` | `league?`, `team?`, `refresh?` | No league means all enabled leagues (live games only, so bounded). |
| `sports_get_game` | `gameId`, `refresh?` | |
| `sports_get_team_schedule` | `team`, `from?`, `to?`, `limit?`, `timezone?` | Defaults to the next 5 plus last 3 games. |

`team` accepts a canonical ID (`nhl:tor`) or a name that `TeamResolver` resolves within `league` (or across leagues if exactly one match). Resources `sports://leagues` and `sports://leagues/{league}/teams` are a Sprint 4 stretch goal.

### 9.2 REST (`/api/v1`)

As in dev plan §8, plus `tz` and `date` query params. Same DTOs as MCP via shared mappers. The envelope is `{data, meta:{source, asOf, isStale, staleReason?, attribution}}`, and errors are `{error:{code, message, details?}}` with 400/404/429/502/503. A hand-maintained `openapi.yaml` (3.1) is served at `/openapi.json`, and a contract test validates real responses against it. `Cache-Control: public, max-age=<remaining freshness>` on game responses. No ETags for live data in MVP.

### 9.3 Ports

- **Public :8080:** `/mcp`, `/api/v1/*`, `/health/live`, `/health/ready`, `/` (landing page with usage and attribution).
- **Management :9090, bound to 127.0.0.1:** `/metrics`, `/admin/resync?league=`, `/admin/sync-state`. Admin calls also require `ADMIN_TOKEN`.

## 10. Public hosting

### 10.1 Abuse and rate limiting
- Client identity: API key (`Authorization: Bearer`) if present and valid, otherwise IP. IP comes from `CF-Connecting-IP` / `X-Forwarded-For` **only when `TRUSTED_PROXY_CIDRS` matches the peer**, else the socket address.
- Defaults (configurable): anonymous **60 req/min, burst 20**; `refresh=true` **6/min**; keyed clients 600/min. In-memory token buckets in a size-bounded Caffeine map (expire after 10 min idle).
- 429 with `Retry-After`. MCP gets a JSON-RPC response carrying a tool error so clients degrade gracefully.
- Hard limits: request body ≤ 64 KB on `/mcp`, server-wide concurrency cap, Jetty idle/read timeouts, response size bounded by `limit`.
- Cloudflare in front for TLS, DDoS and WAF. Origin accepts only Cloudflare IPs (firewall) or uses Cloudflare Tunnel.
- API keys for MVP: hashed keys in config/env (`API_KEYS_SHA256`), issued manually.

### 10.2 Security
- No user-controlled host or path ever reaches an upstream URL. Leagues come from the allowlist and IDs are validated by regex per provider.
- CORS: `GET` on `/api/v1` from `*` (public read-only data). `/mcp` follows MCP Origin-validation guidance (reject untrusted `Origin` headers per spec).
- Logs: structured JSON with request IDs. **IPs are hashed with a rotating salt**, and request headers and API keys are never logged.

### 10.3 Operations
- Docker multi-stage build (`maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre`), non-root user, `/data` volume for SQLite, `HEALTHCHECK` on `/health/ready`.
- Compose example: app plus optional Caddy (for non-Cloudflare self-hosters).
- CI (GitHub Actions): build, test, OWASP/Trivy scan, publish multi-arch image to GHCR on tag. Dependabot.
- Docker isn't installed on the dev Mac. Local dev runs the fat jar, and CI builds images.
- Readiness = DB reachable **and** an initial schedule sync attempted for every enabled league (success not required, so an upstream outage doesn't make the pod unready).

### 10.4 Legal / attribution (owner: you; tracked as Sprint 0 task)
Publicly re-serving ESPN, NHL and MLB data is the **highest-risk** mode on the ToS axis. MLB responses carry an explicit copyright notice and terms link. Mitigations built into the plan:
- Per-provider and per-league kill switches in config, so a provider can be dropped without a redeploy of contracts.
- Attribution in every response `meta` and on the landing page.
- No logo proxying (ESPN CDN URLs only).
- Non-commercial positioning, and a README section stating these are unofficial endpoints.

**Your go/no-go on hosting is needed before Sprint 5 deploy**, not before development.

## 11. Configuration

Env vars (12-factor), with an optional `config.yaml` for league overrides:
`PORT`, `MGMT_PORT`, `BIND_HOST`, `DB_PATH=/data/sports.db`, `DEFAULT_TIMEZONE=America/New_York`, `ENABLED_LEAGUES`, `PROVIDER_{ESPN,NHL,MLB}_ENABLED`, `PROVIDER_*_BASE_URL` (for WireMock), `RATE_LIMIT_ANON_PER_MIN`, `RATE_LIMIT_REFRESH_PER_MIN`, `API_KEYS_SHA256`, `ADMIN_TOKEN`, `TRUSTED_PROXY_CIDRS`, `CORS_ORIGINS`, `RAW_PAYLOAD_CAPTURE`, `RETENTION_FINISHED_DAYS`, `SYNC_HORIZON_DAYS=14`. A sample `.env.example` is committed. No secrets in the repo.

## 12. Delivery sprints

### Sprint 0: Scaffold, spikes, fixtures (3 days)
1. `git init`, `.gitignore`, Apache-2.0 `LICENSE`, README skeleton, Maven wrapper, `pom.xml` with pinned versions, Spotless (google-java-format), GitHub Actions build.
2. **Spike A (gate):** Javalin 7 + `HttpServletStatelessServerTransport` (or the SDK 2.0.1 equivalent) mounted on Javalin's Jetty, with one `ping` tool callable from MCP Inspector and Claude Code (`claude mcp add --transport http`). If mounting fails, fall back to a Javalin handler adapter or standalone Jetty 12 for `/mcp`.
3. **Spike B:** confirm Flyway + SQLite on current versions, and the WAL/single-writer setup.
4. Fixture capture tool (`scripts/capture-fixtures`) that saves gzipped upstream payloads to `src/test/resources/fixtures/{provider}/{scenario}.json`. Capture timing is good this week: **NFL MNF BUF@LAR tonight (Oct 12, 8:15 PM EDT)**, NHL regular season, **MLB postseason**, NBA preseason (regular season ~Oct 20), college football Saturday, EPL and other soccer leagues returning after the international break. Scenarios: scheduled, live, halftime/intermission, OT/extra innings, final, postponed (find historical IDs), empty day. Off-season shapes: NFL/NBA dates in July, MLB dates in January.
5. Write `docs/upstream-contract.md`: endpoints, quirks (§3), status values seen, date-bucket timezone findings.
6. `/health/live` endpoint and a passing build.

**Acceptance:** Spike A green (tool call works end to end); fixtures for all four main leagues in at least scheduled/live/final states; build + CI passing; ToS review task created for you.

### Sprint 1: Domain, persistence, ESPN adapter, team catalogs (4–5 days)
Domain types, ID scheme, `LeagueRegistry` (9 leagues), `UpstreamHttp`, ESPN client/mapper/status mapper, MLB and NHL **team** catalogs plus seed files and abbreviation maps, Flyway V1, repositories, idempotent upserts, team search/resolver.
**Acceptance:** fixture tests pass for every ESPN league's status mappings and null-score rule; team catalogs for all 9 leagues load and survive restart; duplicate events never duplicate rows.

### Sprint 2: NHL + MLB adapters, schedule sync (4–5 days)
*Implementation notes (done 2026-10-12):* schedules are driven by a one-minute tick that reads `sync_state` (`SCHEDULE` = full window, `SCHEDULE_NEAR` = 48 h, `TEAMS`), so restarts and outages catch up without timers. Failing leagues retry every 5 minutes. `SYNC_ON_STARTUP` became `BACKGROUND_SYNC`. Admin endpoints (`/admin/resync`, `/admin/sync-state`) live on the management port now rather than in Sprint 5. MLB makeup games reuse the postponed game's `gamePk` (see upstream-contract.md), which is guarded in both the merge and the upsert.
NHL schedule/score mappers, MLB schedule/linescore mappers with 406 fallback, `ScheduleSyncScheduler` (6 h / 30 min / startup catch-up), `SyncState`, retention job, admin resync, postponed/makeup dedup.
**Acceptance:** after a cold start with no client traffic, D-1…D+14 games exist for all enabled leagues; postponed games update in place; empty off-season windows record success; restart preserves everything.

### Sprint 3: Live score coordinator (4–5 days)
*Implementation notes (done 2026-10-12):*
- **Refresh keys.** A refresh key is a league plus a **US-Eastern day bucket**, the Eastern date of the game's start. ESPN, NHL and MLB all slate by Eastern day, and no game in these leagues starts after midnight Eastern. That replaces Pulseboard's "also fetch yesterday before 3 AM" rule: a game that runs past midnight stays in yesterday's bucket.
- **Coalescing.** Caffeine `AsyncCache` handles single-flight. A finished fetch (success *or* failure) is reused for the 5 s floor, which bounds `refresh=true` too.
- **Freshness.** Each game's own `fetched_at` in SQLite is the source of truth, so there's no separate TTL cache to keep consistent.
- **Resilience.** Rate limits and breakers wrap every provider: ESPN 5/s, NHL 3/s, MLB 2/s, and the breaker opens at 50% failures over 20 calls for 30 s. 4xx responses other than 429 don't trip it.
- **Per-game feeds.** These are a provider capability (`hasPerGameFeed`); only MLB has one.
- **Slow upstreams.** A request waits up to 6 s for upstream, then serves stale data marked "still waiting". The fetch completes in the background for the next request.
`FreshnessPolicy`, Caffeine single-flight with variable expiry, late-game bucket logic, MLB per-game enrichment, Resilience4j limiters/breakers, stale fallback, `refresh=true` floor. Inject `java.time.Clock` everywhere.
**Acceptance:** 100 concurrent requests for one league/date make **one** upstream call; with the breaker open, the last snapshot comes back marked stale; clock-driven tests cover TTL boundaries, game-start transitions, midnight rollover and the DST change (Nov 1, 2026).

### Sprint 4: REST + MCP (4–5 days)
`SportsService` façade, DTOs, REST routes, `openapi.yaml`, MCP tools with input/output schemas and summaries, `TeamResolver` integration, error mapping, MCP resources (stretch).
**Acceptance:** contract test shows REST and MCP structured output are identical for the same query; MCP Inspector, Claude Code and Ack can answer "when do the Leafs play next?", "NFL scores right now" and "what MLB games are today?"; invalid inputs give bounded, actionable errors.

### Sprint 5: Public hardening and release (5–6 days)
Rate limiting/client identity, management port, CORS/Origin checks, IP hashing, Micrometer metrics (upstream calls/latency/errors, coalescing savings, cache hits, sync age, stale responses, breaker state, unmapped statuses), Dockerfile + Compose, GHCR publishing, landing page, README (self-host + hosted usage + attribution + disclaimer), runbook (schema breakage, provider kill switch, resync), WireMock fault suite (429/500/slow/truncated/changed fields), load test (k6 or JUnit) asserting the upstream call budget. VPS provisioning + Cloudflare once you give the go-ahead.
**Acceptance:** dev plan §10 Sprint 5 scenarios pass; a sustained anonymous flood from one IP gets 429s without raising upstream call volume; v0.1.0 tagged and the image published.

**Total: ~24–32 engineering days.**

## 13. Testing

- **Unit:** every status mapping per provider, null-score rule, ID/abbreviation maps, timezone/date-bucket math, team resolver ambiguity, freshness policy table.
- **Fixtures:** recorded payloads per provider × scenario, plus "changed field" mutations (missing `competitors`, string vs int scores, unknown status names).
- **Integration:** WireMock per provider (base URLs configurable), real SQLite in a temp dir, startup/restart, concurrent upserts.
- **Contract:** REST↔MCP equivalence; OpenAPI response validation; an optional nightly "live canary" job hitting real upstreams that alerts on shape drift (e.g. ESPN ranges starting to work, NHL field renames).
- **Load:** burst of N clients × M queries → assert upstream calls ≤ budget.

## 14. Risks and open items

| Risk | Mitigation |
|---|---|
| Unofficial endpoints change or block us | Fixture + canary tests, kill switches, stale fallback, provider SPI |
| ToS / redistribution exposure (public hosting) | §10.4. Your go/no-go before deploy |
| MCP SDK 2.0.1 servlet transport ↔ Javalin 7 integration | Sprint 0 Spike A gate, with fallbacks named |
| Upstream IP blocking from shared VPS traffic | Coalescing + global rate limits keep us around Pulseboard's footprint; identifying User-Agent |
| SQLite write contention during sync | Single-writer executor, WAL |
| College volume (hundreds of teams/games) | `limit`/`groups` params; college sync can run less often if needed |

**Open (non-blocking):** hosted domain (needed for the User-Agent and Sprint 5). Repo: `github.com/matthewmgamble/sports-mcp`; license confirmation (Apache-2.0 assumed); whether Ack needs anything beyond standard Streamable HTTP.

## 15. Follow-on backlog
Dev plan §13 still applies. Additions: ESPN fallback for MLB/NHL via the shared ID scheme; stdio transport (`java -jar sports-mcp.jar --stdio`) for local clients; OAuth per the MCP auth spec; CFL (Pulseboard already self-hosts its logos); team favourites batch endpoint; standings.
