package ca.hex42labs.sports.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteConfig;

/**
 * SQLite access. Reads go through a small Hikari pool; all writes are funnelled through a single
 * dedicated connection so concurrent syncs never fight over SQLite's one writer lock.
 */
public final class Database implements AutoCloseable {

    private final HikariDataSource readPool;
    private final HikariDataSource writer;

    private Database(HikariDataSource readPool, HikariDataSource writer) {
        this.readPool = readPool;
        this.writer = writer;
    }

    /** Opens (creating if needed) the database file and applies pending migrations. */
    public static Database open(String dbPath) {
        Path path = Path.of(dbPath).toAbsolutePath();
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create database directory for " + path, e);
        }
        String url = "jdbc:sqlite:" + path;

        HikariDataSource writer = pool(url, "sqlite-writer", 1);
        migrate(writer);
        HikariDataSource readPool = pool(url, "sqlite-read", 4);
        return new Database(readPool, writer);
    }

    private static HikariDataSource pool(String url, String name, int size) {
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqlite.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        sqlite.setBusyTimeout(5_000);
        sqlite.enforceForeignKeys(true);
        Properties props = sqlite.toProperties();

        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName(name);
        cfg.setJdbcUrl(url);
        cfg.setDataSourceProperties(props);
        cfg.setMaximumPoolSize(size);
        cfg.setMinimumIdle(1);
        return new HikariDataSource(cfg);
    }

    private static void migrate(DataSource ds) {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    }

    public Connection read() throws SQLException {
        return readPool.getConnection();
    }

    /**
     * The single writer connection. Callers must hold it only for the duration of one unit of work;
     * the pool size of 1 serialises writers.
     */
    public Connection write() throws SQLException {
        return writer.getConnection();
    }

    /** Cheap liveness probe used by readiness checks. */
    public boolean isHealthy() {
        try (Connection c = read();
                var st = c.createStatement();
                var rs = st.executeQuery("SELECT 1")) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void close() {
        readPool.close();
        writer.close();
    }
}
