# Sports MCP Server: Development Sprint Plan

**Project:** `sports-mcp`  
**Stack:** Java 21+, Maven, Javalin (or existing Java HTTP framework), Jackson, SQLite or MariaDB, Caffeine, MCP Java SDK  
**Interfaces:** MCP over Streamable HTTP; versioned REST API  
**Primary upstream:** ESPN public JSON endpoints  
**Status:** Proposed implementation plan, October 2026

## 1. Executive summary

Build a reusable sports data service that supplies current scores, completed results, upcoming fixtures, and team information to AI agents through MCP and conventional clients through REST. ESPN is the first data provider, but the service must hide provider-specific formats behind a normalized domain model. Schedule data is proactively cached, while live results are refreshed on demand with short, configurable freshness windows. Concurrent requests for the same game or league should share one upstream fetch.

**Important dependency risk:** ESPN's publicly accessible JSON endpoints are unofficial and undocumented. They can change or become restricted, and availability does not imply a license for unrestricted redistribution. Verify acceptable usage and attribution before deployment, especially for public or commercial distribution. Design a provider interface so an authorized alternative can replace ESPN without changing MCP/REST contracts.

## 2. Goals and non-goals

### Goals
- Provide game scores, statuses, start times, opponents, venues where available, and upcoming schedules.
- Support NFL, NHL, NBA, MLB in MVP, with league registry extensibility for NCAA, soccer, and other competitions.
- Support team-based and league-wide lookups; allow favorite teams to be configured by consuming applications rather than requiring user accounts in this service.
- Offer equivalent capabilities through MCP and REST, using one application/service layer.
- Persist upcoming events and recent final results so restart or upstream failure does not empty the UI.
- Refresh active scores when requested, without creating a separate ESPN call per agent/user request.
- Provide clear timestamps, data provenance, freshness and stale-data warnings.
- Operate securely and observably as a small, self-hosted service.

### Non-goals for MVP
- Betting, odds, fantasy data, player-level analytics, play-by-play, standings, or news.
- User authentication/profile storage for favorite teams.
- Push feeds, websockets, or live streaming updates.
- Promising second-by-second real-time data: scores are near-live and upstream dependent.

## 3. Reference architecture

```text
  Ack / other AI agents             Web / mobile / other clients
            |                                   |
       MCP Streamable HTTP                 REST /api/v1
            |                                   |
            +-------------+---------------------+
                          |
                  Sports application service
                /          |             \
        Team registry   Schedule store   Score coordinator
             |              |                 |
             +--------------+-----------------+
                            |
                  Provider abstraction
                            |
                     ESPN adapter
                            |
                  ESPN public JSON APIs

     Background schedule sync -> DB
     On-demand score fetch -> cache -> DB snapshot
```

Recommended package structure:

```text
com.example.sports
  api.rest          REST routes, DTOs, exception mapping
  api.mcp           MCP tools, resources, transport
  application       SportsService, ScoreRefreshCoordinator
  domain            League, Team, Game, GameStatus, Score
  provider          SportsProvider, ProviderException
  provider.espn     EspnClient, EspnMapper, EspnProvider
  persistence       repositories, migrations
  cache             cache policies, single-flight, stale fallback
  scheduler         schedule refresh jobs
  config            league registry, provider and security settings
  observability     metrics, health and tracing
```

## 4. ESPN integration

Use a configurable upstream base URL and a strict allowlist of league/sport pairs. Initially investigate these public JSON routes:

| Purpose | HTTP path |
|---|---|
| Scoreboard, results and fixtures | `GET /apis/site/v2/sports/{sport}/{league}/scoreboard` |
| Date-specific scoreboard | same endpoint with `?dates=YYYYMMDD` |
| Team directory | `GET /apis/site/v2/sports/{sport}/{league}/teams` |
| Team schedule | `GET /apis/site/v2/sports/{sport}/{league}/teams/{teamId}/schedule` |
| Event detail (optional) | `GET /apis/site/v2/sports/{sport}/{league}/summary?event={eventId}` |

