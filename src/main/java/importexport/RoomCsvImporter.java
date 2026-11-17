package importexport;

import domain.Room;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reads the room CSV format used by the project fixtures. */
public final class RoomCsvImporter {
    public RoomCsvPreview preview(Path path, List<Room> savedRooms,
            Map<String, List<RoomCsvPreview.SeatPosition>> savedBlockedSeats) throws IOException {
        Map<String, Room> savedById = new HashMap<>();
        Map<String, Room> savedByCode = new HashMap<>();
        for (Room room : savedRooms) {
            savedById.put(room.roomId(), room);
            savedByCode.put(room.buildingCode() + "/" + room.roomCode(), room);
        }

        List<RoomCsvPreview.Row> rows = new ArrayList<>();
        Set<String> fileIds = new HashSet<>();
        Set<String> fileCodes = new HashSet<>();
        boolean ignoredAvailability = false;
        String sourceHeaders;
        try (PushbackReader reader = new PushbackReader(Files.newBufferedReader(path, StandardCharsets.UTF_8), 1)) {
            int first = reader.read();
            if (first != 0xFEFF && first != -1) reader.unread(first);
            CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
            try (CSVParser parser = format.parse(reader)) {
                sourceHeaders = CsvImportValues.encodeHeaders(parser.getHeaderMap());
                Map<String, Integer> headers = new HashMap<>();
                for (Map.Entry<String, Integer> header : parser.getHeaderMap().entrySet()) {
                    headers.put(header.getKey().trim().toLowerCase(Locale.ROOT), header.getValue());
                }
                for (String required : List.of("room_id", "building_code", "floor", "room_type",
                        "num_rows", "num_cols", "usable_capacity", "capacity_status")) {
                    if (!headers.containsKey(required)) throw new IOException("CSV is missing required column: " + required);
                }
                ignoredAvailability = headers.containsKey("is_available");
                for (CSVRecord record : parser) {
                    rows.add(readRow(record, headers, savedById, savedByCode, savedBlockedSeats, fileIds, fileCodes));
                }
            }
        }
        return new RoomCsvPreview(path.getFileName().toString(), sha256(path), sourceHeaders, rows, ignoredAvailability);
    }

    private static RoomCsvPreview.Row readRow(CSVRecord record, Map<String, Integer> headers,
            Map<String, Room> savedById, Map<String, Room> savedByCode,
            Map<String, List<RoomCsvPreview.SeatPosition>> savedBlockedSeats,
            Set<String> fileIds, Set<String> fileCodes) {
        int rowNumber = (int) record.getRecordNumber();
        String raw = CsvImportValues.encodeRecord(record.toList());
        try {
            String id = value(record, headers, "room_id").trim();
            String building = value(record, headers, "building_code").trim();
            int floor = integer(record, headers, "floor", rowNumber);
            String roomCode = roomCode(id);
            int roomRows = integer(record, headers, "num_rows", rowNumber);
            int columns = integer(record, headers, "num_cols", rowNumber);
            int capacity = integer(record, headers, "usable_capacity", rowNumber);
            String activeValue = value(record, headers, "active");
            boolean active = activeValue.isBlank() || parseActive(activeValue, rowNumber);
            List<RoomCsvPreview.SeatPosition> blocked = parseBlocked(value(record, headers, "blocked_seats"), roomRows, columns);
            Room room = new Room(id, building, roomCode, floor, value(record, headers, "centre_name"),
                    value(record, headers, "room_type"), roomRows, columns, capacity,
                    value(record, headers, "capacity_status").trim().toUpperCase(Locale.ROOT), active,
                    value(record, headers, "data_label"));
            String normalized = CsvImportValues.encodeTable(
                    List.of("room_id", "building_code", "room_code", "floor", "centre_name", "room_type",
                            "num_rows", "num_cols", "blocked_seats", "usable_capacity", "capacity_status", "active", "data_label"),
                    List.of(room.roomId(), room.buildingCode(), room.roomCode(), String.valueOf(room.floor()),
                            text(room.centreName()), room.roomType(), String.valueOf(room.rows()), String.valueOf(room.columns()),
                            blockedText(blocked), String.valueOf(room.usableCapacity()), room.capacityStatus(),
                            String.valueOf(room.active()), room.dataLabel()));
            String key = building + "/" + roomCode;
            boolean duplicateId = !fileIds.add(id);
            boolean duplicateCode = !fileCodes.add(key);
            if (duplicateId || duplicateCode) {
                return row(rowNumber, RoomCsvPreview.Status.ERROR, null, List.of(),
                        "Duplicate room ID or building/room code in this file.", raw, normalized);
            }
            Room saved = savedById.getOrDefault(id, savedByCode.get(key));
            if (saved != null) {
                return row(rowNumber, RoomCsvPreview.Status.SKIP, room, blocked,
                        describeExisting(saved, room, savedBlockedSeats.getOrDefault(saved.roomId(), List.of()), blocked), raw, normalized);
            }
            if (capacity != roomRows * columns - blocked.size()) {
                return row(rowNumber, RoomCsvPreview.Status.ADD, room, blocked,
                        "Configured capacity differs from the usable seats in the grid.", raw, normalized);
            }
            return row(rowNumber, RoomCsvPreview.Status.ADD, room, blocked, "", raw, normalized);
        } catch (IllegalArgumentException exception) {
            return row(rowNumber, RoomCsvPreview.Status.ERROR, null, List.of(),
                    "Row " + rowNumber + ": " + exception.getMessage() + " Correct the named field and re-import.", raw, "");
        }
    }

