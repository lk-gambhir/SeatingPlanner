package importexport;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reads a staff-to-course relationship CSV and checks its saved references. */
public final class StaffAffiliationCsvImporter {
    public AffiliationImportPreview preview(Path path, Context context) throws IOException {
        List<AffiliationImportPreview.Row> rows = new ArrayList<>();
        Set<String> fileKeys = new HashSet<>();
        String sourceHeaders;
        try (PushbackReader reader = new PushbackReader(Files.newBufferedReader(path, StandardCharsets.UTF_8), 1)) {
            int first = reader.read();
            if (first != 0xFEFF && first != -1) reader.unread(first);
            CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
            try (CSVParser parser = format.parse(reader)) {
                sourceHeaders = CsvImportValues.encodeHeaders(parser.getHeaderMap());
                Map<String, Integer> headers = new java.util.HashMap<>();
                for (Map.Entry<String, Integer> header : parser.getHeaderMap().entrySet()) {
                    headers.put(header.getKey().trim().toLowerCase(Locale.ROOT), header.getValue());
                }
                String staffColumn = headers.containsKey("staff_id") ? "staff_id" : "invigilator_id";
                String courseColumn = headers.containsKey("course_code") ? "course_code" : "course_id";
                if (!headers.containsKey(staffColumn) || !headers.containsKey(courseColumn)
                        || !headers.containsKey("relationship_type")) {
                    throw new IOException("CSV must contain staff_id or invigilator_id, course_code or course_id, and relationship_type columns.");
                }
                for (CSVRecord record : parser) rows.add(readRow(record, headers, staffColumn, courseColumn, context, fileKeys));
            }
        }
        return new AffiliationImportPreview(path.getFileName().toString(), sha256(path), sourceHeaders, rows);
    }

    private static AffiliationImportPreview.Row readRow(CSVRecord record, Map<String, Integer> headers,
            String staffColumn, String courseColumn, Context context, Set<String> fileKeys) {
        int sourceRow = (int) record.getRecordNumber();
        String raw = CsvImportValues.encodeRecord(record.toList());
        String staffId = value(record, headers, staffColumn).trim();
        String courseCode = value(record, headers, courseColumn).trim().toUpperCase(Locale.ROOT);
        String type = value(record, headers, "relationship_type").trim().toUpperCase(Locale.ROOT);
        String dataLabel = value(record, headers, "data_label").trim();
        String courseId = context.courseIds().get(courseCode);
        String message = "";
        if (staffId.isBlank() || courseCode.isBlank()) message = "Staff ID and course code are required.";
        else if (!context.staffIds().contains(staffId)) message = "Unknown staff ID: " + staffId + ".";
        else if (courseId == null) message = "Course is not loaded: " + courseCode + ". Import the event before its staff-course links.";
        else if (!type.equals("TEACHES") && !type.equals("EXCLUDED")) message = "Relationship type must be TEACHES or EXCLUDED.";
        if (!message.isEmpty()) return row(sourceRow, AffiliationImportPreview.Status.ERROR, staffId, courseId,
                courseCode, type, dataLabel, message, raw);

        String key = key(staffId, courseId, type);
        if (!fileKeys.add(key)) return row(sourceRow, AffiliationImportPreview.Status.ERROR, staffId, courseId,
                courseCode, type, dataLabel, "Duplicate staff/course relationship in this file.", raw);
        if (context.existingKeys().contains(key)) return row(sourceRow, AffiliationImportPreview.Status.SKIP,
                staffId, courseId, courseCode, type, dataLabel, "Relationship already exists; saved row will be kept.", raw);
        return row(sourceRow, AffiliationImportPreview.Status.ADD, staffId, courseId, courseCode, type,
                dataLabel.isBlank() ? "OPERATIONAL" : dataLabel, "", raw);
    }

    public static String relationshipKey(String staffId, String courseId, String type) {
        return key(staffId, courseId, type);
    }

    private static String key(String staffId, String courseId, String type) {
        return staffId + "\u0000" + courseId + "\u0000" + type;
    }

    private static AffiliationImportPreview.Row row(int sourceRow, AffiliationImportPreview.Status status,
            String staffId, String courseId, String courseCode, String type, String dataLabel, String message, String raw) {
        String normalized = CsvImportValues.encodeTable(
                List.of("staff_id", "course_code", "relationship_type", "data_label"),
                List.of(staffId, courseCode, type, dataLabel.isBlank() ? "OPERATIONAL" : dataLabel));
        return new AffiliationImportPreview.Row(sourceRow, status, staffId, courseId, courseCode, type,
                dataLabel.isBlank() ? "OPERATIONAL" : dataLabel, message, raw, normalized);
    }

    private static String value(CSVRecord record, Map<String, Integer> headers, String name) {
        Integer index = headers.get(name);
        return index == null || index >= record.size() ? "" : record.get(index);
    }

    private static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Context(Set<String> staffIds, Map<String, String> courseIds, Set<String> existingKeys) {
        public Context {
            staffIds = Set.copyOf(staffIds);
            courseIds = Map.copyOf(courseIds);
            existingKeys = Set.copyOf(existingKeys);
        }
    }
}
