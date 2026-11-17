package persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates the local database and applies packaged schema migrations exactly once. */
public final class DatabaseBootstrap {
    private static final Pattern MIGRATION_NAME = Pattern.compile("V(\\d+)__.*\\.sql");
    private static final List<String> MIGRATIONS = List.of(
            "/db/migrations/V001__initial_schema.sql",
            "/db/migrations/V002__plan_snapshot_metadata.sql",
            "/db/migrations/V003__plan_display_snapshots.sql",
            "/db/migrations/V004__import_source_headers.sql");

    private DatabaseBootstrap() {
    }

    public static void initialize(Path databasePath) throws IOException, SQLException {
        Path normalizedPath = databasePath.toAbsolutePath().normalize();
        Path parent = normalizedPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        SqliteConnectionFactory connectionFactory = new SqliteConnectionFactory(normalizedPath);
        try (Connection connection = connectionFactory.openConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode = WAL");
            }
            createMigrationTable(connection);
            for (String migration : MIGRATIONS) {
                applyMigration(connection, migration);
            }
            verifyDatabase(connection);
        }
    }

    private static void createMigrationTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migration (
                        version INTEGER PRIMARY KEY,
                        file_name TEXT NOT NULL,
                        applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
    }

    private static void applyMigration(Connection connection, String resourcePath)
            throws IOException, SQLException {
        int version = migrationVersion(resourcePath);
        try (var query = connection.prepareStatement(
                "SELECT 1 FROM schema_migration WHERE version = ?")) {
            query.setInt(1, version);
            try (var result = query.executeQuery()) {
                if (result.next()) {
                    return;
                }
            }
        }

        String sql = readResource(resourcePath);
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (String command : splitStatements(sql)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(command);
                }
            }
            try (var insert = connection.prepareStatement(
                    "INSERT INTO schema_migration(version, file_name) VALUES (?, ?)")) {
                insert.setInt(1, version);
                insert.setString(2, resourcePath.substring(resourcePath.lastIndexOf('/') + 1));
                insert.executeUpdate();
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static int migrationVersion(String resourcePath) {
        String fileName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
        Matcher matcher = MIGRATION_NAME.matcher(fileName);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid migration file name: " + fileName);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static String readResource(String resourcePath) throws IOException {
        try (InputStream input = DatabaseBootstrap.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException("Missing database migration resource: " + resourcePath);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Current migrations contain ordinary SQL statements without procedural bodies. */
    private static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder withoutLineComments = new StringBuilder();
        for (String line : sql.lines().toList()) {
            if (!line.stripLeading().startsWith("--")) {
                withoutLineComments.append(line).append('\n');
            }
        }
        for (String raw : withoutLineComments.toString().split(";")) {
            String command = raw.strip();
            if (!command.isEmpty()) {
                statements.add(command);
            }
        }
        return statements;
    }

    private static void verifyDatabase(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA integrity_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                throw new SQLException("SQLite integrity check did not return ok");
            }
        }
        try (Statement statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (result.next()) {
                throw new SQLException("Database contains a foreign-key violation in table "
                        + result.getString(1));
            }
        }
    }
}
