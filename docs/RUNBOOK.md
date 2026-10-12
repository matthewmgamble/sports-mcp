# sports-mcp runbook

Operating the hosted instance (or your own). Architecture and design rationale live in
[`IMPLEMENTATION_PLAN.md`](../IMPLEMENTATION_PLAN.md); upstream quirks in
[`upstream-contract.md`](upstream-contract.md).

## At a glance

| | |
|---|---|
| Public port (8080) | `/mcp` (MCP, stateless Streamable HTTP), `/api/v1/*` (REST), `/openapi.json`, `/`, `/health/live`, `/health/ready` |
| Management port (9090, never public) | `/metrics` (Prometheus), `/admin/sync-state`, `/admin/resync` (Bearer `ADMIN_TOKEN`), `/health/live` |
| State | One SQLite file (`DB_PATH`, `/data/sports.db` in Docker). Safe to delete: everything re-syncs from upstream within minutes |
| Upstreams | ESPN (NFL, NBA, NCAAF, NCAAB, EPL, La Liga, Bundesliga), NHL api-web, MLB statsapi |
| Background work | One-minute tick: team lists daily, full schedule window (yesterday..+14d) every 6 h, next 48 h every 30 min, retention daily. Failing leagues retry every 5 min |
| On-demand | Live/pregame games refresh when requested and older than their freshness window (live: 15 s). One scoreboard call per league per US-Eastern day, shared by all requests, at most every 5 s |

## Deploy

```sh
cp .env.example .env          # set ADMIN_TOKEN, TRUSTED_PROXY_CIDRS (see below)
docker compose pull && docker compose up -d
docker compose logs -f sports-mcp
curl -fsS localhost:8080/health/ready
```

