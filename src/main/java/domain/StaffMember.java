package domain;

/** A staff profile used by the invigilator dashboard. */
public record StaffMember(
        String staffId,
        String fullName,
        String department,
        String designation,
        boolean active,
        String dataLabel) {

    public StaffMember {
        if (staffId == null || staffId.isBlank()) {
            throw new IllegalArgumentException("Staff ID is required");
        }
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("Staff name is required");
        }
        fullName = fullName.trim();
        department = clean(department);
        designation = clean(designation);
        dataLabel = dataLabel == null || dataLabel.isBlank() ? "OPERATIONAL" : dataLabel.trim();
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
