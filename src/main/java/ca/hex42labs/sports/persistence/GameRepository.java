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
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public final class GameRepository {

    private static final String COLUMNS =
            "id, league, provider, provider_event_id, season, season_type, home_team_id,"
                    + " away_team_id, starts_at, status, status_detail, period, period_type, clock,"
                    + " home_score, away_score, home_shootout, away_shootout, venue, broadcasts_json,"
                    + " situation_json, fetched_at, provider_updated_at, round, series, start_time_tbd";

    /**
     * Idempotent upsert keyed on the canonical id. The {@code WHERE} guard makes {@code fetched_at}
     * monotonic: a slow fetch that completes late never overwrites fresher data. Rescheduled games
     * update {@code starts_at}/{@code status} in place, except that a stale "postponed" listing for
     * an earlier date can't overwrite the makeup game.
     */
    private static final String UPSERT =
            "INSERT INTO games ("
                    + COLUMNS
                    + ", created_at, updated_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                    + " ON CONFLICT(id) DO UPDATE SET"
                    + "   season = excluded.season,"
                    + "   season_type = excluded.season_type,"
                    + "   home_team_id = excluded.home_team_id,"
                    + "   away_team_id = excluded.away_team_id,"
                    + "   starts_at = excluded.starts_at,"
                    + "   status = excluded.status,"
                    + "   status_detail = excluded.status_detail,"
                    + "   period = excluded.period,"
                    + "   period_type = excluded.period_type,"
                    + "   clock = excluded.clock,"
                    + "   home_score = excluded.home_score,"
                    + "   away_score = excluded.away_score,"
                    + "   home_shootout = excluded.home_shootout,"
                    + "   away_shootout = excluded.away_shootout,"
                    + "   venue = excluded.venue,"
                    + "   broadcasts_json = excluded.broadcasts_json,"
                    + "   situation_json = excluded.situation_json,"
                    + "   fetched_at = excluded.fetched_at,"
                    + "   provider_updated_at = excluded.provider_updated_at,"
                    // Team schedules don't carry the series: a scoreboard's stays until another
                    // scoreboard replaces it.
                    + "   round = COALESCE(excluded.round, games.round),"
                    + "   series = COALESCE(excluded.series, games.series),"
                    + "   start_time_tbd = excluded.start_time_tbd,"
                    + "   updated_at = excluded.updated_at"
                    + " WHERE excluded.fetched_at >= games.fetched_at"
                    // Original-date listing of a postponed game vs. its later makeup (same id).
                    + "   AND NOT (excluded.status = 'POSTPONED' AND games.status <> 'POSTPONED'"
                    + "            AND excluded.starts_at < games.starts_at)";

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

    /**
     * A game by canonical id. Also accepts a provisional fallback id ({@code mlb:espn401908026})
     * whose game the primary provider has since adopted: it resolves through {@code game_links} to
     * the canonical game, so ids handed out earlier keep working.
     */
    public Optional<Game> findById(String id) {
        Optional<Game> g =
                query("SELECT " + COLUMNS + " FROM games WHERE id = ?", id).stream().findFirst();
        if (g.isPresent()) {
            return g;
        }
        return query(
                        "SELECT "
                                + COLUMNS
                                + " FROM games WHERE id = (SELECT game_id FROM game_links"
                                + " WHERE league || ':' || provider || provider_event_id = ?)",
                        id)
                .stream()
                .findFirst();
    }

    /**
     * One provider's games for a matchup (exact home and away) starting in {@code [from, to)}, by
     * start time. Used to link the same game across providers.
     */
    public List<Game> findMatchup(
            String league,
            String provider,
            String homeTeamId,
            String awayTeamId,
            Instant from,
            Instant to) {
        return query(
                "SELECT "
                        + COLUMNS
                        + " FROM games WHERE league = ? AND provider = ? AND home_team_id = ?"
                        + " AND away_team_id = ? AND starts_at >= ? AND starts_at < ?"
                        + " ORDER BY starts_at, id",
                league,
                provider,
                homeTeamId,
                awayTeamId,
                Timestamps.format(from),
                Timestamps.format(to));
    }

    /** True if any of {@code league}'s stored games came from {@code provider}. */
    public boolean hasProvider(String league, String provider) {
        try (Connection c = db.read();
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT 1 FROM games WHERE league = ? AND provider = ? LIMIT 1")) {
            ps.setString(1, league);
            ps.setString(2, provider);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new PersistenceException("Game query failed", e);
        }
    }

    public boolean deleteById(String id) {
        try (Connection c = db.write();
                PreparedStatement ps = c.prepareStatement("DELETE FROM games WHERE id = ?")) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new PersistenceException("Game delete failed", e);
        }
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

    /**
     * Flexible read for the API: games in {@code [from, to)}, optionally for one league and/or team
     * and one status, earliest first (or latest first with {@code newestFirst}).
     */
    public List<Game> find(
            String league,
            String teamId,
            GameStatus status,
            Instant from,
            Instant to,
            boolean newestFirst,
            int limit) {
        StringBuilder sql =
                new StringBuilder(
                        "SELECT " + COLUMNS + " FROM games WHERE starts_at >= ? AND starts_at < ?");
        List<Object> args =
                new ArrayList<>(List.of(Timestamps.format(from), Timestamps.format(to)));
        if (league != null) {
            sql.append(" AND league = ?");
            args.add(league);
        }
        if (teamId != null) {
            sql.append(" AND (home_team_id = ? OR away_team_id = ?)");
            args.add(teamId);
            args.add(teamId);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        sql.append(newestFirst ? " ORDER BY starts_at DESC, id DESC" : " ORDER BY starts_at, id");
        sql.append(" LIMIT ?");
        args.add(limit);
        return query(sql.toString(), args.toArray());
    }

    public List<Game> findByIds(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return query(
                "SELECT " + COLUMNS + " FROM games WHERE id IN (" + marks + ")", ids.toArray());
    }

    /**
     * Games that may be in progress: LIVE (started after {@code liveSince}, so a game stuck LIVE
     * upstream doesn't haunt us forever), or not yet final and starting in {@code [from, to)}.
     */
    public List<Game> findActive(String league, Instant from, Instant to, Instant liveSince) {
        return query(
                "SELECT "
                        + COLUMNS
                        + " FROM games WHERE league = ? AND ("
                        + " (status = 'LIVE' AND starts_at >= ?)"
                        + " OR (status IN ('SCHEDULED', 'UNKNOWN', 'SUSPENDED') AND starts_at >= ? AND starts_at < ?))"
                        + " ORDER BY starts_at, id",
                league,
                Timestamps.format(liveSince),
                Timestamps.format(from),
                Timestamps.format(to));
    }

    /**
     * Deletes games that started before {@code cutoff}, and cross-provider links to games that are
     * gone. @return games deleted
     */
    public int deleteStartedBefore(Instant cutoff) {
        try (Connection c = db.write();
                PreparedStatement ps = c.prepareStatement("DELETE FROM games WHERE starts_at < ?");
                PreparedStatement links =
                        c.prepareStatement(
                                "DELETE FROM game_links WHERE game_id NOT IN"
                                        + " (SELECT id FROM games)")) {
            ps.setString(1, Timestamps.format(cutoff));
            int deleted = ps.executeUpdate();
            links.executeUpdate();
            return deleted;
        } catch (SQLException e) {
            throw new PersistenceException("Game retention delete failed", e);
        }
    }

    /** Game counts per league (ops view). */
    public Map<String, Integer> countByLeague() {
        Map<String, Integer> out = new TreeMap<>();
        try (Connection c = db.read();
                PreparedStatement ps =
                        c.prepareStatement("SELECT league, count(*) FROM games GROUP BY league");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getInt(2));
            }
            return out;
        } catch (SQLException e) {
            throw new PersistenceException("Game count failed", e);
        }
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
        ps.setString(i++, g.round());
        ps.setString(i++, g.series());
        ps.setInt(i++, g.startTimeTbd() ? 1 : 0);
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
                Timestamps.parse(rs.getString("provider_updated_at")),
                rs.getString("round"),
                rs.getString("series"),
                rs.getInt("start_time_tbd") != 0);
    }

    private static Integer getInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }
}
