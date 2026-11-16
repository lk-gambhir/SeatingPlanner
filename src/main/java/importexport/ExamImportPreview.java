package importexport;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** A joined preview of one schedule PDF and its course-count PDF. */
public record ExamImportPreview(
        String eventCode,
        String scheduleFile,
        String scheduleSha256,
        String countFile,
        String countSha256,
        int durationMinutes,
        List<Session> sessions,
        List<Diagnostic> diagnostics) {

    public ExamImportPreview {
        sessions = List.copyOf(sessions);
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean hasErrors() { return diagnostics.stream().anyMatch(item -> item.severity() == Severity.ERROR); }
    public int paperCount() { return sessions.stream().mapToInt(session -> session.papers().size()).sum(); }
    public int candidateTotal() { return sessions.stream().mapToInt(Session::reportedTotal).sum(); }

    public enum Severity { WARNING, ERROR }
    public record Session(LocalDate date, LocalTime startTime, LocalTime endTime, int sourceRow,
            int schedulePage, int countPage, int reportedTotal, String scheduleSourceText,
            String countSourceText, List<Paper> papers) {
        public Session { papers = List.copyOf(papers); }
    }
    public record Paper(String courseCode, String courseName, int candidateCount, int sourceRow,
            int sourcePage, boolean manuallyCorrected) {
        public Paper(String courseCode, String courseName, int candidateCount, int sourceRow, int sourcePage) {
            this(courseCode, courseName, candidateCount, sourceRow, sourcePage, false);
        }
    }
    public record Diagnostic(Severity severity, String code, String message, Integer sourceRow, Integer sourcePage) {}
}
