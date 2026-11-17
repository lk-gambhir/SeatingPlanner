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

For the complete step-by-step administrator user guide, see [docs/WORKFLOW.md](docs/WORKFLOW.md).

## Current status

The application has working invigilator, room, student, and exam-planning dashboards. It supports reviewed CSV/PDF intake, feasibility checks, named-student conflict preflight, room/seat/invigilator planning, saved plan versions, manual candidate and invigilator corrections, finalization, seating and duty CSVs, and a course-by-block check with CSV export. Synthetic data under `data/demo` is not loaded automatically; import it through the dashboards.

The enrollment dashboard previews PDF-count mismatches and supports withdrawing/restoring individual registrations; named rosters remain optional for count-only seating. Saved plans export centre-wise, course-room, course-by-block, seating, and invigilator CSVs; master-data exports follow visible filters, and plan exports follow the selected session/room. Room availability and staff availability can be set per exam session from the Rooms and Invigilators tabs; marking a room or staff member unavailable lists the saved plans and rooms it affects. A staff member linked to a course is never assigned to a room where that course is examined. The invigilator dashboard summarizes assigned staff and shortages by room for the selected session. Duplicate event codes are blocked and never replace saved event data; imported events cannot be deleted from the app. Printable PDF reports and database backup/restore are deferred. Course-by-block output is available for current saved plans; stale plans do not show that report because their source counts may have changed.
