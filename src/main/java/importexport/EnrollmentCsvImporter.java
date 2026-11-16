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
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reads enrollment rows and checks them against one selected event and its saved records. */
public final class EnrollmentCsvImporter {
    public EnrollmentImportPreview preview(Path path, Context context) throws IOException {
        ReadResult source = read(path);
        List<RawRow> rawRows = source.rows();
        Set<String> sourcePeriods = new HashSet<>();
        for (RawRow row : rawRows) if (!row.sourceSessionId().isBlank()) sourcePeriods.add(row.sourceSessionId());

        List<EnrollmentImportPreview.Row> rows = new ArrayList<>();
        List<EnrollmentImportPreview.Diagnostic> diagnostics = new ArrayList<>();
        Set<String> fileIds = new HashSet<>();
        Set<String> fileRegistrationKeys = new HashSet<>();
        Map<String, Integer> addedRegisteredByCourse = new HashMap<>();

        if (sourcePeriods.size() != 1) diagnostics.add(new EnrollmentImportPreview.Diagnostic(
                "ERROR", "SOURCE_EVENT_KEY", "Every row needs the same non-empty session_id; use the event code shown in the Students dashboard."));
        String sourceSessionId = sourcePeriods.size() == 1 ? sourcePeriods.iterator().next() : "";

        for (RawRow raw : rawRows) {
            rows.add(validate(raw, context, sourceSessionId, fileIds, fileRegistrationKeys, addedRegisteredByCourse));
        }
        for (Map.Entry<String, List<CourseTarget>> entry : context.courses().entrySet()) {
            if (entry.getValue().size() != 1) continue;
            CourseTarget course = entry.getValue().getFirst();
            int total = context.registeredCountByCourse().getOrDefault(entry.getKey(), 0)
                    + addedRegisteredByCourse.getOrDefault(entry.getKey(), 0);
            if (total != course.candidateCount()) diagnostics.add(new EnrollmentImportPreview.Diagnostic(
                    "WARNING", "CANDIDATE_COUNT_MISMATCH", entry.getKey() + " has " + total
                            + " registered students; the PDF count is " + course.candidateCount() + "."));
        }
        return new EnrollmentImportPreview(path.getFileName().toString(), sha256(path), source.headers(), context.eventCode(),
                sourceSessionId, rows, diagnostics);
    }

    private static EnrollmentImportPreview.Row validate(RawRow raw, Context context, String sourceSessionId,
            Set<String> fileIds, Set<String> fileRegistrationKeys, Map<String, Integer> addedRegisteredByCourse) {
        String message = "";
        String registrationStatus = raw.status().trim().toUpperCase(Locale.ROOT);
        if (raw.sourceSessionId().isBlank() || !raw.sourceSessionId().equals(sourceSessionId)) {
            message = "session_id is missing or inconsistent; use the same saved event code on every row.";
        } else if (raw.enrollmentId().isBlank() || raw.studentId().isBlank() || raw.courseCode().isBlank()) {
            message = "enrollment_id, student_id, and course_id are required; fill each column with its saved ID or course code.";
        } else if (!fileIds.add(raw.enrollmentId())) {
            message = "Duplicate enrollment_id in this file.";
        } else if (!context.students().contains(raw.studentId())) {
            message = "Unknown student_id " + raw.studentId() + "; import that student's profile first or correct the ID.";
        } else if (registrationStatus.equals("REGISTERED") && !context.activeStudents().contains(raw.studentId())) {
            message = "Student " + raw.studentId() + " is inactive; reactivate the profile or mark this row WITHDRAWN/INACTIVE.";
        }

        List<CourseTarget> targets = context.courses().getOrDefault(raw.courseCode(), List.of());
        if (message.isEmpty() && targets.isEmpty()) message = "course_id " + raw.courseCode() + " is not in the selected event; check the course code or event.";
        if (message.isEmpty() && targets.size() != 1) message = "course_id " + raw.courseCode() + " appears in multiple sessions; a course-only enrollment row is ambiguous.";
        if (message.isEmpty() && !List.of("REGISTERED", "WITHDRAWN", "INACTIVE").contains(registrationStatus)) {
            message = "status must be REGISTERED, WITHDRAWN, or INACTIVE.";
        }
        if (!message.isEmpty()) return row(raw, EnrollmentImportPreview.Status.ERROR, null, registrationStatus, message);

        CourseTarget target = targets.getFirst();
        String key = key(raw.studentId(), target.sessionCourseId());
        if (!fileRegistrationKeys.add(key)) return row(raw, EnrollmentImportPreview.Status.ERROR,
                target.sessionCourseId(), registrationStatus, "Duplicate student/course registration in this file.");
        ExistingEnrollment existingById = context.existingById().get(raw.enrollmentId());
        if (existingById != null && !existingById.registrationKey().equals(key)) return row(raw,
                EnrollmentImportPreview.Status.ERROR, target.sessionCourseId(), registrationStatus,
                "This enrollment_id is already used for a different registration.");
        if (context.existingKeys().contains(key)) return row(raw, EnrollmentImportPreview.Status.SKIP,
                target.sessionCourseId(), registrationStatus, "Registration already exists; saved row will be kept.");

        if (registrationStatus.equals("REGISTERED")) addedRegisteredByCourse.merge(raw.courseCode(), 1, Integer::sum);
        return row(raw, EnrollmentImportPreview.Status.ADD, target.sessionCourseId(), registrationStatus, "");
    }

