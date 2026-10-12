package ca.hex42labs.sports.domain;

/** How the team part of a canonical team id is derived. */
public enum TeamKeyStyle {
    /** Lowercase ESPN abbreviation, e.g. {@code nhl:tor}. Pro leagues. */
    ABBREVIATION,
    /**
     * ESPN numeric team id, e.g. {@code ncaaf:145}. College and soccer, whose abbreviations clash.
     */
    PROVIDER_ID
}
