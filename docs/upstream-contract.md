# Upstream contract notes

What we rely on from each upstream, verified against live responses on **2026-10-12**. All three are
**unofficial, undocumented** public endpoints; they can change without notice. The recorded payloads
in `src/test/resources/fixtures/` (see `scripts/fixtures.manifest`) are the source of truth for tests.

Recapture with `scripts/capture-fixtures.sh manifest`; grab live game states with
`scripts/capture-fixtures.sh live` (or `watch`) and copy interesting snapshots into fixtures by hand.

---

## General HTTP behaviour

| | ESPN | NHL | MLB |
|---|---|---|---|
| Base | `https://site.api.espn.com/apis/site/v2/sports` | `https://api-web.nhle.com/v1` | `https://statsapi.mlb.com/api/v1` |
| Encoding | **gzip even without `Accept-Encoding`**; Java `HttpClient` won't decode it for us | gzip when asked | gzip when asked |
| Redirects | — | **`…/now` URLs 307 to a concrete season/date**; follow redirects | — |
| Caching | `cache-control: max-age=1` | — | CDN-cached; schedule linescore lags |
| Errors | Bad params → **HTTP 400** `{"code":400,"message":"Failed to get events endpoint."}` | — | Range queries intermittently **406** (seen in Pulseboard) |
| Legal | Unofficial | Unofficial | Response body carries `copyright` (MLBAM, terms at `gdx.mlb.com/components/copyright.txt`) |

Client rules: always send `Accept-Encoding: gzip` and decode by `Content-Encoding`; follow redirects;
send an identifying `User-Agent`; connect timeout 3 s, request timeout 5 s.

---

## ESPN

### Endpoints used

| Purpose | Path | Notes |
|---|---|---|
| Day slate | `/{sport}/{league}/scoreboard?dates=YYYYMMDD` | **Single dates only.** `dates=A-B` ranges return HTTP 400 |
| Current slate | `/{sport}/{league}/scoreboard` | NFL: whole current week + `week.teamsOnBye` |
| NFL week | `…/football/nfl/scoreboard?dates=2026&seasontype=2&week=6` | Works; not needed by the per-day strategy |
| Teams | `/{sport}/{league}/teams` | College: `?limit=1000` |
| Team schedule | `/{sport}/{league}/teams/{abbr\|id}/schedule` | Full season (NHL TOR: 84 events) |

League paths: `football/nfl`, `basketball/nba`, `football/college-football`,
`basketball/mens-college-basketball`, `soccer/eng.1`, `soccer/esp.1`, `soccer/ger.1`.

### Date buckets
`dates=` buckets are **US Eastern** days: the Oct 12 MNF game (`2026-10-13T00:15Z`, 8:15 PM EDT)
is in `dates=20261012`. Store `startsAt` as UTC; use buckets only to decide what to fetch.

### College coverage
- NCAAF: default and `groups=80` both returned 80 games on 2026-09-12, so the default is FBS.
- **NCAAB: default returned 17 games vs 132 with `groups=50&limit=400`** on 2026-02-14. The default is a
  featured/Top-25 subset, so always pass `groups=50` (Division I) and a high `limit`.

### Event shape (fields we map)
```
events[].id                                  provider event id (string)
events[].date                                UTC start, minute precision: "2026-10-13T00:15Z"
events[].status.type.{name,state,shortDetail,completed}
events[].status.{period,displayClock}
events[].competitions[0].venue.fullName
events[].competitions[0].broadcasts[].names[]
events[].competitions[0].situation           live NFL only: possession, downDistanceText, isRedZone
events[].competitions[0].competitors[]       exactly two; homeAway = "home" | "away"
    .score                                   STRING, "0" before kickoff (see null rule)
    .team.{id,abbreviation,displayName,shortDisplayName,location,name,logo,logoDark}
```

### Status values seen
| `type.name` | `state` | `shortDetail` examples | Leagues |
|---|---|---|---|
| `STATUS_SCHEDULED` | pre | `10/12 - 8:15 PM EDT` | all |
| `STATUS_FINAL` | post | `Final`, `Final/OT`, `Final/2OT`, `Final/4OT` | NFL, NBA, NCAAF, NCAAB |
| `STATUS_FULL_TIME` | post | `FT` | EPL, La Liga, Bundesliga |
| **`STATUS_POSTPONED`** | **post** | `Postponed` | NCAAB (2026-02-14, D1 fixture) |

