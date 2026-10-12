package ca.hex42labs.sports.provider.espn;

import ca.hex42labs.sports.domain.GameStatus;
import java.util.Map;

/**
 * ESPN {@code status.type} to {@link GameStatus}.
 *
 * <p>Map on {@code name} first: postponed games carry {@code state: "post"} just like finals, so
 * {@code state} alone would mark them FINAL. {@code state} is only a fallback for names we haven't
 * seen, and then {@code post} counts as FINAL only when {@code completed} is true.
 */
final class EspnStatusMapper {

    private static final Map<String, GameStatus> BY_NAME =
            Map.ofEntries(
                    Map.entry("STATUS_SCHEDULED", GameStatus.SCHEDULED),
                    Map.entry("STATUS_IN_PROGRESS", GameStatus.LIVE),
                    Map.entry("STATUS_HALFTIME", GameStatus.LIVE),
                    Map.entry("STATUS_END_PERIOD", GameStatus.LIVE),
                    Map.entry("STATUS_FIRST_HALF", GameStatus.LIVE),
                    Map.entry("STATUS_SECOND_HALF", GameStatus.LIVE),
                    Map.entry("STATUS_OVERTIME", GameStatus.LIVE),
                    Map.entry("STATUS_END_OF_REGULATION", GameStatus.LIVE),
                    Map.entry("STATUS_EXTRA_TIME", GameStatus.LIVE),
                    Map.entry("STATUS_END_OF_EXTRATIME", GameStatus.LIVE),
                    Map.entry("STATUS_SHOOTOUT", GameStatus.LIVE),
                    Map.entry("STATUS_FINAL", GameStatus.FINAL),
                    Map.entry("STATUS_FULL_TIME", GameStatus.FINAL),
                    Map.entry("STATUS_FINAL_AET", GameStatus.FINAL),
                    Map.entry("STATUS_FINAL_PEN", GameStatus.FINAL),
                    Map.entry("STATUS_FINAL_OT", GameStatus.FINAL),
                    Map.entry("STATUS_POSTPONED", GameStatus.POSTPONED),
                    Map.entry("STATUS_SUSPENDED", GameStatus.SUSPENDED),
                    Map.entry("STATUS_CANCELED", GameStatus.CANCELLED),
                    Map.entry("STATUS_CANCELLED", GameStatus.CANCELLED),
                    Map.entry("STATUS_ABANDONED", GameStatus.CANCELLED));

    private EspnStatusMapper() {}

    /**
     * @param name e.g. {@code STATUS_FINAL}
     * @param state {@code pre} | {@code in} | {@code post}
     * @param completed ESPN's {@code completed} flag
     */
    static GameStatus map(String name, String state, boolean completed) {
        GameStatus byName = name == null ? null : BY_NAME.get(name);
        if (byName != null) {
            return byName;
        }
        // Delays (STATUS_DELAYED, STATUS_RAIN_DELAY, ...) and anything new: trust state.
        return switch (state == null ? "" : state) {
            case "pre" -> GameStatus.SCHEDULED;
            case "in" -> GameStatus.LIVE;
            case "post" -> completed ? GameStatus.FINAL : GameStatus.UNKNOWN;
            default -> GameStatus.UNKNOWN;
        };
    }

    /** True if {@code name} is one we map explicitly (unmapped names are worth a metric). */
    static boolean isKnown(String name) {
        return name != null && BY_NAME.containsKey(name);
    }
}
