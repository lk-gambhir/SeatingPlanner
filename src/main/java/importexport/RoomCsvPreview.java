package importexport;

import domain.Room;

import java.util.List;

public record RoomCsvPreview(String fileName, String sha256, String sourceHeaders, List<Row> rows, boolean hasAvailabilityColumn) {
    public RoomCsvPreview { rows = List.copyOf(rows); }

    public int count(Status status) { return (int) rows.stream().filter(row -> row.status() == status).count(); }
    public boolean hasErrors() { return count(Status.ERROR) > 0; }

    public RoomCsvPreview withExcludedRows(java.util.Set<Integer> excludedRows) {
        if (excludedRows == null || excludedRows.isEmpty()) return this;
        List<Row> updated = rows.stream().map(row -> {
            if (row.status() == Status.ADD && excludedRows.contains(row.sourceRow())) {
                return new Row(row.sourceRow(), Status.SKIP, row.room(), row.blockedSeats(), "Excluded by administrator",
                        row.rawValues(), row.normalizedValues());
            }
            return row;
        }).toList();
        return new RoomCsvPreview(fileName, sha256, sourceHeaders, updated, hasAvailabilityColumn);
    }

    public enum Status { ADD, SKIP, ERROR }
    public record SeatPosition(int row, int column) {}
    public record Row(int sourceRow, Status status, Room room, List<SeatPosition> blockedSeats, String message,
            String rawValues, String normalizedValues) {
        public Row { blockedSeats = List.copyOf(blockedSeats); }
    }
}