**Postponed games have `state: "post"`**, the same as finals. Map on `type.name` first and fall back to
`state` only for names we don't recognise (Pulseboard maps `post` straight to final, which is wrong
for this case). Not seen yet, but expected from community docs: `STATUS_IN_PROGRESS`,
`STATUS_HALFTIME`, `STATUS_END_PERIOD`, `STATUS_DELAYED`, `STATUS_RAIN_DELAY`, `STATUS_SUSPENDED`,
`STATUS_CANCELED`, `STATUS_ABANDONED`, `STATUS_FINAL_AET`, `STATUS_FINAL_PEN`. Capture live examples with
`capture-fixtures.sh watch` during games.

Scanning ~200 past dates (EPL/La Liga 2025-26, NCAAF 2025, NBA 2025-26, NFL 2025) found no other
postponed/cancelled events. ESPN appears to move postponed events to the makeup date, so the original
date shows nothing.

### Null-score rule
Every competitor score is a string, and scheduled games report `"0"` (28 of 28 in the NFL week 6
fixture). Scores are `null` unless the normalised status is LIVE, FINAL or SUSPENDED.

### Teams and logos
`team.id` is numeric (string); `team.abbreviation` is 2–4 chars. Logo CDN:
`https://a.espncdn.com/i/teamlogos/{nfl|nba|nhl|mlb|ncaa|soccer}/500/{abbr-lowercase|id}.png`.
College and soccer logos are keyed by numeric id, pro leagues by lowercase abbreviation.

---

## NHL (api-web.nhle.com)

| Purpose | Path | Notes |
|---|---|---|
| Live/day scores | `/score/{YYYY-MM-DD}` | **Includes `clock` and `periodDescriptor` for every game**, so one call covers live clocks for the day |
| Schedule | `/schedule/{YYYY-MM-DD}` | Returns a 7-day `gameWeek`; `nextStartDate` skips empty weeks (off-season: jumps to preseason), so page by it and stop once past the window |
| Team season | `/club-schedule-season/{ABBR}/{20262027}` | `/now` 307-redirects here |
| Teams | `/standings/now` | 32 teams; `teamAbbrev.default`, `teamName.default` (+`fr`). May be empty off-season, so ship a seed |

Game fields: `id` (e.g. `2026020075`), `startTimeUTC`, `gameState`, `gameScheduleState`, `gameType`
(1 pre, 2 regular, 3 playoffs), `venue.default`, `tvBroadcasts[]`, `homeTeam/awayTeam.{id,abbrev,name.default,score,sog}`,
`periodDescriptor.{number,periodType,maxRegulationPeriods}`, `clock.{timeRemaining,running,inIntermission}`,
`gameOutcome.lastPeriodType` (REG/OT/SO).

States seen: `OFF` + `OK` with `lastPeriodType` REG / OT / SO. Documented elsewhere: `FUT`, `PRE`, `LIVE`,
`CRIT`, `FINAL`; schedule states `PPD`, `SUSP`, `CNCL`. `gameScheduleState` takes priority over `gameState`.

NHL abbreviation → ESPN abbreviation (only those that differ): `LAK→la, SJS→sj, NJD→nj, TBL→tb, UTA→utah`.
Verified against ESPN's NHL team list (`espn/nhl/teams` fixture); `NhlProviderTest` enforces it.

---

## MLB (statsapi.mlb.com)

| Purpose | Path | Notes |
|---|---|---|
| Schedule | `/schedule?sportId=1&date=YYYY-MM-DD&hydrate=linescore,team` | Single date is reliable |
| Schedule range | `…&startDate=…&endDate=…` | Worked today; Pulseboard saw intermittent 406, so fall back to per-day (paced 1 s, stop at the first 406) |
| Live linescore | `/game/{gamePk}/linescore` | ~1 KB, fresher than the schedule's hydrated linescore |
| Teams | `/teams?sportId=1` | 30 teams; numeric ids → ESPN abbreviation via `MlbProvider.ESPN_ABBR` (MLB `AZ`/`CWS` are ESPN `ari`/`chw`; enforced against the `espn/mlb/teams` fixture) |

