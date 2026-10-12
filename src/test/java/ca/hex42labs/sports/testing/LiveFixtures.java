package ca.hex42labs.sports.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Synthesised in-progress games built from recorded fixtures (until real live captures land). */
public final class LiveFixtures {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter ESPN_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'").withZone(ZoneOffset.UTC);

    private LiveFixtures() {}

    /** An NFL scoreboard with one game in Q3, kicked off at {@code kickoff}. */
    public static String nflLive(Instant kickoff, int home, int away) {
        ObjectNode root = Fixtures.json("espn/nfl/scoreboard-week6-scheduled").deepCopy();
        ObjectNode ev = (ObjectNode) root.path("events").path(0);
        ArrayNode events = root.putArray("events");
        events.add(ev);
        ev.put("date", ESPN_DATE.format(kickoff));
        ObjectNode status = (ObjectNode) ev.path("status");
        status.put("period", 3).put("displayClock", "5:42");
        ((ObjectNode) status.path("type"))
                .put("name", "STATUS_IN_PROGRESS")
                .put("state", "in")
                .put("shortDetail", "5:42 - 3rd");
        ObjectNode comp = (ObjectNode) ev.path("competitions").path(0);
        comp.put("date", ESPN_DATE.format(kickoff));
        ((ObjectNode) comp.path("competitors").path(0)).put("score", String.valueOf(home));
        ((ObjectNode) comp.path("competitors").path(1)).put("score", String.valueOf(away));
        try {
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String eventId() {
        return Fixtures.json("espn/nfl/scoreboard-week6-scheduled")
                .path("events")
                .path(0)
                .path("id")
                .asText();
    }
}
