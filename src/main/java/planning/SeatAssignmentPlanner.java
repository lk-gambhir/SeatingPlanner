package planning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Places candidates in one room while avoiding same-course orthogonal neighbors. */
public final class SeatAssignmentPlanner {
    public Result assign(List<Candidate> candidates, List<Seat> seats) {
        List<Seat> openSeats = seats.stream().filter(Seat::usable)
                .sorted(Comparator.comparingInt(Seat::row).thenComparingInt(Seat::column).thenComparingInt(Seat::id))
                .toList();
        Map<Integer, List<Seat>> neighbors = new HashMap<>();
        for (Seat seat : openSeats) neighbors.put(seat.id(), openSeats.stream().filter(other ->
                Math.abs(other.row() - seat.row()) + Math.abs(other.column() - seat.column()) == 1).toList());
        Map<String, Long> courseSizes = candidates.stream()
                .collect(Collectors.groupingBy(Candidate::courseCode, Collectors.counting()));
        List<Candidate> ordered = candidates.stream().sorted(Comparator
                .comparingLong((Candidate candidate) -> courseSizes.get(candidate.courseCode())).reversed()
                .thenComparing(Candidate::courseCode).thenComparing(Candidate::candidateKey)).toList();
        Map<Integer, Candidate> occupied = new HashMap<>();
        Set<String> seen = new HashSet<>();
        List<Assignment> assignments = new ArrayList<>();
        List<FeasibilityReport.Diagnostic> diagnostics = new ArrayList<>();

        for (Candidate candidate : ordered) {
            if (!seen.add(candidate.candidateKey())) {
                diagnostics.add(new FeasibilityReport.Diagnostic("BLOCKER", "DUPLICATE_CANDIDATE",
                        candidate.candidateKey() + " appears more than once in this room input."));
                continue;
            }
            List<Seat> choices = openSeats.stream().filter(seat -> !occupied.containsKey(seat.id())).toList();
            if (choices.isEmpty()) {
                diagnostics.add(new FeasibilityReport.Diagnostic("BLOCKER", "UNASSIGNED_CANDIDATE",
                        candidate.candidateKey() + " has no usable seat."));
                continue;
            }
            Seat selected = choices.stream().min(Comparator
                    .comparingInt((Seat seat) -> sameCourseNeighbors(candidate, seat, occupied, neighbors))
                    .thenComparing(Comparator.comparingInt((Seat seat) -> freeNeighbors(seat, occupied, neighbors)).reversed())
                    .thenComparingInt(Seat::row).thenComparingInt(Seat::column).thenComparingInt(Seat::id))
                    .orElseThrow();
            List<Seat> neighborSeats = neighbors.get(selected.id()).stream()
                    .filter(neighbor -> occupied.containsKey(neighbor.id()))
                    .filter(neighbor -> occupied.get(neighbor.id()).courseCode().equals(candidate.courseCode())).toList();
            for (Seat neighborSeat : neighborSeats) {
                Candidate neighbor = occupied.get(neighborSeat.id());
                diagnostics.add(new FeasibilityReport.Diagnostic("WARNING",
                    "SAME_COURSE_ORTHOGONAL_ADJACENCY", candidate.candidateKey() + " and "
                            + neighbor.candidateKey() + " share " + candidate.courseCode()
                            + " and occupy orthogonal neighbors at " + selected.row() + "," + selected.column()
                            + " and " + neighborSeat.row() + "," + neighborSeat.column() + "."));
            }
            occupied.put(selected.id(), candidate);
            assignments.add(new Assignment(candidate, selected));
        }
        return new Result(assignments, diagnostics);
    }

    private static int sameCourseNeighbors(Candidate candidate, Seat seat,
            Map<Integer, Candidate> occupied, Map<Integer, List<Seat>> neighbors) {
        return (int) neighbors.get(seat.id()).stream().map(other -> occupied.get(other.id()))
                .filter(other -> other != null && other.courseCode().equals(candidate.courseCode())).count();
    }

    private static int freeNeighbors(Seat seat, Map<Integer, Candidate> occupied,
            Map<Integer, List<Seat>> neighbors) {
        return (int) neighbors.get(seat.id()).stream().filter(neighbor -> !occupied.containsKey(neighbor.id())).count();
    }

    public record Candidate(String candidateKey, String courseCode, String studentId,
            Integer anonymousNumber, String sessionCourseId) {}
    public record Seat(int id, int row, int column, boolean usable) {}
    public record Assignment(Candidate candidate, Seat seat, String origin) {
        public Assignment(Candidate candidate, Seat seat) { this(candidate, seat, "GENERATED"); }
    }
    public record Result(List<Assignment> assignments, List<FeasibilityReport.Diagnostic> diagnostics) {
        public Result { assignments = List.copyOf(assignments); diagnostics = List.copyOf(diagnostics); }
    }
}
