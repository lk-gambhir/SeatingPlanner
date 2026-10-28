# Database setup and demo-data import

This guide defines the setup path for the local SQLite database and the order for loading the current demo CSVs. The repository currently contains documentation, the migration, and CSV fixtures; it does not yet contain a Maven project, Java repositories, a migration runner, or the Swing import screens. The steps below are the implementation sequence for those pieces. Do not use direct production-table CSV imports as a substitute for the application's preview and confirmation flow.

## 1. What the database stores

Use SQLite locally, accessed only through Java JDBC repositories. The first schema migration is [`V001__initial_schema.sql`](../db/migrations/V001__initial_schema.sql). It defines normalized records for:

- import batches, source files, staged rows, and row diagnostics;
- buildings, rooms, seat geometry, and reporting centres;
- exam events, dated/time sessions, courses, and per-session course demand;
- students, registrations, staff, staff-course relationships, staff availability, and attendance;
- versioned plans, room/seat/staff assignments, and feasibility diagnostics.

The PDF and CSV files remain outside the database. Store file name, content hash, source type, parse result, source row/page, labels, and accepted-row diagnostics. Staging rows keep the raw and normalized row values reviewable until the administrator commits or rejects the batch. Clear staged raw values after commit or rejection when they contain personal data; retain batch/source metadata and diagnostics.

## 2. Build and configure the application

1. Install a JDK that matches the Java baseline selected in `pom.xml`, plus Maven. The baseline must be pinned when the application skeleton is added; it is not defined in this docs-only repository yet.
2. Create the Maven desktop project and add the SQLite JDBC driver as the only required database dependency. Keep PDF/CSV parsing and JDBC persistence behind services/repositories; Swing must not issue SQL.
3. Choose a local database path outside the source-data directory, for example `~/.exam-planner/exam-planner.sqlite`. Allow an explicit `--database` path or application setting for a separate demo database. Never use the operational database for fixture practice.
4. On every JDBC connection, enable foreign keys (`PRAGMA foreign_keys=ON`) and set a bounded busy timeout. Configure WAL mode once during database initialization if the application will keep a reader open while writing. Use a bounded connection/repository lifecycle; SQLite remains a single-writer local database.
5. Add the migration runner described below and apply `V001` before starting dashboards or imports.

## 3. Migration procedure

1. Open the configured SQLite file using JDBC. SQLite creates the file on first open; create its parent directory first and restrict file permissions to the current user because student/staff records can be personal data.
2. Ensure `PRAGMA foreign_keys=ON` on the connection before any migration or write transaction.
3. Before reading migrations, bootstrap the migration ledger with `CREATE TABLE IF NOT EXISTS schema_migration (version INTEGER PRIMARY KEY, description TEXT NOT NULL, applied_at TEXT NOT NULL)`. Read applied versions from this ledger. It is migration-runner metadata, not part of V001's application schema.
4. Apply each unapplied SQL migration inside a JDBC transaction. Execute the statements from the migration resource, insert its version/description/applied timestamp into `schema_migration`, then commit. Roll back and report the migration name on any failure. Never edit an already released migration; create `V002`, `V003`, etc.
5. After migration, check `PRAGMA foreign_key_check` and `PRAGMA integrity_check`. A healthy new database has no foreign-key violations and returns `ok` for integrity.
6. Seed only application settings that have defined defaults. V001 seeds the system-managed room/staff handover buffer of 30 minutes. The staffing threshold stays in planner code: room capacity greater than 100 requires 3 staff; otherwise it requires 2.

### Migration-runner detail

SQLite connections do not enable foreign keys by default. The JDBC repository factory must execute `PRAGMA foreign_keys=ON` whenever it opens a connection. The migration runner should acquire a write lock with a transaction, use prepared statements for the migration ledger, and prevent a second startup from applying the same version concurrently. Keep each migration small, ordered, and forward-only. For initial project setup, a fresh database is easiest; later migrations must preserve data and include a backup/recovery path.

## 4. Normalization and indexing

The CSVs are convenient interchange files, not the relational schema. Normalize them as follows:

- Store each building once; every room references `building_id`. Store each course once; schedule rows, student registrations, and teacher relationships reference `course_id`.
- Store each dated start-time sitting once in `exam_session`; a course paper and candidate count are one `session_course` row. Many course papers share one session.
- Store each student and staff member once. `enrollment` joins a student to a scheduled course paper. `staff_course_relationship` joins staff to courses with `TEACHES`/`EXCLUDED`; `staff_availability` and `staff_attendance` each contain one row per staff/session.
- Store every physical seat coordinate once. Room capacity and seat geometry are separate: the 200-seat layouts have 200 grid positions; estimated room capacity remains editable and is not silently derived from the grid.
- Keep `unavailable_slot_ids` as source data for preview only. After schedule matching, normalize it into one `staff_availability` row per staff/session; do not keep a semicolon-delimited list in the operational schema.
- Keep counts on `session_course`, not duplicated on every student registration. Do not import both `enrollments.csv` and `course_student_list.csv` as authoritative records; they are two views of the same registrations.
- Use `TEXT` for all externally supplied IDs, including numeric-looking values and roll numbers. Use integer foreign keys only for internal seat row IDs where useful.

V001 includes unique keys for stable IDs/codes, duplicate prevention for event/date/start, session/course, student/paper, room/seat, and staff/course relations, plus indexes for the dashboard and planning lookup paths. SQLite does not automatically index foreign-key child columns, so V001 adds those indexes explicitly. Do not add indexes to every column: add an index when it supports a real filter, join, uniqueness rule, or conflict check. Re-check common queries with `EXPLAIN QUERY PLAN` if fixture or operational size shows a slow path.

