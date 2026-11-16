package persistence;

import planning.FeasibilityAnalyzer;
import planning.FeasibilityReport;
import planning.GeneratedPlan;
import planning.PlanValidator;
import planning.SeatAssignmentPlanner;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Loads one event snapshot, generates a seating draft, and saves reviewed drafts. */
public final class PlanRepository {
    private final SqliteConnectionFactory connections;

    public PlanRepository(SqliteConnectionFactory connections) { this.connections = connections; }

    public List<PlanSummary> findPlans(String eventCode) throws SQLException {
        try (Connection connection = connections.openConnection(); var statement = connection.prepareStatement("""
                SELECT p.plan_id, p.version_number, p.status, p.source_revision, p.created_at, p.change_summary,
                    p.finalized_by, p.finalized_at,
                    e.source_revision AS current_revision,
                    (SELECT COUNT(*) FROM room_assignment ra JOIN plan_session ps ON ps.plan_session_id=ra.plan_session_id
                        WHERE ps.plan_id=p.plan_id) AS room_count,
                    (SELECT COUNT(*) FROM seat_assignment sa JOIN plan_session ps ON ps.plan_session_id=sa.plan_session_id
                        WHERE ps.plan_id=p.plan_id) AS seat_count,
                    (SELECT COUNT(*) FROM plan_diagnostic d WHERE d.plan_id=p.plan_id AND d.severity='ERROR') AS blocker_count
                FROM plan p JOIN exam_event e ON e.event_id=p.event_id
                WHERE e.event_code=? ORDER BY p.version_number DESC
                """)) {
            statement.setString(1, eventCode);
            try (var rows = statement.executeQuery()) {
                List<PlanSummary> plans = new ArrayList<>();
                while (rows.next()) plans.add(new PlanSummary(rows.getString("plan_id"), rows.getInt("version_number"),
                        rows.getString("status"), rows.getInt("source_revision"), rows.getInt("current_revision"),
                        rows.getString("created_at"), rows.getString("change_summary"), rows.getString("finalized_by"),
                        rows.getString("finalized_at"), rows.getInt("room_count"),
                        rows.getInt("seat_count"), rows.getLong("blocker_count")));
                return List.copyOf(plans);
            }
        }
    }

    public LoadedPlan loadPlan(String planId) throws SQLException {
        try (Connection connection = connections.openConnection()) {
            PlanSummary summary;
            String eventCode;
            try (var statement = connection.prepareStatement("""
                    SELECT p.plan_id, e.event_code, p.version_number, p.status, p.source_revision,
                        e.source_revision AS current_revision, p.created_at, p.change_summary, p.finalized_by,
                        p.finalized_at,
                        (SELECT COUNT(*) FROM room_assignment ra JOIN plan_session ps ON ps.plan_session_id=ra.plan_session_id
                            WHERE ps.plan_id=p.plan_id) AS room_count,
                        (SELECT COUNT(*) FROM seat_assignment sa JOIN plan_session ps ON ps.plan_session_id=sa.plan_session_id
                            WHERE ps.plan_id=p.plan_id) AS seat_count,
                        (SELECT COUNT(*) FROM plan_diagnostic d WHERE d.plan_id=p.plan_id AND d.severity='ERROR') AS blocker_count
                    FROM plan p JOIN exam_event e ON e.event_id=p.event_id WHERE p.plan_id=?
                    """)) {
                statement.setString(1, planId);
                try (var row = statement.executeQuery()) {
                    if (!row.next()) throw new IllegalArgumentException("Saved plan not found: " + planId);
                    eventCode = row.getString("event_code");
                    summary = new PlanSummary(planId, row.getInt("version_number"), row.getString("status"),
                            row.getInt("source_revision"), row.getInt("current_revision"), row.getString("created_at"),
                            row.getString("change_summary"), row.getString("finalized_by"), row.getString("finalized_at"),
                            row.getInt("room_count"), row.getInt("seat_count"),
                            row.getLong("blocker_count"));
                }
            }
            List<GeneratedPlan.Session> sessions = readPlanSessions(connection, planId);
            return new LoadedPlan(summary, new GeneratedPlan(eventCode, summary.sourceRevision(), sessions));
        }
    }

    public List<StaffOption> findActiveStaff() throws SQLException {
        try (Connection connection = connections.openConnection(); var statement = connection.prepareStatement(
                "SELECT staff_id, full_name FROM staff WHERE active=1 ORDER BY staff_id");
             var rows = statement.executeQuery()) {
            List<StaffOption> staff = new ArrayList<>();
            while (rows.next()) staff.add(new StaffOption(rows.getString("staff_id"), rows.getString("full_name")));
            return List.copyOf(staff);
        }
    }

