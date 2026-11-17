package planning;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks generated assignments against the resource snapshot used to plan them. */
public final class PlanValidator {
    public List<Violation> validate(GeneratedPlan plan, FeasibilityAnalyzer.Snapshot snapshot) {
        List<Violation> violations = new ArrayList<>();
        Map<String, FeasibilityAnalyzer.Session> sessions = new HashMap<>();
        Map<String, FeasibilityAnalyzer.Room> rooms = new HashMap<>();
        Map<String, FeasibilityAnalyzer.Staff> staff = new HashMap<>();
        for (var session : snapshot.sessions()) sessions.put(session.id(), session);
        for (var room : snapshot.rooms()) rooms.put(room.id(), room);
        for (var person : snapshot.staff()) staff.put(person.id(), person);

        for (GeneratedPlan.Session session : plan.sessions()) {
            Set<String> candidates = new HashSet<>();
            Set<String> usedRooms = new HashSet<>();
            Set<String> usedStaff = new HashSet<>();
            Map<String, Integer> assignedByCourse = new HashMap<>();
            int roomDemand = 0;
            int assigned = 0;
            for (GeneratedPlan.Room room : session.rooms()) {
                roomDemand += room.candidateDemand();
                assigned += room.assignments().size();
                for (var assignment : room.assignments()) assignedByCourse.merge(
                        assignment.candidate().courseCode(), 1, Integer::sum);
                validateRoom(session, room, rooms.get(room.id()), staff, candidates, usedRooms, usedStaff, violations);
            }
            FeasibilityAnalyzer.Session sourceSession = sessions.get(session.id());
            if (sourceSession == null) add(violations, session.id(), null,
                    "UNKNOWN_SESSION", "The plan contains a session outside the source snapshot.");
            else {
                if (session.candidateDemand() != sourceSession.candidateCount()) add(violations, session.id(), null,
                        "SESSION_DEMAND_MISMATCH", "Plan demand does not match the source session count.");
                if (!session.date().equals(sourceSession.date().toString())
                        || !session.startTime().equals(sourceSession.startTime().toString())
                        || !session.endTime().equals(sourceSession.endTime().toString())) add(violations,
                                session.id(), null, "SESSION_TIME_MISMATCH", "Plan timing does not match the source session.");
                for (var course : sourceSession.courseDemand().entrySet()) {
                    if (!course.getValue().equals(assignedByCourse.getOrDefault(course.getKey(), 0))) add(violations,
                            session.id(), null, "COURSE_DEMAND_MISMATCH", course.getKey()
                                    + " has " + assignedByCourse.getOrDefault(course.getKey(), 0)
                                    + " assigned candidates; source demand is " + course.getValue() + ".");
                }
            }
            if (roomDemand != session.candidateDemand()) add(violations, session.id(), null,
                    "ROOM_DEMAND_MISMATCH", "Room demand totals " + roomDemand + " but the session demand is "
                            + session.candidateDemand() + ".");
            if (assigned != session.candidateDemand()) add(violations, session.id(), null,
                    "UNASSIGNED_CANDIDATES", "Expected " + session.candidateDemand() + " assignments but found "
                            + assigned + ".");
            validateSoftPreferences(session, violations);
        }

        validateScheduleConflicts(plan, snapshot.handoverMinutes(), violations);
        return List.copyOf(violations);
    }