### HTTP 406 depends on the client
From the hosted server (appsrv01, a datacenter IP), statsapi answers **HTTP 406** `{"status":406,
"error":"Not Acceptable"}` to most schedule requests other than today's date: ranges, single dates
two or more days out, sometimes the game feed. This persisted after 20+ minutes of silence and at
6 s spacing. The same requests from a home connection always succeed, and Pulseboard (same DC) only
ever asks for today, so it never sees it. Rapid bursts also trigger it (Pulseboard's note).

So MLB runs through `MlbWithEspnFallback`: statsapi first for everything (live path: day scoreboard
plus `game/{pk}/linescore`), and any day statsapi refuses comes from ESPN's
`baseball/mlb/scoreboard?dates=`. ESPN games are linked to statsapi gamePks in `game_links`
(matched by home/away team and US-Eastern day; doubleheaders paired in start order, since statsapi
gives game 2 a nominal time, e.g. BAL@NYY 2026-09-25 20:05/20:10 vs ESPN 20:05/23:30). Unmatched
ESPN games get a provisional id `mlb:espn{eventId}` until statsapi sees them; that id keeps
resolving afterwards. ESPN lists unseeded postseason games as TBD vs TBD; they're skipped. ESPN's
team schedule takes the abbreviation (`teams/lad/schedule`). ESPN creates a new event id for a
makeup game (TOR@BAL 2026-09-23 game 1 is `401923610`), where statsapi reuses the gamePk.

Fixtures: `espn/mlb/scoreboard-20260925-doubleheaders` with `mlb/schedule-20260925-doubleheaders`,
`espn/mlb/scoreboard-2026092{2,3}-*`, `espn/mlb/scoreboard-20261024-tbd`,
`espn/mlb/team-schedule-lad`.

### Status values seen
| abstractGameState | detailedState | codedGameState | statusCode | → normalised |
|---|---|---|---|---|
| Final | Final | F | F | FINAL |
| **Final** | **Postponed** | **D** | DR | POSTPONED |
| **Final** | **Cancelled** | **C** | CR | CANCELLED |

**Postponed and cancelled games report `abstractGameState: "Final"`.** Check `codedGameState` first
(`D` postponed, `C` cancelled, `T`/`U` suspended per community docs), then `abstractGameState`
(Preview / Live / Final).

`gameType` values seen: `R` regular, `F` wild card, `D` division series, `L` league championship.
Expected: `W` World Series, `S` spring training (excluded by default), `A` All-Star, `E` exhibition.

Extra innings: `linescore.currentInning > scheduledInnings` (e.g. gamePk 824312, 11 innings).

### Postponed games and makeups share a gamePk
gamePk 824785 (TOR @ BAL) is listed on **2026-09-22 as Postponed** (`rescheduleDate:
2026-09-23T17:35Z`) and on **2026-09-23 as Final** (`rescheduledFrom: 2026-09-22T22:35Z`). A range
query covering both days returns both listings. We handle this twice:
- in one response, `ScheduleResult.Builder` keeps the non-postponed (makeup) listing;
- across fetches, the games upsert refuses to let a POSTPONED listing with an earlier start
  overwrite a non-postponed row with a later start.

Fixtures: `mlb/schedule-20260922-postponed`, `mlb/schedule-20260923-makeup`,
`mlb/schedule-range-postponed-makeup`.

---

## Fixture index

See `scripts/fixtures.manifest` for URLs. Coverage by scenario:

| Scenario | ESPN | NHL | MLB |
|---|---|---|---|
| Scheduled | NFL week 6, NBA 2026-10-21 | schedule week 2026-10-13 | — (capture live) |
| Final | NFL, NBA, NCAAF, NCAAB, EPL, La Liga, Bundesliga | score 2026-10-06 / 10-10 | 2026-09-02, postseason range |
| Overtime / extras / shootout | NFL Final/OT, NCAAF 2OT/4OT, NBA/NCAAB Final/OT | OT + SO (2026-10-10) | 11 innings (824312) |
| Postponed | NCAAB 2026-02-14 (D1 fixture) | — (synthetic) | 2026-09-22 |
| Cancelled | — (synthetic) | — (synthetic) | 2026-09-27 |
| Empty / off-season | NFL 2026-07-15, NBA 2026-08-01 | 2026-07-15 | 2027-01-15 |
| Teams | all 7 ESPN leagues, plus ESPN MLB/NHL lists to cross-check canonical ids | standings/now | teams |
| **Live / halftime / intermission** | **todo: capture during games** | **todo** | **todo** |
