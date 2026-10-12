package ca.hex42labs.sports.provider;

import static org.assertj.core.api.Assertions.assertThat;

import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.GameStatus;
import ca.hex42labs.sports.testing.Games;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduleResultTest {

    private static final Instant T = Instant.parse("2026-09-22T22:35:00Z");

    @Test
    void makeupListingWinsInEitherOrder() throws Exception {
        Game base = Games.scheduled("mlb:824785", "mlb:bal", "mlb:tor", T, T);
        Game postponed = Games.rescheduled(base, T, GameStatus.POSTPONED, T);
        Game makeup = Games.rescheduled(base, T.plusSeconds(68_400), GameStatus.FINAL, T);
        for (List<Game> order : List.of(List.of(postponed, makeup), List.of(makeup, postponed))) {
            ScheduleResult r = new ScheduleResult.Builder().add(order, List.of(), T).build(T);
            assertThat(r.games())
                    .singleElement()
                    .extracting(Game::status)
                    .isEqualTo(GameStatus.FINAL);
        }
    }

    @Test
    void reportsOldestFetchTime() throws Exception {
        Game a = Games.scheduled("nfl:1", "nfl:gb", "nfl:chi", T, T);
        ScheduleResult r =
                new ScheduleResult.Builder()
                        .add(List.of(a), List.of(), T.plusSeconds(5))
                        .add(List.of(), List.of(), T)
                        .build(T.plusSeconds(10));
        assertThat(r.fetchedAt()).isEqualTo(T);
    }
}
