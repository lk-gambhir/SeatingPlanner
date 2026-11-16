package persistence;

import domain.Student;
import importexport.EnrollmentCsvImporter;
import importexport.EnrollmentImportPreview;
import importexport.StudentImportPreview;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** JDBC access to student profiles and their normalized course registrations. */
public final class StudentRepository {
    private final SqliteConnectionFactory connections;

    public StudentRepository(SqliteConnectionFactory connections) { this.connections = Objects.requireNonNull(connections); }

    public List<Student> findAll() throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("""
                     SELECT s.student_id, s.roll_number, s.full_name, s.program, s.semester, s.section,
                            s.active, s.data_label,
                            COUNT(CASE WHEN e.registration_status='REGISTERED' AND s.active=1 THEN 1 END) AS enrollment_count
                     FROM student s LEFT JOIN enrollment e ON e.student_id=s.student_id
                     GROUP BY s.student_id ORDER BY s.full_name, s.student_id
                     """);
             var rows = statement.executeQuery()) {
            List<Student> students = new ArrayList<>();
            while (rows.next()) students.add(new Student(rows.getString("student_id"), rows.getString("roll_number"),
                    rows.getString("full_name"), rows.getString("program"),
                    (Integer) rows.getObject("semester"), rows.getString("section"),
                    rows.getInt("active") == 1, rows.getString("data_label"), rows.getInt("enrollment_count")));
            return List.copyOf(students);
        }
    }

    public List<Registration> findRegistrations(String studentId) throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("""
                     SELECT n.enrollment_id, sc.session_course_id, c.course_code, c.course_name,
                            x.session_date, x.start_time, e.event_code, n.registration_status
                     FROM enrollment n
                     JOIN session_course sc ON sc.session_course_id=n.session_course_id
                     JOIN course c ON c.course_id=sc.course_id
                     JOIN exam_session x ON x.exam_session_id=sc.exam_session_id
                     JOIN exam_event e ON e.event_id=x.event_id
                     WHERE n.student_id=? ORDER BY x.session_date, x.start_time, c.course_code
                     """)) {
            statement.setString(1, studentId);
            try (var rows = statement.executeQuery()) {
                List<Registration> registrations = new ArrayList<>();
                while (rows.next()) registrations.add(new Registration(rows.getString("enrollment_id"),
                        rows.getString("session_course_id"), rows.getString("course_code"),
                        rows.getString("course_name"), rows.getString("session_date"),
                        rows.getString("start_time"), rows.getString("event_code"),
                        rows.getString("registration_status")));
                return List.copyOf(registrations);
            }
        }
    }

    public void setRegistrationStatus(String enrollmentId, String status) throws SQLException {
        if (!List.of("REGISTERED", "WITHDRAWN", "INACTIVE").contains(status))
            throw new IllegalArgumentException("Choose Registered, Withdrawn, or Inactive.");
        String now = Instant.now().toString();
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try {
                String eventId;
                try (var find = connection.prepareStatement("""
                        SELECT e.event_id, s.active FROM enrollment n JOIN session_course sc ON sc.session_course_id=n.session_course_id
                        JOIN exam_session x ON x.exam_session_id=sc.exam_session_id
                        JOIN exam_event e ON e.event_id=x.event_id JOIN student s ON s.student_id=n.student_id
                        WHERE n.enrollment_id=?
                        """)) {
                    find.setString(1, enrollmentId);
                    try (var row = find.executeQuery()) {
                        if (!row.next()) throw new IllegalArgumentException("Unknown enrollment ID.");
                        eventId = row.getString("event_id");
                        if (status.equals("REGISTERED") && row.getInt("active") != 1)
                            throw new IllegalArgumentException("Reactivate the student profile before registering them.");
                    }
                }
                try (var update = connection.prepareStatement("UPDATE enrollment SET registration_status=? WHERE enrollment_id=?")) {
                    update.setString(1, status); update.setString(2, enrollmentId); update.executeUpdate();
                }
                try (var update = connection.prepareStatement("UPDATE exam_event SET source_revision=source_revision+1, updated_at=? WHERE event_id=?")) {
                    update.setString(1, now); update.setString(2, eventId); update.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback(); throw exception;
            }
        }
    }

    public List<String> findEventCodes() throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("SELECT event_code FROM exam_event ORDER BY event_code");
             var rows = statement.executeQuery()) {
            List<String> eventCodes = new ArrayList<>();
            while (rows.next()) eventCodes.add(rows.getString(1));
            return java.util.Collections.unmodifiableList(eventCodes);
        }
    }

    public List<CourseDemand> findCourseDemand(String eventCode) throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("""
                     SELECT x.session_date, x.start_time, c.course_code, c.course_name, sc.candidate_count,
                            COUNT(n.enrollment_id) AS roster_rows,
                            COUNT(CASE WHEN n.registration_status='REGISTERED' AND s.active=1 THEN 1 END) AS registered
                     FROM exam_event e
                     JOIN exam_session x ON x.event_id=e.event_id
                     JOIN session_course sc ON sc.exam_session_id=x.exam_session_id
                     JOIN course c ON c.course_id=sc.course_id
                     LEFT JOIN enrollment n ON n.session_course_id=sc.session_course_id
                     LEFT JOIN student s ON s.student_id=n.student_id
                     WHERE e.event_code=?
                     GROUP BY sc.session_course_id
                     ORDER BY x.session_date, x.start_time, c.course_code
                     """)) {
            statement.setString(1, eventCode);
            try (var rows = statement.executeQuery()) {
                List<CourseDemand> demand = new ArrayList<>();
                while (rows.next()) demand.add(new CourseDemand(rows.getString("session_date"),
                        rows.getString("start_time"), rows.getString("course_code"), rows.getString("course_name"),
                        rows.getInt("candidate_count"), rows.getInt("registered"), rows.getInt("roster_rows")));
                return List.copyOf(demand);
            }
        }
    }

    public List<EnrollmentExportRow> findEnrollments(String eventCode) throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("""
                     SELECT n.enrollment_id, n.student_id, c.course_code, e.event_code AS session_id,
                            n.registration_status, n.data_label
                     FROM enrollment n JOIN session_course sc ON sc.session_course_id=n.session_course_id
                     JOIN course c ON c.course_id=sc.course_id JOIN exam_session x ON x.exam_session_id=sc.exam_session_id
                     JOIN exam_event e ON e.event_id=x.event_id WHERE e.event_code=?
                     ORDER BY c.course_code, n.student_id
                     """)) {
            statement.setString(1, eventCode);
            try (var rows = statement.executeQuery()) {
                List<EnrollmentExportRow> enrollments = new ArrayList<>();
                while (rows.next()) enrollments.add(new EnrollmentExportRow(rows.getString("enrollment_id"),
                        rows.getString("student_id"), rows.getString("course_code"), eventCode,
                        rows.getString("registration_status"), rows.getString("data_label")));
                return List.copyOf(enrollments);
            }
        }
    }

    public StudentConflictReport findConflicts(String eventCode) throws SQLException {
        try (Connection connection = connections.openConnection()) {
            boolean hasRoster;
            try (var statement = connection.prepareStatement("""
                    SELECT EXISTS(SELECT 1 FROM exam_event e JOIN exam_session x ON x.event_id=e.event_id
                        JOIN session_course sc ON sc.exam_session_id=x.exam_session_id
                        JOIN enrollment n ON n.session_course_id=sc.session_course_id WHERE e.event_code=?)
                    """)) {
                statement.setString(1, eventCode);
                try (var rows = statement.executeQuery()) { hasRoster = rows.next() && rows.getInt(1) == 1; }
            }
            if (!hasRoster) return new StudentConflictReport(false, List.of());
            try (var statement = connection.prepareStatement("""
                    SELECT s.student_id, s.roll_number, s.full_name,
                           c1.course_code AS course_one, c2.course_code AS course_two,
                           x1.session_date AS date_one, x1.start_time AS start_one,
                           x2.session_date AS date_two, x2.start_time AS start_two
                    FROM enrollment n1 JOIN enrollment n2
                        ON n2.student_id=n1.student_id AND n1.session_course_id<n2.session_course_id
                    JOIN student s ON s.student_id=n1.student_id AND s.active=1
                    JOIN session_course sc1 ON sc1.session_course_id=n1.session_course_id
                    JOIN session_course sc2 ON sc2.session_course_id=n2.session_course_id
                    JOIN exam_session x1 ON x1.exam_session_id=sc1.exam_session_id
                    JOIN exam_session x2 ON x2.exam_session_id=sc2.exam_session_id
                    JOIN exam_event e1 ON e1.event_id=x1.event_id
                    JOIN exam_event e2 ON e2.event_id=x2.event_id
                    JOIN course c1 ON c1.course_id=sc1.course_id
                    JOIN course c2 ON c2.course_id=sc2.course_id
                    WHERE e1.event_code=? AND e2.event_code=?
                        AND n1.registration_status='REGISTERED' AND n2.registration_status='REGISTERED'
                        AND x1.status<>'CANCELLED' AND x2.status<>'CANCELLED'
                        AND x1.session_date=x2.session_date
                        AND x1.start_time<x2.end_time AND x2.start_time<x1.end_time
                    ORDER BY s.roll_number, date_one, start_one, course_one, course_two
                    """)) {
                statement.setString(1, eventCode); statement.setString(2, eventCode);
                try (var rows = statement.executeQuery()) {
                    List<StudentConflict> conflicts = new ArrayList<>();
                    while (rows.next()) conflicts.add(new StudentConflict(rows.getString("student_id"),
                            rows.getString("roll_number"), rows.getString("full_name"),
                            rows.getString("course_one"), rows.getString("course_two"),
                            rows.getString("date_one"), rows.getString("start_one"),
                            rows.getString("date_two"), rows.getString("start_two")));
                    return new StudentConflictReport(true, conflicts);
                }
            }
        }
    }

    public EnrollmentCsvImporter.Context findEnrollmentContext(String eventCode) throws SQLException {
        Map<String, List<EnrollmentCsvImporter.CourseTarget>> courses = new HashMap<>();
        Set<String> students = new HashSet<>();
        Set<String> activeStudents = new HashSet<>();
        Set<String> existingKeys = new HashSet<>();
        Map<String, EnrollmentCsvImporter.ExistingEnrollment> existingById = new HashMap<>();
        Map<String, Integer> registeredCountByCourse = new HashMap<>();
        boolean foundEvent = false;
        try (Connection connection = connections.openConnection()) {
            try (var event = connection.prepareStatement("SELECT 1 FROM exam_event WHERE event_code=?")) {
                event.setString(1, eventCode);
                try (var rows = event.executeQuery()) { foundEvent = rows.next(); }
            }
            if (!foundEvent) throw new IllegalArgumentException("Unknown exam event: " + eventCode);
            try (var query = connection.prepareStatement("""
                    SELECT c.course_code, sc.session_course_id, sc.candidate_count
                    FROM exam_event e JOIN exam_session x ON x.event_id=e.event_id
                    JOIN session_course sc ON sc.exam_session_id=x.exam_session_id
                    JOIN course c ON c.course_id=sc.course_id WHERE e.event_code=?
                    ORDER BY c.course_code, x.session_date, x.start_time
                    """)) {
                query.setString(1, eventCode);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) courses.computeIfAbsent(rows.getString("course_code"), ignored -> new ArrayList<>())
                            .add(new EnrollmentCsvImporter.CourseTarget(rows.getString("session_course_id"),
                                    rows.getInt("candidate_count")));
                }
            }
            try (var query = connection.prepareStatement("SELECT student_id, active FROM student");
                 var rows = query.executeQuery()) {
                while (rows.next()) {
                    String studentId = rows.getString("student_id");
                    students.add(studentId);
                    if (rows.getInt("active") == 1) activeStudents.add(studentId);
                }
            }
            try (var query = connection.prepareStatement("""
                    SELECT n.enrollment_id, n.student_id, sc.session_course_id, c.course_code, n.registration_status
                    FROM enrollment n JOIN session_course sc ON sc.session_course_id=n.session_course_id
                    JOIN course c ON c.course_id=sc.course_id JOIN exam_session x ON x.exam_session_id=sc.exam_session_id
                    JOIN exam_event e ON e.event_id=x.event_id WHERE e.event_code=?
                    """)) {
                query.setString(1, eventCode);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        String courseCode = rows.getString("course_code");
                        String registrationKey = EnrollmentCsvImporter.registrationKey(rows.getString("student_id"),
                                rows.getString("session_course_id"));
                        existingKeys.add(registrationKey);
                        existingById.put(rows.getString("enrollment_id"),
                                new EnrollmentCsvImporter.ExistingEnrollment(registrationKey));
                        if (rows.getString("registration_status").equals("REGISTERED")
                                && activeStudents.contains(rows.getString("student_id"))) {
                            registeredCountByCourse.merge(courseCode, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        Map<String, List<EnrollmentCsvImporter.CourseTarget>> immutableCourses = new HashMap<>();
        courses.forEach((code, targets) -> immutableCourses.put(code, List.copyOf(targets)));
        return new EnrollmentCsvImporter.Context(eventCode, Map.copyOf(immutableCourses), Set.copyOf(students),
                Set.copyOf(activeStudents), Set.copyOf(existingKeys), Map.copyOf(existingById),
                Map.copyOf(registeredCountByCourse));
    }

    public void insert(Student student) throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO student(student_id, roll_number, full_name, program, semester, section, active, data_label)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            bind(statement, student);
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (exception.getErrorCode() == 19) throw new IllegalArgumentException("Student ID or roll number already exists.", exception);
            throw exception;
        }
    }

    public void update(Student student) throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("""
                     UPDATE student SET roll_number=?, full_name=?, program=?, semester=?, section=?, active=?, data_label=?
                     WHERE student_id=?
                     """)) {
            connection.setAutoCommit(false);
            try {
                statement.setString(1, student.rollNumber()); statement.setString(2, student.fullName());
                statement.setString(3, student.program()); statement.setObject(4, student.semester());
                statement.setString(5, student.section()); statement.setInt(6, student.active() ? 1 : 0);
                statement.setString(7, student.dataLabel()); statement.setString(8, student.studentId());
                if (statement.executeUpdate() == 0) throw new IllegalArgumentException("Unknown student ID: " + student.studentId());
                bumpStudentEventRevisions(connection, student.studentId(), Instant.now().toString());
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback(); throw exception;
            }
        }
    }

    public void setActive(String studentId, boolean active) throws SQLException {
        setActive(List.of(studentId), active);
    }

    public void setActive(List<String> studentIds, boolean active) throws SQLException {
        if (studentIds.isEmpty()) throw new IllegalArgumentException("Select at least one student.");
        String now = Instant.now().toString();
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement("UPDATE student SET active=? WHERE student_id=?")) {
                    for (String studentId : studentIds) {
                        statement.setInt(1, active ? 1 : 0); statement.setString(2, studentId);
                        if (statement.executeUpdate() == 0) {
                            throw new IllegalArgumentException("Unknown student ID: " + studentId);
                        }
                        bumpStudentEventRevisions(connection, studentId, now);
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback(); throw exception;
            }
        }
    }

    private static void bumpStudentEventRevisions(Connection connection, String studentId, String now) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE exam_event SET source_revision=source_revision+1, updated_at=?
                WHERE event_id IN (
                    SELECT DISTINCT x.event_id FROM enrollment n
                    JOIN session_course sc ON sc.session_course_id=n.session_course_id
                    JOIN exam_session x ON x.exam_session_id=sc.exam_session_id
                    WHERE n.student_id=?
                )
                """)) {
            statement.setString(1, now); statement.setString(2, studentId); statement.executeUpdate();
        }
    }

    public int commitImport(StudentImportPreview preview) throws SQLException {
        if (preview.hasErrors()) throw new IllegalArgumentException("Resolve CSV errors before importing.");
        String batchId = UUID.randomUUID().toString();
        String sourceId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        int added = 0;
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var batch = connection.prepareStatement("""
                        INSERT INTO import_batch(import_batch_id, batch_label, status, created_at, committed_at)
                        VALUES (?, ?, 'COMMITTED', ?, ?)
                        """)) {
                    batch.setString(1, batchId); batch.setString(2, "Students: " + preview.fileName());
                    batch.setString(3, now); batch.setString(4, now); batch.executeUpdate();
                }
                try (var source = connection.prepareStatement("""
                        INSERT INTO import_source(import_source_id, import_batch_id, file_name, source_type,
                            sha256, parse_status, row_count, source_headers, created_at)
                        VALUES (?, ?, ?, 'CSV', ?, 'PARSED', ?, ?, ?)
                        """)) {
                    source.setString(1, sourceId); source.setString(2, batchId); source.setString(3, preview.fileName());
                    source.setString(4, preview.sha256()); source.setInt(5, preview.rows().size());
                    source.setString(6, preview.sourceHeaders()); source.setString(7, now); source.executeUpdate();
                }
                try (var staged = connection.prepareStatement("""
                        INSERT INTO import_staged_row(import_staged_row_id, import_source_id, source_row,
                            raw_values, normalized_values, row_status)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    for (StudentImportPreview.Row row : preview.rows()) {
                        staged.setString(1, UUID.randomUUID().toString()); staged.setString(2, sourceId);
                        staged.setInt(3, row.sourceRow()); staged.setString(4, row.rawValues());
                        staged.setString(5, row.normalizedValues());
                        staged.setString(6, row.status() == StudentImportPreview.Status.ADD ? "ACCEPTED" : "SKIPPED");
                        staged.addBatch();
                    }
                    staged.executeBatch();
                }
                try (var diagnostic = connection.prepareStatement("""
                        INSERT INTO import_diagnostic(diagnostic_id, import_batch_id, import_source_id,
                            source_row, severity, code, message)
                        VALUES (?, ?, ?, ?, 'INFO', 'EXISTING_STUDENT_SKIPPED', ?)
                        """)) {
                    for (StudentImportPreview.Row row : preview.rows()) {
                        if (row.status() != StudentImportPreview.Status.SKIP) continue;
                        diagnostic.setString(1, UUID.randomUUID().toString()); diagnostic.setString(2, batchId);
                        diagnostic.setString(3, sourceId); diagnostic.setInt(4, row.sourceRow());
                        diagnostic.setString(5, row.message()); diagnostic.addBatch();
                    }
                    diagnostic.executeBatch();
                }
                try (var insert = connection.prepareStatement("""
                        INSERT INTO student(student_id, roll_number, full_name, program, semester, section, active, data_label)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    for (StudentImportPreview.Row row : preview.rows()) {
                        if (row.status() != StudentImportPreview.Status.ADD) continue;
                        bind(insert, row.student()); insert.addBatch(); added++;
                    }
                    insert.executeBatch();
                }
                connection.commit();
                return added;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback(); throw exception;
            }
        }
    }

    public EnrollmentImportResult commitEnrollments(EnrollmentImportPreview preview) throws SQLException {
        if (preview.hasErrors()) throw new IllegalArgumentException("Resolve enrollment CSV errors before importing.");
        String batchId = UUID.randomUUID().toString();
        String sourceId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        int added = 0;
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var batch = connection.prepareStatement("""
                        INSERT INTO import_batch(import_batch_id, batch_label, status, created_at, committed_at)
                        VALUES (?, ?, 'COMMITTED', ?, ?)
                        """)) {
                    batch.setString(1, batchId); batch.setString(2, "Enrollments: " + preview.fileName()
                            + " (" + preview.sourceSessionId() + " → " + preview.eventCode() + ")");
                    batch.setString(3, now); batch.setString(4, now); batch.executeUpdate();
                }
                try (var source = connection.prepareStatement("""
                        INSERT INTO import_source(import_source_id, import_batch_id, file_name, source_type,
                            sha256, parse_status, row_count, source_headers, created_at)
                        VALUES (?, ?, ?, 'CSV', ?, ?, ?, ?, ?)
                        """)) {
                    source.setString(1, sourceId); source.setString(2, batchId); source.setString(3, preview.fileName());
                    source.setString(4, preview.sha256()); source.setString(5, preview.hasWarnings() ? "REVIEW_REQUIRED" : "PARSED");
                    source.setInt(6, preview.rows().size()); source.setString(7, preview.sourceHeaders());
                    source.setString(8, now); source.executeUpdate();
                }
                try (var staged = connection.prepareStatement("""
                        INSERT INTO import_staged_row(import_staged_row_id, import_source_id, source_row,
                            raw_values, normalized_values, row_status)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    for (EnrollmentImportPreview.Row row : preview.rows()) {
                        staged.setString(1, UUID.randomUUID().toString()); staged.setString(2, sourceId);
                        staged.setInt(3, row.sourceRow()); staged.setString(4, row.rawValues());
                        staged.setString(5, row.normalizedValues());
                        staged.setString(6, row.status() == EnrollmentImportPreview.Status.ADD ? "ACCEPTED" : "SKIPPED");
                        staged.addBatch();
                    }
                    staged.executeBatch();
                }
                try (var diagnostic = connection.prepareStatement("""
                        INSERT INTO import_diagnostic(diagnostic_id, import_batch_id, import_source_id,
                            severity, code, message)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    for (EnrollmentImportPreview.Diagnostic item : preview.diagnostics()) {
                        diagnostic.setString(1, UUID.randomUUID().toString()); diagnostic.setString(2, batchId);
                        diagnostic.setString(3, sourceId); diagnostic.setString(4, item.severity());
                        diagnostic.setString(5, item.code()); diagnostic.setString(6, item.message()); diagnostic.addBatch();
                    }
                    diagnostic.executeBatch();
                }
                try (var insert = connection.prepareStatement("""
                        INSERT INTO enrollment(enrollment_id, student_id, session_course_id, registration_status,
                            import_batch_id, data_label)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    for (EnrollmentImportPreview.Row row : preview.rows()) {
                        if (row.status() != EnrollmentImportPreview.Status.ADD) continue;
                        insert.setString(1, row.enrollmentId()); insert.setString(2, row.studentId());
                        insert.setString(3, row.sessionCourseId()); insert.setString(4, row.registrationStatus());
                        insert.setString(5, batchId); insert.setString(6, row.dataLabel()); insert.addBatch(); added++;
                    }
                    insert.executeBatch();
                }
                if (added > 0) try (var update = connection.prepareStatement("""
                        UPDATE exam_event SET source_revision=source_revision+1, updated_at=? WHERE event_code=?
                        """)) {
                    update.setString(1, now); update.setString(2, preview.eventCode());
                    if (update.executeUpdate() == 0) throw new IllegalArgumentException("Unknown exam event: " + preview.eventCode());
                }
                connection.commit();
                return new EnrollmentImportResult(added, preview.count(EnrollmentImportPreview.Status.SKIP),
                        preview.diagnostics().size());
            } catch (SQLException | RuntimeException exception) {
                connection.rollback(); throw exception;
            }
        }
    }

    private static void bind(java.sql.PreparedStatement statement, Student student) throws SQLException {
        statement.setString(1, student.studentId()); statement.setString(2, student.rollNumber());
        statement.setString(3, student.fullName()); statement.setString(4, student.program());
        statement.setObject(5, student.semester()); statement.setString(6, student.section());
        statement.setInt(7, student.active() ? 1 : 0); statement.setString(8, student.dataLabel());
    }

    public record Registration(String enrollmentId, String sessionCourseId, String courseCode,
            String courseName, String sessionDate, String startTime, String eventCode, String status) {
        @Override public String toString() {
            return eventCode + " · " + sessionDate + " " + startTime + " · " + courseCode
                    + " " + (courseName == null ? "" : courseName) + " · " + status;
        }
    }
    public record CourseDemand(String sessionDate, String startTime, String courseCode, String courseName,
            int candidateCount, int registeredCount, int rosterRows) {}
    public record EnrollmentExportRow(String enrollmentId, String studentId, String courseCode,
            String sessionId, String status, String dataLabel) {}
    public record EnrollmentImportResult(int added, int skipped, int warnings) {}
    public record StudentConflictReport(boolean rosterAvailable, List<StudentConflict> conflicts) {
        public StudentConflictReport { conflicts = List.copyOf(conflicts); }
    }
    public record StudentConflict(String studentId, String rollNumber, String fullName,
            String firstCourse, String secondCourse, String firstDate, String firstStart,
            String secondDate, String secondStart) {}
}
