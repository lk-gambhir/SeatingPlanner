package planning;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compares event demand with configured room and staff resources. */
public final class FeasibilityAnalyzer {
    public FeasibilityReport analyze(Snapshot snapshot) {
        List<FeasibilityReport.SessionResult> results = new ArrayList<>();
        Map<String, LocalDateTime> roomUseUntil = new HashMap<>();
        Map<String, LocalDateTime> staffUseUntil = new HashMap<>();
        List<Session> sessions = snapshot.sessions().stream()
                .sorted(Comparator.comparing(Session::date).thenComparing(Session::startTime).thenComparing(Session::id))
                .toList();
        for (Session session : sessions) {
            LocalDateTime start = LocalDateTime.of(session.date(), session.startTime());
            Set<String> occupiedRooms = usedAt(roomUseUntil, start);
            Set<String> occupiedStaff = usedAt(staffUseUntil, start);
            FeasibilityReport.SessionResult result = analyzeSession(snapshot, session, occupiedRooms, occupiedStaff);
            results.add(result);
            LocalDateTime released = LocalDateTime.of(session.date(), session.endTime()).plusMinutes(snapshot.handoverMinutes());
            for (FeasibilityReport.RoomUse room : result.selectedRooms()) roomUseUntil.put(room.roomId(), released);
            for (FeasibilityReport.RoomUse room : result.selectedRooms()) {
                for (String staffId : room.staffIds()) staffUseUntil.put(staffId, released);
            }
        }
        return new FeasibilityReport(snapshot.eventCode(), snapshot.sourceRevision(), results);
    }

