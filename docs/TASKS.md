# Implementation Tasks

This is the delivery checklist for the first working release. Tasks are intentionally small and ordered by dependency. Build only the packages and services a task needs; do not create empty folders or framework layers in advance.

## Product rules every milestone follows

- Build the master-data dashboards in this order: invigilators, rooms, students. Build date-sheet/count intake after them, then planning.
- Master-data dashboards work without an exam event. Session availability, attendance, and session assignments become actionable when event/session data exists.
- CSV/PDF imports are staged, diagnosed, previewed, and deliberately confirmed before they change saved data.
- Keep IDs as strings, preserve source metadata, and never turn aggregate candidate counts into fictional named students.
- Keep configured buildings, rooms, capacities, and course data in SQLite. Initial staffing is the fixed exception: capacity `> 100` requires 3 invigilators; otherwise 2.
- Same-course candidates should not be orthogonal neighbors when feasible; diagonals are allowed. Keeping a course in one block is a preference.
- Count-based seating may be anonymous. Named seating and student conflict checks require a reconciled roster.
- Keep SQL in JDBC persistence classes, rules in services/domain code, and rendering/interaction in JavaFX.
- Every draft plan is revalidated and versioned. Finalized plan snapshots and finalization metadata are preserved.
- Verify business rules and persistence before cleanup; temporary verification code is removed after the checks pass.

## Current implementation state

- [x] Maven `pom.xml` exists with Java 25 release target and SQLite JDBC/PDFBox dependencies.
- [x] Shallow source packages exist under `src/main/java/app`, `ui`, and `persistence`.
- [x] Main JavaFX shell has working invigilator, room, student, and exam-import tabs.
- [x] SQLite connection setup enables foreign keys and a busy timeout; startup selects the local database path.
- [x] Initial normalized schema and migration runner exist; startup applies ordered migrations and checks database integrity.
- [x] `mvn clean package` succeeds on the available JDK 26 while compiling for Java 25; Maven Central artifacts were cached under a temporary directory.
- [x] Official Maven Wrapper files pin Maven 3.10.0; `./mvnw clean package` succeeds using a fresh wrapper home.
- [x] Database bootstrap check applies ordered migrations across repeated startup calls; SQLite reports integrity `ok`, and a JDBC connection reports foreign keys enabled.
- [ ] Confirm the JavaFX frame visually; this sandbox reports no usable JavaFX screen and exposes no Java window to desktop automation.
- [x] Staff, room, and student domain records and JDBC repositories exist; profile rules, imports, text IDs, and affiliations were verified before test-source cleanup.
- [x] Plan metadata migrations add generation settings, finalizer fields, and saved display snapshots while retaining pre-existing plan rows.
- [x] Forty-three automated tests passed for migrations, imports, repositories, feasibility, and seat assignment before test-source cleanup.
- [x] Four focused plan-validator cases passed before their temporary test source was removed.
- [x] Saved-plan listing/reopen/finalization/snapshot and CSV-export checks passed before temporary test-source cleanup.
- [x] Manual seat move/replacement, saved `MANUAL` origin, and course-by-block split/count-mismatch checks passed before temporary test-source cleanup.
- [x] Session availability/attendance updates, event revision invalidation, and CSV row validation passed before temporary test-source cleanup.
- [x] Room session availability dashboard and CSV controls invalidate only the selected event and report affected plans.
- [x] Named-student overlap preflight reports conflicting course/time pairs and identifies count-only exams.
- [x] Invigilator dashboard summarizes assigned staff, required staff, shortages, and stale plan versions by room.
- [x] Marking assigned staff absent identifies saved plan rooms and their stale/version status before temporary test-source cleanup.
- [x] Staff, room, and student-profile CSV import/export actions are implemented with preview and explicit confirmation.
- [x] Saved-plan centre-wise and course-room allocation exports are available.
- [x] Attendance status export includes assigned room context and remains import-compatible.
- [x] Plan exports follow the selected session and room where applicable.
- [x] Compare allocation and attendance CSV fields with representative centre-wise and subject-wise source reports.
- [x] Manual seat and invigilator edits save a new validated plan version.
- [x] Course-by-block check compares allocated seats with each course's expected count and exports the result to CSV.

## Owner-selected scope for the current milestone

- Course-by-block report: include in the current build.
- Advanced combined filters and filter presets: deferred; existing basic dashboard search/filters remain.
- Separate `course_student_list.csv` import path: deferred; use `students.csv` plus `enrollments.csv` as the source of truth.
- Graphical click-to-move seat grid: deferred to keep the editor small; use the current candidate/room/seat pickers.
- Printable PDF reports: deferred; retain PDF input and CSV output.
- SQLite backup and restore: deferred.

