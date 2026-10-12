package ca.hex42labs.sports.persistence;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Fixed-width UTC timestamps ({@code 2026-10-13T00:15:00.000Z}) so SQL string comparison matches
 * time order. {@link Instant#toString()} drops zero fractions and would break that.
 */
public final class Timestamps {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Timestamps() {}

    public static String format(Instant instant) {
        return instant == null ? null : FORMAT.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }

    public static Instant parse(String s) {
        return s == null ? null : Instant.parse(s);
    }
}
