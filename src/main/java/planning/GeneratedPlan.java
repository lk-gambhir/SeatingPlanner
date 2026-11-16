package planning;

import java.util.List;

/** A generated but not yet reviewed exam plan. */
public record GeneratedPlan(String eventCode, int sourceRevision, List<Session> sessions) {
    public GeneratedPlan { sessions = List.copyOf(sessions); }

    public int assignedCandidates() {
        return sessions.stream().flatMap(session -> session.rooms().stream())
                .mapToInt(room -> room.assignments().size()).sum();
    }

    public int roomCount() { return (int) sessions.stream().flatMap(session -> session.rooms().stream()).count(); }

    public long blockerCount() {
        return sessions.stream().flatMap(session -> java.util.stream.Stream.concat(session.diagnostics().stream(),
                        session.rooms().stream().flatMap(room -> room.diagnostics().stream())))
                .filter(item -> item.severity().equals("BLOCKER") || item.severity().equals("ERROR")).count();
    }

    public record Session(String id, String date, String startTime, String endTime,
            int candidateDemand, List<Room> rooms, List<FeasibilityReport.Diagnostic> diagnostics) {
        public Session { rooms = List.copyOf(rooms); diagnostics = List.copyOf(diagnostics); }
    }

    public record Room(String id, String buildingCode, String roomCode, String capacityStatus,
            int capacity, int candidateDemand, int staffRequired, List<String> staffIds,
            List<SeatAssignmentPlanner.Assignment> assignments, List<SeatAssignmentPlanner.Seat> seatGrid,
            List<FeasibilityReport.Diagnostic> diagnostics) {
        public Room {
            staffIds = List.copyOf(staffIds);
            assignments = List.copyOf(assignments);
            seatGrid = List.copyOf(seatGrid);
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
