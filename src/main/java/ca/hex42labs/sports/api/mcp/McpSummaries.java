package ca.hex42labs.sports.api.mcp;

import ca.hex42labs.sports.api.dto.Envelope;
import ca.hex42labs.sports.api.dto.GameDto;
import ca.hex42labs.sports.api.dto.LeagueDto;
import ca.hex42labs.sports.api.dto.Meta;
import ca.hex42labs.sports.api.dto.TeamDto;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Short human-readable text that accompanies MCP structured content. The structured data is the
 * source of truth; this is what a model or person reads at a glance.
 */
final class McpSummaries {

    /** Lines of detail before "... and N more". */
    static final int MAX_LINES = 12;

    private static final DateTimeFormatter DAY_TIME =
            DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.ENGLISH);

    private McpSummaries() {}

    static String leagues(Envelope<List<LeagueDto>> e) {
        return "Leagues: "
                + e.data().stream()
                        .map(l -> l.name() + " (" + l.id() + ")")
                        .collect(Collectors.joining(", "))
                + ".";
    }

    static String teams(Envelope<List<TeamDto>> e) {
        if (e.data().isEmpty()) {
            return "No matching teams.";
        }
        return lines(
                e.data().size()
                        + (Boolean.TRUE.equals(e.meta().truncated()) ? "+" : "")
                        + " team(s):",
                e.data().stream().map(t -> t.name() + " (" + t.id() + ")").toList(),
                e.meta());
    }

    static String games(String heading, Envelope<List<GameDto>> e, String empty) {
        if (e.data().isEmpty()) {
            // No times to qualify, so only the staleness note applies.
            return e.meta().isStale()
                    ? empty
                            + "\nNote: some data may be out of date ("
                            + e.meta().staleReason()
                            + ")."
                    : empty;
        }
        return lines(
                heading.formatted(e.data().size()),
                e.data().stream().map(McpSummaries::game).toList(),
                e.meta());
    }

    static String game(Envelope<GameDto> e) {
        return withStale(game(e.data()), e.meta());
    }

    /** "BUF 21 @ LAR 24 - Q4 2:00 (LIVE)" or "BUF @ LAR - Mon Oct 12, 8:15 PM". */
    static String game(GameDto g) {
        String away = label(g.away());
        String home = label(g.home());
        boolean scored = g.home().score() != null && g.away().score() != null;
        String matchup =
                scored
                        ? away + " " + g.away().score() + " @ " + home + " " + g.home().score()
                        : away + " @ " + home;
        String when =
                switch (g.status()) {
                    case "SCHEDULED" -> OffsetDateTime.parse(g.localStart()).format(DAY_TIME);
                    case "LIVE" ->
                            (g.statusDetail() == null ? "in progress" : g.statusDetail())
                                    + " (LIVE)";
                    default -> g.statusDetail() == null ? g.status() : g.statusDetail();
                };
        return matchup + " - " + when + " [" + g.id() + "]";
    }

    private static String label(GameDto.Side s) {
        return s.abbreviation() != null ? s.abbreviation() : s.name();
    }

    private static String lines(String heading, List<String> items, Meta meta) {
        StringBuilder sb = new StringBuilder(heading);
        items.stream().limit(MAX_LINES).forEach(i -> sb.append("\n- ").append(i));
        if (items.size() > MAX_LINES) {
            sb.append("\n- ... and ")
                    .append(items.size() - MAX_LINES)
                    .append(" more (see structured content)");
        }
        return withStale(sb.toString(), meta);
    }

    private static String withStale(String text, Meta meta) {
        String out = text;
        if (meta.timezone() != null) {
            out += "\nTimes in " + meta.timezone() + ".";
        }
        if (meta.isStale()) {
            out += "\nNote: some data may be out of date (" + meta.staleReason() + ").";
        }
        return out;
    }
}