---

## Phase 0 — Finish the runnable foundation

**Outcome:** a contributor can build and launch the starter app, and first launch creates the local SQLite database.

### P0.1 — Build the starter project

- [x] Use JDK 25 or later; Maven Wrapper pins Maven 3.10.0.
- [x] Run `./mvnw clean package` from the project root.
- [x] Resolve compiler/dependency/resource errors without changing the agreed Java baseline.
- [x] Confirm that `db/migrations/V001__initial_schema.sql` is included in the packaged application resources.
- [ ] Confirm the JavaFX frame opens visibly; the current environment exposes no usable screen for JavaFX rendering.

**Accept when:** a clean checkout builds and opens the app using the documented commands.

### P0.2 — Verify first-run database startup

- [x] Initialize a new database and confirm its parent directory/database file are created outside the repository.
- [x] Confirm the initial migration is recorded once in `schema_migration`.
- [x] Initialize a second time and confirm the migration is not applied again.
- [x] Confirm foreign-key enforcement is enabled on each opened connection (`PRAGMA foreign_keys = 1`).
- [ ] Exercise a migration failure path and show a clear startup error without a stack trace in the user dialog.

**Accept when:** a fresh database is initialized once, a second launch preserves it, and a failed startup is recoverable and understandable.

### P0.3 — Add Maven Wrapper

- [x] Install Maven in the development environment for initial wrapper generation.
- [x] Generate the official only-script Maven Wrapper for Maven 3.10.0.
- [x] Add wrapper files and document `./mvnw`/`mvnw.cmd` commands.
- [x] Confirm wrapper build works from a fresh wrapper home without relying on Maven's existing wrapper cache.

**Accept when:** contributors can build with the wrapper on macOS/Linux and Windows.

---

## Phase 1 — Persistence and the smallest shared domain layer

**Depends on:** Phase 0. The initial database schema is already present; check it against the logical model before adding migrations.

### P1.1 — Review schema against the model

- [x] Compare tables, keys, relationships, constraints, and indexes in V001 with `DATA_MODEL.md`.
- [x] Confirm student/staff identifiers are stored as text.
- [x] Confirm `enrollment` links a real student to a scheduled course and does not represent anonymous count demand.
- [x] Confirm provisional room records and operational settings are data, not Java constants.
- [x] Add missing plan snapshot/change/finalization metadata as V002; preserve V001 and existing plan rows.

**Accept when:** a fresh database matches the documented relationships and all corrections preserve existing records.

### P1.2 — Add database access helpers

- [x] Use a short explicit transaction for replacing a staff member's course affiliations.
- [x] Roll back the affiliation replacement if any insert fails.
- [x] Open short-lived connections in repository methods; do not share them with JavaFX components.
- [x] Do not add a separate schema-readiness query: startup applies migrations and fails clearly if bootstrap fails.

**Accept when:** repository writes that affect multiple rows are atomic and SQL remains outside the UI.

### P1.3 — Add repositories needed by the first dashboard

- [x] Define the minimum staff repository operations: list, find by ID, insert, update, deactivate/reactivate.
- [x] Define the minimum staff-course relationship operations: list, add, remove, replace.
- [x] Implement JDBC queries with prepared statements.
- [x] Return domain records/results instead of `ResultSet` or JDBC types.
- [x] Map duplicate staff IDs to an actionable error.

**Accept when:** persistence supports staff profiles and teaching/exclusion relationships without exposing JDBC types.

### P1.4 — Add minimal staff/course domain records

- [x] Represent staff ID, name, department, designation, active state, and data label.
- [x] Represent course ID and staff relationship type.
- [x] Validate required values and preserve IDs as entered text.
- [x] Keep `max_duties` in demo input informational only; no workload cap policy is implemented.
- [x] Keep scheduling-specific availability/attendance out of the unscheduled staff profile model.

**Accept when:** invalid profile values are rejected before persistence and profile records work without an event.

---

## Phase 2 — Shared CSV import/export foundation

**Depends on:** P1. This is a small reusable workflow for the three dashboards, not a general import framework for arbitrary formats.

### P2.1 — Define CSV input behavior

- [x] Read UTF-8 and UTF-8-with-BOM staff and room CSV files.
- [x] Support quoted fields, embedded commas, escaped quotes, and LF/CRLF line endings through Commons CSV.
- [x] Map staff and room headers case-insensitively after trimming whitespace.
- [x] Preserve original header names in import provenance.
- [x] Treat staff and room IDs as strings; validate required fields rather than coercing values.
- [x] Add field-specific repair guidance to profile, enrollment, and session-availability CSV diagnostics.
- [x] Use Commons CSV to meet the required quoting rules reliably.