Default base: `https://site.api.espn.com`. Map NFL=`football/nfl`, NHL=`hockey/nhl`, NBA=`basketball/nba`, MLB=`baseball/mlb`. Confirm payload shapes and date coverage for each league with recorded fixtures before committing to the adapter. Football week/season-type parameters differ from date-based sports; support league-specific schedule strategies. Paginate or window schedule queries where needed and ensure complete event coverage rather than assuming the default scoreboard includes an entire season.

Normalize event ID, league ID, team IDs, home/away, scheduled UTC time, status, period/clock if supplied, scores, and source timestamps. Treat absent scores as null, not zero. Store raw provider payloads only optionally and with bounded retention for troubleshooting.

## 5. Data model

**League:** `id`, `displayName`, `sport`, `providerKey`, `enabled`, `timezone` (display hint).  
**Team:** `id` (canonical), `providerId`, `leagueId`, `name`, `shortName`, `abbreviation`, `logoUrl?`.  
**Game:** `id` (canonical), `providerEventId`, `leagueId`, `homeTeamId`, `awayTeamId`, `startsAt` (UTC), `status` (`SCHEDULED`, `LIVE`, `FINAL`, `POSTPONED`, `CANCELLED`, `UNKNOWN`), `statusDetail?`, `venue?`, `broadcast?`.  
**ScoreSnapshot:** `gameId`, `homeScore?`, `awayScore?`, `period?`, `clock?`, `capturedAt`, `fetchedAt`, `provider`, `isStale`.  
**SyncState:** `leagueId`, `windowStart`, `windowEnd`, `lastSuccessAt`, `lastAttemptAt`, `error?`.

Database: start with SQLite for simple single-node deployment. Use Flyway migrations. Unique indexes on `(provider, providerEventId)` and `(leagueId, providerTeamId)`; index games by `(leagueId, startsAt)`, team references, and status. Preserve game rescheduling and status transitions idempotently. Keep normalized IDs opaque strings.

## 6. Cache and refresh policy

Separate **persistent schedule storage** from **ephemeral score freshness**. Suggested starting values, all configurable:

| Data/status | Refresh behavior | Suggested freshness |
|---|---|---|
| League/team catalog | Background and on cache miss | 24 hours |
| Upcoming games, next 14 days | Scheduled background refresh | Every 6 hours |
| Games starting within 48 hours | More frequent background refresh | Every 30 minutes |
| Live games | Refresh on client request if cache too old | 15 seconds |
| Scheduled game within 1 hour | Refresh on client request | 60 seconds |
| Final games, first 2 hours after completion | Refresh on client request | 5 minutes |
| Older final games | Serve stored snapshot | 24 hours or immutable after confirmation |
| No games / negative result | Cache briefly | 2 minutes |

**Refresh algorithm:** (1) read local events; (2) determine required freshness by status and proximity to start; (3) serve fresh cached data; (4) otherwise acquire per-league/date or per-event single-flight lock; (5) fetch the smallest effective scoreboard batch; (6) atomically upsert event and score snapshots; (7) return result with `fetchedAt` and `isStale`; (8) on timeout/error, return stale data when present with a warning. Explicit `refresh=true` may bypass TTL but must still respect rate limits and coalescing. Never trigger a full season fetch for every score request.

Use Caffeine for in-process TTL and request coalescing; persist the last good normalized snapshot. If running multiple replicas, use a shared Redis cache or distributed refresh lease to prevent multiplied ESPN traffic. Rate-limit upstream requests globally and per league, with exponential backoff, jitter, circuit breaker, and a maximum request timeout. Prefer batch scoreboards over per-game calls. Bound schedule refresh look-ahead and backfill; allow admin-triggered resync.

## 7. MCP contract

Use the official Java MCP SDK with **Streamable HTTP** as the default remote transport. Expose concise, agent-friendly descriptions and strict JSON schemas. Avoid exposing arbitrary ESPN URL fetching.

