package domain;

/** A student profile; registrations remain separate enrollment records. */
public record Student(
        String studentId,
        String rollNumber,
        String fullName,
        String program,
        Integer semester,
        String section,
        boolean active,
        String dataLabel,
        int enrollmentCount) {

    public Student {
        if (studentId == null || studentId.isBlank()) throw new IllegalArgumentException("Student ID is required.");
        if (rollNumber == null || rollNumber.isBlank()) throw new IllegalArgumentException("Roll number is required.");
        if (fullName == null || fullName.isBlank()) throw new IllegalArgumentException("Student name is required.");
        if (semester != null && semester < 1) throw new IllegalArgumentException("Semester must be positive.");
        studentId = studentId.trim();
        rollNumber = rollNumber.trim();
        fullName = fullName.trim();
        program = clean(program);
        section = clean(section);
        dataLabel = dataLabel == null || dataLabel.isBlank() ? "OPERATIONAL" : dataLabel.trim();
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