    public List<CourseBlockSummary> findCourseBlockSummary(String planId, String sessionId) throws SQLException {
        Map<String, CourseBlockCount> courses = new java.util.LinkedHashMap<>();
        try (Connection connection = connections.openConnection(); var statement = connection.prepareStatement("""
                SELECT ps.exam_session_id, COALESCE(ps.session_date_snapshot,x.session_date) AS session_date,
                    COALESCE(ps.start_time_snapshot,x.start_time) AS start_time, sc.session_course_id,
                    c.course_code, sc.candidate_count,
                    COALESCE(ra.building_code_snapshot,b.building_code) AS block_code,
                    COUNT(sa.seat_assignment_id) AS assigned_count
                FROM plan_session ps JOIN exam_session x ON x.exam_session_id=ps.exam_session_id
                JOIN session_course sc ON sc.exam_session_id=x.exam_session_id
                JOIN course c ON c.course_id=sc.course_id
                LEFT JOIN seat_assignment sa ON sa.plan_session_id=ps.plan_session_id
                    AND sa.session_course_id=sc.session_course_id
                LEFT JOIN room_assignment ra ON ra.room_assignment_id=sa.room_assignment_id
                LEFT JOIN room r ON r.room_id=ra.room_id
                LEFT JOIN building b ON b.building_id=r.building_id
                WHERE ps.plan_id=? AND ps.exam_session_id=?
                GROUP BY ps.plan_session_id, sc.session_course_id, block_code
                ORDER BY session_date, start_time, c.course_code, block_code
                """)) {
            statement.setString(1, planId);
            statement.setString(2, sessionId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String sessionCourseId = rows.getString("exam_session_id") + "\u0000" + rows.getString("session_course_id");
                    CourseBlockCount course = courses.get(sessionCourseId);
                    if (course == null) {
                        course = new CourseBlockCount(rows.getString("session_date"), rows.getString("start_time"),
                                rows.getString("course_code"), rows.getInt("candidate_count"));
                        courses.put(sessionCourseId, course);
                    }
                    String block = rows.getString("block_code");
                    int assigned = rows.getInt("assigned_count");
                    if (block != null) course.blocks.put(block, assigned);
                    course.assigned += assigned;
                }
            }
        }
        List<CourseBlockSummary> report = new ArrayList<>();
        for (CourseBlockCount course : courses.values()) {
            int blockCount = course.blocks.size();
            String status = "OK";
            if (course.assigned != course.expected) {
                status = blockCount > 1 ? "COUNT MISMATCH + SPLIT" : "COUNT MISMATCH";
            } else if (blockCount > 1) {
                status = "SPLIT ACROSS BLOCKS";
            }
            String blocks = course.blocks.isEmpty() ? "No seats allocated" : course.blocks.entrySet().stream()
                    .map(entry -> entry.getKey() + ": " + entry.getValue()).collect(java.util.stream.Collectors.joining(" · "));
            report.add(new CourseBlockSummary(course.date, course.startTime, course.courseCode, course.expected,
                    course.assigned, blockCount, blocks, status));
        }
        return List.copyOf(report);
    }

    public List<CentreAllocationSummary> findCentreAllocations(String planId, String sessionId) throws SQLException {
        try (Connection connection = connections.openConnection(); var statement = connection.prepareStatement("""
                SELECT e.event_code, p.version_number, p.status,
                    CASE WHEN p.source_revision=e.source_revision THEN 0 ELSE 1 END AS stale,
                    ps.exam_session_id, COALESCE(ps.session_date_snapshot,x.session_date) AS session_date,
                    COALESCE(ps.start_time_snapshot,x.start_time) AS start_time,
                    COALESCE(ps.end_time_snapshot,x.end_time) AS end_time,
                    COALESCE(c.centre_name,b.building_code) AS centre_name,
                    COALESCE(ra.building_code_snapshot,b.building_code) AS building_code,
                    COALESCE(ra.room_code_snapshot,r.room_code) AS room_code,
                    COUNT(DISTINCT sa.seat_assignment_id) AS assigned_candidates,
                    ra.required_staff, COUNT(DISTINCT ia.staff_id) AS assigned_staff
                FROM room_assignment ra JOIN plan_session ps ON ps.plan_session_id=ra.plan_session_id
                JOIN plan p ON p.plan_id=ps.plan_id JOIN exam_session x ON x.exam_session_id=ps.exam_session_id
                JOIN exam_event e ON e.event_id=p.event_id
                JOIN room r ON r.room_id=ra.room_id JOIN building b ON b.building_id=r.building_id
                LEFT JOIN reporting_centre c ON c.centre_id=r.centre_id
                LEFT JOIN seat_assignment sa ON sa.room_assignment_id=ra.room_assignment_id
                LEFT JOIN invigilation_assignment ia ON ia.room_assignment_id=ra.room_assignment_id
                    AND ia.assignment_status<>'REMOVED'
                WHERE ps.plan_id=? AND ps.exam_session_id=? AND ra.assignment_status<>'REMOVED'
                GROUP BY ra.room_assignment_id
                ORDER BY session_date,start_time,centre_name,building_code,room_code
                """)) {
            statement.setString(1, planId);
            statement.setString(2, sessionId);
            try (var rows = statement.executeQuery()) {
                List<CentreAllocationSummary> report = new ArrayList<>();
                while (rows.next()) report.add(new CentreAllocationSummary(rows.getString("event_code"),
                        rows.getInt("version_number"), rows.getString("status"), rows.getInt("stale") == 1,
                        rows.getString("exam_session_id"), rows.getString("session_date"), rows.getString("start_time"),
                        rows.getString("end_time"), rows.getString("centre_name"), rows.getString("building_code"),
                        rows.getString("room_code"), rows.getInt("assigned_candidates"),
                        rows.getInt("required_staff"), rows.getInt("assigned_staff")));
                return List.copyOf(report);
            }
        }
    }

    public List<CourseRoomAllocationSummary> findCourseRoomAllocations(String planId, String sessionId) throws SQLException {
        List<CourseRoomCount> raw = new ArrayList<>();
        Map<String, Integer> totals = new HashMap<>();
        Map<String, Set<String>> roomsByCourse = new HashMap<>();
        try (Connection connection = connections.openConnection(); var statement = connection.prepareStatement("""
                SELECT ps.exam_session_id, COALESCE(ps.session_date_snapshot,x.session_date) AS session_date,
                    COALESCE(ps.start_time_snapshot,x.start_time) AS start_time, sc.session_course_id,
                    c.course_code, sc.candidate_count,
                    COALESCE(ra.building_code_snapshot,b.building_code,'UNASSIGNED') AS building_code,
                    COALESCE(ra.room_code_snapshot,r.room_code,'UNASSIGNED') AS room_code,
                    COUNT(sa.seat_assignment_id) AS assigned_candidates
                FROM plan_session ps JOIN exam_session x ON x.exam_session_id=ps.exam_session_id
                JOIN session_course sc ON sc.exam_session_id=x.exam_session_id
                JOIN course c ON c.course_id=sc.course_id
                LEFT JOIN seat_assignment sa ON sa.plan_session_id=ps.plan_session_id
                    AND sa.session_course_id=sc.session_course_id
                LEFT JOIN room_assignment ra ON ra.room_assignment_id=sa.room_assignment_id
                    AND ra.assignment_status<>'REMOVED'
                LEFT JOIN room r ON r.room_id=ra.room_id
                LEFT JOIN building b ON b.building_id=r.building_id
                WHERE ps.plan_id=? AND ps.exam_session_id=?
                GROUP BY ps.plan_session_id,sc.session_course_id,ra.room_assignment_id
                ORDER BY session_date,start_time,c.course_code,building_code,room_code
                """)) {
            statement.setString(1, planId);
            statement.setString(2, sessionId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    CourseRoomCount item = new CourseRoomCount(rows.getString("exam_session_id"),
                            rows.getString("session_date"), rows.getString("start_time"),
                            rows.getString("session_course_id"), rows.getString("course_code"),
                            rows.getInt("candidate_count"), rows.getString("building_code"),
                            rows.getString("room_code"), rows.getInt("assigned_candidates"));
                    raw.add(item);
                    String key = item.sessionId + "\u0000" + item.sessionCourseId;
                    totals.merge(key, item.assigned, Integer::sum);
                    if (!item.building.equals("UNASSIGNED")) roomsByCourse.computeIfAbsent(key, ignored -> new HashSet<>())
                            .add(item.building + " " + item.room);
                }
            }
        }
        List<CourseRoomAllocationSummary> report = new ArrayList<>();
        for (CourseRoomCount item : raw) {
            String key = item.sessionId + "\u0000" + item.sessionCourseId;
            int assigned = totals.getOrDefault(key, 0);
            int rooms = roomsByCourse.getOrDefault(key, Set.of()).size();
            report.add(new CourseRoomAllocationSummary(item.sessionId, item.date, item.start, item.courseCode,
                    item.expected, item.building, item.room, item.assigned, assigned, rooms,
                    assigned == item.expected ? "OK" : "COUNT MISMATCH"));
        }
        return List.copyOf(report);
    }

    public void finalizePlan(String planId, String administrator, boolean acknowledgeWarnings) throws SQLException {
        String label = administrator == null ? "" : administrator.trim();
        if (label.isEmpty()) throw new IllegalArgumentException("Enter the administrator name for finalization.");
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try {
                String eventCode;
                int sourceRevision;
                int currentRevision;
                String status;
                try (var statement = connection.prepareStatement("""
                        SELECT e.event_code, p.source_revision, e.source_revision AS current_revision, p.status
                        FROM plan p JOIN exam_event e ON e.event_id=p.event_id WHERE p.plan_id=?
                        """)) {
                    statement.setString(1, planId);
                    try (var row = statement.executeQuery()) {
                        if (!row.next()) throw new IllegalArgumentException("Saved plan not found: " + planId);
                        eventCode = row.getString("event_code");
                        sourceRevision = row.getInt("source_revision");
                        currentRevision = row.getInt("current_revision");
                        status = row.getString("status");
                    }
                }
                if (!status.equals("DRAFT")) throw new IllegalStateException("Only a draft plan can be finalized.");
                if (sourceRevision != currentRevision)
                    throw new IllegalStateException("Exam data changed after this draft was generated. Generate a new draft.");

                FeasibilityAnalyzer.Snapshot snapshot = new FeasibilityRepository(connections).load(connection, eventCode);
                GeneratedPlan plan = new GeneratedPlan(eventCode, sourceRevision, readPlanSessions(connection, planId));
                if (plan.blockerCount() > 0 || new PlanValidator().validate(plan, snapshot).stream()
                        .anyMatch(item -> item.severity().equals("BLOCKER"))) {
                    throw new IllegalStateException("Resolve all hard blockers before finalizing this plan.");
                }
                int savedErrors = countPlanDiagnostics(connection, planId, "ERROR");
                if (savedErrors > 0) throw new IllegalStateException("Resolve all saved hard blockers before finalizing this plan.");
                int requiredWarnings = countRequiredWarnings(connection, planId);
                if (requiredWarnings > 0 && !acknowledgeWarnings)
                    throw new IllegalStateException("Review and acknowledge the capacity and eligibility warnings first.");

                String now = Instant.now().toString();
                try (var statement = connection.prepareStatement("""
                        UPDATE plan SET status='FINALIZED', finalized_at=?, finalized_by=?,
                            change_summary=?
                        WHERE plan_id=? AND status='DRAFT'
                        """)) {
                    statement.setString(1, now);
                    statement.setString(2, label);
                    statement.setString(3, requiredWarnings == 0 ? "Finalized seating plan"
                            : "Finalized seating plan; warnings acknowledged");
                    statement.setString(4, planId);
                    if (statement.executeUpdate() != 1) throw new IllegalStateException("Plan changed before finalization.");
                }
                confirmAssignments(connection, planId);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static int countPlanDiagnostics(Connection connection, String planId, String severity) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM plan_diagnostic WHERE plan_id=? AND severity=?")) {
            statement.setString(1, planId);
            statement.setString(2, severity);
            try (var row = statement.executeQuery()) { row.next(); return row.getInt(1); }
        }
    }

    private static int countRequiredWarnings(Connection connection, String planId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM plan_diagnostic WHERE plan_id=? AND severity='WARNING'
                    AND code NOT IN ('SAME_COURSE_ORTHOGONAL_ADJACENCY','COURSE_SPLIT_ACROSS_BLOCKS')
                """)) {
            statement.setString(1, planId);
            try (var row = statement.executeQuery()) { row.next(); return row.getInt(1); }
        }
    }

    private static void confirmAssignments(Connection connection, String planId) throws SQLException {
        for (String sql : List.of(
                "UPDATE room_assignment SET assignment_status='CONFIRMED' WHERE plan_session_id IN (SELECT plan_session_id FROM plan_session WHERE plan_id=?)",
                "UPDATE invigilation_assignment SET assignment_status='CONFIRMED' WHERE room_assignment_id IN (SELECT ra.room_assignment_id FROM room_assignment ra JOIN plan_session ps ON ps.plan_session_id=ra.plan_session_id WHERE ps.plan_id=?)",
                "UPDATE seat_assignment SET locked=1 WHERE plan_session_id IN (SELECT plan_session_id FROM plan_session WHERE plan_id=?)")) {
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, planId);
                statement.executeUpdate();
            }
        }
    }

    private static List<GeneratedPlan.Session> readPlanSessions(Connection connection, String planId)
            throws SQLException {
        Map<String, List<FeasibilityReport.Diagnostic>> sessionDiagnostics = new HashMap<>();
        Map<String, List<FeasibilityReport.Diagnostic>> roomDiagnostics = new HashMap<>();
        try (var statement = connection.prepareStatement("""
                SELECT ps.exam_session_id, d.entity_type, d.entity_id, d.severity, d.code, d.explanation
                FROM plan_session ps LEFT JOIN plan_diagnostic d ON d.plan_session_id=ps.plan_session_id
                WHERE ps.plan_id=? ORDER BY d.diagnostic_id
                """)) {
            statement.setString(1, planId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String entityType = rows.getString("entity_type");
                    if (entityType == null) continue;
                    FeasibilityReport.Diagnostic diagnostic = new FeasibilityReport.Diagnostic(
                            rows.getString("severity"), rows.getString("code"), rows.getString("explanation"));
                    Map<String, List<FeasibilityReport.Diagnostic>> target = entityType.equals("ROOM")
                            ? roomDiagnostics : sessionDiagnostics;
                    String id = entityType.equals("ROOM") ? rows.getString("entity_id")
                            : rows.getString("exam_session_id");
                    target.computeIfAbsent(id, ignored -> new ArrayList<>()).add(diagnostic);
                }
            }
        }
        Map<String, List<GeneratedPlan.Room>> roomsBySession = readPlanRooms(connection, planId,
                roomDiagnostics);
        List<GeneratedPlan.Session> sessions = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                SELECT x.exam_session_id, COALESCE(ps.session_date_snapshot,x.session_date),
                    COALESCE(ps.start_time_snapshot,x.start_time), COALESCE(ps.end_time_snapshot,x.end_time,''),
                    COALESCE(ps.candidate_demand_snapshot,SUM(sc.candidate_count),0) AS candidate_demand
                FROM plan_session ps JOIN exam_session x ON x.exam_session_id=ps.exam_session_id
                LEFT JOIN session_course sc ON sc.exam_session_id=x.exam_session_id
                WHERE ps.plan_id=? GROUP BY ps.plan_session_id
                ORDER BY x.session_date, x.start_time, x.exam_session_id
                """)) {
            statement.setString(1, planId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString("exam_session_id");
                    sessions.add(new GeneratedPlan.Session(id, rows.getString(2),
                            rows.getString(3), rows.getString(4), rows.getInt("candidate_demand"),
                            roomsBySession.getOrDefault(id, List.of()),
                            sessionDiagnostics.getOrDefault(id, List.of())));
                }
            }
        }
        return List.copyOf(sessions);
    }

    private static Map<String, List<GeneratedPlan.Room>> readPlanRooms(Connection connection, String planId,
            Map<String, List<FeasibilityReport.Diagnostic>> diagnostics) throws SQLException {
        Map<String, List<String>> staffByAssignment = new HashMap<>();
        try (var statement = connection.prepareStatement("""
                SELECT ra.room_assignment_id, ia.staff_id FROM room_assignment ra
                JOIN plan_session ps ON ps.plan_session_id=ra.plan_session_id
                JOIN invigilation_assignment ia ON ia.room_assignment_id=ra.room_assignment_id
                WHERE ps.plan_id=? AND ia.assignment_status<>'REMOVED' ORDER BY ia.staff_id
                """)) {
            statement.setString(1, planId);
            try (var rows = statement.executeQuery()) { while (rows.next()) staffByAssignment
                    .computeIfAbsent(rows.getString(1), ignored -> new ArrayList<>()).add(rows.getString(2)); }
        }
        Map<String, List<SeatAssignmentPlanner.Assignment>> seatsByAssignment = new HashMap<>();
        Map<String, List<SeatAssignmentPlanner.Seat>> gridByAssignment = loadPlanSeatGrids(connection, planId);
        Map<String, List<SeatAssignmentPlanner.Seat>> currentGridByRoom = loadSeats(connection);
        try (var statement = connection.prepareStatement("""
                SELECT sa.room_assignment_id, sa.session_course_id, sa.student_id,
                    sa.anonymous_candidate_number, sa.seat_id, sa.assignment_origin, st.roll_number, c.course_code,
                    COALESCE(sa.row_number_snapshot,s.row_number) AS row_number,
                    COALESCE(sa.column_number_snapshot,s.column_number) AS column_number,
                    COALESCE(sa.seat_status_snapshot,s.seat_status) AS seat_status,
                    COALESCE(sa.candidate_label_snapshot,st.roll_number) AS candidate_label,
                    COALESCE(sa.course_code_snapshot,c.course_code) AS course_code_snapshot
                FROM seat_assignment sa JOIN room_assignment ra ON ra.room_assignment_id=sa.room_assignment_id
                JOIN plan_session ps ON ps.plan_session_id=ra.plan_session_id
                JOIN seat s ON s.room_id=sa.room_id AND s.seat_id=sa.seat_id
                JOIN session_course sc ON sc.session_course_id=sa.session_course_id
                JOIN course c ON c.course_id=sc.course_id
                LEFT JOIN student st ON st.student_id=sa.student_id
                WHERE ps.plan_id=? ORDER BY sa.room_assignment_id, s.row_number, s.column_number
                """)) {
            statement.setString(1, planId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String studentId = rows.getString("student_id");
                    int anonymousNumber = rows.getInt("anonymous_candidate_number");
                    Integer anonymous = rows.wasNull() ? null : anonymousNumber;
                    String candidateKey = rows.getString("candidate_label");
                    if (candidateKey == null) candidateKey = "ANON-" + rows.getString("session_course_id") + "-" + anonymous;
                    var candidate = new SeatAssignmentPlanner.Candidate(candidateKey, rows.getString("course_code_snapshot"),
                            studentId, anonymous, -1, rows.getString("session_course_id"));
                    var seat = new SeatAssignmentPlanner.Seat(rows.getInt("seat_id"), rows.getInt("row_number"),
                            rows.getInt("column_number"), rows.getString("seat_status").equals("USABLE"));
                    seatsByAssignment.computeIfAbsent(rows.getString("room_assignment_id"), ignored -> new ArrayList<>())
                            .add(new SeatAssignmentPlanner.Assignment(candidate, seat, rows.getString("assignment_origin")));
                }
            }
        }
        Map<String, List<GeneratedPlan.Room>> roomsBySession = new HashMap<>();
        try (var statement = connection.prepareStatement("""
                SELECT ps.exam_session_id, ra.room_assignment_id, ra.room_id, ra.candidate_count,
                    ra.required_staff, ra.seat_grid_snapshot,
                    COALESCE(ra.room_code_snapshot,r.room_code) AS room_code,
                    COALESCE(ra.building_code_snapshot,b.building_code) AS building_code,
                    COALESCE(ra.capacity_snapshot,r.usable_capacity) AS capacity,
                    COALESCE(ra.capacity_status_snapshot,r.capacity_status) AS capacity_status
                FROM room_assignment ra JOIN plan_session ps ON ps.plan_session_id=ra.plan_session_id
                JOIN room r ON r.room_id=ra.room_id JOIN building b ON b.building_id=r.building_id
                WHERE ps.plan_id=? ORDER BY b.building_code, r.room_code
                """)) {
            statement.setString(1, planId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String assignmentId = rows.getString("room_assignment_id");
                    String roomId = rows.getString("room_id");
                    List<SeatAssignmentPlanner.Seat> grid = rows.getInt("seat_grid_snapshot") == 1
                            ? gridByAssignment.getOrDefault(assignmentId, List.of())
                            : currentGridByRoom.getOrDefault(roomId, List.of());
                    roomsBySession.computeIfAbsent(rows.getString("exam_session_id"), ignored -> new ArrayList<>())
                            .add(new GeneratedPlan.Room(roomId, rows.getString("building_code"), rows.getString("room_code"),
                                    rows.getString("capacity_status"), rows.getInt("capacity"), rows.getInt("candidate_count"),
                                    rows.getInt("required_staff"), staffByAssignment.getOrDefault(assignmentId, List.of()),
                                    seatsByAssignment.getOrDefault(assignmentId, List.of()), grid,
                                    diagnostics.getOrDefault(roomId, List.of())));
                }
            }
        }
        return roomsBySession;
    }

    private static Map<String, List<SeatAssignmentPlanner.Seat>> loadPlanSeatGrids(Connection connection,
            String planId) throws SQLException {
        Map<String, List<SeatAssignmentPlanner.Seat>> grids = new HashMap<>();
        try (var statement = connection.prepareStatement("""
                SELECT ras.room_assignment_id, ras.seat_id, ras.row_number, ras.column_number, ras.seat_status
                FROM room_assignment_seat ras JOIN room_assignment ra ON ra.room_assignment_id=ras.room_assignment_id
                JOIN plan_session ps ON ps.plan_session_id=ra.plan_session_id
                WHERE ps.plan_id=? ORDER BY ras.room_assignment_id, ras.row_number, ras.column_number
                """)) {
            statement.setString(1, planId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) grids.computeIfAbsent(rows.getString("room_assignment_id"), ignored -> new ArrayList<>())
                        .add(new SeatAssignmentPlanner.Seat(rows.getInt("seat_id"), rows.getInt("row_number"),
                                rows.getInt("column_number"), rows.getString("seat_status").equals("USABLE")));
            }
        }
        return grids;
    }

    public GeneratedPlan prepareDraft(String eventCode) throws SQLException {
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try {
                FeasibilityAnalyzer.Snapshot snapshot = new FeasibilityRepository(connections).load(connection, eventCode);
                FeasibilityReport feasibility = new FeasibilityAnalyzer().analyze(snapshot);
                Map<String, Map<String, Paper>> papers = loadPapers(connection, eventCode);
                Map<String, List<StudentCandidate>> registrations = loadRegistrations(connection, eventCode);
                Set<String> rostersProvided = loadCoursesWithRegistrations(connection, eventCode);
                Map<String, List<SeatAssignmentPlanner.Seat>> seats = loadSeats(connection);
                Map<String, Integer> previousSeats = loadPreviousSeats(connection);
                Set<String> clashes = findClashes(feasibility, papers, registrations);
                List<GeneratedPlan.Session> sessions = makeSessions(feasibility, papers, registrations, rostersProvided,
                        seats, previousSeats, clashes);
                GeneratedPlan plan = new GeneratedPlan(eventCode, snapshot.sourceRevision(), sessions);
                plan = addValidationDiagnostics(plan, new PlanValidator().validate(plan, snapshot));
                connection.commit();
                return plan;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback(); throw exception;
            }
        }
    }

    private static GeneratedPlan addValidationDiagnostics(GeneratedPlan plan,
            List<PlanValidator.Violation> violations) {
        Map<String, List<PlanValidator.Violation>> bySession = new HashMap<>();
        for (PlanValidator.Violation violation : violations)
            bySession.computeIfAbsent(violation.sessionId(), ignored -> new ArrayList<>()).add(violation);
        List<GeneratedPlan.Session> sessions = new ArrayList<>();
        for (GeneratedPlan.Session session : plan.sessions()) {
            List<FeasibilityReport.Diagnostic> sessionDiagnostics = new ArrayList<>(session.diagnostics());
            Map<String, List<PlanValidator.Violation>> byRoom = new HashMap<>();
            for (PlanValidator.Violation violation : bySession.getOrDefault(session.id(), List.of())) {
                if (violation.roomId() == null) {
                    if (sessionDiagnostics.stream().noneMatch(item -> item.code().equals(violation.code()))) {
                        sessionDiagnostics.add(new FeasibilityReport.Diagnostic(violation.severity(),
                                violation.code(), violation.message()));
                    }
                } else byRoom.computeIfAbsent(violation.roomId(), ignored -> new ArrayList<>()).add(violation);
            }
            List<GeneratedPlan.Room> rooms = new ArrayList<>();
            for (GeneratedPlan.Room room : session.rooms()) {
                List<FeasibilityReport.Diagnostic> roomDiagnostics = new ArrayList<>(room.diagnostics());
                for (PlanValidator.Violation violation : byRoom.getOrDefault(room.id(), List.of())) {
                    if (roomDiagnostics.stream().noneMatch(item -> item.code().equals(violation.code()))) {
                        roomDiagnostics.add(new FeasibilityReport.Diagnostic(violation.severity(),
                                violation.code(), violation.message()));
                    }
                }
                rooms.add(new GeneratedPlan.Room(room.id(), room.buildingCode(), room.roomCode(),
                        room.capacityStatus(), room.capacity(), room.candidateDemand(), room.staffRequired(),
                        room.staffIds(), room.assignments(), room.seatGrid(), roomDiagnostics));
            }
            sessions.add(new GeneratedPlan.Session(session.id(), session.date(), session.startTime(),
                    session.endTime(), session.candidateDemand(), rooms, sessionDiagnostics));
        }
        return new GeneratedPlan(plan.eventCode(), plan.sourceRevision(), sessions);
    }

    public SavedPlan saveDraft(GeneratedPlan plan) throws SQLException {
        return saveDraft(plan, "Generated seating draft");
    }

    public SavedPlan saveDraft(GeneratedPlan plan, String changeSummary) throws SQLException {
        String summary = changeSummary == null || changeSummary.isBlank() ? "Saved seating draft" : changeSummary.trim();
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try {
                String eventId;
                int currentRevision;
                try (var statement = connection.prepareStatement("SELECT event_id, source_revision FROM exam_event WHERE event_code=?")) {
                    statement.setString(1, plan.eventCode());
                    try (var row = statement.executeQuery()) {
                        if (!row.next()) throw new IllegalArgumentException("Unknown exam event: " + plan.eventCode());
                        eventId = row.getString("event_id"); currentRevision = row.getInt("source_revision");
                    }
                }
                if (currentRevision != plan.sourceRevision()) {
                    throw new IllegalStateException("Exam data changed during generation. Generate the draft again.");
                }
                FeasibilityAnalyzer.Snapshot snapshot = new FeasibilityRepository(connections).load(connection, plan.eventCode());
                GeneratedPlan checked = addValidationDiagnostics(plan,
                        new PlanValidator().validate(plan, snapshot));
                int version;
                try (var statement = connection.prepareStatement("SELECT COALESCE(MAX(version_number),0)+1 FROM plan WHERE event_id=?")) {
                    statement.setString(1, eventId);
                    try (var row = statement.executeQuery()) { row.next(); version = row.getInt(1); }
                }
                insertPlan(connection, id, eventId, version, plan.sourceRevision(), now, summary);
                int seatCount = insertSessions(connection, id, checked);
                connection.commit();
                return new SavedPlan(id, version, seatCount, checked.roomCount(), checked.blockerCount());
            } catch (SQLException | RuntimeException exception) {
                connection.rollback(); throw exception;
            }
        }
    }

    private static Map<String, Map<String, Paper>> loadPapers(Connection connection, String eventCode) throws SQLException {
        Map<String, Map<String, Paper>> papers = new HashMap<>();
        try (var statement = connection.prepareStatement("""
                SELECT x.exam_session_id, sc.session_course_id, c.course_code, sc.candidate_count
                FROM exam_event e JOIN exam_session x ON x.event_id=e.event_id
                JOIN session_course sc ON sc.exam_session_id=x.exam_session_id
                JOIN course c ON c.course_id=sc.course_id
                WHERE e.event_code=? AND x.status<>'CANCELLED'
                ORDER BY x.session_date, x.start_time, c.course_code
                """)) {
            statement.setString(1, eventCode);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    Paper paper = new Paper(rows.getString("session_course_id"), rows.getString("course_code"),
                            rows.getInt("candidate_count"));
                    papers.computeIfAbsent(rows.getString("exam_session_id"), ignored -> new HashMap<>())
                            .put(paper.courseCode(), paper);
                }
            }
        }
        return papers;
    }

    private static Map<String, List<StudentCandidate>> loadRegistrations(Connection connection, String eventCode)
            throws SQLException {
        Map<String, List<StudentCandidate>> registrations = new HashMap<>();
        try (var statement = connection.prepareStatement("""
                SELECT sc.session_course_id, s.student_id, s.roll_number
                FROM exam_event e JOIN exam_session x ON x.event_id=e.event_id
                JOIN session_course sc ON sc.exam_session_id=x.exam_session_id
                JOIN enrollment n ON n.session_course_id=sc.session_course_id
                JOIN student s ON s.student_id=n.student_id
                WHERE e.event_code=? AND x.status<>'CANCELLED'
                    AND n.registration_status='REGISTERED' AND s.active=1
                ORDER BY sc.session_course_id, s.student_id
                """)) {
            statement.setString(1, eventCode);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) registrations.computeIfAbsent(rows.getString("session_course_id"), ignored -> new ArrayList<>())
                        .add(new StudentCandidate(rows.getString("student_id"), rows.getString("roll_number")));
            }
        }
        return registrations;
    }

    private static Map<String, List<SeatAssignmentPlanner.Seat>> loadSeats(Connection connection) throws SQLException {
        Map<String, List<SeatAssignmentPlanner.Seat>> seats = new HashMap<>();
        try (var statement = connection.prepareStatement("""
                SELECT room_id, seat_id, row_number, column_number, seat_status FROM seat
                ORDER BY room_id, row_number, column_number
                """); var rows = statement.executeQuery()) {
            while (rows.next()) seats.computeIfAbsent(rows.getString("room_id"), ignored -> new ArrayList<>())
                    .add(new SeatAssignmentPlanner.Seat(rows.getInt("seat_id"), rows.getInt("row_number"),
                            rows.getInt("column_number"), rows.getString("seat_status").equals("USABLE")));
        }
        return seats;
    }

    private static Set<String> loadCoursesWithRegistrations(Connection connection, String eventCode) throws SQLException {
        Set<String> courseIds = new HashSet<>();
        try (var statement = connection.prepareStatement("""
                SELECT DISTINCT n.session_course_id FROM exam_event e
                JOIN exam_session x ON x.event_id=e.event_id
                JOIN session_course sc ON sc.exam_session_id=x.exam_session_id
                JOIN enrollment n ON n.session_course_id=sc.session_course_id
                WHERE e.event_code=? AND x.status<>'CANCELLED'
                """)) {
            statement.setString(1, eventCode);
            try (var rows = statement.executeQuery()) { while (rows.next()) courseIds.add(rows.getString(1)); }
        }
        return courseIds;
    }

    private static Map<String, Integer> loadPreviousSeats(Connection connection) throws SQLException {
        Map<String, Integer> previous = new HashMap<>();
        try (var statement = connection.prepareStatement("""
                SELECT a.student_id, a.seat_id FROM seat_assignment a
                JOIN plan_session ps ON ps.plan_session_id=a.plan_session_id
                JOIN plan p ON p.plan_id=ps.plan_id
                WHERE a.student_id IS NOT NULL AND p.status<>'SUPERSEDED'
                ORDER BY p.created_at DESC, p.version_number DESC
                """); var rows = statement.executeQuery()) {
            while (rows.next()) previous.putIfAbsent(rows.getString("student_id"), rows.getInt("seat_id"));
        }
        return previous;
    }

    private static Set<String> findClashes(FeasibilityReport report, Map<String, Map<String, Paper>> papers,
            Map<String, List<StudentCandidate>> registrations) {
        Map<String, List<Registration>> byStudent = new HashMap<>();
        for (FeasibilityReport.SessionResult session : report.sessions()) {
            Map<String, Paper> sessionPapers = papers.getOrDefault(session.sessionId(), Map.of());
            for (Paper paper : sessionPapers.values()) {
                for (StudentCandidate student : registrations.getOrDefault(paper.sessionCourseId(), List.of())) {
                    byStudent.computeIfAbsent(student.studentId(), ignored -> new ArrayList<>()).add(
                            new Registration(session.sessionId(), session.date(), LocalTime.parse(session.startTime()),
                                    LocalTime.parse(session.endTime()), paper.courseCode()));
                }
            }
        }
        Set<String> clashes = new HashSet<>();
        for (Map.Entry<String, List<Registration>> entry : byStudent.entrySet()) {
            List<Registration> exams = entry.getValue();
            for (int i = 0; i < exams.size(); i++) for (int j = i + 1; j < exams.size(); j++) {
                Registration first = exams.get(i), second = exams.get(j);
                if (!first.date().equals(second.date())
                        || !first.startTime().isBefore(second.endTime()) || !second.startTime().isBefore(first.endTime())) continue;
                clashes.add(key(entry.getKey(), first.sessionId()));
                clashes.add(key(entry.getKey(), second.sessionId()));
            }
        }
        return clashes;
    }

    private static List<GeneratedPlan.Session> makeSessions(FeasibilityReport report,
            Map<String, Map<String, Paper>> papers, Map<String, List<StudentCandidate>> registrations,
            Set<String> rostersProvided,
            Map<String, List<SeatAssignmentPlanner.Seat>> seatsByRoom, Map<String, Integer> previousSeats,
            Set<String> clashes) {
        List<GeneratedPlan.Session> generated = new ArrayList<>();
        for (FeasibilityReport.SessionResult session : report.sessions()) {
            List<FeasibilityReport.Diagnostic> diagnostics = new ArrayList<>(session.diagnostics());
            Map<String, Paper> sessionPapers = papers.getOrDefault(session.sessionId(), Map.of());
            addRosterIssues(sessionPapers, registrations, rostersProvided, diagnostics);
            addClashIssues(session, sessionPapers, registrations, clashes, diagnostics);
            List<GeneratedPlan.Room> rooms = new ArrayList<>();
            Map<String, Integer> nextAnonymousNumber = new HashMap<>();
            Map<String, Integer> candidateOffset = new HashMap<>();
            for (FeasibilityReport.RoomUse room : session.selectedRooms()) {
                List<SeatAssignmentPlanner.Candidate> candidates = new ArrayList<>();
                List<FeasibilityReport.Diagnostic> roomDiagnostics = new ArrayList<>();
                for (FeasibilityReport.CoursePortion portion : room.courses()) {
                    Paper paper = sessionPapers.get(portion.courseCode());
                    if (paper == null || hasRosterMismatch(paper, registrations, rostersProvided)) continue;
                    List<StudentCandidate> roster = registrations.getOrDefault(paper.sessionCourseId(), List.of());
                    if (roster.isEmpty()) {
                        int first = nextAnonymousNumber.getOrDefault(paper.sessionCourseId(), 1);
                        for (int number = first; number < first + portion.candidates(); number++) {
                            candidates.add(new SeatAssignmentPlanner.Candidate("ANON-" + paper.sessionCourseId() + "-" + number,
                                    paper.courseCode(), null, number, -1, paper.sessionCourseId()));
                        }
                        nextAnonymousNumber.put(paper.sessionCourseId(), first + portion.candidates());
                    } else {
                        int offset = candidateOffset.getOrDefault(paper.sessionCourseId(), 0);
                        int end = Math.min(roster.size(), offset + portion.candidates());
                        for (int index = offset; index < end; index++) {
                            StudentCandidate student = roster.get(index);
                            if (clashes.contains(key(student.studentId(), session.sessionId()))) continue;
                            candidates.add(new SeatAssignmentPlanner.Candidate(student.rollNumber(), paper.courseCode(),
                                    student.studentId(), null, previousSeats.getOrDefault(student.studentId(), -1),
                                    paper.sessionCourseId()));
                        }
                        candidateOffset.put(paper.sessionCourseId(), end);
                    }
                }
                List<SeatAssignmentPlanner.Seat> seatGrid = seatsByRoom.getOrDefault(room.roomId(), List.of());
                List<SeatAssignmentPlanner.Seat> roomSeats = seatGrid.stream()
                        .filter(SeatAssignmentPlanner.Seat::usable).sorted(Comparator
                                .comparingInt(SeatAssignmentPlanner.Seat::row)
                                .thenComparingInt(SeatAssignmentPlanner.Seat::column))
                        .limit(room.seats()).toList();
                if (roomSeats.isEmpty() && room.courses().stream().anyMatch(course -> course.candidates() > 0)) {
                    roomDiagnostics.add(new FeasibilityReport.Diagnostic("BLOCKER", "ROOM_SEAT_LAYOUT_MISSING",
                            room.buildingCode() + " " + room.roomCode() + " has no usable seat records."));
                }
                SeatAssignmentPlanner.Result seating = new SeatAssignmentPlanner().assign(candidates, roomSeats);
                roomDiagnostics.addAll(seating.diagnostics());
                for (SeatAssignmentPlanner.Assignment assignment : seating.assignments()) {
                    if (assignment.candidate().studentId() != null) {
                        previousSeats.put(assignment.candidate().studentId(), assignment.seat().id());
                    }
                }
                int candidateDemand = room.courses().stream().mapToInt(FeasibilityReport.CoursePortion::candidates).sum();
                rooms.add(new GeneratedPlan.Room(room.roomId(), room.buildingCode(), room.roomCode(),
                        room.capacityStatus(), room.seats(), candidateDemand, room.staffRequired(), room.staffIds(),
                        seating.assignments(), seatGrid, roomDiagnostics));
            }
            int assigned = rooms.stream().mapToInt(room -> room.assignments().size()).sum();
            if (assigned < session.candidates()) diagnostics.add(new FeasibilityReport.Diagnostic("BLOCKER",
                    "UNASSIGNED_CANDIDATES", (session.candidates() - assigned)
                            + " of " + session.candidates() + " candidates remain without seats."));
            generated.add(new GeneratedPlan.Session(session.sessionId(), session.date(), session.startTime(),
                    session.endTime(), session.candidates(), rooms, diagnostics));
        }
        return List.copyOf(generated);
    }

    private static void addRosterIssues(Map<String, Paper> papers, Map<String, List<StudentCandidate>> registrations,
            Set<String> rostersProvided, List<FeasibilityReport.Diagnostic> diagnostics) {
        for (Paper paper : papers.values()) {
            int registered = registrations.getOrDefault(paper.sessionCourseId(), List.of()).size();
            if (rostersProvided.contains(paper.sessionCourseId()) && registered != paper.candidateCount()) diagnostics.add(
                    new FeasibilityReport.Diagnostic("BLOCKER", "ROSTER_COUNT_MISMATCH", paper.courseCode()
                            + " has " + registered + " active registrations; the imported count is "
                            + paper.candidateCount() + ". Correct the roster or course count before seating."));
        }
    }

    private static void addClashIssues(FeasibilityReport.SessionResult session, Map<String, Paper> papers,
            Map<String, List<StudentCandidate>> registrations, Set<String> clashes,
            List<FeasibilityReport.Diagnostic> diagnostics) {
        Set<String> reported = new HashSet<>();
        for (Paper paper : papers.values()) for (StudentCandidate student : registrations.getOrDefault(paper.sessionCourseId(), List.of())) {
            if (clashes.contains(key(student.studentId(), session.sessionId())) && reported.add(student.studentId())) {
                diagnostics.add(new FeasibilityReport.Diagnostic("BLOCKER", "STUDENT_SESSION_CLASH",
                        student.rollNumber() + " has overlapping registrations including " + paper.courseCode()
                                + " and is left unassigned from this session."));
            }
        }
    }

    private static boolean hasRosterMismatch(Paper paper, Map<String, List<StudentCandidate>> registrations,
            Set<String> rostersProvided) {
        int registered = registrations.getOrDefault(paper.sessionCourseId(), List.of()).size();
        return rostersProvided.contains(paper.sessionCourseId()) && registered != paper.candidateCount();
    }

    private static void insertPlan(Connection connection, String id, String eventId, int version,
            int revision, String now, String changeSummary) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO plan(plan_id, event_id, version_number, status, source_revision, planner_version,
                    created_at, generation_config_snapshot, change_summary)
                VALUES (?, ?, ?, 'DRAFT', ?, '1', ?, ?, ?)
                """)) {
            statement.setString(1, id); statement.setString(2, eventId); statement.setInt(3, version);
            statement.setInt(4, revision); statement.setString(5, now);
            statement.setString(6, "{\"handoverMinutes\":30,\"sameCourseSpacing\":\"ORTHOGONAL\"}");
            statement.setString(7, changeSummary); statement.executeUpdate();
        }
    }

    private static int insertSessions(Connection connection, String planId, GeneratedPlan plan)
            throws SQLException {
        int seatCount = 0;
        try (var sessionInsert = connection.prepareStatement(
                    """
                    INSERT INTO plan_session(plan_session_id, plan_id, exam_session_id, session_date_snapshot,
                        start_time_snapshot, end_time_snapshot, candidate_demand_snapshot)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """);
             var roomInsert = connection.prepareStatement("""
                    INSERT INTO room_assignment(room_assignment_id, plan_session_id, room_id, candidate_count,
                        required_staff, assignment_status, building_code_snapshot, room_code_snapshot,
                        capacity_snapshot, capacity_status_snapshot, seat_grid_snapshot)
                    VALUES (?, ?, ?, ?, ?, 'PROPOSED', ?, ?, ?, ?, 1)
                    """);
             var seatInsert = connection.prepareStatement("""
                    INSERT INTO seat_assignment(seat_assignment_id, room_assignment_id, plan_session_id,
                        exam_session_id, room_id, seat_id, session_course_id, student_id,
                        anonymous_candidate_number, assignment_origin, row_number_snapshot,
                        column_number_snapshot, seat_status_snapshot, candidate_label_snapshot, course_code_snapshot)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
             var roomSeatInsert = connection.prepareStatement("""
                    INSERT INTO room_assignment_seat(room_assignment_id, seat_id, row_number, column_number, seat_status)
                    VALUES (?, ?, ?, ?, ?)
                    """);
             var staffInsert = connection.prepareStatement("""
                    INSERT INTO invigilation_assignment(invigilation_assignment_id, room_assignment_id, staff_id,
                        assignment_status) VALUES (?, ?, ?, 'PROPOSED')
                    """);
             var diagnosticInsert = connection.prepareStatement("""
                    INSERT INTO plan_diagnostic(diagnostic_id, plan_id, plan_session_id, code, severity,
                        classification, entity_type, entity_id, explanation)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
            for (GeneratedPlan.Session session : plan.sessions()) {
                String planSessionId = UUID.randomUUID().toString();
                sessionInsert.setString(1, planSessionId); sessionInsert.setString(2, planId);
                sessionInsert.setString(3, session.id()); sessionInsert.setString(4, session.date());
                sessionInsert.setString(5, session.startTime()); sessionInsert.setString(6, session.endTime());
                sessionInsert.setInt(7, session.candidateDemand()); sessionInsert.executeUpdate();
                for (GeneratedPlan.Room room : session.rooms()) {
                    String roomAssignmentId = UUID.randomUUID().toString();
                    roomInsert.setString(1, roomAssignmentId); roomInsert.setString(2, planSessionId);
                    roomInsert.setString(3, room.id()); roomInsert.setInt(4, room.candidateDemand());
                    roomInsert.setInt(5, room.staffRequired()); roomInsert.setString(6, room.buildingCode());
                    roomInsert.setString(7, room.roomCode()); roomInsert.setInt(8, room.capacity());
                    roomInsert.setString(9, room.capacityStatus()); roomInsert.executeUpdate();
                    for (SeatAssignmentPlanner.Seat seat : room.seatGrid()) {
                        roomSeatInsert.setString(1, roomAssignmentId); roomSeatInsert.setInt(2, seat.id());
                        roomSeatInsert.setInt(3, seat.row()); roomSeatInsert.setInt(4, seat.column());
                        roomSeatInsert.setString(5, seat.usable() ? "USABLE" : "BLOCKED");
                        roomSeatInsert.addBatch();
                    }
                    roomSeatInsert.executeBatch();
                    roomSeatInsert.clearBatch();
                    for (String staffId : room.staffIds()) {
                        staffInsert.setString(1, UUID.randomUUID().toString());
                        staffInsert.setString(2, roomAssignmentId); staffInsert.setString(3, staffId);
                        staffInsert.executeUpdate();
                    }
                    for (SeatAssignmentPlanner.Assignment assignment : room.assignments()) {
                        var candidate = assignment.candidate();
                        seatInsert.setString(1, UUID.randomUUID().toString()); seatInsert.setString(2, roomAssignmentId);
                        seatInsert.setString(3, planSessionId); seatInsert.setString(4, session.id());
                        seatInsert.setString(5, room.id()); seatInsert.setInt(6, assignment.seat().id());
                        seatInsert.setString(7, candidate.sessionCourseId());
                        seatInsert.setString(8, candidate.studentId()); seatInsert.setObject(9, candidate.anonymousNumber());
                        seatInsert.setString(10, assignment.origin());
                        seatInsert.setInt(11, assignment.seat().row()); seatInsert.setInt(12, assignment.seat().column());
                        seatInsert.setString(13, assignment.seat().usable() ? "USABLE" : "BLOCKED");
                        seatInsert.setString(14, candidate.candidateKey());
                        seatInsert.setString(15, candidate.courseCode());
                        seatInsert.executeUpdate(); seatCount++;
                    }
                }
                for (FeasibilityReport.Diagnostic diagnostic : session.diagnostics())
                    insertDiagnostic(diagnosticInsert, planId, planSessionId, session.id(), "SESSION", diagnostic);
                for (GeneratedPlan.Room room : session.rooms()) for (FeasibilityReport.Diagnostic diagnostic : room.diagnostics())
                    insertDiagnostic(diagnosticInsert, planId, planSessionId, room.id(), "ROOM", diagnostic);
            }
        }
        return seatCount;
    }

    private static void insertDiagnostic(java.sql.PreparedStatement statement, String planId, String planSessionId,
            String entityId, String entityType, FeasibilityReport.Diagnostic diagnostic) throws SQLException {
        String severity = diagnostic.severity().equals("BLOCKER") ? "ERROR" : diagnostic.severity();
        String classification = diagnostic.severity().equals("BLOCKER") ? "HARD"
                : diagnostic.code().equals("SAME_COURSE_ORTHOGONAL_ADJACENCY")
                    || diagnostic.code().equals("COURSE_SPLIT_ACROSS_BLOCKS") ? "SOFT" : "FEASIBILITY";
        statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, planId);
        statement.setString(3, planSessionId); statement.setString(4, diagnostic.code());
        statement.setString(5, severity); statement.setString(6, classification);
        statement.setString(7, entityType); statement.setString(8, entityId);
        statement.setString(9, diagnostic.message()); statement.executeUpdate();
    }

    private static String key(String studentId, String sessionId) { return studentId + "\u0000" + sessionId; }

    private record Paper(String sessionCourseId, String courseCode, int candidateCount) {}
    private record StudentCandidate(String studentId, String rollNumber) {}
    private record Registration(String sessionId, String date, LocalTime startTime, LocalTime endTime, String courseCode) {}
    private static final class CourseBlockCount {
        private final String date;
        private final String startTime;
        private final String courseCode;
        private final int expected;
        private final Map<String, Integer> blocks = new TreeMap<>();
        private int assigned;
        private CourseBlockCount(String date, String startTime, String courseCode, int expected) {
            this.date = date; this.startTime = startTime; this.courseCode = courseCode; this.expected = expected;
        }
    }
    private static final class CourseRoomCount {
        private final String sessionId, date, start, sessionCourseId, courseCode, building, room;
        private final int expected, assigned;
        private CourseRoomCount(String sessionId, String date, String start, String sessionCourseId,
                String courseCode, int expected, String building, String room, int assigned) {
            this.sessionId=sessionId; this.date=date; this.start=start; this.sessionCourseId=sessionCourseId;
            this.courseCode=courseCode; this.expected=expected; this.building=building; this.room=room; this.assigned=assigned;
        }
    }
    public record PlanSummary(String planId, int version, String status, int sourceRevision, int currentRevision,
            String createdAt, String changeSummary, String finalizedBy, String finalizedAt,
            int roomCount, int seatCount, long blockerCount) {
        public boolean stale() { return sourceRevision != currentRevision; }
    }
    public record LoadedPlan(PlanSummary summary, GeneratedPlan plan) {}
    public record StaffOption(String staffId, String fullName) {
        @Override public String toString() { return staffId + " · " + fullName; }
    }
    public record CourseBlockSummary(String date, String startTime, String courseCode, int expectedCandidates,
            int assignedCandidates, int blocksUsed, String distribution, String status) {}
    public record CentreAllocationSummary(String eventCode, int planVersion, String planStatus, boolean stale,
            String sessionId, String date, String startTime, String endTime, String centreName,
            String buildingCode, String roomCode, int assignedCandidates, int requiredStaff, int assignedStaff) {}
    public record CourseRoomAllocationSummary(String sessionId, String date, String startTime, String courseCode,
            int expectedCandidates, String buildingCode, String roomCode, int assignedInRoom,
            int assignedTotal, int roomsUsed, String status) {}
    public record SavedPlan(String planId, int version, int seatCount, int roomCount, long blockerCount) {}
}
