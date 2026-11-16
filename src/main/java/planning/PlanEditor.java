package planning;

import java.util.ArrayList;
import java.util.List;

/** Makes small manual changes to a draft without changing the saved version. */
public final class PlanEditor {
    public GeneratedPlan moveCandidate(GeneratedPlan plan, String sessionId, String candidateKey,
            String destinationRoomId, int destinationSeatId) {
        List<GeneratedPlan.Session> sessions = new ArrayList<>();
        boolean changed = false;
        for (GeneratedPlan.Session session : plan.sessions()) {
            if (!session.id().equals(sessionId)) {
                sessions.add(session);
                continue;
            }
            GeneratedPlan.Room source = null;
            SeatAssignmentPlanner.Assignment selected = null;
            GeneratedPlan.Room destination = null;
            for (GeneratedPlan.Room room : session.rooms()) {
                if (room.id().equals(destinationRoomId)) destination = room;
                for (var assignment : room.assignments()) if (assignment.candidate().candidateKey().equals(candidateKey)) {
                    if (selected != null) throw new IllegalArgumentException("Candidate label is not unique in this session.");
                    source = room;
                    selected = assignment;
                }
            }
            if (selected == null) throw new IllegalArgumentException("Candidate was not found in this session.");
            if (destination == null) throw new IllegalArgumentException("Destination room is not in this session.");
            var targetSeat = destination.seatGrid().stream().filter(seat -> seat.id() == destinationSeatId)
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Seat does not belong to the destination room."));
            if (!targetSeat.usable()) throw new IllegalArgumentException("Choose a usable seat.");
            if (source.id().equals(destination.id()) && selected.seat().id() == targetSeat.id())
                throw new IllegalArgumentException("Choose a different seat.");
            if (destination.assignments().stream().anyMatch(item -> item.seat().id() == targetSeat.id()))
                throw new IllegalArgumentException("That seat is already occupied.");
            if (!source.id().equals(destination.id()) && destination.candidateDemand() >= destination.capacity())
                throw new IllegalArgumentException("The destination room has no remaining capacity.");

            List<GeneratedPlan.Room> rooms = new ArrayList<>();
            for (GeneratedPlan.Room room : session.rooms()) {
                List<SeatAssignmentPlanner.Assignment> assignments = new ArrayList<>(room.assignments());
                int demand = room.candidateDemand();
                if (room.id().equals(source.id())) {
                    assignments.remove(selected);
                    if (!source.id().equals(destination.id())) demand--;
                }
                if (room.id().equals(destination.id())) {
                    assignments.add(new SeatAssignmentPlanner.Assignment(selected.candidate(), targetSeat, "MANUAL"));
                    if (!source.id().equals(destination.id())) demand++;
                }
                rooms.add(copy(room, demand, assignments, room.staffIds()));
            }
            sessions.add(new GeneratedPlan.Session(session.id(), session.date(), session.startTime(), session.endTime(),
                    session.candidateDemand(), rooms, session.diagnostics()));
            changed = true;
        }
        if (!changed) throw new IllegalArgumentException("Session was not found in this plan.");
        return new GeneratedPlan(plan.eventCode(), plan.sourceRevision(), sessions);
    }

    public GeneratedPlan replaceInvigilator(GeneratedPlan plan, String sessionId, String roomId,
            String oldStaffId, String newStaffId) {
        if (newStaffId == null || newStaffId.isBlank()) throw new IllegalArgumentException("Choose an invigilator.");
        List<GeneratedPlan.Session> sessions = new ArrayList<>();
        boolean changed = false;
        for (GeneratedPlan.Session session : plan.sessions()) {
            if (!session.id().equals(sessionId)) {
                sessions.add(session);
                continue;
            }
            List<GeneratedPlan.Room> rooms = new ArrayList<>();
            for (GeneratedPlan.Room room : session.rooms()) {
                if (!room.id().equals(roomId)) {
                    rooms.add(room);
                    continue;
                }
                if (!room.staffIds().contains(oldStaffId)) throw new IllegalArgumentException("Invigilator was not assigned to this room.");
                if (room.staffIds().contains(newStaffId)) throw new IllegalArgumentException("That invigilator is already assigned to this room.");
                List<String> staff = new ArrayList<>(room.staffIds());
                staff.set(staff.indexOf(oldStaffId), newStaffId);
                rooms.add(copy(room, room.candidateDemand(), room.assignments(), staff));
                changed = true;
            }
            if (!changed) throw new IllegalArgumentException("Room was not found in this session.");
            sessions.add(new GeneratedPlan.Session(session.id(), session.date(), session.startTime(), session.endTime(),
                    session.candidateDemand(), rooms, session.diagnostics()));
        }
        if (!changed) throw new IllegalArgumentException("Session was not found in this plan.");
        return new GeneratedPlan(plan.eventCode(), plan.sourceRevision(), sessions);
    }

    private static GeneratedPlan.Room copy(GeneratedPlan.Room room, int demand,
            List<SeatAssignmentPlanner.Assignment> assignments, List<String> staff) {
        return new GeneratedPlan.Room(room.id(), room.buildingCode(), room.roomCode(), room.capacityStatus(),
                room.capacity(), demand, room.staffRequired(), staff, assignments, room.seatGrid(), room.diagnostics());
    }
}
