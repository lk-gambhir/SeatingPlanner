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

/** Previews attendance and availability changes for one selected exam session. */
public final class SessionStaffStatusCsvImporter {
    public Preview preview(Path path, String sessionId, Set<String> knownStaff) throws IOException {
        List<Row> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (PushbackReader reader = new PushbackReader(Files.newBufferedReader(path, StandardCharsets.UTF_8), 1)) {
            int first = reader.read();
            if (first != 0xFEFF && first != -1) reader.unread(first);
            CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
            try (CSVParser parser = format.parse(reader)) {
                Map<String, Integer> headers = new HashMap<>();
                parser.getHeaderMap().forEach((name, index) -> headers.put(name.trim().toLowerCase(Locale.ROOT), index));
                for (String required : List.of("session_id", "staff_id", "availability", "attendance"))
                    if (!headers.containsKey(required)) throw new IOException("CSV is missing required column: " + required);
                for (CSVRecord record : parser) rows.add(readRow(record, headers, sessionId, knownStaff, seen));
            }
        }
        if (rows.isEmpty()) throw new IOException("CSV contains no staff status rows.");
        return new Preview(path.getFileName().toString(), rows);
    }

    private static Row readRow(CSVRecord record, Map<String, Integer> headers, String sessionId,
            Set<String> knownStaff, Set<String> seen) {
        int sourceRow = (int) record.getRecordNumber();
        String staffId = value(record, headers, "staff_id").trim();
        String rowSession = value(record, headers, "session_id").trim();
        String availability = value(record, headers, "availability").trim().toUpperCase(Locale.ROOT);
        String attendance = value(record, headers, "attendance").trim().toUpperCase(Locale.ROOT);
        if (availability.isBlank()) availability = "NOT_SET";
        if (attendance.isBlank()) attendance = "NOT_RECORDED";
        String error = staffId.isBlank() ? "staff_id is required."
                : !rowSession.equals(sessionId) ? "session_id does not match the selected exam session."
                : !knownStaff.contains(staffId) ? "Unknown staff ID: " + staffId + "."
                : !seen.add(staffId) ? "Duplicate staff ID in this file."
                : !List.of("NOT_SET", "AVAILABLE", "UNAVAILABLE").contains(availability) ? "Invalid availability value."
                : !List.of("PRESENT", "ABSENT", "NOT_RECORDED").contains(attendance) ? "Invalid attendance value." : "";
        return new Row(sourceRow, staffId, availability, attendance, error);
    }

    private static String value(CSVRecord record, Map<String, Integer> headers, String name) {
        Integer index = headers.get(name);
        return index == null || index >= record.size() ? "" : record.get(index);
    }

    public record Preview(String fileName, List<Row> rows) {
        public Preview { rows = List.copyOf(rows); }
        public boolean hasErrors() { return rows.stream().anyMatch(row -> !row.error().isEmpty()); }
        public List<Row> validRows() {
            return rows.stream().filter(row -> row.error().isEmpty()).toList();
        }
    }
    public record Row(int sourceRow, String staffId, String availability, String attendance, String error) {}
}