**Accept when:** fixture CSVs and representative malformed CSV files produce stable staged rows and useful diagnostics.

### P2.2 — Build staging and preview records

- [x] Record staff/room source filename, checksum, type, import timestamp, and row count.
- [x] Persist raw and normalized values for every staged CSV row.
- [x] Show counts for additions, skipped existing rows, and invalid rows.
- [x] Detect duplicate IDs/codes within these files and conflicts with existing records.
- [x] Show field-by-field saved-versus-CSV differences for existing staff, student, and room profiles; compare blocked-seat layouts and keep saved values unchanged.
- [x] Keep saved staff, room, and student profile values for matching IDs; skip the incoming row and show saved-versus-CSV differences in preview.

**Accept when:** parsing a file has no database side effects and every staged row has a review status.

### P2.3 — Confirm and commit imports

- [x] Preview staff/room source filenames, row counts, and row diagnostics.
- [x] Allow the administrator to exclude individual valid rows from a batch.
- [x] Require a deliberate confirmation before writing accepted rows.
- [x] Commit accepted rows and import provenance in one transaction.
- [x] Roll back writes if an accepted row cannot be saved.
- [x] Record source rows imported or skipped; invalid previews are not committed.

**Accept when:** cancelling leaves operational data unchanged and confirming creates one traceable import batch.

### P2.4 — Build filtered CSV export

- [x] Export currently filtered staff/room records with stable headers and IDs.
- [x] Quote commas, quotes, and line breaks correctly.
- [x] Write UTF-8 output with a header row.
- [x] Offer a save location and report success/failure without freezing the UI.
- [x] Ensure exported files can be re-imported through the same preview flow.

**Accept when:** a filtered export round-trips without changing identifiers or field values.

---

## Phase 3 — Invigilator dashboard (build first)

**Depends on:** P1.3–P1.4 and Phase 2. Staff profiles must be manageable before an exam event is loaded.

### P3.1 — Dashboard layout and staff list

- [x] Replace the Invigilators placeholder tab with a dedicated panel.
- [x] Add a summary strip for total active staff, inactive staff, and staff with course affiliations.
- [x] Add a table with staff ID, name, department, designation, active state, and course-affiliation count.
- [x] Load table data through JDBC repositories; keep SQL out of JavaFX components.
- [x] Show empty and database-error states; the synchronous local refresh does not need a separate loading state.
- [x] Keep table updates on the JavaFX Application Thread.

**Accept when:** profiles appear consistently from SQLite and the screen remains responsive during refreshes.

### P3.2 — Search, filters, and selection

- [x] Add text search by staff ID, name, and department.
- [x] Add an active/inactive filter.
- [x] Defer department and course-affiliation filters with the other advanced filters; retain basic search and active filters.
- [x] Clear selection when filters change; visible-row selection and table sorting keep the selected action target clear.
- [x] Add Select all filtered and Clear selection actions.
- [x] Show filtered count; selection and bulk controls remain.
- [x] Ensure bulk actions apply only to the visible filtered selection when requested; filter changes clear selection and bulk actions check visible rows.

**Accept when:** administrators can locate and select individual or filtered groups without accidentally applying actions to hidden rows.

### P3.3 — Add and edit staff profiles

- [x] Add a form for staff ID, display name, department, and designation.
- [x] Validate required fields and trim surrounding whitespace.
- [x] Preserve staff IDs as strings, including leading zeroes.
- [x] Prevent duplicate IDs; the import preview shows the saved record.
- [x] Keep staff ID fixed while editing an existing profile.
- [x] Support deactivate/reactivate rather than hard-deleting staff.
- [x] Show a confirmation summary for bulk deactivation; save selected active staff changes in one transaction.

**Accept when:** staff can be created and maintained safely without an event loaded.

### P3.4 — Manage taught/excluded courses

- [x] Add a staff detail view showing the courses the person teaches or is excluded from supervising.
- [x] Add/remove course relationships for one staff member.
- [x] Support a CSV relationship file keyed by staff ID and course ID/code.
- [x] Validate staff and course references before import confirmation.
- [x] Distinguish a teaching relationship from an explicit exclusion if the source format supports both.
- [x] Use these relationships to exclude a teacher from rooms containing that course in feasibility allocation.

**Accept when:** course eligibility is visible and persisted; no relationship is inferred just from department.

### P3.5 — CSV staff import/export

