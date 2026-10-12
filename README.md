# sports-mcp

Live scores, schedules and team information for AI agents (MCP) and regular apps (REST).

> **Status: v0.1 release candidate.** MCP tools and REST over background-synced schedules and
> on-demand live scores, with per-client rate limits, metrics and a Docker deployment.
> The [runbook](docs/RUNBOOK.md).

**Leagues:** NFL, NHL, NBA, MLB, NCAA football, NCAA men's basketball, Premier League, La Liga,
Bundesliga.

## Quick start

Requires Java 21.

```sh
./mvnw package
java -jar target/sports-mcp.jar          # listens on 127.0.0.1:8080
curl localhost:8080/health/ready
```

Add it to an MCP client (Streamable HTTP):

```sh
claude mcp add --transport http sports http://localhost:8080/mcp
# or inspect it:
npx @modelcontextprotocol/inspector --cli http://localhost:8080/mcp --transport http --method tools/list
```

Configuration is via environment variables; see [`.env.example`](.env.example).

### Docker

```sh
docker run -p 8080:8080 -v sports-data:/data ghcr.io/matthewmgamble/sports-mcp:latest
# or, with the compose file (optional Caddy for TLS):
cp .env.example .env && docker compose up -d
```

Running it publicly? Read the [runbook](docs/RUNBOOK.md): set `TRUSTED_PROXY_CIDRS` when behind
Cloudflare or another proxy so rate limits see real client IPs, and keep the management port
(9090) private.

## MCP tools

| Tool | Use it for |
|---|---|
| `sports_list_leagues` | Which leagues are covered (ids for other tools) |
| `sports_list_teams` | Find a team by name, city, abbreviation or nickname ("leafs") |
| `sports_get_games` | A league's or team's games for a day or date range, with scores |
| `sports_get_live_scores` | Everything in progress now (optionally one league or team) |
| `sports_get_game` | One game by id |
| `sports_get_team_schedule` | A team's last 3 results and next 5 games, or a date range |
| `sports_get_team` | One team's season so far: record, standing and colour |

Team arguments take ids (`nhl:tor`) or names (`Maple Leafs`); ambiguous names return the
candidates. Every result has `structuredContent` (`{data, meta}`) plus a text summary;
`meta.isStale` and `meta.staleReason` say when upstream couldn't be reached. Postseason games carry
`round` ("ALCS Game 4") and `series` ("TOR leads 2-1"); `startTimeTbd: true` marks a game whose
date is set but time isn't.

## REST

The same operations and the same envelope ([OpenAPI 3.1](http://localhost:8080/openapi.json)):

```sh
curl "localhost:8080/api/v1/games?league=nhl&date=today&tz=America/Toronto"
curl "localhost:8080/api/v1/games/live"
curl "localhost:8080/api/v1/teams/leafs/schedule"
curl "localhost:8080/api/v1/teams/nhl:tor"            # record, standing, colour
curl "localhost:8080/api/v1/teams?search=packers"
curl "localhost:8080/api/v1/games/nfl:401872994?refresh=true"
```

## Limits and privacy

Anonymous clients get about 60 requests a minute (REST and MCP tool calls; MCP discovery calls
are free), and `refresh=true` about 6 a minute. Partners can be issued API keys with higher
limits. Upstream traffic doesn't grow with request volume: one scoreboard call per league-day
serves everyone, at most every 5 seconds. Access logs record a daily-rotating salted hash of the
client IP, never the IP itself.

## Operations

- [`docs/RUNBOOK.md`](docs/RUNBOOK.md): deploy, alerting, outage and schema-change playbooks,
  kill switches, API keys, backups.
- Management port (default `127.0.0.1:9090`): `/metrics` (Prometheus), `/admin/sync-state`,
  `/admin/resync` (Bearer `ADMIN_TOKEN`).

## Development

```sh
./mvnw verify                               # build + tests
./mvnw spotless:apply                       # format (google-java-format, AOSP style)
scripts/capture-fixtures.sh manifest        # re-record upstream fixtures
scripts/capture-fixtures.sh watch 300 180   # snapshot live games every 5 min for 3 h
```

Upstream quirks and the fixture index are documented in
[`docs/upstream-contract.md`](docs/upstream-contract.md).

## Data sources and attribution

Data comes from **unofficial, undocumented public endpoints** run by ESPN (`site.api.espn.com`),
the NHL (`api-web.nhle.com`) and MLB Advanced Media (`statsapi.mlb.com`). This project is not
affiliated with or endorsed by any of them. Those endpoints can change or disappear at any time,
and their content is subject to the owners' terms. MLB data is © MLB Advanced Media, L.P. Team
names and logos belong to their owners. Scores are near-live and may lag the broadcast.

## License

[Apache-2.0](LICENSE)
