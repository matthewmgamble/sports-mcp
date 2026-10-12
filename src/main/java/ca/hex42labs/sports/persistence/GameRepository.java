package ca.hex42labs.sports.persistence;

import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.GameStatus;
import ca.hex42labs.sports.domain.PeriodType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class GameRepository {

    private static final String COLUMNS =
            "id, league, provider, provider_event_id, season, season_type, home_team_id,"
                + " away_team_id, starts_at, status, status_detail, period, period_type, clock,"
                + " home_score, away_score, home_shootout, away_shootout, venue, broadcasts_json,"
                + " situation_json, fetched_at, provider_updated_at";

    /**
     * Idempotent upsert keyed on the canonical id. The {@code WHERE} guard makes {@code fetched_at}
     * monotonic: a slow fetch that completes late never overwrites fresher data. Rescheduled games
     * simply update {@code starts_at}/{@code status} in place.
     */
    private static final String UPSERT =
            "INSERT INTO games ("
                    + COLUMNS
                    + ", created_at, updated_at) VALUES"
                    + " (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO"
                    + " UPDATE SET season = excluded.season, season_type = excluded.season_type,"
                    + " home_team_id = excluded.home_team_id, away_team_id = excluded.away_team_id,"
                    + " starts_at = excluded.starts_at, status = excluded.status, status_detail ="
                    + " excluded.status_detail, period = excluded.period, period_type ="
                    + " excluded.period_type, clock = excluded.clock, home_score ="
                    + " excluded.home_score, away_score = excluded.away_score, home_shootout ="
                    + " excluded.home_shootout, away_shootout = excluded.away_shootout, venue ="
                    + " excluded.venue, broadcasts_json = excluded.broadcasts_json, situation_json"
                    + " = excluded.situation_json, fetched_at = excluded.fetched_at,"
                    + " provider_updated_at = excluded.provider_updated_at, updated_at ="
                    + " excluded.updated_at WHERE excluded.fetched_at >= games.fetched_at";

    private final Database db;
    private final JsonColumns json;

    public GameRepository(Database db, ObjectMapper json) {
        this.db = db;
        this.json = new JsonColumns(json);
    }

    /**
     * @return number of rows inserted or updated (stale writes don't count)
     */
    public int upsertAll(Collection<Game> games, Instant now) {
        if (games.isEmpty()) {
            return 0;
        }
        String ts = Timestamps.format(now);
        try (Connection c = db.write()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(UPSERT)) {
                for (Game g : games) {
                    bind(ps, g, ts);
                    ps.addBatch();
                }
                int changed = 0;
                for (int n : ps.executeBatch()) {
                    changed += Math.max(n, 0);
                }
                c.commit();
                return changed;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new PersistenceException("Game upsert failed", e);
        }
    }

    public Optional<Game> findById(String id) {
        return query("SELECT " + COLUMNS + " FROM games WHERE id = ?", id).stream().findFirst();
    }

    /** Games in a league starting in {@code [from, to)}, by start time. */
    public List<Game> findByLeague(String league, Instant from, Instant to, int limit) {
        return query(
                "SELECT "
                        + COLUMNS
                        + " FROM games WHERE league = ? AND starts_at >= ? AND starts_at < ?"
                        + " ORDER BY starts_at, id LIMIT ?",
                league,
                Timestamps.format(from),
                Timestamps.format(to),
                limit);
    }

    /** A team's games (home or away) starting in {@code [from, to)}, by start time. */
    public List<Game> findByTeam(String teamId, Instant from, Instant to, int limit) {
        return query(
                "SELECT "
                        + COLUMNS
                        + " FROM games WHERE (home_team_id = ? OR away_team_id = ?)"
                        + " AND starts_at >= ? AND starts_at < ? ORDER BY starts_at, id LIMIT ?",
                teamId,
                teamId,
                Timestamps.format(from),
                Timestamps.format(to),
                limit);
    }

    public int count() {
        try (Connection c = db.read();
                PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM games");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new PersistenceException("Game count failed", e);
        }
    }

    private void bind(PreparedStatement ps, Game g, String now) throws SQLException {
        int i = 1;
        ps.setString(i++, g.id());
        ps.setString(i++, g.league());
        ps.setString(i++, g.provider());
        ps.setString(i++, g.providerEventId());
        ps.setString(i++, g.season());
        ps.setString(i++, g.seasonType());
        ps.setString(i++, g.homeTeamId());
        ps.setString(i++, g.awayTeamId());
        ps.setString(i++, Timestamps.format(g.startsAt()));
        ps.setString(i++, g.status().name());
        ps.setString(i++, g.statusDetail());
        setInt(ps, i++, g.period());
        ps.setString(i++, g.periodType() == null ? null : g.periodType().name());
        ps.setString(i++, g.clock());
        setInt(ps, i++, g.homeScore());
        setInt(ps, i++, g.awayScore());
        setInt(ps, i++, g.homeShootout());
        setInt(ps, i++, g.awayShootout());
        ps.setString(i++, g.venue());
        ps.setString(i++, json.write(g.broadcasts()));
        ps.setString(i++, json.write(g.situation()));
        ps.setString(i++, Timestamps.format(g.fetchedAt()));
        ps.setString(i++, Timestamps.format(g.providerUpdatedAt()));
        ps.setString(i++, now);
        ps.setString(i, now);
    }

    private static void setInt(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.INTEGER);
        } else {
            ps.setInt(i, v);
        }
    }

    private List<Game> query(String sql, Object... args) {
        try (Connection c = db.read();
                PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            List<Game> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new PersistenceException("Game query failed", e);
        }
    }

    private Game map(ResultSet rs) throws SQLException {
        String periodType = rs.getString("period_type");
        return new Game(
                rs.getString("id"),
                rs.getString("league"),
                rs.getString("provider"),
                rs.getString("provider_event_id"),
                rs.getString("season"),
                rs.getString("season_type"),
                rs.getString("home_team_id"),
                rs.getString("away_team_id"),
                Timestamps.parse(rs.getString("starts_at")),
                GameStatus.valueOf(rs.getString("status")),
                rs.getString("status_detail"),
                getInt(rs, "period"),
                periodType == null ? null : PeriodType.valueOf(periodType),
                rs.getString("clock"),
                getInt(rs, "home_score"),
                getInt(rs, "away_score"),
                getInt(rs, "home_shootout"),
                getInt(rs, "away_shootout"),
                rs.getString("venue"),
                json.strings(rs.getString("broadcasts_json")),
                json.read(rs.getString("situation_json"), Game.Situation.class),
                Timestamps.parse(rs.getString("fetched_at")),
                Timestamps.parse(rs.getString("provider_updated_at")));
    }

    private static Integer getInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }
}
