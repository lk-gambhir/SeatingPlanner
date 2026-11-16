# Project Setup and Owner Inputs

## Current application setup

The repository contains the first JavaFX desktop application build:

- Java 25 source target and Maven build in `pom.xml`.
- JavaFX dashboards for invigilators, rooms, students/enrollments, and exam planning in `src/main/java/ui`.
- CSV import previews and exports for master data, attendance/availability, and saved operational plans.
- PDF date-sheet and candidate-count intake with a reviewable preview before event import.
- Deterministic room/course allocation, seat assignment, invigilator assignment, plan validation, versioned review, and finalization in `planning/` and `persistence/`.
- SQLite JDBC connection setup and automatic application of ordered migrations in `db/migrations`.
- Local database location: `~/.thapar-exam-planner/exam-planner.db` by default. Pass a path as the first program argument to use another database location.
- PDFBox is used for PDF intake. Printable PDF reports and database backup/restore are deferred.

The application database is separate from the fixture CSVs. CSV imports are previewed and require confirmation before accepted records are saved.

## Build and run

Install a JDK 25 or later. From the project directory:

```sh
./mvnw clean package
./mvnw clean javafx:run
```

The Maven Wrapper pins Maven 3.10.0. Maven downloads the JavaFX runtime for the current operating system.
The project currently has no test source files; use `clean package` to compile and package it.

## Information to prepare before operational use

The current CSV fixtures are synthetic. Before using the planner for a real exam, prepare the following source files or validated dashboard values.

### Required for the first dashboards

1. **Invigilator roster CSV**
   - Stable employee/staff ID stored as text (preserve leading zeroes).
   - Display name, department, active/inactive status.
   - Courses each staff member teaches or must be excluded from invigilating.
   - Availability and attendance are session-specific and can be set later in the dashboard.
   - A course-affiliation source is required for the rule that staff cannot invigilate their own subject.

2. **Room inventory CSV or verified room dashboard values**
   - Stable room code, building, floor, reporting centre, active/inactive status.
   - Row and column layout, usable capacity, and capacity status (estimated/verified/overridden).
   - Blocked or reserved seat locations and reason, where applicable.
   - Mark the existing estimated inventory as provisional until an authorized owner verifies it. The current assumptions are seed values, not authoritative campus records.

3. **Student roster CSV, when named students are to be managed**
   - Stable student ID and display/roll number as text, plus active status.
   - Course registrations in a separate student-course/enrollment file keyed by student ID and course code.
   - If a real event only has course counts, named student identities are not needed; those candidate places remain anonymous.

### Required for each exam event

1. **Date sheet PDF** with course/paper code, exam date, start time, and session/slot information.
2. **Course demand/count file** with one course code and expected candidate count per scheduled paper/session.
3. **One shared exam duration** for the event. The application derives the end time for every session.
4. **Optional named student-course roster** for the event, when named seating is required; reconcile its course counts against the demand file.
5. **Administrator review of room and invigilator selection** for the specific sessions before generating a plan.

Keep original source files and identify their event/academic period. For every import, review unmatched course codes, duplicate rows, conflicting counts, invalid dates, capacity mismatches, and row-level diagnostics before confirming changes.

## Decisions already captured

- The application is a local Java/JavaFX desktop app with SQLite and JDBC.
- Administrators review date-sheet/count parsing before database import.
- Named student rows are optional; count-only demand must not generate fictional identities.
- Rooms, buildings, capacities, and inventory are configuration/database records, not planning constants.
- Initial invigilator count is fixed: 3 when configured exam-usable room capacity is greater than 100, otherwise 2.
- Same-subject students should be kept apart orthogonally when feasible; diagonal neighbors are allowed. Keeping a course together in one block is a preference, subordinate to hard constraints.
- Imported conflicting data is previewed and explicitly confirmed; plans are saved as versioned drafts and finalized snapshots.