    private static void validateRoom(GeneratedPlan.Session session, GeneratedPlan.Room room,
            FeasibilityAnalyzer.Room sourceRoom, Map<String, FeasibilityAnalyzer.Staff> staff,
            Set<String> candidates, Set<String> usedRooms, Set<String> usedStaff, List<Violation> violations) {
        if (!usedRooms.add(room.id())) add(violations, session.id(), room.id(),
                "ROOM_ASSIGNED_TWICE", "The room appears more than once in this session.");
        if (sourceRoom == null || !sourceRoom.active() || sourceRoom.unavailableSessions().contains(session.id())) {
            add(violations, session.id(), room.id(), "ROOM_UNAVAILABLE", "The room is not active or is unavailable for this session.");
        } else {
            if (room.capacity() != effectiveCapacity(sourceRoom)) add(violations, session.id(), room.id(),
                    "ROOM_CAPACITY_MISMATCH", "The plan capacity does not match the room's effective capacity.");
            if (room.candidateDemand() > effectiveCapacity(sourceRoom)) add(violations, session.id(), room.id(),
                    "ROOM_CAPACITY_EXCEEDED", "Room demand exceeds its effective capacity.");
        }
        if (room.assignments().size() > room.capacity()) add(violations, session.id(), room.id(),
                "ROOM_CAPACITY_EXCEEDED", "Assigned candidates exceed the room capacity.");

        Map<Integer, SeatAssignmentPlanner.Seat> usableSeats = new HashMap<>();
        for (var seat : room.seatGrid()) if (seat.usable()) usableSeats.put(seat.id(), seat);
        Set<Integer> seatsUsed = new HashSet<>();
        for (var assignment : room.assignments()) {
            String candidateKey = assignment.candidate().studentId() == null
                    ? assignment.candidate().candidateKey() : assignment.candidate().studentId();
            if (!candidates.add(candidateKey)) add(violations, session.id(), room.id(),
                    "CANDIDATE_ASSIGNED_TWICE", candidateKey + " is assigned more than once in this session.");
            int seatId = assignment.seat().id();
            if (!seatsUsed.add(seatId)) add(violations, session.id(), room.id(),
                    "SEAT_ASSIGNED_TWICE", "Seat " + seatId + " is assigned more than once.");
            var seat = usableSeats.get(seatId);
            if (seat == null || !seat.equals(assignment.seat())) add(violations, session.id(), room.id(),
                    "INVALID_SEAT", "An assignment uses a missing, blocked, or mismatched seat.");
        }

        int required = sourceRoom != null && sourceRoom.configuredCapacity() > 100 ? 3 : 2;
        if (room.staffRequired() != required || room.staffIds().size() != required) add(violations,
                session.id(), room.id(), "STAFF_COUNT_MISMATCH", "This room requires " + required + " invigilators.");
        Set<String> courses = room.assignments().stream().map(item -> item.candidate().courseCode())
                .collect(java.util.stream.Collectors.toSet());
        for (String staffId : room.staffIds()) {
            var person = staff.get(staffId);
            if (!usedStaff.add(staffId)) add(violations, session.id(), room.id(),
                    "STAFF_ASSIGNED_TWICE", staffId + " appears more than once in this session.");
            if (person == null || !person.active() || person.absentSessions().contains(session.id())
                    || person.unavailableSessions().contains(session.id())) add(violations, session.id(), room.id(),
                            "STAFF_UNAVAILABLE", staffId + " is inactive, absent, or unavailable.");
            else if (courses.stream().anyMatch(person.courseCodes()::contains)) add(violations, session.id(), room.id(),
                    "STAFF_COURSE_CONFLICT", staffId + " is affiliated with a course examined in this room.");
        }
    }

    private static void validateScheduleConflicts(GeneratedPlan plan, int handoverMinutes,
            List<Violation> violations) {
        for (int i = 0; i < plan.sessions().size(); i++) for (int j = i + 1; j < plan.sessions().size(); j++) {
            GeneratedPlan.Session first = plan.sessions().get(i), second = plan.sessions().get(j);
            if (!first.date().equals(second.date())) continue;
            LocalDateTime firstStart = at(first.date(), first.startTime());
            LocalDateTime secondStart = at(second.date(), second.startTime());
            LocalDateTime firstRelease = at(first.date(), first.endTime()).plusMinutes(handoverMinutes);
            LocalDateTime secondRelease = at(second.date(), second.endTime()).plusMinutes(handoverMinutes);
            boolean resourceOverlap = firstStart.isBefore(secondRelease) && secondStart.isBefore(firstRelease);
            boolean examOverlap = firstStart.isBefore(at(second.date(), second.endTime()))
                    && secondStart.isBefore(at(first.date(), first.endTime()));
            if (!resourceOverlap && !examOverlap) continue;
            Set<String> firstRooms = first.rooms().stream().map(GeneratedPlan.Room::id)
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> firstStaff = first.rooms().stream().flatMap(room -> room.staffIds().stream())
                    .collect(java.util.stream.Collectors.toSet());
            if (resourceOverlap) {
                for (GeneratedPlan.Room room : second.rooms()) if (firstRooms.contains(room.id())) add(violations,
                        second.id(), room.id(), "ROOM_SESSION_CONFLICT", "The room is reused before its handover window ends.");
                for (GeneratedPlan.Room room : second.rooms()) for (String staffId : room.staffIds())
                    if (firstStaff.contains(staffId)) add(violations, second.id(), room.id(),
                            "STAFF_SESSION_CONFLICT", staffId + " is assigned to overlapping sessions.");
            }

            Map<String, String> firstStudents = new HashMap<>();
            for (GeneratedPlan.Room room : first.rooms()) for (var assignment : room.assignments())
                if (assignment.candidate().studentId() != null) firstStudents.put(assignment.candidate().studentId(), assignment.candidate().courseCode());
            if (examOverlap) for (GeneratedPlan.Room room : second.rooms()) for (var assignment : room.assignments()) {
                String studentId = assignment.candidate().studentId();
                if (studentId != null && firstStudents.containsKey(studentId)) add(violations, second.id(), room.id(),
                        "STUDENT_SESSION_CLASH", studentId + " is assigned to overlapping exams "
                                + firstStudents.get(studentId) + " and " + assignment.candidate().courseCode() + ".");
            }
        }
    }