    private static FeasibilityReport.SessionResult analyzeSession(Snapshot snapshot, Session session,
            Set<String> occupiedRooms, Set<String> occupiedStaff) {
        List<FeasibilityReport.Diagnostic> diagnostics = new ArrayList<>();
        if (session.candidateCount() <= 0 || session.courseCodes().isEmpty()) diagnostics.add(diagnostic("BLOCKER",
                "EMPTY_SESSION_DEMAND", "This session has no course papers with candidate demand."));
        List<Room> allAvailable = snapshot.rooms().stream().filter(Room::active)
                .filter(room -> !room.unavailableSessions().contains(session.id()))
                .map(FeasibilityAnalyzer::withEffectiveCapacity).filter(room -> room.effectiveCapacity() > 0)
                .sorted(Comparator.comparingInt(Room::effectiveCapacity).thenComparing(Room::id)).toList();
        List<Room> free = allAvailable.stream().filter(room -> !occupiedRooms.contains(room.id())).toList();
        int allCapacity = allAvailable.stream().mapToInt(Room::effectiveCapacity).sum();
        int availableCapacity = free.stream().mapToInt(Room::effectiveCapacity).sum();
        if (free.isEmpty()) diagnostics.add(diagnostic("BLOCKER", "NO_AVAILABLE_ROOMS",
                "No active, available room is free for this session."));
        if (availableCapacity < session.candidateCount()) {
            boolean handoverConflict = allCapacity >= session.candidateCount();
            diagnostics.add(diagnostic("BLOCKER", handoverConflict ? "ROOM_HANDOVER_SHORTFALL" : "ROOM_CAPACITY_SHORTFALL",
                    "Demand is " + session.candidateCount() + "; free capacity is " + availableCapacity
                            + "; shortfall is " + (session.candidateCount() - availableCapacity)
                            + (handoverConflict ? ". Some rooms are still in their handover window." : ".")));
        }

        Map<String, Integer> courseDemand = session.courseDemand();
        if (courseDemand.isEmpty() && !session.courseCodes().isEmpty()) {
            courseDemand = Map.of(session.courseCodes().getFirst(), session.candidateCount());
        }
        RoomAllocationPlanner.Result allocation = new RoomAllocationPlanner().allocate(courseDemand, free);
        List<Room> selected = free.stream().filter(room -> allocation.coursesByRoom().containsKey(room.id())).toList();
        diagnostics.addAll(allocation.diagnostics());
        for (Room room : selected) addRoomWarnings(room, diagnostics);
        int staffNeeded = selected.stream().mapToInt(room -> room.configuredCapacity() > 100 ? 3 : 2).sum();
        List<Staff> active = snapshot.staff().stream().filter(Staff::active)
                .filter(staff -> !staff.unavailableSessions().contains(session.id()))
                .filter(staff -> !occupiedStaff.contains(staff.id())).sorted(Comparator.comparing(Staff::id)).toList();
        int potentiallyEligible = (int) active.stream().filter(staff -> selected.stream().anyMatch(room ->
                eligibleForRoom(staff, allocation.coursesByRoom().getOrDefault(room.id(), List.of())))).count();
        if (!session.courseCodes().isEmpty() && snapshot.staff().stream().noneMatch(staff -> !staff.courseCodes().isEmpty())) {
            diagnostics.add(diagnostic("WARNING", "SUBJECT_ELIGIBILITY_NOT_CONFIGURED",
                    "No staff/course relationships exist yet; subject exclusions cannot be confirmed."));
        }
        if (active.size() < staffNeeded) diagnostics.add(diagnostic("BLOCKER", "STAFF_CAPACITY_SHORTFALL",
                "The room estimate needs " + staffNeeded + " invigilators; " + active.size()
                        + " active staff are available and free for this session."));
        Map<String, List<String>> staffByRoom = new HashMap<>();
        Set<String> assignedStaff = new HashSet<>();
        List<Room> staffingOrder = selected.stream().sorted(Comparator
                .comparingInt((Room room) -> (int) active.stream().filter(staff -> eligibleForRoom(staff,
                        allocation.coursesByRoom().getOrDefault(room.id(), List.of()))).count())
                .thenComparing(Room::id)).toList();
        for (Room room : staffingOrder) {
            int required = room.configuredCapacity() > 100 ? 3 : 2;
            List<String> roomCourses = allocation.coursesByRoom().getOrDefault(room.id(), List.of()).stream()
                    .map(FeasibilityReport.CoursePortion::courseCode).distinct().toList();
            List<String> assigned = active.stream()
                    .filter(staff -> eligibleForRoom(staff, allocation.coursesByRoom().getOrDefault(room.id(), List.of())))
                    .filter(staff -> !assignedStaff.contains(staff.id())).limit(required).map(Staff::id).toList();
            assignedStaff.addAll(assigned);
            staffByRoom.put(room.id(), assigned);
            if (assigned.size() < required) diagnostics.add(diagnostic("BLOCKER", "STAFF_COURSE_ELIGIBILITY_SHORTFALL",
                    room.buildingCode() + " " + room.roomCode() + " needs " + required
                            + " staff clear of " + String.join(", ", roomCourses) + "; only "
                            + assigned.size() + " could be assigned."));
        }
        List<FeasibilityReport.RoomUse> roomUses = new ArrayList<>();
        for (Room room : selected) {
            int required = room.configuredCapacity() > 100 ? 3 : 2;
            List<String> assigned = staffByRoom.getOrDefault(room.id(), List.of());
            roomUses.add(new FeasibilityReport.RoomUse(room.id(), room.buildingCode(), room.roomCode(),
                    room.effectiveCapacity(), required, assigned, room.capacityStatus(),
                    allocation.coursesByRoom().getOrDefault(room.id(), List.of())));
        }

        return new FeasibilityReport.SessionResult(session.id(), session.date().toString(), session.startTime().toString(),
                session.endTime().toString(), session.candidateCount(), availableCapacity, free.size(), selected.size(),
                staffNeeded, active.size(), potentiallyEligible, roomUses, diagnostics);
    }

