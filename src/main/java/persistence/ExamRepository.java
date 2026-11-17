package persistence;

import importexport.ExamImportPreview;
import importexport.CsvImportValues;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Persists reviewed exam schedule and course-count imports. */
public final class ExamRepository {
    private final SqliteConnectionFactory connections;

    public ExamRepository(SqliteConnectionFactory connections) { this.connections = Objects.requireNonNull(connections); }

    public List<ExamSummary> findAll() throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("""
                     SELECT e.event_code, e.name, e.status, COUNT(DISTINCT x.exam_session_id) AS sessions,
                            COUNT(sc.session_course_id) AS papers, COALESCE(SUM(sc.candidate_count), 0) AS candidates
                     FROM exam_event e
                     LEFT JOIN exam_session x ON x.event_id=e.event_id
                     LEFT JOIN session_course sc ON sc.exam_session_id=x.exam_session_id
                     GROUP BY e.event_id ORDER BY e.created_at DESC
                     """);
             var rows = statement.executeQuery()) {
            List<ExamSummary> events = new ArrayList<>();
            while (rows.next()) events.add(new ExamSummary(rows.getString("event_code"), rows.getString("name"),
                    rows.getString("status"), rows.getInt("sessions"), rows.getInt("papers"), rows.getInt("candidates")));
            return List.copyOf(events);
        }
    }

    public boolean hasEventCode(String eventCode) throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("SELECT 1 FROM exam_event WHERE event_code=?")) {
            statement.setString(1, eventCode);
            try (var row = statement.executeQuery()) { return row.next(); }
        }
    }

    public ExamSummary commitImport(ExamImportPreview preview, String name, String academicPeriod) throws SQLException {
        if (preview.hasErrors()) throw new IllegalArgumentException("Resolve PDF errors before importing.");
        if (preview.eventCode() == null || preview.eventCode().isBlank()) {
            throw new IllegalArgumentException("The schedule PDF does not contain a date-sheet code.");
        }
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Exam name is required.");

        String eventId = UUID.randomUUID().toString();
        String batchId = UUID.randomUUID().toString();
        String scheduleSourceId = UUID.randomUUID().toString();
        String countSourceId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (hasEventCode(connection, preview.eventCode())) {
                    throw new IllegalArgumentException("An exam event already uses code " + preview.eventCode() + ".");
                }
                insertBatch(connection, batchId, preview.scheduleFile() + " + " + preview.countFile(), now);
                insertSource(connection, scheduleSourceId, batchId, preview.scheduleFile(), preview.scheduleSha256(),
                        preview.sessions().size(), now);
                insertSource(connection, countSourceId, batchId, preview.countFile(), preview.countSha256(),
                        preview.sessions().size(), now);
                insertEvent(connection, eventId, preview.eventCode(), name.trim(), academicPeriod,
                        preview.durationMinutes(), now);
                int sessionNumber = 0;
                for (ExamImportPreview.Session session : preview.sessions()) {
                    String sessionId = eventId + "-S" + String.format("%03d", ++sessionNumber);
                    insertSession(connection, sessionId, eventId, session);
                    insertStagedRow(connection, scheduleSourceId, session.sourceRow(), session.scheduleSourceText(),
                            CsvImportValues.encodeTable(List.of("source_row", "source_page", "date", "start_time",
                                            "derived_end_time", "course_codes"),
                                    List.of(String.valueOf(session.sourceRow()), String.valueOf(session.schedulePage()),
                                            session.date().toString(), session.startTime().toString(), session.endTime().toString(),
                                            String.join(";", session.papers().stream().map(ExamImportPreview.Paper::courseCode).toList()))));
                    insertStagedRow(connection, countSourceId, session.papers().getFirst().sourceRow(), session.countSourceText(),
                            CsvImportValues.encodeTable(List.of("source_row", "source_page", "date", "start_time",
                                            "reported_total", "course_counts"),
                                    List.of(String.valueOf(session.papers().getFirst().sourceRow()), String.valueOf(session.countPage()),
                                            session.date().toString(), session.startTime().toString(), String.valueOf(session.reportedTotal()),
                                            String.join(";", session.papers().stream().map(paper ->
                                                    paper.courseCode() + "=" + paper.candidateCount()).toList()))));
                    for (ExamImportPreview.Paper paper : session.papers()) {
                        String courseId = insertCourse(connection, paper.courseCode(), paper.courseName());
                        String sessionCourseId = sessionId + "-" + paper.courseCode();
                        insertSessionCourse(connection, sessionCourseId, sessionId, courseId, paper, batchId);
                    }
                }
                connection.commit();
                return new ExamSummary(preview.eventCode(), name.trim(), "DRAFT", preview.sessions().size(),
                        preview.paperCount(), preview.candidateTotal());
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void insertBatch(Connection connection, String id, String label, String now) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO import_batch(import_batch_id, batch_label, status, created_at, committed_at)
                VALUES (?, ?, 'COMMITTED', ?, ?)
                """)) {
            statement.setString(1, id); statement.setString(2, label); statement.setString(3, now);
            statement.setString(4, now); statement.executeUpdate();
        }
    }

    private static void insertSource(Connection connection, String id, String batchId, String fileName,
            String sha256, int rows, String now) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO import_source(import_source_id, import_batch_id, file_name, source_type,
                    sha256, parse_status, row_count, created_at)
                VALUES (?, ?, ?, 'PDF', ?, 'PARSED', ?, ?)
                """)) {
            statement.setString(1, id); statement.setString(2, batchId); statement.setString(3, fileName);
            statement.setString(4, sha256); statement.setInt(5, rows); statement.setString(6, now);
            statement.executeUpdate();
        }
    }

    private static void insertEvent(Connection connection, String id, String code, String name,
            String period, int duration, String now) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO exam_event(event_id, event_code, name, academic_period, shared_duration_minutes,
                    status, source_revision, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'DRAFT', 1, ?, ?)
                """)) {
            statement.setString(1, id); statement.setString(2, code); statement.setString(3, name);
            statement.setString(4, period == null || period.isBlank() ? null : period.trim());
            statement.setInt(5, duration); statement.setString(6, now); statement.setString(7, now);
            statement.executeUpdate();
        }
    }

    private static void insertSession(Connection connection, String id, String eventId,
            ExamImportPreview.Session session) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO exam_session(exam_session_id, event_id, session_date, start_time, end_time,
                    reported_candidate_total, status, source_row)
                VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?)
                """)) {
            statement.setString(1, id); statement.setString(2, eventId);
            statement.setString(3, session.date().format(DateTimeFormatter.ISO_DATE));
            statement.setString(4, session.startTime().toString()); statement.setString(5, session.endTime().toString());
            statement.setInt(6, session.reportedTotal()); statement.setString(7, String.valueOf(session.sourceRow()));
            statement.executeUpdate();
        }
    }

    private static void insertStagedRow(Connection connection, String sourceId, int row, String raw, String normalized) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO import_staged_row(import_staged_row_id, import_source_id, source_row,
                    raw_values, normalized_values, row_status)
                VALUES (?, ?, ?, ?, ?, 'ACCEPTED')
                """)) {
            statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, sourceId);
            statement.setInt(3, row); statement.setString(4, raw); statement.setString(5, normalized);
            statement.executeUpdate();
        }
    }

    private static String insertCourse(Connection connection, String code, String name) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO course(course_id, course_code, course_name, data_label)
                VALUES (?, ?, ?, 'OPERATIONAL')
                """)) {
            statement.setString(1, code); statement.setString(2, code); statement.setString(3, name);
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("SELECT course_id FROM course WHERE course_code=?")) {
            statement.setString(1, code);
            try (var row = statement.executeQuery()) { row.next(); return row.getString(1); }
        }
    }

    private static void insertSessionCourse(Connection connection, String id, String sessionId,
            String courseId, ExamImportPreview.Paper paper, String batchId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO session_course(session_course_id, exam_session_id, course_id, candidate_count,
                    count_provenance, source_page, source_row, import_batch_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, id); statement.setString(2, sessionId); statement.setString(3, courseId);
            statement.setInt(4, paper.candidateCount());
            statement.setString(5, paper.manuallyCorrected() ? "CONFIRMED" : "PDF_IMPORTED");
            statement.setInt(6, paper.sourcePage()); statement.setString(7, String.valueOf(paper.sourceRow()));
            statement.setString(8, batchId);
            statement.executeUpdate();
        }
    }

    private static boolean hasEventCode(Connection connection, String eventCode) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM exam_event WHERE event_code=?")) {
            statement.setString(1, eventCode);
            try (var row = statement.executeQuery()) { return row.next(); }
        }
    }

    public record ExamSummary(String eventCode, String name, String status,
            int sessionCount, int paperCount, int candidateCount) {}
}
