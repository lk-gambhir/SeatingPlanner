package domain;

/** One room and its editable seating-grid dimensions. */
public record Room(
        String roomId,
        String buildingCode,
        String roomCode,
        int floor,
        String centreName,
        String roomType,
        int rows,
        int columns,
        int usableCapacity,
        String capacityStatus,
        boolean active,
        String dataLabel) {

    public Room {
        if (roomId == null || roomId.isBlank()) throw new IllegalArgumentException("Room ID is required.");
        if (buildingCode == null || buildingCode.isBlank()) throw new IllegalArgumentException("Building is required.");
        if (roomCode == null || roomCode.isBlank()) throw new IllegalArgumentException("Room code is required.");
        if (floor < 0 || rows < 1 || columns < 1 || usableCapacity < 0 || usableCapacity > rows * columns) {
            throw new IllegalArgumentException("Check the floor, grid size, and usable capacity.");
        }
        if (!"ESTIMATED".equals(capacityStatus) && !"VERIFIED".equals(capacityStatus)
                && !"OVERRIDDEN".equals(capacityStatus)) {
            throw new IllegalArgumentException("Capacity status must be ESTIMATED, VERIFIED, or OVERRIDDEN.");
        }
        buildingCode = buildingCode.trim();
        roomCode = roomCode.trim();
        roomType = roomType == null || roomType.isBlank() ? "CLASSROOM" : roomType.trim();
        centreName = clean(centreName);
        dataLabel = dataLabel == null || dataLabel.isBlank() ? "OPERATIONAL" : dataLabel.trim();
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
