package ca.hex42labs.sports.persistence;

import ca.hex42labs.sports.domain.Team;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public final class TeamRepository {

    private static final String COLUMNS =
            "id, league, provider, provider_team_id, name, short_name, abbreviation, location,"
                    + " logo_url, aliases_json, updated_at";

    private static final String INSERT =
            "INSERT INTO teams (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)";

    private static final String UPSERT =
            INSERT
                    + " ON CONFLICT(id) DO UPDATE SET provider = excluded.provider,"
                    + " provider_team_id = excluded.provider_team_id, name = excluded.name,"
                    + " short_name = excluded.short_name, abbreviation = excluded.abbreviation,"
                    + " location = excluded.location, logo_url = excluded.logo_url,"
                    + " aliases_json = excluded.aliases_json, updated_at = excluded.updated_at";

    private final Database db;
    private final JsonColumns json;

    public TeamRepository(Database db, ObjectMapper json) {
        this.db = db;
        this.json = new JsonColumns(json);
    }

    /** Authoritative write from a team-catalog sync: inserts new teams, overwrites existing. */
    public void upsertAll(Collection<Team> teams, Instant now) {
        write(UPSERT, teams, now);
    }

    /**
     * Adds teams seen in game payloads without touching existing rows. Game feeds carry thinner
     * team data (NHL scores only have "Maple Leafs") than the catalog, so they must not overwrite.
     */
    public void insertMissing(Collection<Team> teams, Instant now) {
        write(INSERT + " ON CONFLICT DO NOTHING", teams, now);
    }

    public List<Team> findAll() {
        return query("SELECT " + COLUMNS + " FROM teams ORDER BY league, name");
    }

    public List<Team> findByLeague(String league) {
        return query("SELECT " + COLUMNS + " FROM teams WHERE league = ? ORDER BY name", league);
    }

    public Optional<Team> findById(String id) {
        return query("SELECT " + COLUMNS + " FROM teams WHERE id = ?", id).stream().findFirst();
    }

    private void write(String sql, Collection<Team> teams, Instant now) {
        if (teams.isEmpty()) {
            return;
        }
        String ts = Timestamps.format(now);
        try (Connection c = db.write()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (Team t : teams) {
                    int i = 1;
                    ps.setString(i++, t.id());
                    ps.setString(i++, t.league());
                    ps.setString(i++, t.provider());
                    ps.setString(i++, t.providerTeamId());
                    ps.setString(i++, t.name());
                    ps.setString(i++, t.shortName());
                    ps.setString(i++, t.abbreviation());
                    ps.setString(i++, t.location());
                    ps.setString(i++, t.logoUrl());
                    ps.setString(i++, json.write(t.aliases()));
                    ps.setString(i, ts);
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new PersistenceException("Team write failed", e);
        }
    }

    private List<Team> query(String sql, Object... args) {
        try (Connection c = db.read();
                PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            List<Team> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(
                            new Team(
                                    rs.getString("id"),
                                    rs.getString("league"),
                                    rs.getString("provider"),
                                    rs.getString("provider_team_id"),
                                    rs.getString("name"),
                                    rs.getString("short_name"),
                                    rs.getString("abbreviation"),
                                    rs.getString("location"),
                                    rs.getString("logo_url"),
                                    json.strings(rs.getString("aliases_json"))));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new PersistenceException("Team query failed", e);
        }
    }
}
