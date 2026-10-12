package ca.hex42labs.sports.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {

    @TempDir Path tmp;

    @Test
    void migratesFreshDatabaseInWalMode() throws Exception {
        try (Database db = Database.open(tmp.resolve("sub/dir/sports.db").toString())) {
            assertThat(db.isHealthy()).isTrue();
            try (Connection c = db.read()) {
                assertThat(tables(c)).contains("teams", "games", "sync_state", "raw_payloads");
                try (ResultSet rs = c.createStatement().executeQuery("PRAGMA journal_mode")) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualToIgnoringCase("wal");
                }
            }
        }
    }

    @Test
    void reopeningIsIdempotentAndKeepsData() throws Exception {
        String path = tmp.resolve("sports.db").toString();
        try (Database db = Database.open(path);
                Connection c = db.write()) {
            c.createStatement()
                    .executeUpdate(
                            "INSERT INTO sync_state(league, kind, last_attempt_at)"
                                    + " VALUES ('nhl', 'SCHEDULE', '2026-10-12T00:00:00Z')");
        }
        try (Database db = Database.open(path);
                Connection c = db.read();
                ResultSet rs =
                        c.createStatement().executeQuery("SELECT count(*) FROM sync_state")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    private static List<String> tables(Connection c) throws Exception {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getTables(null, null, "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                names.add(rs.getString("TABLE_NAME"));
            }
        }
        return names;
    }
}
