package ca.hex42labs.sports.persistence;

import ca.hex42labs.sports.domain.SyncState;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SyncStateRepository {

    private final Database db;

    public SyncStateRepository(Database db) {
        this.db = db;
    }

    public void recordAttempt(String league, SyncState.Kind kind, Instant at) {
        exec(
                "INSERT INTO sync_state (league, kind, last_attempt_at) VALUES (?,?,?) ON"
                        + " CONFLICT(league, kind) DO UPDATE SET last_attempt_at ="
                        + " excluded.last_attempt_at",
                league,
                kind.name(),
                Timestamps.format(at));
    }

    public void recordSuccess(
            String league,
            SyncState.Kind kind,
            Instant at,
            LocalDate windowStart,
            LocalDate windowEnd) {
        String ts = Timestamps.format(at);
        exec(
                "INSERT INTO sync_state (league, kind, window_start, window_end, last_success_at,"
                        + " last_attempt_at, error) VALUES (?,?,?,?,?,?,NULL) ON CONFLICT(league, kind)"
                        + " DO UPDATE SET window_start = excluded.window_start, window_end ="
                        + " excluded.window_end, last_success_at = excluded.last_success_at,"
                        + " last_attempt_at = excluded.last_attempt_at, error = NULL",
                league,
                kind.name(),
                windowStart == null ? null : windowStart.toString(),
                windowEnd == null ? null : windowEnd.toString(),
                ts,
                ts);
    }

    public void recordFailure(String league, SyncState.Kind kind, Instant at, String error) {
        exec(
                "INSERT INTO sync_state (league, kind, last_attempt_at, error) VALUES (?,?,?,?) ON"
                        + " CONFLICT(league, kind) DO UPDATE SET last_attempt_at ="
                        + " excluded.last_attempt_at, error = excluded.error",
                league,
                kind.name(),
                Timestamps.format(at),
                error);
    }

    public Optional<SyncState> find(String league, SyncState.Kind kind) {
        return query("SELECT * FROM sync_state WHERE league = ? AND kind = ?", league, kind.name())
                .stream()
                .findFirst();
    }

    public List<SyncState> findAll() {
        return query("SELECT * FROM sync_state ORDER BY league, kind");
    }

    private List<SyncState> query(String sql, Object... args) {
        try (Connection c = db.read();
                PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            List<SyncState> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ws = rs.getString("window_start");
                    String we = rs.getString("window_end");
                    out.add(
                            new SyncState(
                                    rs.getString("league"),
                                    SyncState.Kind.valueOf(rs.getString("kind")),
                                    ws == null ? null : LocalDate.parse(ws),
                                    we == null ? null : LocalDate.parse(we),
                                    Timestamps.parse(rs.getString("last_success_at")),
                                    Timestamps.parse(rs.getString("last_attempt_at")),
                                    rs.getString("error")));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new PersistenceException("Sync state query failed", e);
        }
    }

    private void exec(String sql, Object... args) {
        try (Connection c = db.write();
                PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Sync state write failed", e);
        }
    }
}
