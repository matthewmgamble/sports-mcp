package ca.hex42labs.sports.domain;

/** Which phase a game is in (live) or ended in (final). */
public enum PeriodType {
    REGULATION,
    OVERTIME,
    SHOOTOUT,
    EXTRA_TIME,
    PENALTIES,
    EXTRA_INNINGS
}