- [x] Add Import CSV and Export CSV actions to the dashboard.
- [x] Map profile fields and warn that demo-only duty/availability columns are not applied.
- [x] Preview new staff, duplicate IDs, and invalid rows.
- [x] Show changed staff fields and their saved/CSV values when an existing ID is skipped.
- [x] Keep saved staff profiles for duplicate IDs and show differences; preview course-reference problems in the staff-course import.
- [x] Let the user select rows/actions and confirm the batch.
- [x] Import staff-course relationships after valid staff/course records are available.
- [x] Export the current filtered staff list.
- [x] Export a separate relationship file.
- [x] Preserve the `data_label` source marker for synthetic data.

**Accept when:** `invigilators.csv` and `invigilator_course_assignments.csv` can be reviewed and imported without automatic startup seeding.

### P3.6 — Add session-specific staff controls when sessions exist

- [x] Add a session selector that is disabled with a clear explanation before schedule data exists.
- [x] Add whole-session availability controls for available/unavailable/not-set.
- [x] Add present/absent/not-recorded attendance controls for a selected session.
- [x] Import/export session statuses as CSV with validation and preview before updates.
- [x] Show saved room assignments, assigned/required staff counts, shortages, and stale plan status for a selected session.
- [x] Ensure absence/unavailability changes identify the specific assigned rooms and affected plan versions.
- [x] Do not apply a duty-count limit from demo `max_duties` values.

**Accept when:** session controls operate only against a real selected session and explain pending state before one exists.

---

## Phase 4 — Room dashboard

**Depends on:** P1 persistence and Phase 2 CSV flow. Build after the invigilator dashboard.

### P4.1 — Seed/reconcile provisional room data

- [x] Load provisional inventory through CSV import into SQLite, not Java constants.
- [x] Verify the fixture estimates: G/B/C/D/E/F use 3 floors × 12 rooms × 30 seats; LT uses 4 × 2 × 200; TAN uses 3 × 2 × 200.
- [x] Preserve `ESTIMATED` capacity status and `DEMO` labels from the fixture.
- [x] Make the 5×6 and 10×20 layouts editable and keep them marked estimated.
- [x] Re-import previews and skips already saved IDs/codes.
- [x] Allow the administrator to reconcile demo rooms with authoritative room codes later.

**Accept when:** a demo database contains the expected provisional inventory once, and all values are editable and marked unverified.

### P4.2 — Room summary and list

- [x] Replace the Rooms placeholder tab with a room dashboard.
- [x] Show active rooms, configured seats, and rooms needing verification.
- [x] Show unavailable-room count when a session is selected.
- [x] Add a room table with building, floor, room code, configured/seat-derived capacity, capacity status, active state, and layout status.
- [x] Add search by room code/building/centre and filters by building, floor, capacity status, and active state.
- [x] Filter by availability in the selected session.
- [x] Make each summary card apply its matching inventory filter.
- [x] Provide an empty state and database error alerts. A loading state is not needed for current synchronous local queries.

**Accept when:** room inventory can be inspected and filtered without opening an exam event.

### P4.3 — Add/edit room and seat layout

- [x] Add/edit building, room code, floor, reporting centre, room type, layout dimensions, and configured usable capacity.
- [x] Reject duplicate room codes within a building and invalid dimensions/capacity.
- [x] Defer the graphical seat grid to keep the editor small; retain the existing blocked-coordinate controls.
- [x] Give imported blocked seats an import reason; reserved seats and custom reasons remain.
- [x] Show configured capacity and seat-derived capacity side by side.
- [x] Warn when configured capacity differs from the usable grid during CSV preview.
- [x] Allow estimated/verified/overridden capacity status.
- [x] Add a free-form source/note field.
- [x] Deactivate rooms rather than deleting rooms referenced by plans.

**Accept when:** saved room layout, capacity, and provenance agree and invalid charts cannot be generated silently.

### P4.4 — Room CSV import/export

- [x] Map room fixture columns to building, floor, centre, room layout, blocked seats, capacity, and capacity status.
- [x] Preview new rooms, duplicate room codes, and layout inconsistencies.
- [x] Show saved-versus-CSV room and blocked-seat differences when an existing room is skipped.
- [x] Keep saved room profiles for duplicate IDs/codes and show the proposed CSV differences in preview; do not replace existing layout or capacity values.
- [x] Export filtered room inventory with stable room/building codes.
- [x] Import/export per-session room availability CSV with validation and preview.
- [x] Preserve source labels and CSV import provenance.

**Accept when:** `rooms.csv` imports through preview and can be exported round-trip without silently overwriting verified values.

### P4.5 — Session availability controls when sessions exist