    private static String text(String value) { return value == null ? "" : value; }

    private static String blockedText(List<RoomCsvPreview.SeatPosition> seats) {
        return seats.stream().map(seat -> seat.row() + ":" + seat.column()).collect(java.util.stream.Collectors.joining("; "));
    }

    private static String describeExisting(Room saved, Room incoming,
            List<RoomCsvPreview.SeatPosition> savedBlocked, List<RoomCsvPreview.SeatPosition> incomingBlocked) {
        List<String> changes = new ArrayList<>();
        compare(changes, "room_id", saved.roomId(), incoming.roomId());
        compare(changes, "building_code", saved.buildingCode(), incoming.buildingCode());
        compare(changes, "floor", saved.floor(), incoming.floor());
        compare(changes, "centre_name", saved.centreName(), incoming.centreName());
        compare(changes, "room_type", saved.roomType(), incoming.roomType());
        compare(changes, "num_rows", saved.rows(), incoming.rows());
        compare(changes, "num_cols", saved.columns(), incoming.columns());
        compare(changes, "usable_capacity", saved.usableCapacity(), incoming.usableCapacity());
        compare(changes, "capacity_status", saved.capacityStatus(), incoming.capacityStatus());
        compare(changes, "data_label", saved.dataLabel(), incoming.dataLabel());
        compare(changes, "blocked_seats", positions(savedBlocked), positions(incomingBlocked));
        return changes.isEmpty() ? "Room already exists; the CSV row matches saved data."
                : "Saved room and seat layout will be kept. CSV changes: " + String.join("; ", changes);
    }

    private static void compare(List<String> changes, String field, Object saved, Object incoming) {
        if (!java.util.Objects.equals(saved, incoming)) {
            changes.add(field + " [" + display(saved) + " → " + display(incoming) + "]");
        }
    }

    private static List<RoomCsvPreview.SeatPosition> positions(List<RoomCsvPreview.SeatPosition> seats) {
        return seats.stream().sorted(java.util.Comparator.comparingInt(RoomCsvPreview.SeatPosition::row)
                .thenComparingInt(RoomCsvPreview.SeatPosition::column)).toList();
    }

    private static String display(Object value) {
        return value == null ? "empty" : value.toString();
    }

    private static List<RoomCsvPreview.SeatPosition> parseBlocked(String value, int rows, int columns) {
        List<RoomCsvPreview.SeatPosition> blocked = new ArrayList<>();
        Set<RoomCsvPreview.SeatPosition> unique = new HashSet<>();
        if (value.isBlank()) return blocked;
        for (String position : value.split(";")) {
            String[] parts = position.trim().split(":");
            if (parts.length != 2) throw new IllegalArgumentException("blocked_seats must use row:column pairs such as 1:2.");
            try {
                RoomCsvPreview.SeatPosition seat = new RoomCsvPreview.SeatPosition(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                if (seat.row() < 1 || seat.row() > rows || seat.column() < 1 || seat.column() > columns) {
                    throw new IllegalArgumentException("blocked_seats coordinates must be inside the room grid.");
                }
                if (!unique.add(seat)) throw new IllegalArgumentException("blocked_seats contains the same coordinate more than once.");
                blocked.add(seat);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("blocked_seats coordinates must be whole numbers such as 1:2.");
            }
        }
        return blocked;
    }

    private static int integer(CSVRecord record, Map<String, Integer> headers, String column, int row) {
        try {
            return Integer.parseInt(value(record, headers, column).trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(column + " must be a whole number on row " + row + ".");
        }
    }

    private static boolean parseActive(String value, int row) {
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "TRUE", "1", "ACTIVE" -> true;
            case "FALSE", "0", "INACTIVE" -> false;
            default -> throw new IllegalArgumentException("active must be TRUE, FALSE, 1, 0, ACTIVE, or INACTIVE.");
        };
    }

    private static String roomCode(String roomId) {
        int lastDash = roomId.lastIndexOf('-');
        int floorDash = roomId.lastIndexOf('-', lastDash - 1);
        if (floorDash < 0 || lastDash < 0) throw new IllegalArgumentException("room_id must end with -<floor>-<room code>.");
        return roomId.substring(floorDash + 1);
    }

    private static RoomCsvPreview.Row row(int row, RoomCsvPreview.Status status, Room room,
            List<RoomCsvPreview.SeatPosition> blocked, String message, String raw, String normalized) {
        return new RoomCsvPreview.Row(row, status, room, blocked, message, raw, normalized);
    }

    private static String value(CSVRecord record, Map<String, Integer> headers, String column) {
        Integer index = headers.get(column);
        return index == null || index >= record.size() ? "" : record.get(index);
    }

    private static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
