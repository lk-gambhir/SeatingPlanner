package persistence;

import domain.StaffMember;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** JDBC operations used by the first invigilator-dashboard slice. */
public final class StaffRepository {
    private final SqliteConnectionFactory connections;

    public StaffRepository(SqliteConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections);
    }

    public List<StaffMember> findAll() throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("""
                     SELECT staff_id, full_name, department, designation, active, data_label
                     FROM staff ORDER BY full_name, staff_id
                     """);
             var rows = statement.executeQuery()) {
            List<StaffMember> staff = new ArrayList<>();
            while (rows.next()) {
                staff.add(new StaffMember(rows.getString("staff_id"), rows.getString("full_name"),
                        rows.getString("department"), rows.getString("designation"),
                        rows.getInt("active") == 1, rows.getString("data_label")));
            }
            return List.copyOf(staff);
        }
    }

    public Optional<StaffMember> findById(String staffId) throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("""
                     SELECT staff_id, full_name, department, designation, active, data_label
                     FROM staff WHERE staff_id=?
                     """)) {
            statement.setString(1, staffId);
            try (var row = statement.executeQuery()) {
                return row.next() ? Optional.of(new StaffMember(row.getString("staff_id"),
                        row.getString("full_name"), row.getString("department"),
                        row.getString("designation"), row.getInt("active") == 1,
                        row.getString("data_label"))) : Optional.empty();
            }
        }
    }

    public void insert(StaffMember staff) throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO staff(staff_id, full_name, department, designation, active, data_label)
                     VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
            bind(statement, staff);
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (exception.getErrorCode() == 19) {
                throw new IllegalArgumentException("Staff ID already exists: " + staff.staffId(), exception);
            }
            throw exception;
        }
    }

    public void update(StaffMember staff) throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("""
                     UPDATE staff SET full_name=?, department=?, designation=?, active=?, data_label=?
                     WHERE staff_id=?
                     """)) {
            statement.setString(1, staff.fullName());
            statement.setString(2, staff.department());
            statement.setString(3, staff.designation());
            statement.setInt(4, staff.active() ? 1 : 0);
            statement.setString(5, staff.dataLabel());
            statement.setString(6, staff.staffId());
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("Unknown staff ID: " + staff.staffId());
            }
        }
    }

    public void setActive(String staffId, boolean active) throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("UPDATE staff SET active=? WHERE staff_id=?")) {
            statement.setInt(1, active ? 1 : 0);
            statement.setString(2, staffId);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("Unknown staff ID: " + staffId);
            }
        }
    }

    public void addAffiliation(String staffId, Affiliation affiliation) throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO staff_course_relationship(staff_id, course_id, relationship_type)
                     VALUES (?, ?, ?)
                     """)) {
            statement.setString(1, staffId);
            statement.setString(2, affiliation.courseId());
            statement.setString(3, affiliation.type().name());
            statement.executeUpdate();
        }
    }

    public void removeAffiliation(String staffId, Affiliation affiliation) throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("""
                     DELETE FROM staff_course_relationship
                     WHERE staff_id=? AND course_id=? AND relationship_type=?
                     """)) {
            statement.setString(1, staffId);
            statement.setString(2, affiliation.courseId());
            statement.setString(3, affiliation.type().name());
            statement.executeUpdate();
        }
    }

    public void replaceAffiliations(String staffId, List<Affiliation> affiliations) throws SQLException {
        try (Connection connection = connections.openConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (var exists = connection.prepareStatement("SELECT 1 FROM staff WHERE staff_id=?")) {
                    exists.setString(1, staffId);
                    try (var row = exists.executeQuery()) {
                        if (!row.next()) throw new IllegalArgumentException("Unknown staff ID: " + staffId);
                    }
                }
                try (var delete = connection.prepareStatement("DELETE FROM staff_course_relationship WHERE staff_id=?")) {
                    delete.setString(1, staffId);
                    delete.executeUpdate();
                }
                try (var insert = connection.prepareStatement("""
                        INSERT INTO staff_course_relationship(staff_id, course_id, relationship_type)
                        VALUES (?, ?, ?)
                        """)) {
                    for (Affiliation affiliation : affiliations) {
                        insert.setString(1, staffId);
                        insert.setString(2, affiliation.courseId());
                        insert.setString(3, affiliation.type().name());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    public List<Affiliation> findAffiliations(String staffId) throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("""
                     SELECT course_id, relationship_type FROM staff_course_relationship
                     WHERE staff_id=? ORDER BY course_id, relationship_type
                     """)) {
            statement.setString(1, staffId);
            try (var rows = statement.executeQuery()) {
                List<Affiliation> affiliations = new ArrayList<>();
                while (rows.next()) {
                    affiliations.add(new Affiliation(rows.getString("course_id"),
                            AffiliationType.valueOf(rows.getString("relationship_type"))));
                }
                return List.copyOf(affiliations);
            }
        }
    }

    private static void bind(java.sql.PreparedStatement statement, StaffMember staff) throws SQLException {
        statement.setString(1, staff.staffId());
        statement.setString(2, staff.fullName());
        statement.setString(3, staff.department());
        statement.setString(4, staff.designation());
        statement.setInt(5, staff.active() ? 1 : 0);
        statement.setString(6, staff.dataLabel());
    }

    public record Affiliation(String courseId, AffiliationType type) {
        public Affiliation {
            if (courseId == null || courseId.isBlank()) {
                throw new IllegalArgumentException("Course ID is required");
            }
            Objects.requireNonNull(type, "Affiliation type is required");
        }
    }

    public enum AffiliationType { TEACHES, EXCLUDED }
}