| Tool | Inputs | Output |
|---|---|---|
| `sports_list_leagues` | none | Enabled leagues |
| `sports_list_teams` | `league`, `search?` | Teams and canonical IDs |
| `sports_get_games` | `league?`, `teamId?`, `from?`, `to?`, `status?`, `limit?` | Normalized games |
| `sports_get_live_scores` | `league?`, `teamId?`, `refresh?` | Live games and score snapshots |
| `sports_get_game` | `gameId`, `refresh?` | One game and score |
| `sports_get_team_schedule` | `teamId`, `from?`, `to?`, `limit?` | Upcoming and recent games |

Optional read-only MCP resources: `sports://leagues`, `sports://leagues/{league}/teams`, and `sports://games/{gameId}`. Ensure tool responses include `asOf`, `source`, `isStale`, and meaningful errors. Bound date ranges, result counts and output size. Favor stable machine-readable structured content plus a short human-readable summary.

## 8. REST contract

All endpoints are read-only and return the same normalized DTOs as MCP:

```http
GET /api/v1/leagues
GET /api/v1/leagues/{league}/teams?search=pack
GET /api/v1/games?league=nfl&from=2026-10-12&to=2026-10-19
GET /api/v1/games/live?league=nhl
GET /api/v1/games/{gameId}?refresh=false
GET /api/v1/teams/{teamId}/schedule?from=2026-10-12&limit=10
GET /health/live
GET /health/ready
GET /metrics
```

Response envelope example:

```json
{
  "data": [{
    "id": "espn:401000000", "league": "nfl", "status": "LIVE",
    "startsAt": "2026-10-12T00:00:00Z",
    "home": {"id": "nfl:gb", "name": "Green Bay Packers", "score": 21},
    "away": {"id": "nfl:opponent", "name": "Example Opponent", "score": 17}
  }],
  "meta": {"source": "espn", "asOf": "2026-10-12T00:45:00Z", "isStale": false}
}
```

*Illustrative values only, not an actual game result.* Provide OpenAPI 3.1, consistent 400/404/429/502/503 error objects, UTC timestamps, ISO dates, pagination and request limits. Use ETag/Cache-Control where safe, but do not claim live data is immutable.

## 9. Security, operations and configuration

- Bind to localhost or a private network by default. For remote access, require TLS and configurable API-key or gateway authentication for REST and MCP; document MCP client authorization requirements.
- Validate league IDs, date ranges, page sizes and `refresh` usage; block arbitrary host/path input to prevent SSRF.
- Configure CORS explicitly. Protect health/metrics and any administrative sync endpoint as appropriate.
- Supply Dockerfile, Maven wrapper, sample `.env`, config YAML and Docker Compose example. Never commit secrets.
- Emit structured logs with correlation IDs; avoid logging credentials or sensitive request headers.
- Metrics: ESPN request count/latency/errors, cache hit ratio, single-flight savings, schedule sync age, stale responses, live refresh lag, circuit-breaker state and per-league freshness.
- Define SLO targets after baseline testing; alert on schedule age, persistent upstream failures and sustained stale-live responses.
- Make provider attribution and any upstream terms/licensing constraints visible in deployment docs.

## 10. Delivery sprints

### Sprint 0: Provider discovery and project scaffold (2–3 days)
**Tasks:** Validate ESPN response examples across four leagues, season/offseason, live/final/postponed games; confirm use/attribution requirements; select Javalin or existing framework; initialize Java 21 Maven modules, formatting, CI, Docker and Flyway.  
**Acceptance:** A documented ESPN contract/fixture set, runnable skeleton, health endpoint, build and tests passing. Provider limitations recorded.

### Sprint 1: Normalized model and persistence (3–4 days)
**Tasks:** Implement domain DTOs, status mapper, league registry, provider SPI, DB schema/repositories, idempotent upsert and team catalog sync.  
**Acceptance:** All four league mappings pass fixture tests; restarted service retains games; duplicate upstream events do not create duplicate records.

### Sprint 2: Schedule ingestion and background cache (3–5 days)
**Tasks:** Build date/week-aware schedule fetchers, 14-day horizon refresh, near-term refresh, missed-job catchup, configurable retention and admin-only resync.  
**Acceptance:** Future games load without an agent request, schedules survive restart, postponed games update correctly, empty off-season results are handled.

