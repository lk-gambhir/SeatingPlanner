package persistence;

import domain.Room;
import importexport.RoomCsvPreview;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** JDBC access for room inventory and imported room layouts. */
public final class RoomRepository {
    private final SqliteConnectionFactory connections;

    public RoomRepository(SqliteConnectionFactory connections) { this.connections = Objects.requireNonNull(connections); }

    public List<Room> findAll() throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("""
                     SELECT r.room_id, b.building_code, r.room_code, r.floor_number, c.centre_name,
                            r.room_type, r.row_count, r.column_count, r.usable_capacity,
                            r.capacity_status, r.active, r.data_label, r.source_note
                     FROM room r JOIN building b ON b.building_id=r.building_id
                     LEFT JOIN reporting_centre c ON c.centre_id=r.centre_id
                     ORDER BY b.building_code, r.floor_number, r.room_code
                     """);
             var rows = statement.executeQuery()) {
            List<Room> rooms = new ArrayList<>();
            while (rows.next()) rooms.add(new Room(rows.getString("room_id"), rows.getString("building_code"),
                    rows.getString("room_code"), rows.getInt("floor_number"), rows.getString("centre_name"),
                    rows.getString("room_type"), rows.getInt("row_count"), rows.getInt("column_count"),
                    rows.getInt("usable_capacity"), rows.getString("capacity_status"),
                    rows.getInt("active") == 1, rows.getString("data_label"), rows.getString("source_note")));
            return List.copyOf(rooms);
        }
    }

    public List<SessionOption> findSessions() throws SQLException {
        try (Connection connection = connections.openConnection(); var statement = connection.prepareStatement("""
                SELECT x.exam_session_id, e.event_code, x.session_date, x.start_time
                FROM exam_session x JOIN exam_event e ON e.event_id=x.event_id
                WHERE x.status<>'CANCELLED' AND e.status<>'ARCHIVED'
                ORDER BY x.session_date, x.start_time, e.event_code
                """); var rows = statement.executeQuery()) {
            List<SessionOption> sessions = new ArrayList<>();
            while (rows.next()) sessions.add(new SessionOption(rows.getString("exam_session_id"),
                    rows.getString("event_code"), rows.getString("session_date"), rows.getString("start_time")));
            return List.copyOf(sessions);
        }
    }

    public Map<String, Boolean> findSessionAvailability(String sessionId) throws SQLException {
        try (Connection connection = connections.openConnection(); var statement = connection.prepareStatement(
                "SELECT room_id, available FROM room_availability WHERE exam_session_id=?")) {
            statement.setString(1, sessionId);
            try (var rows = statement.executeQuery()) {
                Map<String, Boolean> availability = new HashMap<>();
                while (rows.next()) availability.put(rows.getString("room_id"), rows.getInt("available") == 1);
                return Map.copyOf(availability);
            }
        }
    }

    public List<AssignedPlanView> findPlansUsingRooms(String sessionId, List<String> roomIds) throws SQLException {
        if (roomIds.isEmpty()) return List.of();
        String marks = String.join(",", java.util.Collections.nCopies(roomIds.size(), "?"));
        String query = """
                SELECT e.event_code, p.version_number, p.status,
                    CASE WHEN p.source_revision=e.source_revision THEN 0 ELSE 1 END AS stale,
                    COALESCE(ra.building_code_snapshot,b.building_code) AS building_code,
                    COALESCE(ra.room_code_snapshot,r.room_code) AS room_code
                FROM room_assignment ra JOIN plan_session ps ON ps.plan_session_id=ra.plan_session_id
                JOIN plan p ON p.plan_id=ps.plan_id
                JOIN exam_session x ON x.exam_session_id=ps.exam_session_id
                JOIN exam_event e ON e.event_id=x.event_id
                JOIN room r ON r.room_id=ra.room_id JOIN building b ON b.building_id=r.building_id
                WHERE ps.exam_session_id=? AND ra.assignment_status<>'REMOVED' AND p.status<>'SUPERSEDED'
                    AND ra.room_id IN (%s)
                ORDER BY p.version_number DESC, building_code, room_code
                """.formatted(marks);
        try (Connection connection = connections.openConnection(); var statement = connection.prepareStatement(query)) {
            statement.setString(1, sessionId);
            for (int index = 0; index < roomIds.size(); index++) statement.setString(index + 2, roomIds.get(index));
            try (var rows = statement.executeQuery()) {
                List<AssignedPlanView> plans = new ArrayList<>();
                while (rows.next()) plans.add(new AssignedPlanView(rows.getString("event_code"),
                        rows.getInt("version_number"), rows.getString("status"), rows.getInt("stale") == 1,
                        rows.getString("building_code"), rows.getString("room_code")));
                return List.copyOf(plans);
            }
        }
    }

    public void setSessionAvailability(String sessionId, List<String> roomIds, Boolean available) throws SQLException {
        updateSessionAvailability(sessionId, roomIds.stream()
                .map(roomId -> new AvailabilityUpdate(roomId, available)).toList());
    }

    public void updateSessionAvailability(String sessionId, List<AvailabilityUpdate> updates) throws SQLException {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("Choose an exam session.");
        if (updates.isEmpty()) throw new IllegalArgumentException("Select at least one room.");
        String now = Instant.now().toString();
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try (var clear = connection.prepareStatement("DELETE FROM room_availability WHERE room_id=? AND exam_session_id=?");
                 var save = connection.prepareStatement("""
                         INSERT INTO room_availability(room_id,exam_session_id,available,reason) VALUES (?,?,?,NULL)
                         ON CONFLICT(room_id,exam_session_id) DO UPDATE SET available=excluded.available, reason=NULL
                         """)) {
                for (AvailabilityUpdate update : updates) {
                    if (update.roomId() == null || update.roomId().isBlank()) throw new IllegalArgumentException("Room ID is required.");
                    if (update.available() == null) {
                        clear.setString(1, update.roomId()); clear.setString(2, sessionId); clear.executeUpdate();
                    } else {
                        save.setString(1, update.roomId()); save.setString(2, sessionId);
                        save.setInt(3, update.available() ? 1 : 0); save.executeUpdate();
                    }
                }
                bumpSessionRevision(connection, sessionId, now);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback(); throw exception;
            }
        }
    }

    public void insert(Room room) throws SQLException { insert(room, List.of(), ""); }

    public void insert(Room room, List<RoomCsvPreview.SeatPosition> blocked, String reason) throws SQLException {
        save(room, blocked, false, reason);
    }

    public void update(Room room, List<RoomCsvPreview.SeatPosition> blocked, String reason) throws SQLException {
        save(room, blocked, true, reason);
    }

    public Map<String, List<RoomCsvPreview.SeatPosition>> findBlockedSeats() throws SQLException {
        try (Connection connection = connections.openConnection();
             var statement = connection.prepareStatement("SELECT room_id, row_number, column_number FROM seat WHERE seat_status='BLOCKED' ORDER BY room_id, row_number, column_number");
             var rows = statement.executeQuery()) {
            Map<String, List<RoomCsvPreview.SeatPosition>> seats = new HashMap<>();
            while (rows.next()) seats.computeIfAbsent(rows.getString("room_id"), ignored -> new ArrayList<>())
                    .add(new RoomCsvPreview.SeatPosition(rows.getInt("row_number"), rows.getInt("column_number")));
            return seats;
        }
    }

    public void setActive(String roomId, boolean active) throws SQLException {
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("UPDATE room SET active=? WHERE room_id=?")) {
                statement.setInt(1, active ? 1 : 0);
                statement.setString(2, roomId);
                if (statement.executeUpdate() == 0) throw new IllegalArgumentException("Unknown room ID: " + roomId);
                bumpEventRevisions(connection);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback(); throw exception;
            }
        }
    }

    public int commitImport(RoomCsvPreview preview) throws SQLException {
        if (preview.hasErrors()) throw new IllegalArgumentException("Resolve CSV errors before importing.");
        String batchId = UUID.randomUUID().toString();
        String sourceId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        int added = 0;
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var batch = connection.prepareStatement("""
                        INSERT INTO import_batch(import_batch_id, batch_label, status, created_at, committed_at)
                        VALUES (?, ?, 'COMMITTED', ?, ?)
                        """)) {
                    batch.setString(1, batchId); batch.setString(2, "Rooms: " + preview.fileName());
                    batch.setString(3, now); batch.setString(4, now); batch.executeUpdate();
                }
                try (var source = connection.prepareStatement("""
                        INSERT INTO import_source(import_source_id, import_batch_id, file_name, source_type,
                            sha256, parse_status, row_count, source_headers, created_at)
                        VALUES (?, ?, ?, 'CSV', ?, 'PARSED', ?, ?, ?)
                        """)) {
                    source.setString(1, sourceId); source.setString(2, batchId); source.setString(3, preview.fileName());
                    source.setString(4, preview.sha256()); source.setInt(5, preview.rows().size());
                    source.setString(6, preview.sourceHeaders()); source.setString(7, now); source.executeUpdate();
                }
                try (var staged = connection.prepareStatement("""
                        INSERT INTO import_staged_row(import_staged_row_id, import_source_id, source_row,
                            raw_values, normalized_values, row_status)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
                    for (RoomCsvPreview.Row row : preview.rows()) {
                        staged.setString(1, UUID.randomUUID().toString()); staged.setString(2, sourceId);
                        staged.setInt(3, row.sourceRow()); staged.setString(4, row.rawValues());
                        staged.setString(5, row.normalizedValues());
                        staged.setString(6, row.status() == RoomCsvPreview.Status.ADD ? "ACCEPTED" : "SKIPPED");
                        staged.addBatch();
                    }
                    staged.executeBatch();
                }
                try (var diagnostic = connection.prepareStatement("""
                        INSERT INTO import_diagnostic(diagnostic_id, import_batch_id, import_source_id,
                            source_row, severity, code, message)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    for (RoomCsvPreview.Row row : preview.rows()) {
                        if (row.message().isBlank()) continue;
                        diagnostic.setString(1, UUID.randomUUID().toString()); diagnostic.setString(2, batchId);
                        diagnostic.setString(3, sourceId); diagnostic.setInt(4, row.sourceRow());
                        diagnostic.setString(5, row.status() == RoomCsvPreview.Status.SKIP ? "INFO" : "WARNING");
                        diagnostic.setString(6, row.status() == RoomCsvPreview.Status.SKIP ? "EXISTING_ROOM_SKIPPED" : "CAPACITY_LAYOUT_MISMATCH");
                        diagnostic.setString(7, row.message()); diagnostic.addBatch();
                    }
                    if (preview.hasAvailabilityColumn()) {
                        diagnostic.setString(1, UUID.randomUUID().toString()); diagnostic.setString(2, batchId);
                        diagnostic.setString(3, sourceId); diagnostic.setObject(4, null);
                        diagnostic.setString(5, "INFO"); diagnostic.setString(6, "SESSION_AVAILABILITY_IGNORED");
                        diagnostic.setString(7, "is_available is not applied without an exam session."); diagnostic.addBatch();
                    }
                    diagnostic.executeBatch();
                }
                for (RoomCsvPreview.Row row : preview.rows()) {
                    if (row.status() != RoomCsvPreview.Status.ADD) continue;
                    save(connection, row.room(), row.blockedSeats(), false, "Imported from room CSV");
                    added++;
                }
                if (added > 0) bumpEventRevisions(connection);
                connection.commit();
                return added;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private void save(Room room, List<RoomCsvPreview.SeatPosition> blocked, boolean update, String reason) throws SQLException {
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try { save(connection, room, blocked, update, reason); bumpEventRevisions(connection); connection.commit(); }
            catch (SQLException | RuntimeException exception) { connection.rollback(); throw exception; }
        }
    }

    private static void bumpEventRevisions(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE exam_event SET source_revision=source_revision+1, updated_at=CURRENT_TIMESTAMP
                WHERE status<>'ARCHIVED'
                """)) {
            statement.executeUpdate();
        }
    }

    private static void bumpSessionRevision(Connection connection, String sessionId, String now) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE exam_event SET source_revision=source_revision+1, updated_at=?
                WHERE event_id=(SELECT event_id FROM exam_session WHERE exam_session_id=?) AND status<>'ARCHIVED'
                """)) {
            statement.setString(1, now); statement.setString(2, sessionId); statement.executeUpdate();
        }
    }

    public void reconcileRoom(String roomId, String newRoomCode, int newCapacity, String newStatus, String sourceNote) throws SQLException {
        if (roomId == null || roomId.isBlank()) throw new IllegalArgumentException("Room ID is required.");
        if (newRoomCode == null || newRoomCode.isBlank()) throw new IllegalArgumentException("Room code is required.");
        if (newCapacity < 0) throw new IllegalArgumentException("Usable capacity must be non-negative.");
        if (!"VERIFIED".equals(newStatus) && !"OVERRIDDEN".equals(newStatus) && !"ESTIMATED".equals(newStatus)) {
            throw new IllegalArgumentException("Capacity status must be ESTIMATED, VERIFIED, or OVERRIDDEN.");
        }
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try {
                int seatCount;
                int usableSeats;
                try (var seats = connection.prepareStatement(
                        "SELECT COUNT(*), SUM(CASE WHEN seat_status='USABLE' THEN 1 ELSE 0 END) FROM seat WHERE room_id=?")) {
                    seats.setString(1, roomId);
                    try (var row = seats.executeQuery()) { row.next(); seatCount = row.getInt(1); usableSeats = row.getInt(2); }
                }
                if (seatCount > 0 && newCapacity > usableSeats) {
                    throw new IllegalArgumentException("Room capacity cannot exceed its usable seat grid; update the layout first.");
                }
                try (var statement = connection.prepareStatement("""
                        UPDATE room SET room_code=?, usable_capacity=?, capacity_status=?, source_note=?, data_label='OPERATIONAL'
                        WHERE room_id=?
                        """)) {
                    statement.setString(1, newRoomCode.trim());
                    statement.setInt(2, newCapacity);
                    statement.setString(3, newStatus.trim());
                    statement.setString(4, sourceNote == null || sourceNote.isBlank() ? "Reconciled with official inventory" : sourceNote.trim());
                    statement.setString(5, roomId);
                    if (statement.executeUpdate() == 0) throw new IllegalArgumentException("Unknown room ID: " + roomId);
                    bumpEventRevisions(connection);
                    connection.commit();
                }
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void save(Connection connection, Room room, List<RoomCsvPreview.SeatPosition> blocked,
            boolean update, String reason) throws SQLException {
        try (var building = connection.prepareStatement("""
                INSERT INTO building(building_id, building_code, display_name, data_label) VALUES (?, ?, ?, ?)
                ON CONFLICT(building_id) DO UPDATE SET display_name=excluded.display_name
                """)) {
            building.setString(1, room.buildingCode()); building.setString(2, room.buildingCode());
            building.setString(3, room.buildingCode()); building.setString(4, room.dataLabel()); building.executeUpdate();
        }
        String centreId = null;
        if (room.centreName() != null) {
            centreId = room.centreName();
            try (var centre = connection.prepareStatement("INSERT OR IGNORE INTO reporting_centre(centre_id, centre_name) VALUES (?, ?)")) {
                centre.setString(1, centreId); centre.setString(2, room.centreName()); centre.executeUpdate();
            }
        }
        String sql = update ? """
                UPDATE room SET building_id=?, centre_id=?, room_code=?, floor_number=?, room_type=?, row_count=?,
                    column_count=?, usable_capacity=?, capacity_status=?, active=?, data_label=?, source_note=? WHERE room_id=?
                """ : """
                INSERT INTO room(building_id, centre_id, room_code, floor_number, room_type, row_count,
                    column_count, usable_capacity, capacity_status, active, data_label, source_note, room_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, room.buildingCode()); statement.setString(2, centreId);
            statement.setString(3, room.roomCode()); statement.setInt(4, room.floor());
            statement.setString(5, room.roomType()); statement.setInt(6, room.rows());
            statement.setInt(7, room.columns()); statement.setInt(8, room.usableCapacity());
            statement.setString(9, room.capacityStatus()); statement.setInt(10, room.active() ? 1 : 0);
            statement.setString(11, room.dataLabel()); statement.setString(12, room.sourceNote());
            statement.setString(13, room.roomId());
            if (update && statement.executeUpdate() == 0) throw new IllegalArgumentException("Unknown room ID: " + room.roomId());
            if (!update) statement.executeUpdate();
        }
        if (update) {
            try (var assigned = connection.prepareStatement("SELECT 1 FROM seat_assignment WHERE room_id=? LIMIT 1")) {
                assigned.setString(1, room.roomId());
                try (var row = assigned.executeQuery()) {
                    if (row.next()) throw new IllegalArgumentException("Room layout cannot change after seats are assigned in a plan.");
                }
            }
            try (var delete = connection.prepareStatement("DELETE FROM seat WHERE room_id=?")) {
                delete.setString(1, room.roomId()); delete.executeUpdate();
            }
        }
        addSeats(connection, room, blocked, reason);
    }

    private static void addSeats(Connection connection, Room room, List<RoomCsvPreview.SeatPosition> blocked, String reason) throws SQLException {
        if (!blocked.isEmpty() && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("Enter a reason for blocked seats.");
        }
        for (RoomCsvPreview.SeatPosition position : blocked) {
            if (position.row() < 1 || position.row() > room.rows() || position.column() < 1 || position.column() > room.columns()) {
                throw new IllegalArgumentException("Blocked seat falls outside the room grid: " + room.roomId());
            }
        }
        try (var seat = connection.prepareStatement("""
                INSERT INTO seat(room_id, row_number, column_number, seat_status, reason) VALUES (?, ?, ?, ?, ?)
                """)) {
            for (int row = 1; row <= room.rows(); row++) {
                for (int column = 1; column <= room.columns(); column++) {
                    boolean isBlocked = blocked.contains(new RoomCsvPreview.SeatPosition(row, column));
                    seat.setString(1, room.roomId()); seat.setInt(2, row); seat.setInt(3, column);
                    seat.setString(4, isBlocked ? "BLOCKED" : "USABLE");
                    seat.setString(5, isBlocked ? reason.trim() : null); seat.addBatch();
                }
            }
            seat.executeBatch();
        }
    }

    public record SessionOption(String sessionId, String eventCode, String date, String startTime) {
        @Override public String toString() { return eventCode + " · " + date + " " + startTime; }
    }

    public record AvailabilityUpdate(String roomId, Boolean available) {}

    public record AssignedPlanView(String eventCode, int version, String planStatus, boolean stale,
            String buildingCode, String roomCode) {}
}
