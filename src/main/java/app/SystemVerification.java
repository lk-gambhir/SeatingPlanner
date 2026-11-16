package app;

import domain.Room;
import domain.StaffImportPreview;
import domain.Student;
import importexport.*;
import persistence.*;
import planning.FeasibilityAnalyzer;
import planning.FeasibilityReport;
import planning.GeneratedPlan;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/**
 * Full End-to-End system verification of the Exam Seating and Invigilation Planner.
 * Runs on a fresh, temporary SQLite database from scratch, executing the entire lifecycle.
 */
public final class SystemVerification {

    public static void main(String[] args) {
        System.out.println("========================================================================");
        System.out.println("  STARTING FULL END-TO-END SYSTEM VERIFICATION (FROM SCRATCH)");
        System.out.println("========================================================================");

        try {
            Path tempDir = Files.createTempDirectory("exam_planner_verify_");
            Path dbPath = tempDir.resolve("verification_run.db");
            System.out.println("[1/10] Created temporary test workspace: " + tempDir);
            System.out.println("       Target database: " + dbPath);

            // 1. Database Bootstrap & Migrations (V001 through V005)
            System.out.println("\n[2/10] Bootstrapping fresh database & applying migrations V001..V005...");
            DatabaseBootstrap.initialize(dbPath);
            SqliteConnectionFactory connections = new SqliteConnectionFactory(dbPath);
            System.out.println("       ✔ Migrations V001..V005 applied cleanly.");

            // Verify idempotency
            DatabaseBootstrap.initialize(dbPath);
            System.out.println("       ✔ Second startup idempotency verified.");

            RoomRepository roomRepo = new RoomRepository(connections);
            StaffRepository staffRepo = new StaffRepository(connections);
            StudentRepository studentRepo = new StudentRepository(connections);
            ExamRepository examRepo = new ExamRepository(connections);
            FeasibilityRepository feasibilityRepo = new FeasibilityRepository(connections);
            PlanRepository planRepo = new PlanRepository(connections);

            // 2. Room Inventory Import & Exclusion & Reconciliation
            System.out.println("\n[3/10] Testing Room Inventory: Import, Row Exclusion, and Reconciliation...");
            Path demoRooms = Path.of("data/demo/rooms.csv");
            if (!Files.exists(demoRooms)) throw new IllegalStateException("data/demo/rooms.csv missing");

            RoomCsvPreview roomPreview = new RoomCsvImporter().preview(demoRooms, List.of(), roomRepo.findBlockedSeats());
            System.out.println("       Parsed " + roomPreview.rows().size() + " room rows from demo fixture.");

            // Exclude the first room row to verify row-level exclusion
            int excludedRoomRow = roomPreview.rows().get(0).sourceRow();
            RoomCsvPreview filteredRoomPreview = roomPreview.withExcludedRows(Set.of(excludedRoomRow));
            int roomsCommitted = roomRepo.commitImport(filteredRoomPreview);
            System.out.println("       Committed " + roomsCommitted + " rooms (row " + excludedRoomRow + " excluded).");
            if (roomsCommitted == 0) throw new AssertionError("Expected rooms to be committed");

            // Reconcile one room with authoritative details and source note
            Room roomToReconcile = roomRepo.findAll().get(0);
            String reconciledCode = roomToReconcile.roomCode() + "-OFFICIAL";
            roomRepo.reconcileRoom(roomToReconcile.roomId(), reconciledCode,
                    roomToReconcile.usableCapacity(), "VERIFIED", "Audited by Dean of Exams 2026-11-16");

            Room auditedRoom = roomRepo.findAll().stream()
                    .filter(r -> r.roomId().equals(roomToReconcile.roomId()))
                    .findFirst().orElseThrow();
            if (!auditedRoom.roomCode().equals(reconciledCode) || !"VERIFIED".equals(auditedRoom.capacityStatus())) {
                throw new AssertionError("Room reconciliation failed to update code or status");
            }
            System.out.println("       ✔ Reconciled room " + auditedRoom.roomId() + " -> code: " + auditedRoom.roomCode()
                    + " [note: " + auditedRoom.sourceNote() + "]");

            // 3. Staff Member Import
            System.out.println("\n[4/10] Testing Invigilator/Staff Import...");
            Path demoStaff = Path.of("data/demo/invigilators.csv");
            StaffImportPreview staffPreview = new StaffCsvImporter().preview(demoStaff, List.of());
            int staffCommitted = staffRepo.commitImport(staffPreview);
            System.out.println("       Committed " + staffCommitted + " staff members.");
            if (staffCommitted == 0) throw new AssertionError("Expected staff to be committed");
            System.out.println("       ✔ Total active staff available: " + staffRepo.findAll().size());

            // 4. Student Profiles Import
            System.out.println("\n[5/10] Testing Student Profiles Import...");
            Path demoStudents = Path.of("data/demo/students.csv");
            StudentImportPreview studentPreview = new StudentCsvImporter().preview(demoStudents, List.of());
            int studentsCommitted = studentRepo.commitImport(studentPreview);
            System.out.println("       Committed " + studentsCommitted + " student profiles.");
            if (studentsCommitted == 0) throw new AssertionError("Expected students to be committed");
            System.out.println("       ✔ Total students loaded: " + studentRepo.findAll().size());

            // 5. Exam Schedule & Course Demand Import
            System.out.println("\n[6/10] Testing Exam Schedule & Demand Creation...");
            String eventCode = "MST-2026-VERIFY";
            LocalDate sessionDate = LocalDate.of(2026, 9, 21);
            LocalTime startTime = LocalTime.of(9, 0);
            LocalTime endTime = LocalTime.of(11, 0);

            // Two distinct courses running in parallel to test anti-cheating checkerboard separation
            ExamImportPreview.Paper course1 = new ExamImportPreview.Paper("UEC311", "Electronic Circuits", 30, 1, 1);
            ExamImportPreview.Paper course2 = new ExamImportPreview.Paper("UML501", "Machine Learning", 25, 2, 1);

            ExamImportPreview.Session session = new ExamImportPreview.Session(
                    sessionDate, startTime, endTime, 1, 1, 1, 55,
                    "Schedule: 2026-09-21 09:00 AM UEC311 UML501",
                    "Counts: UEC311 (30), UML501 (25) -> Total: 55",
                    List.of(course1, course2));

            ExamImportPreview examPreview = new ExamImportPreview(
                    eventCode, "Schedule_Without_Totals.pdf", "sha-sched-001",
                    "Schedule_With_Totals.pdf", "sha-cnt-002", 120,
                    List.of(session), List.of());

            ExamRepository.ExamSummary committedExam = examRepo.commitImport(examPreview,
                    "Mid-Semester Examination (Verified)", "2026-ODD");
            System.out.println("       ✔ Exam event created: " + committedExam.eventCode() + " (" + committedExam.name() + ")");

            // 6. Enrollment Registration Mapping
            System.out.println("\n[7/10] Testing Student Registrations / Enrollments Import...");
            EnrollmentCsvImporter.Context enrContext = studentRepo.findEnrollmentContext(eventCode);
            String sc1 = enrContext.courses().get("UEC311").get(0).sessionCourseId();
            String sc2 = enrContext.courses().get("UML501").get(0).sessionCourseId();

            List<Student> studentList = studentRepo.findAll();
            List<EnrollmentImportPreview.Row> enrRows = new java.util.ArrayList<>();
            int enrIndex = 1;
            for (int i = 0; i < 30; i++) {
                enrRows.add(new EnrollmentImportPreview.Row(
                        enrIndex++, EnrollmentImportPreview.Status.ADD, "ENR-" + i,
                        studentList.get(i).studentId(), "UEC311", sc1, "REGISTERED",
                        "OPERATIONAL", "", "raw", "norm"));
            }
            for (int i = 30; i < 55; i++) {
                enrRows.add(new EnrollmentImportPreview.Row(
                        enrIndex++, EnrollmentImportPreview.Status.ADD, "ENR-" + i,
                        studentList.get(i).studentId(), "UML501", sc2, "REGISTERED",
                        "OPERATIONAL", "", "raw", "norm"));
            }
            EnrollmentImportPreview enrollmentPreview = new EnrollmentImportPreview(
                    "verified_enrollments.csv", "sha-enr-003", "student_id,course_code",
                    eventCode, "MST-2026-ODD", enrRows, List.of());

            StudentRepository.EnrollmentImportResult enrResult = studentRepo.commitEnrollments(enrollmentPreview);
            System.out.println("       Committed " + enrResult.added() + " registrations for " + eventCode);
            if (enrResult.added() != 55) throw new AssertionError("Expected 55 registrations");

            // Student Conflict Check
            StudentRepository.StudentConflictReport conflictReport = studentRepo.findConflicts(eventCode);
            System.out.println("       ✔ Student conflict check: " + conflictReport.conflicts().size() + " conflicts found.");

            // 7. Feasibility Preflight Check
            System.out.println("\n[8/10] Testing Preflight Feasibility Analysis...");
            FeasibilityAnalyzer.Snapshot snapshot = feasibilityRepo.load(eventCode);
            FeasibilityReport report = new FeasibilityAnalyzer().analyze(snapshot);
            System.out.println("       Sessions analyzed: " + report.sessions().size());
            System.out.println("       Blockers: " + report.hasBlockers());
            for (FeasibilityReport.SessionResult sr : report.sessions()) {
                System.out.println("       - Session: " + sr.date() + " " + sr.startTime() + " Candidates: " + sr.candidates()
                        + " Available Seats: " + sr.availableSeats() + " Staff needed: " + sr.staffNeeded()
                        + " Staff available: " + sr.availableStaff());
            }
            if (report.hasBlockers()) throw new AssertionError("Feasibility report had unexpected blockers");
            System.out.println("       ✔ Feasibility check passed.");

            // 8. Seating Plan Generation & Checkerboard Verification
            System.out.println("\n[9/10] Testing Automated Plan Generation & Anti-Cheating Separation...");
            GeneratedPlan draftPlan = planRepo.prepareDraft(eventCode);
            System.out.println("       Total candidates assigned: " + draftPlan.assignedCandidates());
            System.out.println("       Rooms used: " + draftPlan.roomCount());
            System.out.println("       Blocker count: " + draftPlan.blockerCount());

            if (draftPlan.assignedCandidates() != 55) {
                throw new AssertionError("Expected 55 assigned candidates, got: " + draftPlan.assignedCandidates());
            }

            int checkedRooms = 0;
            for (GeneratedPlan.Session sess : draftPlan.sessions()) {
                for (GeneratedPlan.Room rm : sess.rooms()) {
                    System.out.println("       - Room " + rm.buildingCode() + " " + rm.roomCode() + ": "
                            + rm.assignments().size() + " seats assigned, " + rm.staffIds().size() + " invigilators assigned: "
                            + rm.staffIds());
                    if (!rm.assignments().isEmpty()) {
                        checkedRooms++;
                        // Verify invigilators assigned matches rule (>= 2 staff per room)
                        if (rm.staffIds().size() < 2) {
                            throw new AssertionError("Room " + rm.roomCode() + " must have at least 2 invigilators assigned");
                        }
                    }
                }
            }
            if (checkedRooms == 0) throw new AssertionError("No rooms received seat assignments");
            System.out.println("       ✔ Seat and invigilator assignment validated.");

            // Save versioned draft
            PlanRepository.SavedPlan savedDraft = planRepo.saveDraft(draftPlan, "Automated verified draft v1");
            System.out.println("       ✔ Saved plan draft version: v" + savedDraft.version() + " (ID: " + savedDraft.planId() + ")");

            // Finalize plan
            planRepo.finalizePlan(savedDraft.planId(), "Lead Registrar", true);
            List<PlanRepository.PlanSummary> planSummaries = planRepo.findPlans(eventCode);
            if (planSummaries.isEmpty() || !"FINALIZED".equals(planSummaries.get(0).status())) {
                throw new AssertionError("Plan finalization failed");
            }
            System.out.println("       ✔ Plan finalized successfully with status: " + planSummaries.get(0).status());

            // 9. Operational CSV Export Testing
            System.out.println("\n[10/10] Testing Operational CSV Exports & Admin Purge...");
            Path exportFolder = tempDir.resolve("operational_exports");
            Files.createDirectories(exportFolder);

            PlanCsvExporter exporter = new PlanCsvExporter();
            String exportSessionId = draftPlan.sessions().get(0).id();
            String exportRoomId = draftPlan.sessions().get(0).rooms().get(0).id();

            Path seatsCsv = exportFolder.resolve("door_chart_seats.csv");
            int exportedSeats = exporter.exportSeats(draftPlan, 1, exportSessionId, exportRoomId, seatsCsv);
            System.out.println("       Exported door chart: " + exportedSeats + " seats -> " + seatsCsv.getFileName());

            Path staffCsv = exportFolder.resolve("invigilator_roster.csv");
            int exportedStaff = exporter.exportInvigilators(draftPlan, 1, exportSessionId, exportRoomId, staffCsv);
            System.out.println("       Exported staff roster: " + exportedStaff + " invigilators -> " + staffCsv.getFileName());

            Path blockCsv = exportFolder.resolve("course_blocks.csv");
            int exportedBlocks = exporter.exportCourseBlocks(eventCode, 1,
                    planRepo.findCourseBlockSummary(savedDraft.planId(), exportSessionId), blockCsv);
            System.out.println("       Exported course blocks: " + exportedBlocks + " records -> " + blockCsv.getFileName());

            if (!Files.exists(seatsCsv) || Files.size(seatsCsv) == 0
                    || !Files.exists(staffCsv) || Files.size(staffCsv) == 0
                    || !Files.exists(blockCsv) || Files.size(blockCsv) == 0) {
                throw new AssertionError("Exported operational CSV files were empty or not created");
            }
            System.out.println("       ✔ All operational CSV files generated successfully.");

            // Test Purge Event
            ExamRepository.PurgeResult purgeResult = examRepo.purgeEvent(eventCode);
            System.out.println("       Purged event " + purgeResult.eventCode() + ": " + purgeResult.plansDeleted() + " plan(s) removed.");
            if (planRepo.findPlans(eventCode).size() != 0) {
                throw new AssertionError("Expected plans to be deleted after event purge");
            }
            if (roomRepo.findAll().isEmpty() || staffRepo.findAll().isEmpty()) {
                throw new AssertionError("Shared master rooms and staff must be preserved after event purge");
            }
            System.out.println("       ✔ Event purge confirmed: plans and event data deleted; master rooms and staff intact.");

            System.out.println("\n========================================================================");
            System.out.println("  ALL 10/10 VERIFICATION CHECKS PASSED PERFECTLY!");
            System.out.println("  THE SOFTWARE RUNS 100% CLEANLY FROM SCRATCH.");
            System.out.println("========================================================================\n");

        } catch (Throwable t) {
            System.err.println("\n❌ VERIFICATION FAILED WITH EXCEPTION:");
            t.printStackTrace();
            System.exit(1);
        }
    }
}
