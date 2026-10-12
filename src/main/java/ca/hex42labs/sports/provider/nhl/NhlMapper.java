package ca.hex42labs.sports.provider.nhl;

import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.GameStatus;
import ca.hex42labs.sports.domain.Ids;
import ca.hex42labs.sports.domain.PeriodType;
import ca.hex42labs.sports.domain.Team;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * NHL game JSON to domain objects. {@code score/{date}} and {@code schedule/{date}} share the game
 * shape; {@code score} adds the live {@code clock}.
 */
final class NhlMapper {

    private NhlMapper() {}

    static Optional<Game> game(JsonNode g, Instant fetchedAt, Map<String, Team> teamsSeen) {
        String id = g.path("id").asText(null);
        JsonNode home = g.path("homeTeam");
        JsonNode away = g.path("awayTeam");
        String homeAbbrev = home.path("abbrev").asText(null);
        String awayAbbrev = away.path("abbrev").asText(null);
        Instant startsAt = instant(g.path("startTimeUTC").asText(null));
        if (id == null || homeAbbrev == null || awayAbbrev == null || startsAt == null) {
            return Optional.empty();
        }
        Team homeTeam = team(home);
        Team awayTeam = team(away);
        teamsSeen.putIfAbsent(homeTeam.id(), homeTeam);
        teamsSeen.putIfAbsent(awayTeam.id(), awayTeam);

        GameStatus status =
                status(g.path("gameState").asText(""), g.path("gameScheduleState").asText(""));
        boolean played = status == GameStatus.LIVE || status == GameStatus.FINAL;
        JsonNode pd = g.path("periodDescriptor");
        Integer period = played && pd.hasNonNull("number") ? pd.path("number").asInt() : null;
        String periodTypeRaw =
                status == GameStatus.FINAL && g.path("gameOutcome").hasNonNull("lastPeriodType")
                        ? g.path("gameOutcome").path("lastPeriodType").asText()
                        : pd.path("periodType").asText(null);
        PeriodType periodType = played ? periodType(periodTypeRaw) : null;
        JsonNode clock = g.path("clock");
        boolean intermission = clock.path("inIntermission").asBoolean(false);
        String clockText =
                status == GameStatus.LIVE && !intermission
                        ? clock.path("timeRemaining").asText(null)
                        : null;

        boolean scored = status.hasScore();
        return Optional.of(
                new Game(
                        Ids.game("nhl", id),
                        "nhl",
                        NhlProvider.ID,
                        id,
                        season(g.path("season").asText(null)),
                        seasonType(g.path("gameType").asInt(0)),
                        homeTeam.id(),
                        awayTeam.id(),
                        startsAt,
                        status,
                        detail(status, period, periodType, clockText, intermission, g),
                        period,
                        periodType,
                        clockText,
                        scored && home.hasNonNull("score") ? home.path("score").asInt() : null,
                        scored && away.hasNonNull("score") ? away.path("score").asInt() : null,
                        null,
                        null,
                        g.path("venue").path("default").asText(null),
                        broadcasts(g),
                        null,
                        fetchedAt,
                        null));
    }

    /** {@code gameScheduleState} (postponed etc.) overrides {@code gameState}. */
    static GameStatus status(String gameState, String scheduleState) {
        switch (scheduleState) {
            case "PPD":
                return GameStatus.POSTPONED;
            case "SUSP":
                return GameStatus.SUSPENDED;
            case "CNCL":
                return GameStatus.CANCELLED;
            default:
                break;
        }
        return switch (gameState) {
            case "FUT", "PRE" -> GameStatus.SCHEDULED;
            case "LIVE", "CRIT" -> GameStatus.LIVE;
            case "FINAL", "OFF" -> GameStatus.FINAL;
            default -> {
                ca.hex42labs.sports.observability.SportsMetrics.unmappedStatus("nhl", gameState);
                yield GameStatus.UNKNOWN;
            }
        };
    }

    static PeriodType periodType(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw) {
            case "OT" -> PeriodType.OVERTIME;
            case "SO" -> PeriodType.SHOOTOUT;
            default -> PeriodType.REGULATION;
        };
    }

    /** ESPN-style short text: "2nd 12:34", "2nd Intermission", "Final/OT", "Postponed". */
    static String detail(
            GameStatus status,
            Integer period,
            PeriodType type,
            String clock,
            boolean intermission,
            JsonNode g) {
        return switch (status) {
            case SCHEDULED -> "PRE".equals(g.path("gameState").asText()) ? "Pre-game" : "Scheduled";
            case LIVE -> {
                String p = periodName(period, type);
                if (intermission) {
                    yield p + " Intermission";
                }
                yield clock == null ? p : p + " " + clock;
            }
            case FINAL ->
                    type == null || type == PeriodType.REGULATION
                            ? "Final"
                            : "Final/" + periodName(period, type);
            case POSTPONED -> "Postponed";
            case SUSPENDED -> "Suspended";
            case CANCELLED -> "Cancelled";
            case UNKNOWN -> null;
        };
    }

    /** 1st/2nd/3rd, OT (2OT, 3OT... in the playoffs), SO. */
    static String periodName(Integer period, PeriodType type) {
        if (type == PeriodType.SHOOTOUT) {
            return "SO";
        }
        if (type == PeriodType.OVERTIME) {
            int n = period == null ? 1 : period - 3;
            return n <= 1 ? "OT" : n + "OT";
        }
        if (period == null) {
            return "";
        }
        return switch (period) {
            case 1 -> "1st";
            case 2 -> "2nd";
            case 3 -> "3rd";
            default -> period + "th";
        };
    }

    /** Teams as game payloads describe them (thin: used only to fill catalog gaps). */
    static Team team(JsonNode t) {
        String abbrev = t.path("abbrev").asText();
        String common =
                firstText(t.path("commonName").path("default"), t.path("name").path("default"));
        String place = t.path("placeName").path("default").asText(null);
        String name =
                place != null && common != null
                        ? place + " " + common
                        : common != null ? common : abbrev;
        String key = NhlProvider.espnKey(abbrev);
        return new Team(
                Ids.team("nhl", key),
                "nhl",
                NhlProvider.ID,
                abbrev,
                name,
                common,
                abbrev,
                place,
                "https://a.espncdn.com/i/teamlogos/nhl/500/" + key + ".png",
                List.of());
    }

    /** 20262027 to "2026-27". */
    static String season(String raw) {
        if (raw == null || raw.length() != 8) {
            return raw;
        }
        return raw.substring(0, 4) + "-" + raw.substring(6);
    }

    static String seasonType(int gameType) {
        return switch (gameType) {
            case 1 -> "preseason";
            case 2 -> "regular";
            case 3 -> "postseason";
            default -> "other";
        };
    }

    private static List<String> broadcasts(JsonNode g) {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode b : g.path("tvBroadcasts")) {
            String n = b.path("network").asText("");
            if (!n.isBlank()) {
                names.add(n);
            }
        }
        return List.copyOf(names);
    }

    private static String firstText(JsonNode... nodes) {
        for (JsonNode n : nodes) {
            if (n != null && n.isTextual() && !n.asText().isBlank()) {
                return n.asText();
            }
        }
        return null;
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
