# Automated Exam Seating and Invigilation Planner

A Java desktop application for preparing exam-session seating plans, managing rooms and invigilators, reviewing feasibility, and exporting operational reports.

The implementation order is: invigilator dashboard → room dashboard → student dashboard → date-sheet/course-count intake → seating and invigilation planning. The supplied CSVs are synthetic fixtures for the first three dashboards and later planning flows. In day-to-day use, administrators can maintain staff, rooms, and students before an exam event; for each event they import the date sheet and course counts, optionally reconcile a named roster, choose rooms/staff, and generate the plan. One shared exam duration is entered per event and the application derives each session end time. Count-only inputs create anonymous candidate seats; named seating requires a validated roster.

The intended room inventory uses provisional estimates for G/B/C/D/E/F, LT, and TAN. These estimates are provided as demo CSV data; the current application does not seed or import them automatically. The initial staffing rule is 3 invigilators for rooms with more than 100 exam-usable seats and 2 otherwise. Same-day room/staff reuse is designed around a system-managed 30-minute handover buffer.

The synthetic fixture set in [`data/demo`](data/demo/README.md) contains 10,000 generated students, a 73,616-row course roster sized to the supplied 2026 course-count PDF, 500 generated invigilators, 230 estimated rooms, and separate parser references for the older 2022 date sheet. These are demo records; no student or staff identities are copied from the PDFs.

## Technology

- JavaFX desktop UI.
- SQLite accessed through JDBC for local persistence.
- Maven Wrapper (`./mvnw`) pins Maven 3.10.0 for dependency and build management.
- PDFBox for schedule PDF import. Printable report generation is deferred.
- CSV import/export for room, student, and invigilator management. Spreadsheet import may be added as an optional adapter.

## Build and run

Install JDK 25 or later. From the project directory:

```sh
./mvnw clean package
./mvnw clean javafx:run
```

To run the automated end-to-end verification test suite from scratch:

```sh
java -cp target/classes:$(./mvnw dependency:build-classpath -Dmdep.outputFile=/dev/stdout -q) app.SystemVerification
```

For the complete step-by-step administrator user guide, see [docs/WORKFLOW.md](docs/WORKFLOW.md).

## Current status

The application has working invigilator, room, student, and exam-planning dashboards. It supports reviewed CSV/PDF intake, feasibility checks, named-student conflict preflight, room/seat/invigilator planning, saved plan versions, manual candidate and invigilator corrections, finalization, seating and duty CSVs, and a course-by-block check with CSV export. Synthetic data under `data/demo` is not loaded automatically; import it through the dashboards.

The enrollment dashboard previews PDF-count mismatches and supports withdrawing/restoring individual registrations; named rosters remain optional for count-only seating. Current saved plans export centre-wise, course-room, course-by-block, seating, and invigilator CSVs; session status export includes assigned rooms and remains import-compatible. Allocation and attendance columns were checked against representative centre-wise and subject-wise reports. Master-data exports follow visible filters; session exports use the selected session, and plan exports follow the selected session/room where applicable. Attendance export includes all staff for the selected session. Per-seat CSVs include roll/candidate ID, course, building/room, and seat coordinates; centre names appear in the separate centre-allocation CSV, while semester is not exported. Room availability and staff availability/attendance can be managed per exam session; marking a room unavailable or a staff member absent identifies affected saved plans and rooms. The invigilator dashboard also summarizes assigned staff and shortages by room for the selected session. Duplicate event codes are blocked and never replace saved event data. Runtime acceptance remains open. Printable PDF reports and database backup/restore are deferred for this release. Course-by-block output is available for current saved plans; stale plans do not show that report because their source counts may have changed.
