package persistence;

import planning.FeasibilityAnalyzer;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads one consistent database snapshot for the feasibility check. */
public final class FeasibilityRepository {
    private final SqliteConnectionFactory connections;

    public FeasibilityRepository(SqliteConnectionFactory connections) { this.connections = connections; }

    public FeasibilityAnalyzer.Snapshot load(String eventCode) throws SQLException {
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try {
                FeasibilityAnalyzer.Snapshot snapshot = load(connection, eventCode);
                connection.commit();
                return snapshot;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback(); throw exception;
            }
        }
    }

    public FeasibilityAnalyzer.Snapshot load(Connection connection, String eventCode) throws SQLException {
        String eventId;
        int revision;
        try (var statement = connection.prepareStatement(
                "SELECT event_id, source_revision FROM exam_event WHERE event_code=?")) {
            statement.setString(1, eventCode);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Unknown exam event: " + eventCode);
                eventId = rows.getString("event_id"); revision = rows.getInt("source_revision");
            }
        }
        int handoverMinutes;
        try (var statement = connection.prepareStatement(
                "SELECT setting_value FROM application_setting WHERE setting_key='room_staff_handover_minutes'");
             var rows = statement.executeQuery()) {
            if (!rows.next()) throw new SQLException("Missing room/staff handover setting.");
            handoverMinutes = Integer.parseInt(rows.getString(1));
        }
        List<FeasibilityAnalyzer.Session> sessions = loadSessions(connection, eventId);
        List<FeasibilityAnalyzer.Room> rooms = loadRooms(connection, eventId);
        List<FeasibilityAnalyzer.Staff> staff = loadStaff(connection, eventId);
        return new FeasibilityAnalyzer.Snapshot(eventCode, revision, handoverMinutes, sessions, rooms, staff);
    }

    private static List<FeasibilityAnalyzer.Session> loadSessions(Connection connection, String eventId) throws SQLException {
        List<FeasibilityAnalyzer.Session> sessions = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                SELECT x.exam_session_id, x.session_date, x.start_time, x.end_time,
                       COALESCE(SUM(sc.candidate_count), 0) AS candidates
                FROM exam_session x LEFT JOIN session_course sc ON sc.exam_session_id=x.exam_session_id
                WHERE x.event_id=? AND x.status<>'CANCELLED'
                GROUP BY x.exam_session_id ORDER BY x.session_date, x.start_time
                """)) {
            statement.setString(1, eventId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String sessionId = rows.getString("exam_session_id");
                    Map<String, Integer> courseDemand = new HashMap<>();
                    try (var courses = connection.prepareStatement("""
                            SELECT c.course_code, sc.candidate_count
                            FROM session_course sc JOIN course c ON c.course_id=sc.course_id
                            WHERE sc.exam_session_id=? ORDER BY c.course_code
                            """)) {
                        courses.setString(1, sessionId);
                        try (var courseRows = courses.executeQuery()) {
                            while (courseRows.next()) courseDemand.put(courseRows.getString("course_code"),
                                    courseRows.getInt("candidate_count"));
                        }
                    }
                    sessions.add(new FeasibilityAnalyzer.Session(sessionId,
                            LocalDate.parse(rows.getString("session_date")), LocalTime.parse(rows.getString("start_time")),
                            LocalTime.parse(rows.getString("end_time")), rows.getInt("candidates"),
                            List.copyOf(courseDemand.keySet()), courseDemand));
                }
            }
        }
        return List.copyOf(sessions);
    }

    private static List<FeasibilityAnalyzer.Room> loadRooms(Connection connection, String eventId) throws SQLException {
        Map<String, Set<String>> unavailable = new HashMap<>();
        try (var statement = connection.prepareStatement("""
                SELECT a.room_id, a.exam_session_id FROM room_availability a
                JOIN exam_session x ON x.exam_session_id=a.exam_session_id
                WHERE x.event_id=? AND a.available=0
                """)) {
            statement.setString(1, eventId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) unavailable.computeIfAbsent(rows.getString("room_id"), ignored -> new HashSet<>())
                        .add(rows.getString("exam_session_id"));
            }
        }
        List<FeasibilityAnalyzer.Room> rooms = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                SELECT r.room_id, b.building_code, r.room_code, r.usable_capacity, r.capacity_status, r.active,
                       COUNT(s.seat_id) AS seat_records,
                       COALESCE(SUM(CASE WHEN s.seat_status='USABLE' THEN 1 ELSE 0 END), 0) AS usable_seats
                FROM room r JOIN building b ON b.building_id=r.building_id
                LEFT JOIN seat s ON s.room_id=r.room_id GROUP BY r.room_id
                ORDER BY b.building_code, r.room_code
                """); var rows = statement.executeQuery()) {
            while (rows.next()) {
                String roomId = rows.getString("room_id");
                rooms.add(new FeasibilityAnalyzer.Room(roomId, rows.getString("building_code"),
                        rows.getString("room_code"), rows.getInt("usable_capacity"), rows.getInt("seat_records"),
                        rows.getInt("usable_seats"), rows.getString("capacity_status"), rows.getInt("active") == 1,
                        unavailable.getOrDefault(roomId, Set.of())));
            }
        }
        return List.copyOf(rooms);
    }

    private static List<FeasibilityAnalyzer.Staff> loadStaff(Connection connection, String eventId) throws SQLException {
        Map<String, Set<String>> courses = new HashMap<>();
        Map<String, Set<String>> unavailable = new HashMap<>();
        try (var statement = connection.prepareStatement("""
                SELECT rel.staff_id, c.course_code FROM staff_course_relationship rel
                JOIN course c ON c.course_id=rel.course_id
                """); var rows = statement.executeQuery()) {
            while (rows.next()) courses.computeIfAbsent(rows.getString("staff_id"), ignored -> new HashSet<>())
                    .add(rows.getString("course_code"));
        }
        try (var statement = connection.prepareStatement("""
                SELECT a.staff_id, a.exam_session_id FROM staff_availability a
                JOIN exam_session x ON x.exam_session_id=a.exam_session_id
                WHERE x.event_id=? AND a.availability='UNAVAILABLE'
                """)) {
            statement.setString(1, eventId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) unavailable.computeIfAbsent(rows.getString("staff_id"), ignored -> new HashSet<>())
                        .add(rows.getString("exam_session_id"));
            }
        }
        List<FeasibilityAnalyzer.Staff> staff = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT staff_id, active FROM staff ORDER BY staff_id");
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                String staffId = rows.getString("staff_id");
                staff.add(new FeasibilityAnalyzer.Staff(staffId, rows.getInt("active") == 1,
                        courses.getOrDefault(staffId, Set.of()), unavailable.getOrDefault(staffId, Set.of())));
            }
        }
        return List.copyOf(staff);
    }
}
