package importexport;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Previews availability overrides for rooms in one selected exam session. */
public final class RoomAvailabilityCsvImporter {
    public Preview preview(Path path, String sessionId, Set<String> knownRoomIds) throws IOException {
        List<Row> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (PushbackReader reader = new PushbackReader(Files.newBufferedReader(path, StandardCharsets.UTF_8), 1)) {
            int first = reader.read();
            if (first != 0xFEFF && first != -1) reader.unread(first);
            CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
            try (CSVParser parser = format.parse(reader)) {
                Map<String, Integer> headers = new HashMap<>();
                parser.getHeaderMap().forEach((name, index) -> headers.put(name.trim().toLowerCase(Locale.ROOT), index));
                for (String required : List.of("session_id", "room_id", "is_available"))
                    if (!headers.containsKey(required)) throw new IOException("CSV is missing required column: " + required);
                for (CSVRecord record : parser) rows.add(readRow(record, headers, sessionId, knownRoomIds, seen));
            }
        }
        if (rows.isEmpty()) throw new IOException("CSV contains no room availability rows.");
        return new Preview(path.getFileName().toString(), rows);
    }

    private static Row readRow(CSVRecord record, Map<String, Integer> headers, String sessionId,
            Set<String> knownRoomIds, Set<String> seen) {
        int sourceRow = (int) record.getRecordNumber();
        String rowSession = value(record, headers, "session_id").trim();
        String roomId = value(record, headers, "room_id").trim();
        String status = value(record, headers, "is_available").trim().toUpperCase(Locale.ROOT);
        Boolean available = switch (status) {
            case "TRUE", "1", "AVAILABLE" -> true;
            case "FALSE", "0", "UNAVAILABLE" -> false;
            case "NOT_SET" -> null;
            default -> null;
        };
        String error = roomId.isBlank() ? "room_id is required; copy the ID from the Rooms dashboard."
                : !rowSession.equals(sessionId) ? "session_id must match the selected exam session ID."
                : !knownRoomIds.contains(roomId) ? "Unknown room_id " + roomId + "; copy a saved room ID from the Rooms dashboard."
                : !seen.add(roomId) ? "Duplicate room_id in this file; keep only one row for this room and session."
                : !List.of("TRUE", "1", "AVAILABLE", "FALSE", "0", "UNAVAILABLE", "NOT_SET").contains(status)
                    ? "is_available must be TRUE, FALSE, or NOT_SET." : "";
        return new Row(sourceRow, roomId, available, status, error);
    }

    private static String value(CSVRecord record, Map<String, Integer> headers, String name) {
        Integer index = headers.get(name);
        return index == null || index >= record.size() ? "" : record.get(index);
    }

    public record Preview(String fileName, List<Row> rows) {
        public Preview { rows = List.copyOf(rows); }
        public boolean hasErrors() { return rows.stream().anyMatch(row -> !row.error().isEmpty()); }
        public List<Row> validRows() { return rows.stream().filter(row -> row.error().isEmpty()).toList(); }
    }

    public record Row(int sourceRow, String roomId, Boolean available, String status, String error) {}
}
