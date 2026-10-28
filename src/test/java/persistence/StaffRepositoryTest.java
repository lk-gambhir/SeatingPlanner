package persistence;

import domain.StaffMember;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StaffRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void storesTextIdsAndReplacesAffiliationsAtomically() throws Exception {
        Path database = tempDir.resolve("staff.db");
        DatabaseBootstrap.initialize(database);
        SqliteConnectionFactory connections = new SqliteConnectionFactory(database);
        StaffRepository repository = new StaffRepository(connections);
        insertCourse(database, "0007");

        StaffMember staff = new StaffMember("0012", "  Asha Rao  ", "Maths", "Professor", true, "DEMO");
        repository.insert(staff);
        repository.replaceAffiliations(staff.staffId(), List.of(
                new StaffRepository.Affiliation("0007", StaffRepository.AffiliationType.TEACHES)));

        assertEquals("0012", repository.findAll().getFirst().staffId());
        assertEquals("Asha Rao", repository.findAll().getFirst().fullName());
        assertEquals(1, repository.findAffiliations("0012").size());

        assertThrows(SQLException.class, () -> repository.replaceAffiliations("0012", List.of(
                new StaffRepository.Affiliation("missing-course", StaffRepository.AffiliationType.EXCLUDED))));
        assertEquals(1, repository.findAffiliations("0012").size());
    }

    @Test
    void rejectsDuplicateStaffIds() throws Exception {
        Path database = tempDir.resolve("duplicate.db");
        DatabaseBootstrap.initialize(database);
        StaffRepository repository = new StaffRepository(new SqliteConnectionFactory(database));
        StaffMember staff = new StaffMember("S1", "Asha Rao", null, null, true, "DEMO");
        repository.insert(staff);
        assertThrows(IllegalArgumentException.class, () -> repository.insert(staff));
    }

    @Test
    void findsAndDeactivatesStaffByTextId() throws Exception {
        Path database = tempDir.resolve("status.db");
        DatabaseBootstrap.initialize(database);
        StaffRepository repository = new StaffRepository(new SqliteConnectionFactory(database));
        repository.insert(new StaffMember("0009", "Ravi Kumar", null, null, true, "DEMO"));

        repository.setActive("0009", false);

        assertEquals(false, repository.findById("0009").orElseThrow().active());
        assertEquals(true, repository.findById("9").isEmpty());
    }

    private static void insertCourse(Path database, String courseId) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement(
                     "INSERT INTO course(course_id, course_code, course_name) VALUES (?, ?, ?)")) {
            statement.setString(1, courseId);
            statement.setString(2, "MAT101");
            statement.setString(3, "Mathematics");
            statement.executeUpdate();
        }
    }
}
