package ca.hex42labs.sports.provider.espn;

import static org.assertj.core.api.Assertions.assertThat;

import ca.hex42labs.sports.domain.GameStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EspnStatusMapperTest {

    @ParameterizedTest(name = "{0}/{1}/completed={2} -> {3}")
    @CsvSource({
        "STATUS_SCHEDULED,   pre,  false, SCHEDULED",
        "STATUS_IN_PROGRESS, in,   false, LIVE",
        "STATUS_HALFTIME,    in,   false, LIVE",
        "STATUS_END_PERIOD,  in,   false, LIVE",
        "STATUS_FINAL,       post, true,  FINAL",
        "STATUS_FULL_TIME,   post, true,  FINAL",
        "STATUS_FINAL_AET,   post, true,  FINAL",
        "STATUS_FINAL_PEN,   post, true,  FINAL",
        // The trap: postponed games share state=post with finals.
        "STATUS_POSTPONED,   post, false, POSTPONED",
        "STATUS_SUSPENDED,   in,   false, SUSPENDED",
        "STATUS_CANCELED,    post, false, CANCELLED",
        "STATUS_ABANDONED,   post, false, CANCELLED",
        // Delays and unseen names fall back to state.
        "STATUS_DELAYED,     pre,  false, SCHEDULED",
        "STATUS_RAIN_DELAY,  in,   false, LIVE",
        "STATUS_SOMETHING_NEW, post, true,  FINAL",
        "STATUS_SOMETHING_NEW, post, false, UNKNOWN",
        "STATUS_SOMETHING_NEW, '',  false, UNKNOWN",
    })
    void mapsNameFirstThenState(String name, String state, boolean completed, GameStatus expected) {
        assertThat(EspnStatusMapper.map(name, state, completed)).isEqualTo(expected);
    }
}
