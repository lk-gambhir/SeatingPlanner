package planning;

import java.util.List;

/** Capacity and staffing preflight for one saved exam event. */
public record FeasibilityReport(String eventCode, int sourceRevision, List<SessionResult> sessions) {
    public FeasibilityReport { sessions = List.copyOf(sessions); }
    public boolean hasBlockers() { return sessions.stream().flatMap(row -> row.diagnostics().stream())
            .anyMatch(item -> item.severity().equals("BLOCKER")); }

    public record SessionResult(String sessionId, String date, String startTime, String endTime,
            int candidates, int availableSeats, int availableRooms, int roomsNeeded,
            int staffNeeded, int availableStaff, int potentiallyEligibleStaff,
            List<RoomUse> selectedRooms, List<Diagnostic> diagnostics) {
        public SessionResult { selectedRooms = List.copyOf(selectedRooms); diagnostics = List.copyOf(diagnostics); }
    }
    public record RoomUse(String roomId, String buildingCode, String roomCode, int seats,
            int staffRequired, List<String> staffIds, String capacityStatus, List<CoursePortion> courses) {
        public RoomUse { staffIds = List.copyOf(staffIds); courses = List.copyOf(courses); }
        public RoomUse(String roomId, String buildingCode, String roomCode, int seats,
                int staffRequired, List<String> staffIds, String capacityStatus) {
            this(roomId, buildingCode, roomCode, seats, staffRequired, staffIds, capacityStatus, List.of());
        }
    }
    public record CoursePortion(String courseCode, int candidates) {}
    public record Diagnostic(String severity, String code, String message) {}
}