    private static EnrollmentImportPreview.Row row(RawRow raw, EnrollmentImportPreview.Status status,
            String sessionCourseId, String registrationStatus, String message) {
        return new EnrollmentImportPreview.Row(raw.sourceRow(), status, raw.enrollmentId(), raw.studentId(),
                raw.courseCode(), sessionCourseId, registrationStatus,
                raw.dataLabel().isBlank() ? "OPERATIONAL" : raw.dataLabel().trim(), message,
                raw.rawValues(), CsvImportValues.encodeTable(
                        List.of("enrollment_id", "student_id", "course_code", "session_course_id", "status", "data_label"),
                        List.of(raw.enrollmentId(), raw.studentId(), raw.courseCode(), sessionCourseId == null ? "" : sessionCourseId,
                                registrationStatus, raw.dataLabel().isBlank() ? "OPERATIONAL" : raw.dataLabel().trim())));
    }

    private static ReadResult read(Path path) throws IOException {
        List<RawRow> rows = new ArrayList<>();
        String sourceHeaders;
        try (PushbackReader reader = new PushbackReader(Files.newBufferedReader(path, StandardCharsets.UTF_8), 1)) {
            int first = reader.read();
            if (first != 0xFEFF && first != -1) reader.unread(first);
            CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
            try (CSVParser parser = format.parse(reader)) {
                sourceHeaders = CsvImportValues.encodeHeaders(parser.getHeaderMap());
                Map<String, Integer> headers = new HashMap<>();
                for (Map.Entry<String, Integer> header : parser.getHeaderMap().entrySet()) {
                    headers.put(header.getKey().trim().toLowerCase(Locale.ROOT), header.getValue());
                }
                for (String required : List.of("enrollment_id", "student_id", "course_id", "session_id", "status")) {
                    if (!headers.containsKey(required)) throw new IOException("CSV is missing required column: " + required);
                }
                for (CSVRecord record : parser) rows.add(new RawRow((int) record.getRecordNumber(),
                        value(record, headers, "enrollment_id").trim(), value(record, headers, "student_id").trim(),
                        value(record, headers, "course_id").trim().toUpperCase(Locale.ROOT), value(record, headers, "session_id").trim(),
                        value(record, headers, "status").trim(), value(record, headers, "data_label").trim(),
                        CsvImportValues.encodeRecord(record.toList())));
            }
        }
        return new ReadResult(sourceHeaders, rows);
    }

    private static String value(CSVRecord record, Map<String, Integer> headers, String name) {
        Integer index = headers.get(name);
        return index == null || index >= record.size() ? "" : record.get(index);
    }

    private static String key(String studentId, String sessionCourseId) { return studentId + "\u0000" + sessionCourseId; }

    private static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String registrationKey(String studentId, String sessionCourseId) {
        return key(studentId, sessionCourseId);
    }

    public record Context(String eventCode, Map<String, List<CourseTarget>> courses, Set<String> students,
            Set<String> activeStudents, Set<String> existingKeys, Map<String, ExistingEnrollment> existingById,
            Map<String, Integer> registeredCountByCourse) {}
    public record CourseTarget(String sessionCourseId, int candidateCount) {}
    public record ExistingEnrollment(String registrationKey) {}
    private record ReadResult(String headers, List<RawRow> rows) {}
    private record RawRow(int sourceRow, String enrollmentId, String studentId, String courseCode,
            String sourceSessionId, String status, String dataLabel, String rawValues) {}
}