- [x] Add session selection to the room dashboard.
- [x] Show available/unavailable status for the selected session; rooms without an override default to available.
- [x] Support selected-room availability changes with confirmation and CSV import/export.
- [x] Report saved plan versions and rooms affected when a room is marked unavailable.
- [x] Bump only the selected event's source revision so its saved plans become stale and require review.

**Accept when:** room availability is event/session-specific and plan impact is visible.

---

## Phase 5 — Student and enrollment dashboard

**Depends on:** P1 schema and Phase 2 CSV flow. Build after rooms.

### P5.1 — Student list and search

- [x] Replace the Students placeholder tab with a student profile view and registration summary.
- [x] Show student ID, roll number, program, semester, section, active status, and enrollment count.
- [x] Search by student/roll ID and name; filter by program, semester, section, and active state.
- [x] Preserve IDs as text, including leading zeroes.
- [x] Retain `data_label=DEMO` from the synthetic student fixture.

**Accept when:** dashboard shows existing records without treating course counts as students.

### P5.2 — Student profile and course registrations

- [x] Add/edit student profile fields that exist in the source roster.
- [x] Show existing course registrations with course, event, and session context.
- [x] Withdraw or restore an individual registration from the selected student's registration list.
- [x] Keep course records normalized; do not concatenate multiple course IDs into one student field.
- [x] Support per-student status changes and confirmed bulk active/inactive changes for selected visible students; apply bulk updates transactionally and clear selections when filters change.

**Accept when:** one student can have multiple distinct course registrations and each registration has a valid relationship.

### P5.3 — Student/enrollment CSV import/export

- [x] Map and import `students.csv` as the profile source with a row preview and transactional provenance.
- [x] Parse and save large student profile CSVs in background tasks; show up to 15 row errors plus 10 valid sample rows.
- [x] Show changed student fields and their saved/CSV values when an existing ID or roll number is skipped.
- [x] Map `enrollments.csv` to a selected saved event after event/course/session intake.
- [x] Preview missing students, unknown/ambiguous courses, repeated enrollment keys, inactive students, and ID conflicts.
- [x] Make import order explicit: import student profiles and the event before its enrollments.
- [x] Keep `course_student_list.csv` out of the import path; `enrollments.csv` is the authoritative registration source.
- [x] Compare active registered rows by course to PDF demand and show mismatches before confirmation.
- [x] Export filtered student rows.
- [x] Export enrollments for a selected event using the importable CSV columns.
- [x] Allow count-only operation without loading any named student rows; the dashboard explains anonymous seating.

**Accept when:** enrollment count mismatches are visible, missing rows can be added by CSV import, excess rows can be withdrawn, and count-only exams do not require named student data.

### P5.4 — Student conflict preflight

- [x] For a selected exam event, identify the same active student registered in overlapping courses.
- [x] Show the student ID, conflicting course papers, and affected session times.
- [x] Keep unresolved student conflicts as hard blockers for named seating during plan validation.
- [x] Report that the check is unavailable when the exam has no named enrollment rows.

**Accept when:** roster conflicts are identified before named plan generation and count-only inputs remain unaffected.

### P5.5 — Show exam demand in the student dashboard

- [x] Use the selected exam to list every dated/time session and its PDF course counts.
- [x] Show active registered counts and per-course match/mismatch status when a roster exists.
- [x] Label the event count-only when no roster has been imported.
- [x] Refresh the demand view when the selected event changes.
- [x] Verify the supplied PDF counts and a sample registration against a temporary SQLite database; remove the temporary check source.

**Accept when:** each exam's multiple sessions and courses are visible independently of the master student profile list, without representing aggregate counts as named students.

---

## Phase 6 — Date-sheet and course-count intake

**Depends on:** the three dashboards and import preview workflow. PDFBox is already declared in Maven; implement only the parsing adapters needed for the supplied formats.

### P6.1 — Create event and import workflow

- [x] Add event name and optional academic period; take the event code from the PDFs.
- [x] Select the schedule-without-counts PDF and the course-count PDF.
- [x] Import named student/course registrations after event creation, targeting the selected event from the student dashboard.
- [x] Ask once for shared exam duration and derive each session end from its start time.
- [x] Parse PDFs in a JavaFX background worker.

**Accept when:** event input follows the agreed order and no per-session end time or handover buffer is requested.

### P6.2 — Parse and preview the date sheet

