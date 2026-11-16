package importexport;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import planning.GeneratedPlan;
import persistence.PlanRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes the two CSV lists exam staff need from a saved seating plan. */
public final class PlanCsvExporter {
    public int exportSeats(GeneratedPlan plan, int version, String sessionId, String roomId, Path path)
            throws IOException {
        try (CSVPrinter csv = new CSVPrinter(Files.newBufferedWriter(path, StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
            csv.printRecord("event_code", "plan_version", "session_id", "date", "start_time", "end_time",
                    "building", "room", "row", "column", "course_code", "candidate_type", "candidate_id");
            int count = 0;
            for (GeneratedPlan.Session session : plan.sessions()) {
                if (!session.id().equals(sessionId)) continue;
                for (GeneratedPlan.Room room : session.rooms()) {
                    if (roomId != null && !room.id().equals(roomId)) continue;
                    for (var assignment : room.assignments()) {
                        var candidate = assignment.candidate();
                        csv.printRecord(plan.eventCode(), version, session.id(), session.date(), session.startTime(),
                                session.endTime(), room.buildingCode(), room.roomCode(), assignment.seat().row(),
                                assignment.seat().column(), candidate.courseCode(),
                                candidate.studentId() == null ? "ANONYMOUS" : "NAMED", candidate.candidateKey());
                        count++;
                    }
                }
            }
            return count;
        }
    }

    public int exportInvigilators(GeneratedPlan plan, int version, String sessionId, String roomId, Path path)
            throws IOException {
        try (CSVPrinter csv = new CSVPrinter(Files.newBufferedWriter(path, StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
            csv.printRecord("event_code", "plan_version", "session_id", "date", "start_time", "end_time",
                    "building", "room", "staff_id", "required_staff", "assigned_staff_count");
            int count = 0;
            for (GeneratedPlan.Session session : plan.sessions()) {
                if (!session.id().equals(sessionId)) continue;
                for (GeneratedPlan.Room room : session.rooms()) {
                    if (roomId != null && !room.id().equals(roomId)) continue;
                    if (room.staffIds().isEmpty()) {
                        csv.printRecord(plan.eventCode(), version, session.id(), session.date(), session.startTime(),
                                session.endTime(), room.buildingCode(), room.roomCode(), "", room.staffRequired(), 0);
                        count++;
                    } else {
                        for (String staffId : room.staffIds()) {
                            csv.printRecord(plan.eventCode(), version, session.id(), session.date(), session.startTime(),
                                    session.endTime(), room.buildingCode(), room.roomCode(), staffId,
                                    room.staffRequired(), room.staffIds().size());
                            count++;
                        }
                    }
                }
            }
            return count;
        }
    }

    public int exportCourseBlocks(String eventCode, int version,
            java.util.List<PlanRepository.CourseBlockSummary> rows, Path path) throws IOException {
        try (CSVPrinter csv = new CSVPrinter(Files.newBufferedWriter(path, StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
            csv.printRecord("event_code", "plan_version", "date", "start_time", "course_code",
                    "expected_candidates", "assigned_candidates", "blocks_used", "block_distribution", "check");
            for (var row : rows) csv.printRecord(eventCode, version, row.date(), row.startTime(), row.courseCode(),
                    row.expectedCandidates(), row.assignedCandidates(), row.blocksUsed(), row.distribution(), row.status());
            return rows.size();
        }
    }

    public int exportCentreAllocations(java.util.List<PlanRepository.CentreAllocationSummary> rows, Path path)
            throws IOException {
        try (CSVPrinter csv = new CSVPrinter(Files.newBufferedWriter(path, StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
            csv.printRecord("event_code", "plan_version", "plan_status", "stale", "session_id", "date",
                    "start_time", "end_time", "centre_or_building", "building", "room",
                    "assigned_candidates", "assigned_staff", "required_staff", "check");
            for (var row : rows) {
                String check = row.stale() ? "STALE PLAN" : row.assignedStaff() < row.requiredStaff() ? "STAFF SHORTAGE" : "OK";
                csv.printRecord(row.eventCode(), row.planVersion(), row.planStatus(), row.stale(), row.sessionId(),
                        row.date(), row.startTime(), row.endTime(), row.centreName(), row.buildingCode(), row.roomCode(),
                        row.assignedCandidates(), row.assignedStaff(), row.requiredStaff(), check);
            }
            return rows.size();
        }
    }

    public int exportCourseRoomAllocations(String eventCode, int version,
            java.util.List<PlanRepository.CourseRoomAllocationSummary> rows, Path path) throws IOException {
        try (CSVPrinter csv = new CSVPrinter(Files.newBufferedWriter(path, StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
            csv.printRecord("event_code", "plan_version", "session_id", "date", "start_time", "course_code",
                    "expected_course_candidates", "assigned_in_room", "assigned_course_total", "rooms_used",
                    "building", "room", "check");
            for (var row : rows) csv.printRecord(eventCode, version, row.sessionId(), row.date(), row.startTime(),
                    row.courseCode(), row.expectedCandidates(), row.assignedInRoom(), row.assignedTotal(),
                    row.roomsUsed(), row.buildingCode(), row.roomCode(), row.status());
            return rows.size();
        }
    }
}
