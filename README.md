# Automated Exam Seating and Invigilation Planner

A Java desktop application for preparing exam-session seating plans, managing rooms and invigilators, reviewing feasibility, and exporting operational reports.

The implementation order is: invigilator dashboard → room dashboard → student dashboard → date-sheet/course-count intake → seating and invigilation planning. The supplied CSVs are synthetic fixtures for the first three dashboards and later planning flows. In day-to-day use, administrators can maintain staff, rooms, and students before an exam event; for each event they import the date sheet and course counts, optionally reconcile a named roster, choose rooms/staff, and generate the plan. One shared exam duration is entered per event and the application derives each session end time. Count-only inputs create anonymous candidate seats; named seating requires a validated roster.

The room dashboard starts with editable provisional records for G/B/C/D/E/F, LT, and TAN based on the current room assumptions. The initial fixed staffing rule requires 3 invigilators for rooms with more than 100 exam-usable seats and 2 otherwise. Same-day room/staff reuse uses a system-managed 30-minute handover buffer.

The synthetic fixture set in [`data/demo`](data/demo/README.md) contains 10,000 generated students, a 73,616-row course roster sized to the supplied 2026 course-count PDF, 500 generated invigilators, 230 estimated rooms, and separate parser references for the older 2022 date sheet. These are demo records; no student or staff identities are copied from the PDFs.

## Project documents

- [Product requirements](docs/PRD.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Logical data model](docs/DATA_MODEL.md)
- [Database setup, normalization, indexes, and demo imports](docs/DATABASE_SETUP.md)
- [Initial SQLite schema migration](db/migrations/V001__initial_schema.sql)
- [Seating assignment algorithm](docs/SEATING_ALGORITHM.md)
- [Implementation tasks](docs/TASKS.md)
- [Testing and acceptance](docs/TESTING.md)
- [Product decisions and remaining owner inputs](docs/DECISIONS.md)
- [Agent and contributor guidance](AGENTS.md)

## Proposed technology

- Java desktop UI with Swing.
- SQLite accessed through JDBC for local persistence.
- Maven Wrapper (`./mvnw`) pins Maven 3.10.0 for dependency and build management.
- PDFBox for schedule PDF import and printable PDF reports.
- CSV import/export for room, student, and invigilator management. Spreadsheet import may be added as an optional adapter.

## Current status

The repository now contains the Maven/Swing shell, ordered SQLite migrations, and the first staff domain/JDBC repository slice. The dashboard tabs are placeholders; next add the staff CSV review flow and invigilator screen, then room and student management using the supplied synthetic fixtures. Build date-sheet/course-count intake after those dashboards, followed by seating generation and plan review.

See [Project Setup and Owner Inputs](docs/PROJECT_SETUP.md) for build/run instructions and the operational data still needed from the project owner.