## 5. Safe demo import sequence

Imports should run in a background worker. Parse to staged rows, show a preview, and only then write accepted rows in one JDBC transaction using prepared statements. The preview must show inserts, updates, skipped duplicates, validation errors, and warnings before commit. Save an `import_batch`/`import_source` record and row diagnostics. On any write failure, roll back the whole confirmed batch. Do not delete or overwrite a conflicting existing record without a previewed administrator choice.

### 5.1 Load master dashboards

1. **Rooms:** import `data/demo/rooms.csv`. Upsert only by stable `room_id` after preview; map each `building_code` to one `building` row, then insert/update its rooms. Derive a display `room_code` from the suffix of `room_id` (for example, `DEMO-G-F01-R01` becomes `F01-R01`) while preserving the full stable ID. Empty centre fields become SQL `NULL`, not the strings `""` or `"null"`. Mark all demo capacity rows estimated. Expand the configured rectangular grid to seat-coordinate rows: 216 small rooms × 30 seats and 14 large rooms × 200 seats = 9,280 starter seats. If a future room has blocked seats, set the seat status and compare usable-seat count with configured capacity; report a mismatch instead of rewriting capacity.
2. **Invigilators:** import `data/demo/invigilators.csv` and `data/demo/invigilator_course_assignments.csv` as separate previews. The second file depends on staff and course records. Split `unavailable_slot_ids` by `;`, match each slot to the event's date/time session, and create availability rows. Unknown slot IDs become row diagnostics. `max_duties` is demo-only and is not stored as a workload rule.
3. **Students:** import `data/demo/students.csv`. Preserve all student and roll identifiers as exact text.

Room, staff, and student dashboard records can be edited before an exam exists. For first development, implement staff → room → student dashboards as stated in the PRD; that UI implementation order is separate from the foreign-key import order above.

### 5.2 Load the 2026 event and course demand

1. Import `exam_sessions.csv` as the assessment-period/event record. Map `session_id` to `exam_event.event_code`/source key, not to a timed sitting.
2. Import `exam_events.csv` and `course_demand_source.csv` as one schedule/count review. Deduplicate the 386 rows by `(exam_date, start_time)` to create 41 `exam_session` records; create 386 courses/session-course relations. `course_id`/`course_code` is the course code. The PDF-derived `candidate_count` goes on `session_course` with `count_provenance='PDF_IMPORTED'`. Preserve page, source row, reported session total, file name, and source hash in import provenance/staging.
3. Ask once for the shared exam duration. Compute and preview every end time from the start. Do not infer a duration from the old 2022 schedule or import a handover buffer from a file.
4. Reconcile each slot's 2026 reported total against the sum of its course counts. The fixture has 73,616 candidates in total, 386 papers, 41 time slots, and a maximum slot demand of 3,831. A mismatch is an import diagnostic; never adjust course counts automatically.
5. The 2022 `date_sheet_parser_2022_courses.csv` is a parser-only fixture. It has no matched roster or count source and must not be attached to the 2026 event.

### 5.3 Load named registrations

1. Import `enrollments.csv` as the canonical normalized roster for this fixture. It depends on students, course records, event schedule, and `session_course` rows. For each row, resolve the course and event to exactly one scheduled paper and write one `enrollment` record.
2. Alternatively, use `course_student_list.csv` when exercising the flat roster dashboard importer. It is a convenience representation of those same rows. Never import both files into the same event unless the preview detects and deliberately skips the exact duplicate keys.
3. Reconcile the registration count for each paper to `candidate_count` before planning. The demo fixture is constructed to match exactly, but the production flow must report mismatches and preserve both values.
4. Validate no student has two papers in the same session. Keep registrations separate from seat assignments; plan generation creates assignments only in a versioned draft.

### 5.4 Load staff/course exclusions

Import `invigilator_course_assignments.csv` after course master records exist. The explicit relationship type drives staff eligibility. Never infer exclusions from department. For each planned room, validate teachers against every course scheduled in that room.

## 6. Required import checks

Before committing a demo batch, check:

- 10,000 students; 73,616 registrations; 386 unique course papers; 41 dated/time sessions; 500 staff; 1,000 staff/course relationships; 230 rooms; 9,280 estimated seat positions.
- Every foreign key resolves; no duplicate stable IDs, roll numbers, `(session, course)`, `(student, session_course)`, or `(building, room_code)` keys.
- For each course paper, registered count equals the imported PDF candidate count. For each session, sum of course counts equals its reported total.
- Every student has at most one course paper in each same-time session.
- Every seat coordinate falls inside its room grid, and usable-seat count versus configured capacity produces an explicit diagnostic when different.
- `PRAGMA foreign_key_check` returns no rows and `PRAGMA integrity_check` returns `ok` after commit.

## 7. Backups and separation

- Store operational SQLite files outside the repository. Add `.sqlite`, `.sqlite-wal`, `.sqlite-shm`, and backup artifacts to `.gitignore`.
- Keep the synthetic source CSVs in `data/demo` under version control; never commit a database file containing real student/staff data.
- Before schema upgrades or large imports, create a timestamped backup. Use SQLite's online backup API or close the database cleanly and copy it; do not copy only the main file while WAL writes are active.
- Retain source CSV/PDF references and checksums for review. Do not store source documents as BLOBs by default.