    private static boolean eligibleForRoom(Staff staff, List<FeasibilityReport.CoursePortion> courses) {
        return courses.stream().noneMatch(course -> staff.courseCodes().contains(course.courseCode()));
    }

    private static Set<String> usedAt(Map<String, LocalDateTime> uses, LocalDateTime start) {
        Set<String> active = new HashSet<>();
        uses.forEach((id, until) -> { if (start.isBefore(until)) active.add(id); });
        return active;
    }

    private static Room withEffectiveCapacity(Room room) {
        int effective = room.seatRecordCount() == 0 ? room.configuredCapacity()
                : Math.min(room.configuredCapacity(), room.usableSeatCount());
        return room.withEffectiveCapacity(effective);
    }

    private static void addRoomWarnings(Room room, List<FeasibilityReport.Diagnostic> diagnostics) {
        if (room.seatRecordCount() == 0) diagnostics.add(diagnostic("WARNING", "ROOM_LAYOUT_MISSING",
                room.buildingCode() + " " + room.roomCode() + " has no seat grid; configured capacity is used for this check."));
        else if (room.usableSeatCount() != room.configuredCapacity()) diagnostics.add(diagnostic("WARNING",
                "ROOM_LAYOUT_CAPACITY_MISMATCH", room.buildingCode() + " " + room.roomCode()
                        + " is configured for " + room.configuredCapacity() + " seats but has "
                        + room.usableSeatCount() + " usable seats; " + room.effectiveCapacity() + " will be counted."));
        if (room.capacityStatus().equals("ESTIMATED")) diagnostics.add(diagnostic("WARNING", "ESTIMATED_ROOM_CAPACITY",
                room.buildingCode() + " " + room.roomCode() + " has estimated capacity " + room.configuredCapacity() + "."));
    }

    private static FeasibilityReport.Diagnostic diagnostic(String severity, String code, String message) {
        return new FeasibilityReport.Diagnostic(severity, code, message);
    }

    public record Snapshot(String eventCode, int sourceRevision, int handoverMinutes, List<Session> sessions,
            List<Room> rooms, List<Staff> staff) {
        public Snapshot { sessions = List.copyOf(sessions); rooms = List.copyOf(rooms); staff = List.copyOf(staff); }
    }
    public record Session(String id, LocalDate date, LocalTime startTime, LocalTime endTime,
            int candidateCount, List<String> courseCodes, Map<String, Integer> courseDemand) {
        public Session {
            courseCodes = List.copyOf(courseCodes);
            courseDemand = Map.copyOf(courseDemand);
        }
        public Session(String id, LocalDate date, LocalTime startTime, LocalTime endTime,
                int candidateCount, List<String> courseCodes) {
            this(id, date, startTime, endTime, candidateCount, courseCodes, Map.of());
        }
    }
    public record Room(String id, String buildingCode, String roomCode, int configuredCapacity,
            int seatRecordCount, int usableSeatCount, String capacityStatus, boolean active,
            Set<String> unavailableSessions, int effectiveCapacity) {
        public Room { unavailableSessions = Set.copyOf(unavailableSessions); }
        public Room(String id, String buildingCode, String roomCode, int configuredCapacity,
                int seatRecordCount, int usableSeatCount, String capacityStatus, boolean active,
                Set<String> unavailableSessions) {
            this(id, buildingCode, roomCode, configuredCapacity, seatRecordCount, usableSeatCount,
                    capacityStatus, active, unavailableSessions, 0);
        }
        Room withEffectiveCapacity(int value) { return new Room(id, buildingCode, roomCode, configuredCapacity,
                seatRecordCount, usableSeatCount, capacityStatus, active, unavailableSessions, value); }
    }
    public record Staff(String id, boolean active, Set<String> courseCodes,
            Set<String> unavailableSessions) {
        public Staff {
            courseCodes = Set.copyOf(courseCodes);
            unavailableSessions = Set.copyOf(unavailableSessions);
        }
    }
}
