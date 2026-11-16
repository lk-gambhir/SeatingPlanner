package importexport;

import domain.Student;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Parses the student profile CSV without saving it. */
public final class StudentCsvImporter {
    public StudentImportPreview preview(Path path, List<Student> savedStudents) throws IOException {
        Map<String, Student> savedIds = new HashMap<>();
        Map<String, Student> savedRolls = new HashMap<>();
        for (Student student : savedStudents) {
            savedIds.put(student.studentId(), student);
            savedRolls.put(student.rollNumber(), student);
        }

        List<StudentImportPreview.Row> rows = new ArrayList<>();
        Set<String> fileIds = new HashSet<>();
        Set<String> fileRolls = new HashSet<>();
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
                for (String required : List.of("student_id", "roll_number", "full_name")) {
                    if (!headers.containsKey(required)) throw new IOException("CSV is missing required column: " + required);
                }
                for (CSVRecord record : parser) rows.add(readRow(record, headers, savedIds, savedRolls, fileIds, fileRolls));
            }
        }
        return new StudentImportPreview(path.getFileName().toString(), sha256(path), sourceHeaders, rows);
    }

    private static StudentImportPreview.Row readRow(CSVRecord record, Map<String, Integer> headers,
            Map<String, Student> savedIds, Map<String, Student> savedRolls, Set<String> fileIds, Set<String> fileRolls) {
        int row = (int) record.getRecordNumber();
        String raw = CsvImportValues.encodeRecord(record.toList());
        try {
            String id = value(record, headers, "student_id").trim();
            String roll = value(record, headers, "roll_number").trim();
            String semesterText = value(record, headers, "semester").trim();
            Integer semester = semesterText.isBlank() ? null : Integer.valueOf(semesterText);
            Student student = new Student(id, roll, value(record, headers, "full_name"),
                    value(record, headers, "program"), semester, value(record, headers, "section"),
                    parseActive(value(record, headers, "active")), value(record, headers, "data_label"), 0);
            String normalized = CsvImportValues.encodeTable(
                    List.of("student_id", "roll_number", "full_name", "program", "semester", "section", "active", "data_label"),
                    List.of(student.studentId(), student.rollNumber(), student.fullName(), text(student.program()),
                            text(student.semester()), text(student.section()), String.valueOf(student.active()), student.dataLabel()));
            boolean duplicateId = !fileIds.add(id);
            boolean duplicateRoll = !fileRolls.add(roll);
            if (duplicateId || duplicateRoll) {
                return new StudentImportPreview.Row(row, StudentImportPreview.Status.ERROR, null,
                        "Duplicate student ID or roll number in this file.", raw, normalized);
            }
            Student saved = savedIds.containsKey(id) ? savedIds.get(id) : savedRolls.get(roll);
            if (saved != null) {
                return new StudentImportPreview.Row(row, StudentImportPreview.Status.SKIP, saved,
                        describeExisting(saved, student), raw, normalized);
            }
            return new StudentImportPreview.Row(row, StudentImportPreview.Status.ADD, student, "", raw, normalized);
        } catch (NumberFormatException exception) {
            return new StudentImportPreview.Row(row, StudentImportPreview.Status.ERROR, null,
                    "Row " + row + ": semester must be a whole number, such as 1 or 2.", raw, "");
        } catch (IllegalArgumentException exception) {
            return new StudentImportPreview.Row(row, StudentImportPreview.Status.ERROR, null,
                    "Row " + row + ": " + exception.getMessage() + " Correct the named CSV field and re-import.", raw, "");
        }
    }

    private static String text(Object value) { return value == null ? "" : value.toString(); }

    private static String describeExisting(Student saved, Student incoming) {
        List<String> changes = new ArrayList<>();
        compare(changes, "student_id", saved.studentId(), incoming.studentId());
        compare(changes, "roll_number", saved.rollNumber(), incoming.rollNumber());
        compare(changes, "full_name", saved.fullName(), incoming.fullName());
        compare(changes, "program", saved.program(), incoming.program());
        compare(changes, "semester", saved.semester(), incoming.semester());
        compare(changes, "section", saved.section(), incoming.section());
        compare(changes, "active", saved.active(), incoming.active());
        compare(changes, "data_label", saved.dataLabel(), incoming.dataLabel());
        return changes.isEmpty() ? "This student profile matches saved data."
                : "Existing profile will be kept. CSV changes: " + String.join("; ", changes);
    }

    private static void compare(List<String> changes, String field, Object saved, Object incoming) {
        if (!java.util.Objects.equals(saved, incoming)) {
            changes.add(field + " [" + display(saved) + " → " + display(incoming) + "]");
        }
    }

    private static String display(Object value) {
        return value == null ? "empty" : value.toString();
    }

    private static boolean parseActive(String value) {
        if (value.isBlank()) return true;
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "TRUE", "1", "ACTIVE" -> true;
            case "FALSE", "0", "INACTIVE" -> false;
            default -> throw new IllegalArgumentException("active must be TRUE, FALSE, 1, 0, ACTIVE, or INACTIVE.");
        };
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
}
