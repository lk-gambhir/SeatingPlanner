package domain;

import java.util.List;

/** Rows prepared for review before a staff CSV is committed. */
public record StaffImportPreview(
        String fileName,
        String sha256,
        String sourceHeaders,
        List<Row> rows,
        List<Warning> warnings) {

    public StaffImportPreview {
        rows = List.copyOf(rows);
        warnings = List.copyOf(warnings);
    }

    public int count(Status status) {
        int count = 0;
        for (Row row : rows) {
            if (row.status() == status) count++;
        }
        return count;
    }

    public boolean hasErrors() {
        return count(Status.ERROR) > 0;
    }

    public StaffImportPreview withExcludedRows(java.util.Set<Integer> excludedRows) {
        if (excludedRows == null || excludedRows.isEmpty()) return this;
        List<Row> updated = rows.stream().map(row -> {
            if (row.status() == Status.ADD && excludedRows.contains(row.sourceRow())) {
                return new Row(row.sourceRow(), Status.SKIP, row.staff(), "Excluded by administrator",
                        row.rawValues(), row.normalizedValues());
            }
            return row;
        }).toList();
        return new StaffImportPreview(fileName, sha256, sourceHeaders, updated, warnings);
    }

    public enum Status { ADD, SKIP, ERROR }

    public record Row(int sourceRow, Status status, StaffMember staff, String message,
            String rawValues, String normalizedValues) {}

    public record Warning(Integer sourceRow, String code, String message) {}
}
