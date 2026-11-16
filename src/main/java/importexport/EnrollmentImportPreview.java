package importexport;

import java.util.List;

/** A reviewed registration CSV mapped to one saved exam event. */
public record EnrollmentImportPreview(String fileName, String sha256, String sourceHeaders, String eventCode,
        String sourceSessionId, List<Row> rows, List<Diagnostic> diagnostics) {
    public EnrollmentImportPreview {
        rows = List.copyOf(rows);
        diagnostics = List.copyOf(diagnostics);
    }

    public int count(Status status) { return (int) rows.stream().filter(row -> row.status() == status).count(); }
    public boolean hasErrors() { return count(Status.ERROR) > 0 || diagnostics.stream().anyMatch(item -> item.severity().equals("ERROR")); }
    public boolean hasWarnings() { return !diagnostics.isEmpty(); }

    public EnrollmentImportPreview withExcludedRows(java.util.Set<Integer> excludedRows) {
        if (excludedRows == null || excludedRows.isEmpty()) return this;
        List<Row> updated = rows.stream().map(row -> {
            if (row.status() == Status.ADD && excludedRows.contains(row.sourceRow())) {
                return new Row(row.sourceRow(), Status.SKIP, row.enrollmentId(), row.studentId(),
                        row.courseCode(), row.sessionCourseId(), row.registrationStatus(), row.dataLabel(),
                        "Excluded by administrator", row.rawValues(), row.normalizedValues());
            }
            return row;
        }).toList();
        return new EnrollmentImportPreview(fileName, sha256, sourceHeaders, eventCode, sourceSessionId, updated, diagnostics);
    }

    public enum Status { ADD, SKIP, ERROR }
    public record Row(int sourceRow, Status status, String enrollmentId, String studentId,
            String courseCode, String sessionCourseId, String registrationStatus, String dataLabel, String message,
            String rawValues, String normalizedValues) {}
    public record Diagnostic(String severity, String code, String message) {}
}
