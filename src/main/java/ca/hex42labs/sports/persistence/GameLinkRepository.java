package ca.hex42labs.sports.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fallback-provider event id to canonical game id ({@code game_links}, see V2 migration). */
public final class GameLinkRepository {

    private final Database db;

    public GameLinkRepository(Database db) {
        this.db = db;
    }

    /**
     * Canonical game ids for {@code provider}'s events, keyed by event id (unlinked are absent).
     */
    public Map<String, String> gameIdsFor(String provider, Collection<String> eventIds) {
        Map<String, String> out = new HashMap<>();
        if (eventIds.isEmpty()) {
            return out;
        }
        String marks = String.join(",", Collections.nCopies(eventIds.size(), "?"));
        try (Connection c = db.read();
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT provider_event_id, game_id FROM game_links"
                                        + " WHERE provider = ? AND provider_event_id IN ("
                                        + marks
                                        + ")")) {
            ps.setString(1, provider);
            int i = 2;
            for (String id : List.copyOf(eventIds)) {
                ps.setString(i++, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new PersistenceException("Game link lookup failed", e);
        }
    }

    /** True if some event of {@code provider} is already linked to {@code gameId}. */
    public boolean isLinked(String provider, String gameId) {
        try (Connection c = db.read();
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT 1 FROM game_links WHERE provider = ? AND game_id = ?")) {
            ps.setString(1, provider);
            ps.setString(2, gameId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new PersistenceException("Game link lookup failed", e);
        }
    }

    /**
     * Records a link. First writer wins: an event that's already linked, or a game that already has
     * a link from this provider, is left alone.
     *
     * @return true if the link was added
     */
    public boolean link(
            String league, String provider, String eventId, String gameId, Instant now) {
        try (Connection c = db.write();
                PreparedStatement ps =
                        c.prepareStatement(
                                "INSERT OR IGNORE INTO game_links"
                                        + " (league, provider, provider_event_id, game_id,"
                                        + " linked_at) VALUES (?,?,?,?,?)")) {
            ps.setString(1, league);
            ps.setString(2, provider);
            ps.setString(3, eventId);
            ps.setString(4, gameId);
            ps.setString(5, Timestamps.format(now));
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new PersistenceException("Game link insert failed", e);
        }
    }

    public int count() {
        try (Connection c = db.read();
                PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM game_links");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new PersistenceException("Game link count failed", e);
        }
    }
}
