package importexport;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the supplied Thapar course schedule PDFs and joins papers with their counts. */
public final class ExamSchedulePdfImporter {
    private static final Pattern SCHEDULE_ROW = Pattern.compile(
            "^\\s*(\\d{1,3})\\s+(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{1,2}:\\d{2})\\s*([AP]M)\\s*(.*)$");
    private static final Pattern COUNT_ROW = Pattern.compile(
            "^\\s*(\\d{1,3})\\s+(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{1,2}:\\d{2})\\s*([AP]M)\\s+(\\d[\\d,]*)\\s*(.*)$");
    private static final Pattern EVENT_CODE = Pattern.compile("Date Sheet Code\\s*:\\s*([A-Z0-9-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAPER = Pattern.compile("(?<![A-Z0-9])([A-Z]{1,4}\\d{2,4}[A-Z]?)\\s+(.+)");
    private static final Pattern PAPER_COUNT = Pattern.compile("([A-Z]{1,4}\\d{2,4}[A-Z]?)\\s*\\(\\s*([\\d,]+)\\s*\\)");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    public ExamImportPreview preview(Path schedulePdf, Path countPdf, int durationMinutes) throws IOException {
        if (durationMinutes < 1 || durationMinutes > 600) {
            throw new IllegalArgumentException("Exam duration must be between 1 and 600 minutes.");
        }
        List<ExamImportPreview.Diagnostic> diagnostics = new ArrayList<>();
        ParsedSchedule schedule = parseSchedule(schedulePdf, diagnostics);
        ParsedCounts counts = parseCounts(countPdf, diagnostics);
        if (schedule.eventCode() == null || counts.eventCode() == null) {
            diagnostics.add(error("MISSING_EVENT_CODE", "Both PDFs must contain a date-sheet code.", null, null));
        }
        if (schedule.eventCode() != null && counts.eventCode() != null
                && !schedule.eventCode().equalsIgnoreCase(counts.eventCode())) {
            diagnostics.add(error("EVENT_CODE_MISMATCH", "The schedule and count PDFs have different date-sheet codes.", null, null));
        }

        List<ExamImportPreview.Session> sessions = new ArrayList<>();
        for (Map.Entry<Slot, ScheduleRow> entry : schedule.rows().entrySet()) {
            Slot slot = entry.getKey();
            ScheduleRow scheduleRow = entry.getValue();
            CountRow countRow = counts.rows().get(slot);
            if (countRow == null) {
                diagnostics.add(error("MISSING_SESSION_COUNTS", "No matching course-count row for " + slot.label() + ".",
                        scheduleRow.rowNumber(), scheduleRow.page()));
                continue;
            }
            Map<String, CountPaper> papers = countRow.papers();
            List<ExamImportPreview.Paper> joined = new ArrayList<>();
            for (SchedulePaper paper : scheduleRow.papers().values()) {
                CountPaper count = papers.remove(paper.code());
                if (count == null) {
                    diagnostics.add(error("MISSING_COURSE_COUNT", "No candidate count for " + paper.code() + " on " + slot.label() + ".",
                            scheduleRow.rowNumber(), scheduleRow.page()));
                    continue;
                }
                joined.add(new ExamImportPreview.Paper(paper.code(), paper.name(), count.count(),
                        countRow.rowNumber(), countRow.page()));
            }
            for (String code : papers.keySet()) {
                diagnostics.add(error("COUNT_WITHOUT_SCHEDULE_COURSE", "Count PDF lists " + code
                        + " but the schedule PDF does not.", countRow.rowNumber(), countRow.page()));
            }
            int sum = joined.stream().mapToInt(ExamImportPreview.Paper::candidateCount).sum();
            if (sum != countRow.reportedTotal()) {
                diagnostics.add(error("SESSION_TOTAL_MISMATCH", "Course counts sum to " + sum
                        + ", while the PDF reports " + countRow.reportedTotal() + " for " + slot.label() + ".",
                        countRow.rowNumber(), countRow.page()));
            }
            if (!joined.isEmpty()) sessions.add(new ExamImportPreview.Session(slot.date(), slot.startTime(),
                    slot.startTime().plusMinutes(durationMinutes), scheduleRow.rowNumber(), scheduleRow.page(),
                    countRow.page(), countRow.reportedTotal(), String.join("\n", scheduleRow.rawLines()),
                    String.join("\n", countRow.rawLines()), joined));
        }
        for (Map.Entry<Slot, CountRow> entry : counts.rows().entrySet()) {
            if (!schedule.rows().containsKey(entry.getKey())) {
                CountRow row = entry.getValue();
                diagnostics.add(error("COUNT_WITHOUT_SCHEDULE_SESSION", "Count PDF has no matching schedule session for "
                        + entry.getKey().label() + ".", row.rowNumber(), row.page()));
            }
        }
        if (sessions.isEmpty()) diagnostics.add(error("NO_SESSIONS_PARSED", "No usable exam sessions were found in the PDFs.", null, null));

        return new ExamImportPreview(schedule.eventCode() != null ? schedule.eventCode() : counts.eventCode(),
                schedulePdf.getFileName().toString(), sha256(schedulePdf), countPdf.getFileName().toString(),
                sha256(countPdf), durationMinutes, sessions, diagnostics);
    }

    private static ParsedSchedule parseSchedule(Path path, List<ExamImportPreview.Diagnostic> diagnostics) throws IOException {
        Map<Slot, ScheduleRow> rows = new LinkedHashMap<>();
        String eventCode = null;
        ScheduleRow current = null;
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page); stripper.setEndPage(page);
                for (String line : stripper.getText(document).lines().toList()) {
                    Matcher code = EVENT_CODE.matcher(line);
                    if (code.find()) eventCode = code.group(1).trim();
                    Matcher start = SCHEDULE_ROW.matcher(line);
                    if (start.matches()) {
                        if (current != null) saveScheduleRow(rows, current, diagnostics);
                        try {
                            current = new ScheduleRow(Integer.parseInt(start.group(1)), page,
                                    new Slot(LocalDate.parse(start.group(2)), parseTime(start.group(3), start.group(4))),
                                    new LinkedHashMap<>(), new ArrayList<>());
                            current.rawLines().add(line);
                        } catch (DateTimeParseException | NumberFormatException exception) {
                            current = null;
                            diagnostics.add(error("INVALID_SCHEDULE_ROW", "Could not parse date or start time: " + line.trim(), null, page));
                        }
                        addSchedulePaper(current, start.group(5), page, diagnostics);
                    } else if (current != null) {
                        current.rawLines().add(line);
                        addSchedulePaper(current, line, page, diagnostics);
                    }
                }
            }
        }
        if (current != null) saveScheduleRow(rows, current, diagnostics);
        if (rows.isEmpty()) diagnostics.add(error("SCHEDULE_NOT_RECOGNIZED",
                "No date/time rows were found. Select the supplied 'Without Total Students' schedule PDF.", null, null));
        return new ParsedSchedule(eventCode, rows);
    }

    private static void addSchedulePaper(ScheduleRow row, String line, int page,
            List<ExamImportPreview.Diagnostic> diagnostics) {
        if (row == null) return;
        Matcher paper = PAPER.matcher(line.trim());
        if (!paper.find()) return;
        String code = paper.group(1).trim().toUpperCase(Locale.ROOT);
        String name = paper.group(2).trim();
        row.papers().putIfAbsent(code, new SchedulePaper(code, name, page));
    }

    private static void saveScheduleRow(Map<Slot, ScheduleRow> rows, ScheduleRow row,
            List<ExamImportPreview.Diagnostic> diagnostics) {
        if (row.papers().isEmpty()) {
            diagnostics.add(error("EMPTY_SCHEDULE_ROW", "No course codes found for schedule row " + row.rowNumber() + ".",
                    row.rowNumber(), row.page()));
            return;
        }
        if (rows.putIfAbsent(row.slot(), row) != null) {
            diagnostics.add(error("DUPLICATE_SCHEDULE_SESSION", "More than one schedule row has " + row.slot().label() + ".",
                    row.rowNumber(), row.page()));
        }
    }

    private static ParsedCounts parseCounts(Path path, List<ExamImportPreview.Diagnostic> diagnostics) throws IOException {
        Map<Slot, CountRow> rows = new LinkedHashMap<>();
        String eventCode = null;
        CountRow current = null;
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page); stripper.setEndPage(page);
                for (String line : stripper.getText(document).lines().toList()) {
                    Matcher code = EVENT_CODE.matcher(line);
                    if (code.find()) eventCode = code.group(1).trim();
                    Matcher start = COUNT_ROW.matcher(line);
                    if (start.matches()) {
                        if (current != null) saveCountRow(rows, current, diagnostics);
                        try {
                            current = new CountRow(Integer.parseInt(start.group(1)), page,
                                    new Slot(LocalDate.parse(start.group(2)), parseTime(start.group(3), start.group(4))),
                                    Integer.parseInt(start.group(5).replace(",", "")), new LinkedHashMap<>(), new ArrayList<>());
                            current.rawLines().add(line);
                        } catch (DateTimeParseException | NumberFormatException exception) {
                            current = null;
                            diagnostics.add(error("INVALID_COUNT_ROW", "Could not parse date, time, or reported total: " + line.trim(), null, page));
                        }
                        addCountPapers(current, start.group(6), diagnostics);
                    } else if (current != null) {
                        current.rawLines().add(line);
                        addCountPapers(current, line, diagnostics);
                    }
                }
            }
        }
        if (current != null) saveCountRow(rows, current, diagnostics);
        if (rows.isEmpty()) diagnostics.add(error("COUNTS_NOT_RECOGNIZED",
                "No session totals were found. Select the supplied 'With Total Students' PDF.", null, null));
        return new ParsedCounts(eventCode, rows);
    }

    private static void addCountPapers(CountRow row, String text, List<ExamImportPreview.Diagnostic> diagnostics) {
        if (row == null) return;
        Matcher matcher = PAPER_COUNT.matcher(text);
        while (matcher.find()) {
            String code = matcher.group(1).toUpperCase(Locale.ROOT);
            int count;
            try { count = Integer.parseInt(matcher.group(2).replace(",", "")); }
            catch (NumberFormatException exception) {
                diagnostics.add(error("INVALID_COURSE_COUNT", "Invalid count for " + code + ".", row.rowNumber(), row.page()));
                continue;
            }
            if (row.papers().putIfAbsent(code, new CountPaper(count)) != null) {
                diagnostics.add(error("DUPLICATE_COURSE_COUNT", "Course " + code + " appears more than once in this session.",
                        row.rowNumber(), row.page()));
            }
        }
    }

    private static void saveCountRow(Map<Slot, CountRow> rows, CountRow row,
            List<ExamImportPreview.Diagnostic> diagnostics) {
        if (row.papers().isEmpty()) {
            diagnostics.add(error("EMPTY_COUNT_ROW", "No course counts found for row " + row.rowNumber() + ".",
                    row.rowNumber(), row.page()));
            return;
        }
        if (rows.putIfAbsent(row.slot(), row) != null) {
            diagnostics.add(error("DUPLICATE_COUNT_SESSION", "More than one count row has " + row.slot().label() + ".",
                    row.rowNumber(), row.page()));
        }
    }

    private static LocalTime parseTime(String time, String meridiem) {
        return LocalTime.parse(time.trim() + " " + meridiem.trim().toUpperCase(Locale.ROOT), TIME);
    }

    private static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ExamImportPreview.Diagnostic error(String code, String message, Integer row, Integer page) {
        return new ExamImportPreview.Diagnostic(ExamImportPreview.Severity.ERROR, code, message, row, page);
    }

    private record Slot(LocalDate date, LocalTime startTime) { String label() { return date + " " + startTime; } }
    private record SchedulePaper(String code, String name, int page) {}
    private record CountPaper(int count) {}
    private record ParsedSchedule(String eventCode, Map<Slot, ScheduleRow> rows) {}
    private record ParsedCounts(String eventCode, Map<Slot, CountRow> rows) {}
    private record ScheduleRow(int rowNumber, int page, Slot slot, Map<String, SchedulePaper> papers, List<String> rawLines) {}
    private record CountRow(int rowNumber, int page, Slot slot, int reportedTotal,
            Map<String, CountPaper> papers, List<String> rawLines) {}
}