### Sprint 3: Live score coordinator (3–5 days)
**Tasks:** Add Caffeine, adaptive TTL, request-driven polling, per-key single-flight, batched scoreboard fetch, timeouts, retries, stale fallback and provider rate limiter.  
**Acceptance:** 100 simultaneous requests for one league/date cause at most one eligible upstream refresh; fresh requests use cache; provider outage returns last known snapshot clearly marked stale.

### Sprint 4: REST and MCP interfaces (4–5 days)
**Tasks:** Implement all MVP endpoints/tools through shared service, input schemas, OpenAPI, MCP transport, auth, errors and integration examples.  
**Acceptance:** REST and MCP return equivalent data; tools work with Ack-compatible MCP client; invalid filters produce bounded actionable errors.

### Sprint 5: Hardening and release (3–4 days)
**Tasks:** Add integration/load tests, mock ESPN server, health/metrics, dashboards, alert examples, CI dependency scanning, docs, Docker deployment and versioned release.  
**Acceptance:** Cold-start, restart, upstream outage, malformed JSON, timeout, concurrent load and offseason test scenarios pass. Runbook documents provider schema breakage and recovery.

**Estimated effort:** Approximately 18–26 engineering days, excluding external licensing/terms review and deployment approvals.

## 11. Testing strategy

- Unit tests for every status mapping, null score, timezone conversion, league mapping and rescheduled game.
- Recorded ESPN fixtures for scheduled, live, halftime/intermission, final, postponed, cancelled, overtime and empty responses.
- WireMock/MockWebServer integration tests for 429, 500, slow responses, truncated JSON and changed fields.
- DB migration and restart tests, including concurrent upserts.
- Contract tests that compare MCP and REST normalized outputs for identical queries.
- Load tests for repeated dashboard polling and bursts from many agents; assert upstream call budget.
- Clock-controlled tests for TTL boundaries, game start transitions, finalization and daylight saving transitions.

## 12. Decisions and defaults

| Decision | MVP default | Future option |
|---|---|---|
| Service | Standalone Java service | Integrate into shared service platform |
| HTTP framework | Javalin | Spring Boot if existing operational standard requires |
| MCP transport | Streamable HTTP | stdio adapter for local clients |
| Storage | MariaDB or SQLite | Postgres or shared storage |
| Hot cache | Caffeine | Redis for multi-replica |
| Source | ESPN adapter | Licensed second provider |
| Schedule horizon | Rolling 14 days | Season-wide prefetch |
| Live polling | On-demand, 15-second minimum freshness | Push/event-driven updates |
| Favorites | Client-owned team IDs | Optional user preference service |

## 13. Follow-on backlog

1. Team favorites batch endpoint, e.g. `GET /api/v1/teams/games?ids=...`, to minimize mobile home-screen calls.
2. Standings, records, playoff brackets, game summaries and player stats.
3. WebSocket/SSE subscription layer with a shared poller and subscriber-aware refresh frequency.
4. Additional leagues, including CFL and major soccer leagues; team identity aliases and search improvements.
5. Multi-provider fallback with source selection, field-level provenance and licensing compliance.
6. Historical results archive, user-defined notifications, and configurable timezone presentation.
7. Distributed caching/locking and horizontally scaled deployment.

## 14. Definition of done

The release is complete when an agent can ask for a team's next game, today's league games or current live scores through MCP; a generic application can obtain the same data through REST; upcoming games are available from persistent cache before any client asks; concurrent live requests do not overwhelm ESPN; stale data is explicitly marked; and the service remains usable for cached schedules during a temporary upstream outage.

## 15. References and validation notes

- Community-maintained ESPN endpoint catalogue: https://github.com/sejaldua/espn-api/blob/main/references/endpoints.md
- Community-maintained ESPN API reference: https://github.com/DanielTomaro13/sportsdata-mcp/blob/main/documentation/ESPN.md
- ESPN public JSON endpoint example: https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard

These are unofficial references. Reconfirm endpoint behavior and permissible use before production launch.
