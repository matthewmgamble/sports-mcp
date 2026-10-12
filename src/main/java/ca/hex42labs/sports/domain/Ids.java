package ca.hex42labs.sports.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** Canonical id construction and validation. Ids are opaque to clients but readable. */
public final class Ids {

    private static final Pattern LEAGUE = Pattern.compile("[a-z][a-z0-9]{1,15}");
    private static final Pattern KEY = Pattern.compile("[a-z0-9]{1,24}");
    private static final Pattern ID = Pattern.compile("([a-z][a-z0-9]{1,15}):([a-z0-9]{1,24})");

    private Ids() {}

    /** {@code nhl:tor}, {@code ncaaf:145}. */
    public static String team(String league, String key) {
        return league + ":" + key.toLowerCase(Locale.ROOT);
    }

    /** {@code nfl:401872994}. */
    public static String game(String league, String providerEventId) {
        return league + ":" + providerEventId.toLowerCase(Locale.ROOT);
    }

    /** True if {@code id} has the shape {@code league:key}. Says nothing about existence. */
    public static boolean isWellFormed(String id) {
        return id != null && ID.matcher(id).matches();
    }

    public static boolean isLeague(String league) {
        return league != null && LEAGUE.matcher(league).matches();
    }

    public static boolean isKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    /** League part of a well-formed id. */
    public static String leagueOf(String id) {
        if (!isWellFormed(id)) {
            throw new IllegalArgumentException("Not a canonical id: " + id);
        }
        return id.substring(0, id.indexOf(':'));
    }
}