- [x] Extract PDF text while retaining page numbers and source filename/checksum.
- [x] Parse the supplied tabular schedule and count PDF pair.
- [x] Verify the supplied course schedule PDF pair imports as 41 sessions, 386 papers, and 73,616 candidates with no diagnostics.
- [x] Normalize dates, start times, course codes, and session labels.
- [x] Show sessions, papers, page/row provenance, derived end times, and diagnostics before saving.
- [x] Flag missing, duplicate, mismatched, and malformed rows rather than accepting them.
- [x] Treat document contents as data; never follow instructions found inside a PDF.
- [ ] Compare the compact matrix date sheet only as an optional cross-check.

**Accept when:** preview shows parsed rows, raw source context, derived end times, and correctable diagnostics before saving.

### P6.3 — Parse and reconcile course demand

- [x] Parse course codes and candidate totals from the count sheet.
- [x] Match demand rows to schedule entries by exam date, start time, and course code.
- [x] Flag unmatched, duplicated, malformed, or conflicting course counts.
- [x] Compare session/course sums with the reported totals and show the difference; per-course source counts are validated against the session total.
- [x] When named registrations are included, compare per-course enrollment counts with imported demand.
- [x] Preserve aggregate-only demand as candidate counts without creating student records.

**Accept when:** mismatches remain visible; parsed session corrections must reconcile the course total, while unparsed or unmatched source rows remain blockers until repaired.

### P6.4 — Commit event imports

- [x] Save event, sessions, courses, demand, source checksums, and source rows in one transaction after confirmation.
- [x] Start the event source-data revision at 1 and increment it when registrations are added or changed.
- [x] Retain source checksums, file names, import timestamps, and source page/row references.
- [x] Persist extracted source text and normalized session/count values for each staged PDF session row.
- [x] Allow corrections to parsed course candidate counts; keep original PDF text and save the corrected normalized totals.
- [x] Allow corrections to parsed session dates and start times; recalculate end times and reject duplicate session start slots.
- [ ] Add correction controls for schedule/count rows that fail to parse or cannot be matched to a parsed session; these errors currently remain blocking diagnostics.
- [x] Block a repeated event code with a clear message; importing never replaces saved event data.
- [x] Confirm the event import in a dialog; failed writes roll back without partial records.

**Accept when:** accepted data is traceable and duplicate event codes are blocked without changing saved data.

---

## Phase 7 — Feasibility and room allocation

**Depends on:** event/session/count data plus room and staff dashboard data.

### P7.1 — Build preflight feasibility report

- [x] Calculate candidate demand per session and available room capacity.
- [x] Apply seat-derived effective capacity when a seat grid exists; report when a layout is missing.
- [x] Report estimated/unverified room use distinctly from verified capacity.
- [x] Calculate staffing need from the fixed capacity threshold per selected room.
- [x] Exclude unavailable rooms and staff with session unavailability, absence, handover conflicts, or known course ineligibility.
- [x] Report the exact shortfall and affected session/room where possible.
- [x] Separate hard blockers from warnings, including pending subject-eligibility data.
- [x] Load a consistent SQLite snapshot and show the event source revision in the report.

**Accept when:** a user can see why planning is feasible or blocked before a seating chart is generated.

### P7.2 — Allocate papers to blocks and rooms

- [x] Start from immutable inputs: session demand, room inventory, availability, and policy settings.
- [x] Sort papers by descending demand with stable course-code tie-breaking.
- [x] Prefer a block that can hold a course's remaining candidates where feasible.
- [x] Respect room capacity and one-session room conflicts including the system-managed 30-minute handover buffer.
- [x] Split a course across rooms/blocks only when no single building has enough remaining capacity.
- [x] Record course splits, per-building counts, and the reason for each split.
- [x] Keep allocation deterministic for identical inputs.

**Accept when:** every candidate is allocated exactly once when feasible; capacity or room shortage produces a blocking diagnostic.

---

## Phase 8 — Seat and invigilator assignment

**Depends on:** feasible room allocations.

### P8.1 — Implement deterministic seat assignment

- [x] Enumerate usable seats in a stable row/column order; exclude blocked/reserved seats.
- [x] Define orthogonal neighbors as up/down/left/right; diagonal seats are not neighbors for this rule.
- [x] Assign each eligible named student or anonymous course candidate once; report blocked candidates.
- [x] For each candidate, consider remaining seats and score added same-course orthogonal conflicts.
- [x] Use stable tie-breaking by room, row, and column.
- [x] Apply course/block cohesion as a room-allocation preference after hard constraints and capacity.
- [x] Keep all candidates seated when capacity allows; use available seats to reduce same-course adjacency.
- [x] Return assignment results and structured diagnostics; keep seat logic out of JavaFX and SQL.

**Accept when:** results are repeatable, no seat is double-used, all candidates are accounted for, and every unavoidable adjacency is locatable in the result.