**Behind Cloudflare**, set `TRUSTED_PROXY_CIDRS` to Cloudflare's ranges
(<https://www.cloudflare.com/ips-v4>, <https://www.cloudflare.com/ips-v6>, comma-separated) so
rate limits apply to real client IPs. Firewall the origin to those ranges too. Without this,
every visitor looks like one Cloudflare IP and they'll share one rate-limit bucket.

**Rollback:** `SPORTS_MCP_TAG=<previous> docker compose up -d`. Schema migrations are additive;
an older build refuses to start against a newer schema (Flyway validation), in which case
restore the DB backup taken before the upgrade, or delete it and let it re-sync.

**Release:** bump `<version>` in `pom.xml`, commit, `git tag vX.Y.Z && git push --tags`. The
`release` workflow tests, publishes `ghcr.io/matthewmgamble/sports-mcp:{X.Y.Z,X.Y,latest}`
(amd64 + arm64) and creates a GitHub release.

## Health and alerting

| Signal | Query / check | Suggested alert |
|---|---|---|
| Process up | `GET /health/live` | 2 consecutive failures |
| DB usable | `GET /health/ready` | 2 consecutive failures |
| Schedule freshness | `sports_sync_age_seconds{kind="SCHEDULE"}` | > 14400 (4 h) for any enabled league; `-1` = never synced |
| Upstream errors | `rate(sports_upstream_request_seconds_count{outcome!="ok"}[10m])` vs ok | error ratio > 50% for 15 min |
| Circuit breakers | `sports_circuit_state` (0 closed, 1 half-open, 2 open) | == 2 for 10 min |
| Stale responses | `rate(sports_stale_responses_total[10m])` | sustained > 0 for live hours |
| MLB fallback | `rate(sports_fallback_total{league="mlb"}[1h])` by operation/outcome | `outcome="failed"` sustained (both statsapi and ESPN failing); `ok` is normal from datacenter IPs |
| Schema drift | `sports_status_unmapped_total` | any increase (see "Upstream changed its payload") |
| Abuse | `rate(sports_rate_limited_total[5m])`, `sports_overloaded_total` | spikes: investigate, maybe add a WAF rule |
| Latency | `sports_http_request_seconds` by interface/status | p95 > 2 s |

`/admin/sync-state` shows per-league sync bookkeeping, game counts and breaker states in one
JSON document:

```sh
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" localhost:9090/admin/sync-state | jq
```

## Playbooks

### Upstream outage (ESPN/NHL/MLB down or slow)
Symptoms: `sports_circuit_state=2` for a provider, `meta.isStale=true` in responses,
`staleReason` naming the provider.
- Nothing to do in the moment: clients get the last good data, clearly marked stale; the breaker
  stops us hammering upstream and probes again every 30 s; background sync retries every 5 min.
- If it lasts hours, check the upstream from the host (`curl -sI` the URLs in
  upstream-contract.md). If they're blocking us (403/429 for every call), see "We're blocked".

### MLB: statsapi refusing (HTTP 406)
statsapi answers some hosts (datacenter IPs) with 406 for anything but today. Expected and handled:
refused days come from ESPN, linked onto statsapi game ids (`game_links`); ESPN-only games show as
`mlb:espn<eventId>` until statsapi returns them. Live scores stay on statsapi. Only act if
`sports_fallback_total{outcome="failed"}` climbs (ESPN failing too): then it's a normal outage.

### Upstream changed its payload
Symptoms: `sports_status_unmapped_total` rising, `outcome="parse"` upstream errors, games
missing or stuck in a status, breaker opening on PARSE errors.
1. Capture what upstream returns now: `scripts/capture-fixtures.sh live` (or the specific URL).
2. Diff against the recorded fixture in `src/test/resources/fixtures/` for that endpoint.
3. Add the new payload as a fixture (and to `scripts/fixtures.manifest`), write the failing
   test in the provider's `*MapperTest`/`*ProviderTest`, fix the mapper.
4. Unknown status names already degrade gracefully (mapped by `state`, counted in the metric);
   add them to the status mapper so they're explicit.
5. Release. Meanwhile, if the data is wrong (not just missing), take the league offline (below).

### Take a league or provider offline (kill switch)
Set `ENABLED_LEAGUES` to the leagues to keep and restart, e.g. drop all NHL:
`ENABLED_LEAGUES=nfl,nba,mlb,ncaaf,ncaab,epl,laliga,bundesliga`. Disabled leagues disappear from
tools, REST and background sync; their stored data is ignored and later cleaned by retention.
Dropping a whole provider = disabling its leagues (ESPN: nfl,nba,ncaaf,ncaab,epl,laliga,bundesliga;
NHL: nhl; MLB: mlb).

### Force a resync
```sh
# full window (yesterday..+14d) for one league
curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" "localhost:9090/admin/resync?league=nhl"
# a specific window (max 62 days), e.g. to backfill after an outage
curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" "localhost:9090/admin/resync?league=mlb&from=2026-09-20&to=2026-09-30"
# team list
curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" "localhost:9090/admin/resync?league=nfl&kind=teams"
```
Nuclear option: stop, delete the DB file, start. Teams and schedules rebuild in about 30 s.

### Abuse / traffic spike
- Per-client limits (`RATE_LIMIT_PER_MIN`, `RATE_LIMIT_BURST`, `RATE_LIMIT_REFRESH_PER_MIN`)
  apply to REST and MCP tool calls; upstream traffic doesn't grow with request volume.
- `MAX_CONCURRENT_REQUESTS` (200) sheds load with 503 before the JVM is in trouble.
- Access logs (`logger=access`) carry a daily-rotating hash of the client IP: correlate one
  abuser within a day, then block at Cloudflare (WAF rule / rate-limiting rule) by the IP shown
  in Cloudflare's own logs.
- A partner who needs more: issue an API key (below); keyed clients get
  `RATE_LIMIT_KEYED_MULTIPLIER` times the anonymous limits.

### Issue or revoke an API key
```sh
KEY=$(openssl rand -hex 24); echo "give to partner: $KEY"
echo -n "$KEY" | shasum -a 256 | cut -d' ' -f1   # append to API_KEYS_SHA256 (comma-separated)
docker compose up -d                               # restart picks up the change
```
Revoke by removing its hash. Keys are only ever stored hashed.

### We're blocked by an upstream
Symptoms: every call to one provider returns 403/429 for hours.
- Check our volume first: `sports_upstream_request_seconds_count` by provider. Normal is a few
  thousand calls a day per provider.
- Lower our footprint: raise `FRESH_LIVE_SECONDS` / `REFRESH_FLOOR_SECONDS`, or disable that
  provider's leagues. Don't rotate IPs to evade a block.
- Revisit the terms question in IMPLEMENTATION_PLAN.md §10.4.

### Rotate the admin token
Change `ADMIN_TOKEN` in `.env`, `docker compose up -d`. Blank disables the admin API.

### Backup / restore
```sh
docker compose exec sports-mcp sqlite3 /data/sports.db ".backup /data/backup.db"   # if sqlite3 is installed
# or: stop the container and copy the volume's sports.db (plus -wal/-shm files)
```
The DB is a cache of public data, so losing it only costs a re-sync.

## Hosted instance
`https://sports-mcp.pulseboard.ca` runs on appsrv01 in the shared `/srv/stack` compose project:
Cloudflare → tunnel (`cloudflared`, catch-all to nginx) → nginx vhost `nginx/conf.d/sports-mcp.conf`
→ `sports-mcp` container (amazoncorretto:21 running `apps/sports-mcp/sports-mcp.jar`, settings in
`apps/sports-mcp/env`, data in `data/sports-mcp`). No public ports; only nginx (172.30.7.10) can
reach the app, which is why `TRUSTED_PROXY_CIDRS=172.30.7.10/32` (nginx passes Cloudflare's
`CF-Connecting-IP`). Metrics/admin: `curl 172.30.7.20:9090/metrics` from the host.

Update: copy the new jar over `apps/sports-mcp/sports-mcp.jar` (keep the old one as `.prev`), then
`docker compose restart sports-mcp`. Recreating nginx blips every site on the box.

## Configuration reference
See [`.env.example`](../.env.example); every setting has a safe default.
