package persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseBootstrapTest {
    @TempDir
    Path tempDir;

    @Test
    void appliesMigrationsOnceAndAddsPlanSnapshotFields() throws Exception {
        Path database = tempDir.resolve("fresh.db");
        DatabaseBootstrap.initialize(database);
        DatabaseBootstrap.initialize(database);

        try (var connection = new SqliteConnectionFactory(database).openConnection();
             var statement = connection.createStatement();
             var versions = statement.executeQuery("SELECT count(*) FROM schema_migration")) {
            versions.next();
            assertEquals(2, versions.getInt(1));
        }
        assertTrue(planColumns(database).containsAll(Set.of(
                "generation_config_snapshot", "change_summary", "finalized_by")));
    }

    @Test
    void upgradesAnExistingVersionOnePlanWithoutDeletingIt() throws Exception {
        Path database = tempDir.resolve("upgrade.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_migration(version INTEGER PRIMARY KEY, file_name TEXT NOT NULL, applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            try (InputStream input = DatabaseBootstrap.class.getResourceAsStream(
                    "/db/migrations/V001__initial_schema.sql")) {
                String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                sql = sql.lines().filter(line -> !line.stripLeading().startsWith("--"))
                        .collect(Collectors.joining("\n"));
                for (String command : sql.split(";")) {
                    if (!command.isBlank()) statement.execute(command);
                }
            }
            statement.execute("INSERT INTO schema_migration(version, file_name) VALUES (1, 'V001__initial_schema.sql')");
            statement.execute("INSERT INTO exam_event(event_id, event_code, name, status, created_at, updated_at) VALUES ('E1','E1','Exam','DRAFT','now','now')");
            statement.execute("INSERT INTO plan(plan_id,event_id,version_number,status,source_revision,planner_version,created_at) VALUES ('P1','E1',1,'DRAFT',0,'test','now')");
        }

        DatabaseBootstrap.initialize(database);

        try (var connection = new SqliteConnectionFactory(database).openConnection();
             var statement = connection.createStatement();
             var plan = statement.executeQuery("SELECT change_summary FROM plan WHERE plan_id='P1'")) {
            assertTrue(plan.next());
            assertEquals("Migrated from an earlier schema", plan.getString(1));
        }
    }

    private static Set<String> planColumns(Path database) throws Exception {
        try (var connection = new SqliteConnectionFactory(database).openConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("PRAGMA table_info(plan)")) {
            Set<String> columns = new java.util.HashSet<>();
            while (rows.next()) columns.add(rows.getString("name"));
            return columns;
        }
    }
}
