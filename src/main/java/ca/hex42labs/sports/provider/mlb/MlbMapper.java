package ca.hex42labs.sports.provider.mlb;

import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.GameStatus;
import ca.hex42labs.sports.domain.Ids;
import ca.hex42labs.sports.domain.PeriodType;
import ca.hex42labs.sports.domain.Team;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** MLB Stats API schedule JSON ({@code hydrate=linescore,team}) to domain objects. */
final class MlbMapper {

    /**
     * Game types we serve: regular season, wild card, division series, LCS, World Series, All-Star.
     * Spring training (S) and exhibitions (E) are excluded.
     */
    static final Set<String> GAME_TYPES = Set.of("R", "F", "D", "L", "W", "A");

    private MlbMapper() {}

    static Optional<Game> game(JsonNode g, Instant fetchedAt, Map<String, Team> teamsSeen) {
        if (!GAME_TYPES.contains(g.path("gameType").asText())) {
            return Optional.empty();
        }
        String pk = g.path("gamePk").asText(null);
        JsonNode home = g.path("teams").path("home");
        JsonNode away = g.path("teams").path("away");
        Optional<Team> homeTeam = MlbProvider.team(home.path("team"));
        Optional<Team> awayTeam = MlbProvider.team(away.path("team"));
        Instant startsAt = instant(g.path("gameDate").asText(null));
        if (pk == null || homeTeam.isEmpty() || awayTeam.isEmpty() || startsAt == null) {
            return Optional.empty(); // e.g. split-squad or non-MLB opponents
        }
        teamsSeen.putIfAbsent(homeTeam.get().id(), homeTeam.get());
        teamsSeen.putIfAbsent(awayTeam.get().id(), awayTeam.get());

        JsonNode st = g.path("status");
        GameStatus status =
                status(
                        st.path("codedGameState").asText(""),
                        st.path("abstractGameState").asText(""));
        boolean played = status == GameStatus.LIVE || status == GameStatus.FINAL;
        JsonNode ls = g.path("linescore");
        Integer inning =
                played && ls.hasNonNull("currentInning") ? ls.path("currentInning").asInt() : null;
        int scheduled = ls.path("scheduledInnings").asInt(9);
        PeriodType periodType =
                played
                        ? (inning != null && inning > scheduled
                                ? PeriodType.EXTRA_INNINGS
                                : PeriodType.REGULATION)
                        : null;
        boolean scored = status.hasScore();

        return Optional.of(
                new Game(
                        Ids.game("mlb", pk),
                        "mlb",
                        MlbProvider.ID,
                        pk,
                        g.path("season").asText(null),
                        seasonType(g.path("gameType").asText()),
                        homeTeam.get().id(),
                        awayTeam.get().id(),
                        startsAt,
                        status,
                        detail(status, st, ls, inning, periodType),
                        inning,
                        periodType,
                        null,
                        scored ? runs(home, ls.path("teams").path("home")) : null,
                        scored ? runs(away, ls.path("teams").path("away")) : null,
                        null,
                        null,
                        g.path("venue").path("name").asText(null),
                        List.of(),
                        null,
                        fetchedAt,
                        null));
    }

    /**
     * {@code codedGameState} first: postponed (D) and cancelled (C) games report {@code
     * abstractGameState: "Final"}. T/U are suspended games.
     */
    static GameStatus status(String coded, String abstractState) {
        switch (coded) {
            case "D":
                return GameStatus.POSTPONED;
            case "C":
                return GameStatus.CANCELLED;
            case "T", "U":
                return GameStatus.SUSPENDED;
            default:
                break;
        }
        return switch (abstractState) {
            case "Preview" -> GameStatus.SCHEDULED;
            case "Live" -> GameStatus.LIVE;
            case "Final" -> GameStatus.FINAL;
            default -> GameStatus.UNKNOWN;
        };
    }

    /** "Top 7th", "Mid 7th", "Final/11", "Postponed: Rain", "Delayed Start: Rain". */
    static String detail(
            GameStatus status, JsonNode st, JsonNode ls, Integer inning, PeriodType type) {
        String detailed = st.path("detailedState").asText(null);
        String reason = st.path("reason").asText(null);
        return switch (status) {
            case LIVE -> {
                if (detailed != null && !detailed.equals("In Progress")) {
                    yield detailed; // "Delayed: Rain", "Manager challenge", ...
                }
                yield halfInning(ls);
            }
            case FINAL -> type == PeriodType.EXTRA_INNINGS ? detailed + "/" + inning : detailed;
            case POSTPONED, CANCELLED, SUSPENDED ->
                    reason != null && detailed != null && !detailed.contains(":")
                            ? detailed + ": " + reason
                            : detailed;
            default -> detailed;
        };
    }

    /**
     * Updates a live game from its per-game linescore: runs, inning and half-inning text. A
     * delay/challenge status text from the schedule is kept; the linescore can't see those.
     */
    static Game applyLinescore(Game g, JsonNode ls, Instant fetchedAt) {
        if (g.status() != GameStatus.LIVE || !ls.hasNonNull("currentInning")) {
            return g;
        }
        int inning = ls.path("currentInning").asInt();
        int scheduled = ls.path("scheduledInnings").asInt(9);
        String half = halfInning(ls);
        boolean showsInning =
                g.statusDetail() == null || HALF_INNING.matcher(g.statusDetail()).matches();
        JsonNode teams = ls.path("teams");
        return new Game(
                g.id(),
                g.league(),
                g.provider(),
                g.providerEventId(),
                g.season(),
                g.seasonType(),
                g.homeTeamId(),
                g.awayTeamId(),
                g.startsAt(),
                g.status(),
                showsInning && !half.isEmpty() ? half : g.statusDetail(),
                inning,
                inning > scheduled ? PeriodType.EXTRA_INNINGS : PeriodType.REGULATION,
                g.clock(),
                teams.path("home").hasNonNull("runs")
                        ? teams.path("home").path("runs").asInt()
                        : g.homeScore(),
                teams.path("away").hasNonNull("runs")
                        ? teams.path("away").path("runs").asInt()
                        : g.awayScore(),
                g.homeShootout(),
                g.awayShootout(),
                g.venue(),
                g.broadcasts(),
                g.situation(),
                fetchedAt,
                g.providerUpdatedAt());
    }

    private static final Pattern HALF_INNING =
            Pattern.compile("(Top|Bot|Mid|End) \\d+(st|nd|rd|th)");

    /** "Top 7th" from a linescore (or a hydrated schedule linescore). */
    static String halfInning(JsonNode ls) {
        String prefix =
                switch (ls.path("inningState").asText("")) {
                    case "Top" -> "Top";
                    case "Bottom" -> "Bot";
                    case "Middle" -> "Mid";
                    case "End" -> "End";
                    default -> "";
                };
        return (prefix + " " + ls.path("currentInningOrdinal").asText("")).trim();
    }

    /** Prefer the team's {@code score}; the linescore's runs are a fallback. */
    private static Integer runs(JsonNode side, JsonNode linescoreSide) {
        if (side.hasNonNull("score")) {
            return side.path("score").asInt();
        }
        return linescoreSide.hasNonNull("runs") ? linescoreSide.path("runs").asInt() : null;
    }

    static String seasonType(String gameType) {
        return switch (gameType) {
            case "R" -> "regular";
            case "F", "D", "L", "W" -> "postseason";
            case "A" -> "allstar";
            case "S", "E" -> "preseason";
            default -> "other";
        };
    }

    static Instant instant(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