### P8.2 — Implement independent plan validator

- [x] Check every session/course demand is allocated exactly once; report shortages and duplicate candidates.
- [x] Check no seat or room is double-used in a conflicting session.
- [x] Check effective capacity, room availability, and seat status.
- [x] Check named students do not have simultaneous exams, using actual exam times.
- [x] Check invigilator count, availability, session overlap, and course eligibility.
- [x] Identify soft same-course adjacency and course block splits with location details.
- [x] Prevent finalization on any hard violation; a temporary SQLite check confirmed an invalid draft stays DRAFT after finalization is rejected.

**Accept when:** validator can reject an intentionally invalid proposal regardless of which planner produced it.

### P8.3 — Assign invigilators

- [x] Require exactly 3 invigilators when configured room capacity is greater than 100; otherwise require 2.
- [x] Exclude inactive, absent, unavailable, already-conflicted, and course-ineligible staff.
- [x] Assign staff in stable order for deterministic results.
- [x] Report shortage with session, room, required count, eligible count, and excluded-reason summaries.
- [x] Permit manual replacement and revalidate the room team when saving the new plan version.

**Accept when:** no room team violates the hard staffing rule or course conflict rule.

---

## Phase 9 — Plan review, persistence, and finalization

**Depends on:** validated room, seat, and staff assignments.

### P9.1 — Save versioned drafts

- [x] Capture the event source revision used to generate the plan.
- [x] Save proposed room/seat/staff assignments and diagnostics transactionally.
- [x] Compare the current event revision before saving; reject stale generated output.
- [x] Save each generation as a new version with a concise change summary.
- [x] Preserve prior versions.
- [x] Save session/room/seat display snapshots so reopened plans keep their original charts after inventory changes.

**Accept when:** failed saves roll back and regenerated plans never overwrite prior snapshots.

### P9.2 — Build plan review UI

- [x] Show per-session totals, buildings, rooms, assigned seats, course counts, and invigilator teams in the generated draft review.
- [x] Show room-level seating chart with row/column positions and course/student labels as appropriate.
- [x] Show hard blockers and soft violations with room/session detail.
- [x] List saved plan versions and reopen their seating charts; warn when event data has changed since generation.
- [x] Allow manual seat and staff edits; each edit saves as a new version and is revalidated before it can be finalized.
- [x] Display estimated-capacity and eligibility warnings and require acknowledgement before finalization.
- [x] Reopen finalized plans with saved session, room, seat-grid, candidate/course labels, and finalizer metadata.

**Accept when:** the administrator can understand and correct a proposed plan before finalizing it.

### P9.3 — Finalize immutable plan

- [x] Re-run the independent validator at finalization time.
- [x] Block finalization on hard violations; require acknowledgement for non-soft warnings.
- [x] Save finalization user/administrator label and timestamp.
- [x] Preserve displayed session, room, seat-grid, candidate-label, and course-code snapshots.
- [x] Lock finalized seat assignments; later generations create new versions.

**Accept when:** reopening a finalized plan shows the exact saved assignments and finalization metadata.

---

## Phase 10 — Reports, backups, and release readiness

### P10.1 — CSV operational exports

- [x] Export centre-wise and subject-wise room allocation summaries from current saved plans.
- [x] Show and export a course-by-block check with expected count, assigned count, block distribution, and split/mismatch status.
- [x] Export room-wise seat assignments with candidate, course, room, date/time, and coordinates.
- [x] Export teacher-wise/session-wise room duty assignments with staffing counts.
- [x] Export present/absent attendance sheets with event/session, date/time, staff names/status, and assigned rooms.
- [x] Include event/session identifiers, date/time, building/room, course, counts, and relevant check status in allocation exports.
- [x] Match centre and course-room allocation fields to the source report structure; seat rows identify named candidates by roll and include room coordinates.
- [x] Confirm attendance export includes staff identity, availability/attendance, and assigned room context.
- [x] Verify master-data exports use visible filtered rows, session exports use the selected session, and plan reports use the selected session/room.

Session attendance exports intentionally include all staff for the selected session. Centre/course/block reports include all rooms in the selected session; seat and invigilator exports also follow the selected room.

**Known CSV difference:** the source subject-wise seat report includes semester and exam-centre name. The current per-seat CSV uses roll/candidate ID and building/room coordinates; centre names are in the separate centre-allocation CSV, and semester is not exported.

**Accept when:** exports can be used by exam staff without manual reconstruction of room or course assignments.

### P10.2 — Printable PDF reports

**Deferred by owner for the current milestone.** PDF intake remains required; printable report generation does not.

