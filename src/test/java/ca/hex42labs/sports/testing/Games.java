package ca.hex42labs.sports.testing;

import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.GameStatus;
import ca.hex42labs.sports.domain.PeriodType;
import java.time.Instant;
import java.util.List;

/** Builders for hand-made games in tests. */
public final class Games {

    private Games() {}

    public static Game scheduled(
            String id, String home, String away, Instant startsAt, Instant fetchedAt) {
        String league = id.substring(0, id.indexOf(':'));
        return new Game(
                id,
                league,
                "espn",
                id.substring(id.indexOf(':') + 1),
                "2026",
                "regular",
                home,
                away,
                startsAt,
                GameStatus.SCHEDULED,
                "scheduled",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "Somewhere Stadium",
                List.of("ESPN"),
                null,
                fetchedAt,
                null);
    }

    public static Game live(Game g, int home, int away, String clock, Instant fetchedAt) {
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
                GameStatus.LIVE,
                "Q2 " + clock,
                2,
                PeriodType.REGULATION,
                clock,
                home,
                away,
                null,
                null,
                g.venue(),
                g.broadcasts(),
                new Game.Situation(g.homeTeamId(), "1st & 10", "OWN 25", false),
                fetchedAt,
                null);
    }

    public static Game rescheduled(Game g, Instant startsAt, GameStatus status, Instant fetchedAt) {
        return new Game(
                g.id(),
                g.league(),
                g.provider(),
                g.providerEventId(),
                g.season(),
                g.seasonType(),
                g.homeTeamId(),
                g.awayTeamId(),
                startsAt,
                status,
                status.name(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                g.venue(),
                g.broadcasts(),
                null,
                fetchedAt,
                null);
    }
}
