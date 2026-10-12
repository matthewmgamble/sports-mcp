package ca.hex42labs.sports.provider.espn;

import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.GameStatus;
import ca.hex42labs.sports.domain.Ids;
import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.domain.PeriodType;
import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.domain.TeamKeyStyle;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** ESPN site-API JSON to domain objects. Pure functions; no I/O. */
final class EspnMapper {

    private static final Logger log = LoggerFactory.getLogger(EspnMapper.class);

    static final String LOGO_URL = "https://a.espncdn.com/i/teamlogos/%s/500/%s.png";

    private EspnMapper() {}

    /** Result of mapping one scoreboard: games plus the teams they reference. */
    record Scoreboard(List<Game> games, List<Team> teams) {}

    static Scoreboard scoreboard(League league, JsonNode root, Instant fetchedAt) {
        List<Game> games = new ArrayList<>();
        Map<String, Team> teams = new LinkedHashMap<>();
        for (JsonNode ev : root.path("events")) {
            event(league, ev, fetchedAt, teams).ifPresent(games::add);
        }
        return new Scoreboard(games, List.copyOf(teams.values()));
    }

    /** The {@code /teams} endpoint: {@code sports[0].leagues[0].teams[].team}. */
    static List<Team> teams(League league, JsonNode root) {
        Map<String, Team> out = new LinkedHashMap<>();
        for (JsonNode sport : root.path("sports")) {
            for (JsonNode lg : sport.path("leagues")) {
                for (JsonNode wrapper : lg.path("teams")) {
                    team(league, wrapper.path("team")).ifPresent(t -> out.putIfAbsent(t.id(), t));
                }
            }
        }
        return List.copyOf(out.values());
    }

