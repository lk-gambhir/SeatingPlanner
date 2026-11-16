package persistence;

import domain.StaffMember;
import domain.StaffImportPreview;
import importexport.AffiliationImportPreview;
import importexport.StaffAffiliationCsvImporter;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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

    public List<SessionOption> findSessions() throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("""
                     SELECT x.exam_session_id, e.event_code, x.session_date, x.start_time
                     FROM exam_session x JOIN exam_event e ON e.event_id=x.event_id
                     WHERE x.status<>'CANCELLED' AND e.status<>'ARCHIVED'
                     ORDER BY x.session_date, x.start_time, e.event_code
                     """); var rows = statement.executeQuery()) {
            List<SessionOption> sessions = new ArrayList<>();
            while (rows.next()) sessions.add(new SessionOption(rows.getString("exam_session_id"),
                    rows.getString("event_code"), rows.getString("session_date"), rows.getString("start_time")));
            return List.copyOf(sessions);
        }
    }

    public java.util.Map<String, SessionStaffStatus> findSessionStatuses(String sessionId) throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("""
                     SELECT s.staff_id, COALESCE(a.availability,'NOT_SET') AS availability,
                         COALESCE(t.attendance,'NOT_RECORDED') AS attendance
                     FROM staff s
                     LEFT JOIN staff_availability a ON a.staff_id=s.staff_id AND a.exam_session_id=?
                     LEFT JOIN staff_attendance t ON t.staff_id=s.staff_id AND t.exam_session_id=?
                     ORDER BY s.full_name, s.staff_id
                     """)) {
            statement.setString(1, sessionId); statement.setString(2, sessionId);
            try (var rows = statement.executeQuery()) {
                java.util.Map<String, SessionStaffStatus> statuses = new java.util.HashMap<>();
                while (rows.next()) statuses.put(rows.getString("staff_id"), new SessionStaffStatus(
                        rows.getString("availability"), rows.getString("attendance")));
                return java.util.Map.copyOf(statuses);
            }
        }
    }

    public List<AssignedRoomView> findAssignedRooms(String sessionId, List<String> staffIds) throws SQLException {
        if (staffIds.isEmpty()) return List.of();
        String marks = String.join(",", java.util.Collections.nCopies(staffIds.size(), "?"));
        String query = """
                SELECT ia.staff_id, e.event_code, p.version_number, p.status,
                    CASE WHEN p.source_revision=e.source_revision THEN 0 ELSE 1 END AS stale,
                    COALESCE(ra.building_code_snapshot,b.building_code) AS building_code,
                    COALESCE(ra.room_code_snapshot,r.room_code) AS room_code
                FROM invigilation_assignment ia JOIN room_assignment ra ON ra.room_assignment_id=ia.room_assignment_id
                JOIN plan_session ps ON ps.plan_session_id=ra.plan_session_id
                JOIN plan p ON p.plan_id=ps.plan_id
                JOIN exam_session x ON x.exam_session_id=ps.exam_session_id
                JOIN exam_event e ON e.event_id=x.event_id
                JOIN room r ON r.room_id=ra.room_id
                JOIN building b ON b.building_id=r.building_id
                WHERE ps.exam_session_id=? AND ia.assignment_status<>'REMOVED'
                    AND ra.assignment_status<>'REMOVED' AND p.status<>'SUPERSEDED'
                    AND ia.staff_id IN (%s)
                ORDER BY ia.staff_id, p.version_number DESC, building_code, room_code
                """.formatted(marks);
        try (Connection connection = connections.openConnection(); var statement = connection.prepareStatement(query)) {
            statement.setString(1, sessionId);
            for (int index = 0; index < staffIds.size(); index++) statement.setString(index + 2, staffIds.get(index));
            try (var rows = statement.executeQuery()) {
                List<AssignedRoomView> rooms = new ArrayList<>();
                while (rows.next()) rooms.add(new AssignedRoomView(rows.getString("staff_id"),
                        rows.getString("event_code"), rows.getInt("version_number"), rows.getString("status"),
                        rows.getInt("stale") == 1, rows.getString("building_code"), rows.getString("room_code")));
                return List.copyOf(rooms);
            }
        }
    }

    public List<RoomAssignmentSummary> findSessionAssignments(String sessionId) throws SQLException {
        try (Connection connection = connections.openConnection(); var statement = connection.prepareStatement("""
                SELECT e.event_code, p.version_number, p.status,
                    CASE WHEN p.source_revision=e.source_revision THEN 0 ELSE 1 END AS stale,
                    COALESCE(ra.building_code_snapshot,b.building_code) AS building_code,
                    COALESCE(ra.room_code_snapshot,r.room_code) AS room_code,
                    ra.required_staff, COUNT(ia.staff_id) AS assigned_staff,
                    GROUP_CONCAT(ia.staff_id, ', ') AS staff_ids
                FROM room_assignment ra JOIN plan_session ps ON ps.plan_session_id=ra.plan_session_id
                JOIN plan p ON p.plan_id=ps.plan_id
                JOIN exam_session x ON x.exam_session_id=ps.exam_session_id
                JOIN exam_event e ON e.event_id=x.event_id
                JOIN room r ON r.room_id=ra.room_id JOIN building b ON b.building_id=r.building_id
                LEFT JOIN invigilation_assignment ia ON ia.room_assignment_id=ra.room_assignment_id
                    AND ia.assignment_status<>'REMOVED'
                WHERE ps.exam_session_id=? AND ra.assignment_status<>'REMOVED' AND p.status<>'SUPERSEDED'
                GROUP BY ra.room_assignment_id
                ORDER BY p.version_number DESC, building_code, room_code
                """)) {
            statement.setString(1, sessionId);
            try (var rows = statement.executeQuery()) {
                List<RoomAssignmentSummary> assignments = new ArrayList<>();
                while (rows.next()) assignments.add(new RoomAssignmentSummary(rows.getString("event_code"),
                        rows.getInt("version_number"), rows.getString("status"), rows.getInt("stale") == 1,
                        rows.getString("building_code"), rows.getString("room_code"),
                        rows.getInt("required_staff"), rows.getInt("assigned_staff"), rows.getString("staff_ids")));
                return List.copyOf(assignments);
            }
        }
    }

    public List<SessionAttendanceRow> findSessionAttendance(String sessionId) throws SQLException {
        try (Connection connection = connections.openConnection(); var statement = connection.prepareStatement("""
                SELECT e.event_code, x.exam_session_id, x.session_date, x.start_time,
                    s.staff_id, s.full_name, s.department, s.active,
                    COALESCE(a.availability,'NOT_SET') AS availability,
                    COALESCE(t.attendance,'NOT_RECORDED') AS attendance,
                    GROUP_CONCAT(DISTINCT e.event_code || ' v' || p.version_number || ' ' || b.building_code || ' ' || r.room_code) AS assigned_rooms
                FROM staff s JOIN exam_session x ON x.exam_session_id=?
                JOIN exam_event e ON e.event_id=x.event_id
                LEFT JOIN staff_availability a ON a.staff_id=s.staff_id AND a.exam_session_id=x.exam_session_id
                LEFT JOIN staff_attendance t ON t.staff_id=s.staff_id AND t.exam_session_id=x.exam_session_id
                LEFT JOIN invigilation_assignment ia ON ia.staff_id=s.staff_id AND ia.assignment_status<>'REMOVED'
                LEFT JOIN room_assignment ra ON ra.room_assignment_id=ia.room_assignment_id
                    AND ra.assignment_status<>'REMOVED'
                LEFT JOIN plan_session ps ON ps.plan_session_id=ra.plan_session_id
                    AND ps.exam_session_id=x.exam_session_id
                LEFT JOIN plan p ON p.plan_id=ps.plan_id AND p.status<>'SUPERSEDED'
                LEFT JOIN room r ON r.room_id=ra.room_id
                LEFT JOIN building b ON b.building_id=r.building_id
                GROUP BY s.staff_id
                ORDER BY s.full_name,s.staff_id
                """)) {
            statement.setString(1, sessionId);
            try (var rows = statement.executeQuery()) {
                List<SessionAttendanceRow> result = new ArrayList<>();
                while (rows.next()) result.add(new SessionAttendanceRow(rows.getString("event_code"),
                        rows.getString("exam_session_id"), rows.getString("session_date"), rows.getString("start_time"),
                        rows.getString("staff_id"), rows.getString("full_name"), rows.getString("department"),
                        rows.getInt("active") == 1, rows.getString("availability"), rows.getString("attendance"),
                        rows.getString("assigned_rooms")));
                return List.copyOf(result);
            }
        }
    }

    public void setSessionStatuses(String sessionId, List<String> staffIds, String availability,
            String attendance) throws SQLException {
        List<SessionStatusUpdate> updates = staffIds.stream()
                .map(staffId -> new SessionStatusUpdate(staffId, availability, attendance)).toList();
        updateSessionStatuses(sessionId, updates);
    }

    public void updateSessionStatuses(String sessionId, List<SessionStatusUpdate> updates) throws SQLException {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("Choose an exam session.");
        if (updates.isEmpty()) throw new IllegalArgumentException("Select at least one staff member.");
        String now = Instant.now().toString();
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try {
                for (SessionStatusUpdate update : updates) {
                    if (update.availability() != null && !List.of("NOT_SET", "AVAILABLE", "UNAVAILABLE").contains(update.availability()))
                        throw new IllegalArgumentException("Choose Available, Unavailable, or Not set.");
                    if (update.attendance() != null && !List.of("PRESENT", "ABSENT", "NOT_RECORDED").contains(update.attendance()))
                        throw new IllegalArgumentException("Choose Present, Absent, or Not recorded.");
                    if (update.availability() == null && update.attendance() == null)
                        throw new IllegalArgumentException("Choose an availability or attendance change.");
                }
                try (var removeAvailability = connection.prepareStatement(
                            "DELETE FROM staff_availability WHERE staff_id=? AND exam_session_id=?");
                     var saveAvailability = connection.prepareStatement("""
                            INSERT INTO staff_availability(staff_id,exam_session_id,availability)
                            VALUES (?,?,?) ON CONFLICT(staff_id,exam_session_id)
                            DO UPDATE SET availability=excluded.availability, reason=NULL
                            """);
                     var removeAttendance = connection.prepareStatement(
                            "DELETE FROM staff_attendance WHERE staff_id=? AND exam_session_id=?");
                     var saveAttendance = connection.prepareStatement("""
                            INSERT INTO staff_attendance(staff_id,exam_session_id,attendance,marked_at)
                            VALUES (?,?,?,?) ON CONFLICT(staff_id,exam_session_id)
                            DO UPDATE SET attendance=excluded.attendance, marked_at=excluded.marked_at
                            """)) {
                    for (SessionStatusUpdate update : updates) {
                        if (update.availability() != null) {
                            if (update.availability().equals("NOT_SET")) {
                                removeAvailability.setString(1, update.staffId()); removeAvailability.setString(2, sessionId);
                                removeAvailability.executeUpdate();
                            } else {
                                saveAvailability.setString(1, update.staffId()); saveAvailability.setString(2, sessionId);
                                saveAvailability.setString(3, update.availability()); saveAvailability.executeUpdate();
                            }
                        }
                        if (update.attendance() != null) {
                            if (update.attendance().equals("NOT_RECORDED")) {
                                removeAttendance.setString(1, update.staffId()); removeAttendance.setString(2, sessionId);
                                removeAttendance.executeUpdate();
                            } else {
                                saveAttendance.setString(1, update.staffId()); saveAttendance.setString(2, sessionId);
                                saveAttendance.setString(3, update.attendance()); saveAttendance.setString(4, now);
                                saveAttendance.executeUpdate();
                            }
                        }
                    }
                }
                bumpSessionRevision(connection, sessionId, now);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback(); throw exception;
            }
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
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("""
                    INSERT INTO staff(staff_id, full_name, department, designation, active, data_label)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                bind(statement, staff); statement.executeUpdate();
                bumpEventRevisions(connection, Instant.now().toString());
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback(); throw exception;
            }
        } catch (SQLException exception) {
            if (exception.getErrorCode() == 19) {
                throw new IllegalArgumentException("Staff ID already exists: " + staff.staffId(), exception);
            }
            throw exception;
        }
    }

    /** Adds new staff from a reviewed CSV and records the import in one transaction. */
    public int commitImport(StaffImportPreview preview) throws SQLException {
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
                    batch.setString(1, batchId);
                    batch.setString(2, "Invigilators: " + preview.fileName());
                    batch.setString(3, now);
                    batch.setString(4, now);
                    batch.executeUpdate();
                }
                try (var source = connection.prepareStatement("""
                        INSERT INTO import_source(import_source_id, import_batch_id, file_name, source_type,
                            sha256, parse_status, row_count, source_headers, created_at)
                        VALUES (?, ?, ?, 'CSV', ?, 'PARSED', ?, ?, ?)
                        """)) {
                    source.setString(1, sourceId);
                    source.setString(2, batchId);
                    source.setString(3, preview.fileName());
                    source.setString(4, preview.sha256());
                    source.setInt(5, preview.rows().size());
                    source.setString(6, preview.sourceHeaders());
                    source.setString(7, now);
                    source.executeUpdate();
                }
                try (var staged = connection.prepareStatement("""
                        INSERT INTO import_staged_row(import_staged_row_id, import_source_id, source_row,
                            raw_values, normalized_values, row_status)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    for (StaffImportPreview.Row row : preview.rows()) {
                        String status = switch (row.status()) {
                            case ADD -> "ACCEPTED";
                            case SKIP -> "SKIPPED";
                            case ERROR -> "ERROR";
                        };
                        staged.setString(1, UUID.randomUUID().toString());
                        staged.setString(2, sourceId);
                        staged.setInt(3, row.sourceRow());
                        staged.setString(4, row.rawValues());
                        staged.setString(5, row.normalizedValues());
                        staged.setString(6, status);
                        staged.addBatch();
                    }
                    staged.executeBatch();
                }
                try (var diagnostic = connection.prepareStatement("""
                        INSERT INTO import_diagnostic(diagnostic_id, import_batch_id, import_source_id,
                            source_row, severity, code, message)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    for (StaffImportPreview.Warning warning : preview.warnings()) {
                        diagnostic.setString(1, UUID.randomUUID().toString());
                        diagnostic.setString(2, batchId);
                        diagnostic.setString(3, sourceId);
                        diagnostic.setObject(4, warning.sourceRow());
                        diagnostic.setString(5, "WARNING");
                        diagnostic.setString(6, warning.code());
                        diagnostic.setString(7, warning.message());
                        diagnostic.addBatch();
                    }
                    for (StaffImportPreview.Row row : preview.rows()) {
                        if (row.status() == StaffImportPreview.Status.SKIP) {
                            diagnostic.setString(1, UUID.randomUUID().toString());
                            diagnostic.setString(2, batchId);
                            diagnostic.setString(3, sourceId);
                            diagnostic.setInt(4, row.sourceRow());
                            diagnostic.setString(5, "INFO");
                            diagnostic.setString(6, "EXISTING_STAFF_SKIPPED");
                            diagnostic.setString(7, row.message());
                            diagnostic.addBatch();
                        }
                    }
                    diagnostic.executeBatch();
                }
                try (var insert = connection.prepareStatement("""
                        INSERT INTO staff(staff_id, full_name, department, designation, active, data_label)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    for (StaffImportPreview.Row row : preview.rows()) {
                        if (row.status() != StaffImportPreview.Status.ADD) continue;
                        StaffMember staff = row.staff();
                        insert.setString(1, staff.staffId());
                        insert.setString(2, staff.fullName());
                        insert.setString(3, staff.department());
                        insert.setString(4, staff.designation());
                        insert.setInt(5, staff.active() ? 1 : 0);
                        insert.setString(6, staff.dataLabel());
                        insert.addBatch();
                        added++;
                    }
                    insert.executeBatch();
                }
                if (added > 0) bumpEventRevisions(connection, now);
                connection.commit();
                return added;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public StaffAffiliationCsvImporter.Context findAffiliationImportContext() throws SQLException {
        List<String> staff = new ArrayList<>();
        java.util.Map<String, String> courses = new java.util.HashMap<>();
        java.util.Set<String> existing = new java.util.HashSet<>();
        try (Connection connection = connections.openConnection()) {
            try (var statement = connection.prepareStatement("SELECT staff_id FROM staff");
                 var rows = statement.executeQuery()) {
                while (rows.next()) staff.add(rows.getString(1));
            }
            try (var statement = connection.prepareStatement("SELECT course_code, course_id FROM course");
                 var rows = statement.executeQuery()) {
                while (rows.next()) courses.put(rows.getString("course_code"), rows.getString("course_id"));
            }
            try (var statement = connection.prepareStatement("""
                    SELECT staff_id, course_id, relationship_type FROM staff_course_relationship
                    """); var rows = statement.executeQuery()) {
                while (rows.next()) existing.add(StaffAffiliationCsvImporter.relationshipKey(
                        rows.getString("staff_id"), rows.getString("course_id"), rows.getString("relationship_type")));
            }
        }
        return new StaffAffiliationCsvImporter.Context(java.util.Set.copyOf(staff), courses, existing);
    }

    public List<AffiliationView> findAffiliationViews(String staffId) throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("""
                     SELECT c.course_code, r.relationship_type, r.data_label
                     FROM staff_course_relationship r JOIN course c ON c.course_id=r.course_id
                     WHERE r.staff_id=? ORDER BY c.course_code, r.relationship_type
                     """)) {
            statement.setString(1, staffId);
            try (var rows = statement.executeQuery()) {
                List<AffiliationView> affiliations = new ArrayList<>();
                while (rows.next()) affiliations.add(new AffiliationView(rows.getString("course_code"),
                        AffiliationType.valueOf(rows.getString("relationship_type")), rows.getString("data_label")));
                return List.copyOf(affiliations);
            }
        }
    }

    public java.util.Map<String, Integer> findAffiliationCounts() throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("""
                     SELECT staff_id, COUNT(*) AS total FROM staff_course_relationship GROUP BY staff_id
                     """); var rows = statement.executeQuery()) {
            java.util.Map<String, Integer> counts = new java.util.HashMap<>();
            while (rows.next()) counts.put(rows.getString("staff_id"), rows.getInt("total"));
            return java.util.Map.copyOf(counts);
        }
    }

    public List<AffiliationExportRow> findAllAffiliations() throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("""
                     SELECT r.staff_id, c.course_code, r.relationship_type, r.data_label
                     FROM staff_course_relationship r JOIN course c ON c.course_id=r.course_id
                     ORDER BY r.staff_id, c.course_code, r.relationship_type
                     """); var rows = statement.executeQuery()) {
            List<AffiliationExportRow> affiliations = new ArrayList<>();
            while (rows.next()) affiliations.add(new AffiliationExportRow(rows.getString("staff_id"),
                    rows.getString("course_code"), rows.getString("relationship_type"), rows.getString("data_label")));
            return List.copyOf(affiliations);
        }
    }

    public int commitAffiliationImport(AffiliationImportPreview preview) throws SQLException {
        if (preview.hasErrors()) throw new IllegalArgumentException("Resolve relationship CSV errors before importing.");
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
                    batch.setString(1, batchId); batch.setString(2, "Staff course links: " + preview.fileName());
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
                    for (AffiliationImportPreview.Row row : preview.rows()) {
                        staged.setString(1, UUID.randomUUID().toString()); staged.setString(2, sourceId);
                        staged.setInt(3, row.sourceRow()); staged.setString(4, row.rawValues());
                        staged.setString(5, row.normalizedValues());
                        staged.setString(6, row.status() == AffiliationImportPreview.Status.ADD ? "ACCEPTED" : "SKIPPED");
                        staged.addBatch();
                    }
                    staged.executeBatch();
                }
                try (var diagnostic = connection.prepareStatement("""
                        INSERT INTO import_diagnostic(diagnostic_id, import_batch_id, import_source_id,
                            source_row, severity, code, message)
                        VALUES (?, ?, ?, ?, 'INFO', 'EXISTING_AFFILIATION_SKIPPED', ?)
                        """)) {
                    for (AffiliationImportPreview.Row row : preview.rows()) {
                        if (row.status() != AffiliationImportPreview.Status.SKIP) continue;
                        diagnostic.setString(1, UUID.randomUUID().toString()); diagnostic.setString(2, batchId);
                        diagnostic.setString(3, sourceId); diagnostic.setInt(4, row.sourceRow());
                        diagnostic.setString(5, row.message()); diagnostic.addBatch();
                    }
                    diagnostic.executeBatch();
                }
                try (var insert = connection.prepareStatement("""
                        INSERT INTO staff_course_relationship(staff_id, course_id, relationship_type, import_batch_id, data_label)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
                    for (AffiliationImportPreview.Row row : preview.rows()) {
                        if (row.status() != AffiliationImportPreview.Status.ADD) continue;
                        insert.setString(1, row.staffId()); insert.setString(2, row.courseId());
                        insert.setString(3, row.relationshipType()); insert.setString(4, batchId);
                        insert.setString(5, row.dataLabel()); insert.addBatch(); added++;
                    }
                    insert.executeBatch();
                }
                if (added > 0) bumpEventRevisions(connection, now);
                connection.commit();
                return added;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback(); throw exception;
            }
        }
    }

    public void update(StaffMember staff) throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("""
                     UPDATE staff SET full_name=?, department=?, designation=?, active=?, data_label=?
                     WHERE staff_id=?
                     """)) {
            connection.setAutoCommit(false);
            try {
                statement.setString(1, staff.fullName()); statement.setString(2, staff.department());
                statement.setString(3, staff.designation()); statement.setInt(4, staff.active() ? 1 : 0);
                statement.setString(5, staff.dataLabel()); statement.setString(6, staff.staffId());
                if (statement.executeUpdate() == 0) throw new IllegalArgumentException("Unknown staff ID: " + staff.staffId());
                bumpEventRevisions(connection, Instant.now().toString());
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback(); throw exception;
            }
        }
    }

    public void setActive(String staffId, boolean active) throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("UPDATE staff SET active=? WHERE staff_id=?")) {
            connection.setAutoCommit(false);
            try {
                statement.setInt(1, active ? 1 : 0); statement.setString(2, staffId);
                if (statement.executeUpdate() == 0) throw new IllegalArgumentException("Unknown staff ID: " + staffId);
                bumpEventRevisions(connection, Instant.now().toString());
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback(); throw exception;
            }
        }
    }

    public void deactivate(List<String> staffIds) throws SQLException {
        if (staffIds.isEmpty()) return;
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("UPDATE staff SET active=0 WHERE staff_id=?")) {
            connection.setAutoCommit(false);
            try {
                for (String staffId : staffIds) {
                    statement.setString(1, staffId);
                    if (statement.executeUpdate() == 0) throw new IllegalArgumentException("Unknown staff ID: " + staffId);
                }
                bumpEventRevisions(connection, Instant.now().toString());
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback(); throw exception;
            }
        }
    }

    public void addAffiliation(String staffId, Affiliation affiliation) throws SQLException {
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement("""
                        INSERT INTO staff_course_relationship(staff_id, course_id, relationship_type)
                        VALUES (?, ?, ?)
                        """)) {
                    statement.setString(1, staffId); statement.setString(2, affiliation.courseId());
                    statement.setString(3, affiliation.type().name()); statement.executeUpdate();
                }
                bumpEventRevisions(connection, Instant.now().toString());
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback(); throw exception;
            }
        }
    }

    public void addAffiliationByCourseCode(String staffId, String courseCode, AffiliationType type) throws SQLException {
        String courseId = findCourseId(courseCode);
        addAffiliation(staffId, new Affiliation(courseId, type));
    }

    public void removeAffiliation(String staffId, Affiliation affiliation) throws SQLException {
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try {
                int removed;
                try (var statement = connection.prepareStatement("""
                        DELETE FROM staff_course_relationship
                        WHERE staff_id=? AND course_id=? AND relationship_type=?
                        """)) {
                    statement.setString(1, staffId); statement.setString(2, affiliation.courseId());
                    statement.setString(3, affiliation.type().name()); removed = statement.executeUpdate();
                }
                if (removed > 0) bumpEventRevisions(connection, Instant.now().toString());
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback(); throw exception;
            }
        }
    }

    public void removeAffiliationByCourseCode(String staffId, String courseCode, AffiliationType type) throws SQLException {
        removeAffiliation(staffId, new Affiliation(findCourseId(courseCode), type));
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
                bumpEventRevisions(connection, Instant.now().toString());
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

    private String findCourseId(String courseCode) throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("SELECT course_id FROM course WHERE course_code=?")) {
            statement.setString(1, courseCode.trim().toUpperCase(java.util.Locale.ROOT));
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Unknown course code: " + courseCode);
                return rows.getString(1);
            }
        }
    }

    private static void bumpEventRevisions(Connection connection, String now) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE exam_event SET source_revision=source_revision+1, updated_at=?
                WHERE status<>'ARCHIVED'
                """)) {
            statement.setString(1, now); statement.executeUpdate();
        }
    }

    private static void bumpSessionRevision(Connection connection, String sessionId, String now) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE exam_event SET source_revision=source_revision+1, updated_at=?
                WHERE event_id=(SELECT event_id FROM exam_session WHERE exam_session_id=?) AND status<>'ARCHIVED'
                """)) {
            statement.setString(1, now); statement.setString(2, sessionId); statement.executeUpdate();
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

    public record AffiliationView(String courseCode, AffiliationType type, String dataLabel) {}
    public record AffiliationExportRow(String staffId, String courseCode, String relationshipType, String dataLabel) {}
    public record SessionOption(String sessionId, String eventCode, String date, String startTime) {
        @Override public String toString() { return eventCode + " · " + date + " " + startTime; }
    }
    public record SessionStaffStatus(String availability, String attendance) {}
    public record SessionStatusUpdate(String staffId, String availability, String attendance) {}
    public record AssignedRoomView(String staffId, String eventCode, int planVersion, String planStatus,
            boolean stale, String buildingCode, String roomCode) {}
    public record RoomAssignmentSummary(String eventCode, int planVersion, String planStatus, boolean stale,
            String buildingCode, String roomCode, int requiredStaff, int assignedStaff, String staffIds) {
        public boolean hasShortage() { return assignedStaff < requiredStaff; }
    }
    public record SessionAttendanceRow(String eventCode, String sessionId, String date, String startTime,
            String staffId, String fullName, String department, boolean active, String availability,
            String attendance, String assignedRooms) {}

    public enum AffiliationType { TEACHES, EXCLUDED }
}
