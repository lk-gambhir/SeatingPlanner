package persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Creates configured SQLite JDBC connections for the local application database. */
public final class SqliteConnectionFactory {
    private final String jdbcUrl;

    public SqliteConnectionFactory(Path databasePath) {
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath().normalize();
    }

    public Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
        return connection;
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }
}