    static Optional<Team> team(League league, JsonNode t) {
        String providerId = text(t, "id");
        String abbreviation = text(t, "abbreviation");
        String key =
                league.teamKeyStyle() == TeamKeyStyle.ABBREVIATION && abbreviation != null
                        ? abbreviation
                        : providerId;
        if (providerId == null || key == null || !Ids.isKey(key.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        String id = Ids.team(league.id(), key);
        String name =
                firstNonNull(text(t, "displayName"), text(t, "name"), abbreviation, providerId);
        return Optional.of(
                new Team(
                        id,
                        league.id(),
                        "espn",
                        providerId,
                        name,
                        firstNonNull(text(t, "shortDisplayName"), text(t, "name")),
                        abbreviation,
                        text(t, "location"),
                        String.format(LOGO_URL, league.logoSlug(), key.toLowerCase(Locale.ROOT)),
                        List.of()));
    }

    static Optional<Game> event(
            League league, JsonNode ev, Instant fetchedAt, Map<String, Team> teamsSeen) {
        String eventId = text(ev, "id");
        JsonNode comp = ev.path("competitions").path(0);
        JsonNode home = null;
        JsonNode away = null;
        for (JsonNode c : comp.path("competitors")) {
            switch (c.path("homeAway").asText("")) {
                case "home" -> home = c;
                case "away" -> away = c;
                default -> {}
            }
        }
        Instant startsAt = parseInstant(firstNonNull(text(ev, "date"), text(comp, "date")));
        if (eventId == null || home == null || away == null || startsAt == null) {
            log.debug("Skipping malformed {} event {}", league.id(), eventId);
            return Optional.empty();
        }
        Optional<Team> homeTeam = team(league, home.path("team"));
        Optional<Team> awayTeam = team(league, away.path("team"));
        if (homeTeam.isEmpty() || awayTeam.isEmpty()) {
            log.debug("Skipping {} event {}: unmappable team", league.id(), eventId);
            return Optional.empty();
        }
        teamsSeen.putIfAbsent(homeTeam.get().id(), homeTeam.get());
        teamsSeen.putIfAbsent(awayTeam.get().id(), awayTeam.get());

        JsonNode status =
                ev.path("status").isMissingNode() ? comp.path("status") : ev.path("status");
        JsonNode type = status.path("type");
        String statusName = text(type, "name");
        GameStatus gameStatus =
                EspnStatusMapper.map(
                        statusName, text(type, "state"), type.path("completed").asBoolean(false));
        if (!EspnStatusMapper.isKnown(statusName)) {
            ca.hex42labs.sports.observability.SportsMetrics.unmappedStatus("espn", statusName);
            log.info(
                    "Unmapped ESPN status {} ({}) for {} -> {}",
                    statusName,
                    text(type, "state"),
                    eventId,
                    gameStatus);
        }

        boolean scored = gameStatus.hasScore();
        boolean played = gameStatus == GameStatus.LIVE || gameStatus == GameStatus.FINAL;
        Integer period =
                played && status.hasNonNull("period") ? status.path("period").asInt() : null;

        JsonNode season = ev.path("season");
        int seasonTypeId = season.path("type").asInt(ev.path("seasonType").path("type").asInt(0));
        boolean postseason = seasonTypeId == 3;
        return Optional.of(
                new Game(
                        Ids.game(league.id(), eventId),
                        league.id(),
                        "espn",
                        eventId,
                        season.hasNonNull("year") ? season.path("year").asText() : null,
                        // Scoreboards put the type on season; team schedules on seasonType.
                        seasonType(seasonTypeId, text(season, "slug")),
                        homeTeam.get().id(),
                        awayTeam.get().id(),
                        startsAt,
                        gameStatus,
                        firstNonNull(text(type, "shortDetail"), text(type, "detail")),
                        period,
                        played
                                ? periodType(
                                        league,
                                        gameStatus,
                                        statusName,
                                        period,
                                        text(type, "shortDetail"))
                                : null,
                        gameStatus == GameStatus.LIVE ? text(status, "displayClock") : null,
                        scored ? score(home.path("score")) : null,
                        scored ? score(away.path("score")) : null,
                        scored ? score(home.path("shootoutScore")) : null,
                        scored ? score(away.path("shootoutScore")) : null,
                        text(comp.path("venue"), "fullName"),
                        broadcasts(comp),
                        gameStatus == GameStatus.LIVE
                                ? situation(comp, home, away, homeTeam.get(), awayTeam.get())
                                : null,
                        fetchedAt,
                        null,
                        postseason ? round(comp) : null,
                        // Scoreboards carry the series; team schedules don't.
                        postseason ? text(comp.path("series"), "summary") : null,
                        timeTbd(ev, comp)));
    }

    /** {@code timeValid: false} (on the competition in scoreboards, the event in schedules). */
    static boolean timeTbd(JsonNode ev, JsonNode comp) {
        JsonNode valid =
                comp.path("timeValid").isBoolean() ? comp.path("timeValid") : ev.path("timeValid");
        return valid.isBoolean() && !valid.asBoolean();
    }

    /** "ALCS - Game 4" to "ALCS Game 4"; "NLCS - Game 5 If Necessary" keeps its "If Necessary". */
    static String round(JsonNode comp) {
        for (JsonNode note : comp.path("notes")) {
            String headline = text(note, "headline");
            if (headline != null) {
                return headline.replace(" - ", " ").strip();
            }
        }
        return null;
    }

    static PeriodType periodType(
            League league,
            GameStatus status,
            String statusName,
            Integer period,
            String shortDetail) {
        if (league.isSoccer()) {
            if ("STATUS_FINAL_PEN".equals(statusName) || "STATUS_SHOOTOUT".equals(statusName)) {
                return PeriodType.PENALTIES;
            }
            if ("STATUS_FINAL_AET".equals(statusName)) {
                return PeriodType.EXTRA_TIME;
            }
            if (period != null && period > 4) {
                return PeriodType.PENALTIES;
            }
            return period != null && period > league.regulationPeriods()
                    ? PeriodType.EXTRA_TIME
                    : PeriodType.REGULATION;
        }
        if (status == GameStatus.FINAL && shortDetail != null && shortDetail.contains("OT")) {
            return PeriodType.OVERTIME;
        }
        return period != null && period > league.regulationPeriods()
                ? PeriodType.OVERTIME
                : PeriodType.REGULATION;
    }

    private static String seasonType(int type, String slug) {
        return switch (type) {
            case 1 -> "preseason";
            case 2 -> "regular";
            case 3 -> "postseason";
            default -> slug;
        };
    }

    /** Network names, falling back to streaming/geo broadcasts (TNF lists only Prime there). */
    private static List<String> broadcasts(JsonNode comp) {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode b : comp.path("broadcasts")) {
            for (JsonNode n : b.path("names")) {
                if (!n.asText("").isBlank()) {
                    names.add(n.asText());
                }
            }
        }
        if (names.isEmpty()) {
            for (JsonNode g : comp.path("geoBroadcasts")) {
                String n = text(g.path("media"), "shortName");
                if (n != null) {
                    names.add(n);
                }
            }
        }
        return List.copyOf(names);
    }

    private static Game.Situation situation(
            JsonNode comp, JsonNode home, JsonNode away, Team homeTeam, Team awayTeam) {
        JsonNode sit = comp.path("situation");
        if (sit.isMissingNode()
                || sit.isNull()
                || !sit.has("downDistanceText") && !sit.has("possession")) {
            return null;
        }
        String possession = text(sit, "possession");
        String possessionTeamId = null;
        if (possession != null) {
            if (possession.equals(text(home, "id"))) {
                possessionTeamId = homeTeam.id();
            } else if (possession.equals(text(away, "id"))) {
                possessionTeamId = awayTeam.id();
            }
        }
        return new Game.Situation(
                possessionTeamId,
                firstNonNull(text(sit, "shortDownDistanceText"), text(sit, "downDistanceText")),
                text(sit, "possessionText"),
                sit.path("isRedZone").asBoolean(false));
    }

    /** ESPN scores are strings; tolerate numbers, blanks and junk. */
    static Integer score(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (n.isNumber()) {
            return n.asInt();
        }
        if (n.isObject()) { // some feeds nest {"value": 3.0, "displayValue": "3"}
            return n.has("value") ? n.path("value").asInt() : null;
        }
        String s = n.asText("").trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** ESPN dates are minute precision ("2026-10-13T00:15Z"), which Instant.parse rejects. */
    static Instant parseInstant(String s) {
        if (s == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s.isBlank() ? null : s;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
