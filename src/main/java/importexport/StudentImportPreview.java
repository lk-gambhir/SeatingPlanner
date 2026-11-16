package importexport;

import domain.Student;

import java.util.List;

public record StudentImportPreview(String fileName, String sha256, String sourceHeaders, List<Row> rows) {
    public StudentImportPreview { rows = List.copyOf(rows); }
    public int count(Status status) { return (int) rows.stream().filter(row -> row.status() == status).count(); }
    public boolean hasErrors() { return count(Status.ERROR) > 0; }

    public StudentImportPreview withExcludedRows(java.util.Set<Integer> excludedRows) {
        if (excludedRows == null || excludedRows.isEmpty()) return this;
        List<Row> updated = rows.stream().map(row -> {
            if (row.status() == Status.ADD && excludedRows.contains(row.sourceRow())) {
                return new Row(row.sourceRow(), Status.SKIP, row.student(), "Excluded by administrator",
                        row.rawValues(), row.normalizedValues());
            }
            return row;
        }).toList();
        return new StudentImportPreview(fileName, sha256, sourceHeaders, updated);
    }

    public enum Status { ADD, SKIP, ERROR }
    public record Row(int sourceRow, Status status, Student student, String message,
            String rawValues, String normalizedValues) {}
}
