package planning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Allocates course demand to available rooms, preferring one building per course. */
public final class RoomAllocationPlanner {
    public Result allocate(Map<String, Integer> courseDemand, List<FeasibilityAnalyzer.Room> rooms) {
        Map<String, Integer> freeByRoom = new HashMap<>();
        for (FeasibilityAnalyzer.Room room : rooms) {
            freeByRoom.put(room.id(), room.effectiveCapacity());
        }

        Map<String, List<FeasibilityReport.CoursePortion>> coursesByRoom = new TreeMap<>();
        List<FeasibilityReport.Diagnostic> diagnostics = new ArrayList<>();
        List<Map.Entry<String, Integer>> courses = courseDemand.entrySet().stream()
                .filter(course -> course.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .toList();

        for (Map.Entry<String, Integer> course : courses) {
            List<String> blocks = rooms.stream().map(FeasibilityAnalyzer.Room::buildingCode).distinct().sorted().toList();
            Map<String, Integer> byBlock = freeByBlock(blocks, rooms, freeByRoom);
            List<String> fitting = blocks.stream().filter(block -> byBlock.getOrDefault(block, 0) >= course.getValue()).toList();
            Map<String, Integer> usedBlocks = new LinkedHashMap<>();

            if (!fitting.isEmpty()) {
                String block = fitting.stream().map(name -> new BlockFit(name,
                                simulate(course.getValue(), name, rooms, freeByRoom)))
                        .min(Comparator.comparingInt((BlockFit fit) -> fit.allocation().roomCount())
                                .thenComparingInt(fit -> fit.allocation().unusedCapacity())
                                .thenComparing(BlockFit::name))
                        .orElseThrow().name();
                add(course.getKey(), course.getValue(), block, rooms, freeByRoom, coursesByRoom, usedBlocks);
            } else {
                List<String> fullestFirst = blocks.stream().sorted(Comparator
                        .comparingInt((String block) -> byBlock.getOrDefault(block, 0)).reversed()
                        .thenComparing(block -> block)).toList();
                int remaining = course.getValue();
                for (String block : fullestFirst) {
                    if (remaining == 0) break;
                    int assigned = add(course.getKey(), remaining, block, rooms, freeByRoom, coursesByRoom, usedBlocks);
                    remaining -= assigned;
                }
                if (remaining > 0) diagnostics.add(new FeasibilityReport.Diagnostic("BLOCKER",
                        "COURSE_DEMAND_SHORTFALL", course.getKey() + " has " + remaining
                                + " candidates without room capacity."));
            }

            if (usedBlocks.size() > 1) {
                String detail = usedBlocks.entrySet().stream()
                        .map(entry -> entry.getKey() + " " + entry.getValue()).reduce((a, b) -> a + ", " + b).orElse("");
                diagnostics.add(new FeasibilityReport.Diagnostic("WARNING", "COURSE_SPLIT_ACROSS_BLOCKS",
                        course.getKey() + " was split because no single building had enough remaining capacity: "
                                + detail + " candidates."));
            }
        }
        return new Result(coursesByRoom, diagnostics);
    }

    private static Map<String, Integer> freeByBlock(List<String> blocks, List<FeasibilityAnalyzer.Room> rooms,
            Map<String, Integer> freeByRoom) {
        Map<String, Integer> result = new HashMap<>();
        for (String block : blocks) {
            int total = rooms.stream().filter(room -> room.buildingCode().equals(block))
                    .mapToInt(room -> freeByRoom.getOrDefault(room.id(), 0)).sum();
            result.put(block, total);
        }
        return result;
    }

    private static Fit simulate(int demand, String block, List<FeasibilityAnalyzer.Room> rooms,
            Map<String, Integer> freeByRoom) {
        List<Integer> capacities = new ArrayList<>(rooms.stream()
                .filter(room -> room.buildingCode().equals(block))
                .map(room -> freeByRoom.getOrDefault(room.id(), 0)).filter(capacity -> capacity > 0).toList());
        int remaining = demand;
        int unused = 0;
        int used = 0;
        while (remaining > 0 && !capacities.isEmpty()) {
            int needed = remaining;
            int capacity = capacities.stream().filter(value -> value >= needed).min(Integer::compareTo)
                    .orElseGet(() -> capacities.stream().max(Integer::compareTo).orElse(0));
            capacities.remove(Integer.valueOf(capacity));
            int assigned = Math.min(capacity, remaining);
            remaining -= assigned;
            unused += capacity - assigned;
            used++;
        }
        return new Fit(used, unused);
    }

    private static int add(String courseCode, int demand, String block, List<FeasibilityAnalyzer.Room> rooms,
            Map<String, Integer> freeByRoom,
            Map<String, List<FeasibilityReport.CoursePortion>> coursesByRoom, Map<String, Integer> usedBlocks) {
        int remaining = demand;
        while (remaining > 0) {
            List<FeasibilityAnalyzer.Room> candidates = rooms.stream()
                    .filter(room -> room.buildingCode().equals(block))
                    .filter(room -> freeByRoom.getOrDefault(room.id(), 0) > 0).toList();
            if (candidates.isEmpty()) break;
            int needed = remaining;
            FeasibilityAnalyzer.Room selected = candidates.stream()
                    .filter(room -> freeByRoom.get(room.id()) >= needed)
                    .min(Comparator.comparingInt((FeasibilityAnalyzer.Room room) -> freeByRoom.get(room.id()) - needed)
                            .thenComparing(FeasibilityAnalyzer.Room::id))
                    .orElseGet(() -> candidates.stream().max(Comparator
                            .comparingInt((FeasibilityAnalyzer.Room room) -> freeByRoom.get(room.id()))
                            .thenComparing(room -> room.id(), Comparator.reverseOrder())).orElseThrow());
            int assigned = Math.min(remaining, freeByRoom.get(selected.id()));
            freeByRoom.put(selected.id(), freeByRoom.get(selected.id()) - assigned);
            coursesByRoom.computeIfAbsent(selected.id(), ignored -> new ArrayList<>())
                    .add(new FeasibilityReport.CoursePortion(courseCode, assigned));
            usedBlocks.merge(block, assigned, Integer::sum);
            remaining -= assigned;
        }
        return demand - remaining;
    }

    public record Result(Map<String, List<FeasibilityReport.CoursePortion>> coursesByRoom,
            List<FeasibilityReport.Diagnostic> diagnostics) {
        public Result {
            Map<String, List<FeasibilityReport.CoursePortion>> copied = new TreeMap<>();
            coursesByRoom.forEach((room, courses) -> copied.put(room, List.copyOf(courses)));
            coursesByRoom = Map.copyOf(copied);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private record BlockFit(String name, Fit allocation) {}
    private record Fit(int roomCount, int unusedCapacity) {}
}
