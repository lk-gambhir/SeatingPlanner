package importexport;

import java.util.List;

/** Staff-course links prepared for review before they are saved. */
public record AffiliationImportPreview(String fileName, String sha256, String sourceHeaders, List<Row> rows) {
    public AffiliationImportPreview { rows = List.copyOf(rows); }
    public int count(Status status) { return (int) rows.stream().filter(row -> row.status() == status).count(); }
    public boolean hasErrors() { return count(Status.ERROR) > 0; }

    public enum Status { ADD, SKIP, ERROR }
    public record Row(int sourceRow, Status status, String staffId, String courseId,
            String courseCode, String relationshipType, String dataLabel, String message,
            String rawValues, String normalizedValues) {}
}
