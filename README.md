# sports-mcp

Sports scores, schedules and team information for AI agents (MCP) and regular apps (REST).

> **Status: Sprint 4 (interfaces).** MCP tools and the REST API are live over background-synced
> schedules and on-demand live scores. Public-hosting hardening (rate limits, metrics, deploy)
> is Sprint 5. See [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md).

**Leagues:** NFL, NHL, NBA, MLB, NCAA football, NCAA men's basketball, Premier League,
La Liga, Bundesliga.

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

### MCP tools

| Tool | Use it for |
|---|---|
| `sports_list_leagues` | Which leagues are covered (ids for other tools) |
| `sports_list_teams` | Find a team by name, city, abbreviation or nickname ("leafs") |
| `sports_get_games` | A league's or team's games for a day or date range, with scores |
| `sports_get_live_scores` | Everything in progress now (optionally one league or team) |
| `sports_get_game` | One game by id |
| `sports_get_team_schedule` | A team's last 3 results and next 5 games, or a date range |

Team arguments take ids (`nhl:tor`) or names (`Maple Leafs`); ambiguous names return the
candidates. Every result has `structuredContent` (`{data, meta}`) plus a text summary;
`meta.isStale` and `meta.staleReason` say when upstream couldn't be reached.

### REST

The same operations, same envelope ([OpenAPI](http://localhost:8080/openapi.json)):

```sh
curl "localhost:8080/api/v1/games?league=nhl&date=today&tz=America/Toronto"
curl "localhost:8080/api/v1/games/live"
curl "localhost:8080/api/v1/teams/leafs/schedule"
curl "localhost:8080/api/v1/teams?search=packers"
curl "localhost:8080/api/v1/games/nfl:401872994?refresh=true"
```

Configuration is via environment variables; see [`.env.example`](.env.example).

### Operations

A separate management listener (default `127.0.0.1:9090`) serves admin endpoints when
`ADMIN_TOKEN` is set:

```sh
curl -H "Authorization: Bearer $ADMIN_TOKEN" localhost:9090/admin/sync-state
curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" "localhost:9090/admin/resync?league=nhl"
curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" "localhost:9090/admin/resync?league=mlb&from=2026-09-20&to=2026-09-30"
curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" "localhost:9090/admin/resync?league=nfl&kind=teams"
```

### Docker

```sh
docker build -t sports-mcp .
docker run -p 8080:8080 -v sports-data:/data sports-mcp
```

## Development

```sh
./mvnw verify                           # build + tests
./mvnw spotless:apply                   # format (google-java-format, AOSP style)
scripts/capture-fixtures.sh manifest    # re-record upstream fixtures
scripts/capture-fixtures.sh watch 300 180   # snapshot live games every 5 min for 3 h
```

Upstream quirks and the fixture index are documented in
[`docs/upstream-contract.md`](docs/upstream-contract.md).

## Data sources and attribution

Data comes from **unofficial, undocumented public endpoints** run by ESPN (`site.api.espn.com`),
the NHL (`api-web.nhle.com`) and MLB Advanced Media (`statsapi.mlb.com`). This project is not
affiliated with or endorsed by any of them. Those endpoints can change or disappear at any time,
and their content is subject to the owners' terms. MLB data is © MLB Advanced Media, L.P.
Scores are near-live and may lag the broadcast.

## License

[Apache-2.0](LICENSE)