- [ ] Generate readable room charts with candidate/seat labels, room identity, date, and session time.
- [ ] Generate centre-wise and subject-wise allocation summaries.
- [ ] Generate invigilation and attendance sheets.
- [ ] Include feasibility summary and unresolved preference diagnostics.
- [ ] Check page breaks, font size, and print margins on representative data.

**Accept when:** a full-size demo plan can be printed and each page identifies its event, session, room, and source plan version.

### P10.3 — Database backup and recovery

**Deferred by owner for the current milestone.**

- [ ] Add a user-visible backup action for the local SQLite database.
- [ ] Ensure the backup is consistent while WAL mode is enabled.
- [ ] Add a restore flow that first creates a safety copy of the current database.
- [ ] Validate the restored database integrity and schema version before using it.
- [ ] Document where backups and exports are written.

**Accept when:** an administrator can back up and restore without copying an inconsistent live database file.

### P10.4 — Operational error handling and privacy

- [x] Add a safe error reference ID for unexpected UI failures and write technical details to a local log.
- [x] Avoid logging unnecessary student/staff names or full IDs.
- [x] Add an administrator-initiated event purge that deletes its plans and event data, removes unreferenced student profiles and their CSV snapshots, and preserves shared master records.
- [x] Use native file choosers so admins select import files and export destinations directly.
- [x] Keep demo labels visible and never import demo data automatically into a normal first-run database.

**Accept when:** common failures are recoverable, personal data exposure in logs is minimized, and demo/operational data remain distinguishable.

---

## Verification tasks (complete alongside each phase)

### V.1 — Database and repository checks

- [ ] Create a temporary SQLite database for each repository test run.
- [ ] Verify a fresh migration creates expected tables and indexes.
- [ ] Verify a second startup does not reapply the migration.
- [ ] Verify foreign keys, unique keys, and check constraints reject invalid data.
- [ ] Verify failed multi-row imports roll back all accepted writes.
- [ ] Verify prepared repository operations preserve identifiers with leading zeroes.

### V.2 — Import workflow checks

- [x] Test UTF-8 BOM, quoted commas, escaped quotes, and CRLF/LF input.
- [x] Test malformed headers, invalid fields, duplicate rows, and existing-record conflicts.
- [x] Verify preview/cancel causes no operational data changes.
- [x] Verify selected-row confirmation commits once and retains source row provenance.
- [ ] Verify exports can be imported again without changing IDs or values.

### V.3 — Dashboard service checks

- [x] Verify staff profile validation, duplicate ID rejection, and deactivate/reactivate behavior.
- [x] Verify staff-course relationships are persisted and course-ineligible staff are excluded from eligibility results.
- [x] Verify room capacity/layout mismatches are reported and unavailable rooms are excluded.
- [x] Verify one student can have multiple course enrollments and duplicate enrollment keys are rejected.
- [x] Verify count-only demand does not create student records.

### V.4 — Planning and finalization checks

- [x] Verify deterministic room/seat/staff results for identical inputs in a small SQLite event.
- [x] Verify same-course diagonal seats are allowed and unavoidable orthogonal adjacency is reported when crowded.
- [x] Verify candidates are assigned once when capacity fits and a hard blocker is reported when it does not.
- [x] Verify a room of 4 configured seats receives the 2-staff team requirement.
- [x] Verify the 3-staff threshold above 100 configured seats and subject-course exclusion.
- [x] Verify interval overlap and 30-minute handover conflicts on the same date, but not different dates.
- [x] Verify stale plan generation cannot save after its source revision changes.
- [x] Verify hard violations block finalization and missing subject-eligibility data requires warning acknowledgement.
- [x] Verify finalized snapshots stay immutable after master-data changes.

### V.5 — Build and manual acceptance

- [x] Run the Maven compile/package lifecycle from a clean checkout.
- [ ] Launch the JavaFX application and exercise startup error handling.
- [ ] Walk through synthetic staff CSV preview, confirmation, filtering, profile edit, and export.
- [ ] Continue one end-to-end acceptance walkthrough for each dashboard before beginning schedule intake.
- [ ] Record known limitations in the relevant product/task document.

---

## What to build next

1. Finish import preview repairs and accepted source-correction tracking.
2. Complete runtime UI acceptance when JavaFX can launch in a desktop session.
3. Continue hardening the selected first-build scope.

The current feature milestone is: import an event, student profiles, optional event registrations, rooms, staff, and staff-course links; check roster conflicts and counts; generate, validate, review, reopen, manually adjust, finalize, and export plan versions. Accepted source corrections and runtime acceptance remain. Duplicate event codes are blocked and never replace saved event data.
