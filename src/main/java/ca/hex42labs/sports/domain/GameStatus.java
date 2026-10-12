package ca.hex42labs.sports.domain;

/** Provider-independent game state. */
public enum GameStatus {
    SCHEDULED,
    LIVE,
    FINAL,
    POSTPONED,
    SUSPENDED,
    CANCELLED,
    UNKNOWN;

    /**
     * Whether scores are meaningful in this state. Upstreams report "0" before kickoff, so scores
     * are only kept for games that have actually been played (or partly played).
     */
    public boolean hasScore() {
        return this == LIVE || this == FINAL || this == SUSPENDED;
    }
}
