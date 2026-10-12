# sports-mcp

Sports scores, schedules and team information for AI agents (MCP) and regular apps (REST).

> **Status: Sprint 3 (live scores).** Schedules sync in the background; live scores refresh
> on demand with per-league-day request coalescing, rate limits, circuit breakers and stale
> fallback. MCP exposes only `sports_ping` until Sprint 4.
> See [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) for the roadmap.

**Leagues (planned MVP):** NFL, NHL, NBA, MLB, NCAA football, NCAA men's basketball, Premier League,
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