    private static void validateSoftPreferences(GeneratedPlan.Session session, List<Violation> violations) {
        Map<String, Set<String>> blocksByCourse = new HashMap<>();
        Map<String, Set<String>> roomsByCourse = new HashMap<>();
        for (GeneratedPlan.Room room : session.rooms()) {
            List<SeatAssignmentPlanner.Assignment> assignments = room.assignments();
            for (int i = 0; i < assignments.size(); i++) for (int j = i + 1; j < assignments.size(); j++) {
                var first = assignments.get(i);
                var second = assignments.get(j);
                if (first.candidate().courseCode().equals(second.candidate().courseCode())
                        && Math.abs(first.seat().row() - second.seat().row())
                                + Math.abs(first.seat().column() - second.seat().column()) == 1) {
                    addWarning(violations, session.id(), room.id(), "SAME_COURSE_ORTHOGONAL_ADJACENCY",
                            first.candidate().candidateKey() + " and " + second.candidate().candidateKey()
                                    + " are same-course orthogonal neighbors at " + first.seat().row() + ","
                                    + first.seat().column() + " and " + second.seat().row() + ","
                                    + second.seat().column() + ".");
                }
                String course = first.candidate().courseCode();
                blocksByCourse.computeIfAbsent(course, ignored -> new HashSet<>()).add(room.buildingCode());
                roomsByCourse.computeIfAbsent(course, ignored -> new HashSet<>())
                        .add(room.buildingCode() + " " + room.roomCode());
            }
            for (var assignment : assignments) {
                String course = assignment.candidate().courseCode();
                blocksByCourse.computeIfAbsent(course, ignored -> new HashSet<>()).add(room.buildingCode());
                roomsByCourse.computeIfAbsent(course, ignored -> new HashSet<>())
                        .add(room.buildingCode() + " " + room.roomCode());
            }
        }
        blocksByCourse.forEach((course, blocks) -> {
            if (blocks.size() > 1) addWarning(violations, session.id(), null, "COURSE_SPLIT_ACROSS_BLOCKS",
                    course + " is assigned across blocks " + blocks + " in rooms " + roomsByCourse.get(course) + ".");
        });
    }

    private static int effectiveCapacity(FeasibilityAnalyzer.Room room) {
        return room.seatRecordCount() == 0 ? room.configuredCapacity()
                : Math.min(room.configuredCapacity(), room.usableSeatCount());
    }

    private static LocalDateTime at(String date, String time) {
        return LocalDateTime.of(java.time.LocalDate.parse(date), LocalTime.parse(time));
    }

    private static void add(List<Violation> violations, String sessionId, String roomId, String code, String message) {
        violations.add(new Violation(sessionId, roomId, "BLOCKER", code, message));
    }

    private static void addWarning(List<Violation> violations, String sessionId, String roomId, String code, String message) {
        violations.add(new Violation(sessionId, roomId, "WARNING", code, message));
    }

    public record Violation(String sessionId, String roomId, String severity, String code, String message) {}
}
