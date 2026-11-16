package importexport;

import domain.StaffImportPreview;
import domain.StaffMember;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;

/** Parses the staff profile columns from the invigilator CSV. */
public final class StaffCsvImporter {
    public StaffImportPreview preview(Path path, List<StaffMember> savedStaff) throws IOException {
        Map<String, StaffMember> savedById = new java.util.HashMap<>();
        for (StaffMember staff : savedStaff) savedById.put(staff.staffId(), staff);

        List<StaffImportPreview.Row> rows = new ArrayList<>();
        List<StaffImportPreview.Warning> warnings = new ArrayList<>();
        Set<String> fileIds = new HashSet<>();
        String sourceHeaders;

        try (PushbackReader reader = new PushbackReader(Files.newBufferedReader(path, StandardCharsets.UTF_8), 1)) {
            int first = reader.read();
            if (first != 0xFEFF && first != -1) reader.unread(first);

            CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
            try (CSVParser parser = format.parse(reader)) {
                sourceHeaders = CsvImportValues.encodeHeaders(parser.getHeaderMap());
                Map<String, Integer> headers = normalizedHeaders(parser.getHeaderMap());
                String idColumn = chooseHeader(headers, "staff_id", "invigilator_id");
                String nameColumn = chooseHeader(headers, "full_name", "name");
                if (idColumn == null || nameColumn == null) {
                    throw new IOException("CSV must contain a staff_id or invigilator_id column and a full_name or name column.");
                }

                for (CSVRecord record : parser) {
                    rows.add(readRow(record, headers, idColumn, nameColumn, savedById, fileIds, warnings));
                }
            }
        }

        return new StaffImportPreview(path.getFileName().toString(), sha256(path), sourceHeaders, rows, warnings);
    }

    private static StaffImportPreview.Row readRow(
            CSVRecord record,
            Map<String, Integer> headers,
            String idColumn,
            String nameColumn,
            Map<String, StaffMember> savedById,
            Set<String> fileIds,
            List<StaffImportPreview.Warning> warnings) {
        int sourceRow = (int) record.getRecordNumber();
        String raw = CsvImportValues.encodeRecord(record.toList());
        try {
            String staffId = value(record, headers, idColumn).trim();
            String name = value(record, headers, nameColumn);
            String activeText = value(record, headers, "active");
            boolean active = activeText.isBlank() || parseActive(activeText, sourceRow);
            String dataLabel = value(record, headers, "data_label");
            StaffMember staff = new StaffMember(staffId, name,
                    value(record, headers, "department"),
                    value(record, headers, "designation"),
                    active,
                    dataLabel);
            String normalized = CsvImportValues.encodeTable(
                    List.of("staff_id", "full_name", "department", "designation", "active", "data_label"),
                    List.of(staff.staffId(), staff.fullName(), text(staff.department()), text(staff.designation()),
                            String.valueOf(staff.active()), staff.dataLabel()));

            if (!fileIds.add(staffId)) {
                return new StaffImportPreview.Row(sourceRow, StaffImportPreview.Status.ERROR, null,
                        "Duplicate staff ID in this file.", raw, normalized);
            }
            StaffMember saved = savedById.get(staffId);
            if (saved != null) {
                return new StaffImportPreview.Row(sourceRow, StaffImportPreview.Status.SKIP, saved,
                        describeExisting(saved, staff), raw, normalized);
            }

            String duties = value(record, headers, "max_duties");
            if (!duties.isBlank()) {
                warnings.add(new StaffImportPreview.Warning(sourceRow, "DEMO_MAX_DUTIES_IGNORED",
                        "max_duties is demo data and is not imported as a staffing rule."));
            }
            String unavailable = value(record, headers, "unavailable_slot_ids");
            if (!unavailable.isBlank()) {
                warnings.add(new StaffImportPreview.Warning(sourceRow, "AVAILABILITY_NEEDS_SESSIONS",
                        "Unavailable slot IDs are not applied until exam sessions are imported."));
            }
            return new StaffImportPreview.Row(sourceRow, StaffImportPreview.Status.ADD, staff,
                    warningText(duties, unavailable), raw, normalized);
        } catch (IllegalArgumentException exception) {
            return new StaffImportPreview.Row(sourceRow, StaffImportPreview.Status.ERROR, null,
                    "Row " + sourceRow + ": " + exception.getMessage() + " Correct the named CSV field and re-import.", raw, "");
        }
    }

    private static String text(String value) { return value == null ? "" : value; }

    private static String describeExisting(StaffMember saved, StaffMember incoming) {
        List<String> changes = new ArrayList<>();
        compare(changes, "full_name", saved.fullName(), incoming.fullName());
        compare(changes, "department", saved.department(), incoming.department());
        compare(changes, "designation", saved.designation(), incoming.designation());
        compare(changes, "active", saved.active(), incoming.active());
        compare(changes, "data_label", saved.dataLabel(), incoming.dataLabel());
        return changes.isEmpty() ? "This staff ID already exists; the CSV row matches saved data."
                : "Existing record will be kept. CSV changes: " + String.join("; ", changes);
    }

    private static void compare(List<String> changes, String field, Object saved, Object incoming) {
        if (!java.util.Objects.equals(saved, incoming)) {
            changes.add(field + " [" + display(saved) + " → " + display(incoming) + "]");
        }
    }

    private static String display(Object value) {
        return value == null ? "empty" : value.toString();
    }

    private static String warningText(String duties, String unavailable) {
        if (!duties.isBlank() && !unavailable.isBlank()) return "Demo duty cap and session availability need review.";
        if (!duties.isBlank()) return "Demo duty cap will not be imported.";
        if (!unavailable.isBlank()) return "Session availability will not be imported.";
        return "";
    }

    private static boolean parseActive(String value, int sourceRow) {
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "TRUE", "1", "ACTIVE" -> true;
            case "FALSE", "0", "INACTIVE" -> false;
            default -> throw new IllegalArgumentException("active must be TRUE, FALSE, 1, 0, ACTIVE, or INACTIVE.");
        };
    }

    private static Map<String, Integer> normalizedHeaders(Map<String, Integer> headers) {
        Map<String, Integer> normalized = new java.util.HashMap<>();
        for (Map.Entry<String, Integer> header : headers.entrySet()) {
            normalized.put(header.getKey().trim().toLowerCase(Locale.ROOT), header.getValue());
        }
        return normalized;
    }

    private static String chooseHeader(Map<String, Integer> headers, String first, String second) {
        if (headers.containsKey(first)) return first;
        return headers.containsKey(second) ? second : null;
    }

    private static String value(CSVRecord record, Map<String, Integer> headers, String column) {
        Integer index = headers.get(column);
        return index == null || index >= record.size() ? "" : record.get(index);
    }

    private static String sha256(Path path) throws IOException {
        try {
            byte[] bytes = Files.readAllBytes(path);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
