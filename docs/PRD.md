# Product Requirements Document

## 1. Product summary

The Automated Exam Seating and Invigilation Planner is a local desktop tool for administrators who prepare examination logistics. It imports exam sessions and paper-level candidate counts from schedule documents, assigns candidates to available rooms, creates room-level invigilation teams, reports feasibility, and exports plans.

The product supports two operating modes:

1. **Count-based planning:** course-level counts from schedule PDFs are represented as anonymous candidate demand. This supports course-labelled seating charts and capacity planning.
2. **Student-level planning:** an optional student roster and course-registration import enables named seating charts, individual exam-clash checks, student lookup, and continuity preferences.

Count-based planning must never be presented as named student seating.

## 2. Problem

Preparing a multi-session exam plan requires reconciling course schedules, candidate counts, rooms, seat layouts, staff eligibility, staff availability, and operational changes. Manual processes make it difficult to identify capacity shortages, preserve course groupings, avoid adjacent same-course seats, and recover from room or staff changes.

## 3. Goals

- Import a date sheet, a course/student-count sheet, and an optional named student/course registration sheet in a guided event workflow.
- Group multiple courses into exam sessions by date and start time.
- Manage buildings, rooms, seat layouts, room availability, students, course registrations, staff, and staff attendance.
- Generate a feasible room, seat, and invigilator plan using the defined seating constraints and initial fixed staffing rule.
- Prefer keeping a course's candidates in one building block when feasible.
- Avoid same-course candidates in orthogonally adjacent seats when feasible; diagonal adjacency is allowed.
- Clearly explain shortages, rule violations, and affected records.
- Support review, manual edits, revalidation, plan versioning, and CSV/PDF exports.

### Implementation order

Build and demonstrate the master-data dashboards in this order: (1) invigilator, (2) room, (3) student; then implement date-sheet/course-count intake and planning. This is the development sequence, not a restriction on when administrators may edit master data. The supplied CSVs provide synthetic examples for dashboard development. The user-facing exam workflow still begins by creating an event and importing its schedule and course counts, then reviewing its roster, rooms, and invigilators before generating a plan.

## 4. Non-goals for the first release

- Automatically create the institution's exam timetable from course registrations.
- Replace an official student information system or human resources system.
- Provide a multi-user cloud service or synchronize several active database copies.
- Use scanned-PDF OCR unless supported schedule files require it and the feature is separately approved.
- Implement student accessibility accommodation matching in the first release.
- Promise a globally optimal solution for large constraint problems.
- Publish results directly to a student portal.

## 5. Users

- **Exam administrator:** imports schedules, manages rooms and staff, generates and finalizes plans.

The first release is a single-user local application with no in-app role or authorization system. Additional operators use the same administrator workflow.

## 6. Core concepts

- **Exam event:** a named examination period or assessment cycle.
- **Session:** one date and required start/end time window. A session can contain several concurrent course papers. The administrator enters one shared exam duration for the event; the application derives and previews each session's end from its start time.
- **Course paper:** a course scheduled in a session, with an imported or verified candidate count.
- **Room:** a physical room in a building/block, with configured or estimated layout/capacity and session availability. A reporting centre label may group rooms across buildings and does not replace the physical location.
- **Plan:** a versioned result for one or more sessions.
- **Candidate:** either an actual student linked to a registration or an anonymous count-based slot.
- **Room invigilation team:** staff assigned to supervise one room during one session.

## 7. Functional requirements

### FR-1: Schedule PDF import

- Use this order: (1) import the date sheet, (2) import the course/candidate-count sheet, and (3) optionally import named students and their course registrations.
- Treat the tabular date sheet as the primary schedule input; accept a compact date-sheet matrix as a cross-check.
- Extract event metadata, program/semester, dates, start times, course codes, names, and counts when present.
- Ask once for the shared exam duration, derive each session's end from its own start, and show all derived windows for review.
- Match course demand to the schedule by normalized date, start time, and course code.
- Show a preview with per-session totals, count reconciliation, missing/duplicate rows, and whether named roster validation is available.
- Allow corrections before committing the batch.
- Preserve source filenames, import timestamp, and validation status.
- Support manual correction if PDF extraction or layout parsing is uncertain.

### FR-2: Room dashboard

- Open with provisional room records preloaded from the agreed block/floor/room/capacity assumptions; keep those values in database seed records, not Java constants.
- Show summary cards for active blocks, floors, rooms, total estimated/verified capacity, rooms needing verification, and rooms unavailable in the selected session.
- Make each summary card clickable to apply its corresponding room filter; include a session selector that refreshes availability and assigned-room indicators.
- Provide a searchable room table with block/building, floor, room code, type, layout, physical seats, exam-usable capacity, verification status, and availability.
- Offer room types such as classroom, lecture theatre, lab, and other; keep the type editable per room.
- Filter by block, floor, room type, active status, capacity range, layout status, verification status, exam centre/reporting group, and selected-session availability.
- Provide row selection, select-all-filtered, clear selection, bulk activate/deactivate, session availability changes, room-type updates, and CSV export of selected/filtered rows; preview and confirm bulk changes.
- Provide a room detail view with editable identity/location, room type, capacity source and notes, floor placement, and per-session availability/reason.
- Provide a visual row/column seat-layout editor with per-seat usable/blocked/reserved state and a live usable-seat count; display discrepancies against configured exam capacity.
- Support CSV import/export with template download, header mapping, preview, row-level diagnostics, and confirmation.
- Distinguish provisional seeded values, historical observations, and administrator-verified inventory.
- Implement this dashboard after the invigilator dashboard and before the student dashboard; it must be usable before any date sheet is imported, while session-specific availability can be attached when sessions exist.

### FR-3: Student dashboard

- Refresh course demand automatically after a date sheet and course/count sheet are accepted.
- Show summary cards for sessions, scheduled courses, total candidates, roster-matched candidates, and count/registration mismatches.
- Show a course-demand table with session, date/time, code/name, candidate count, source, reconciliation status, and anonymous/named mode.
- In count-based mode, show anonymous candidate slots and clearly label them as anonymous.
- Offer session/date, course code/name, program/semester, registration status, and roster-import status filters, plus per-entry or select-all-filtered actions.
- Optionally import named student records and student-to-course registrations to enable named seating and student-level conflict checks.
- Support search, per-record review/correction, filtered bulk selection, CSV import/export, and explicit separation between anonymous demand and named roster records.
- Compare registration totals against imported course candidate counts and report discrepancies without silently choosing a source.
- Implement this dashboard after the room dashboard and before date-sheet intake. It may manage the supplied synthetic student/enrollment master data before exams are scheduled; reconcile those registrations to course demand when an event is imported.

### FR-4: Invigilator dashboard

- Deliver first among the three master-data dashboards, before room/student dashboards and before date-sheet intake. It must support staff profile management without an event loaded; session availability, attendance, and room assignments become active when schedule/plan records exist.
- Manage staff identity, active status, department/team, course-teaching/exclusion relationships, and whole-session availability.
- Support approximately 400 real staff records without requiring placeholder staff records.
- Provide summary cards for active staff, available staff in the selected session, assigned staff, absent staff, and remaining staffing shortages.
- Provide search and filters by department/team, active status, course affiliation/exclusion, session availability, assigned room, attendance status, and selection state.
- Support per-entry selection, select-all-filtered, clear selection, bulk availability updates, and CSV import/export with staging and preview.
- Record present/absent/not-recorded attendance per session; show impacted rooms and allow replacement selection with eligibility checks.
- Never select a teacher for a room containing a course they teach or are explicitly excluded from.

### FR-5: Planning and constraints

- Generate plans for one session or a selected set of sessions.
- Treat two same-date sessions as conflicting for a room or staff member when their half-open intervals `[start, end + 30 minutes)` intersect. Sessions on different dates do not overlap. The system-managed global 30-minute buffer applies to room and staff reuse and is not requested during event intake.
- Derive session end times from the confirmed event-level duration and each session's start time; show the derived schedule for review before planning.
- Respect room availability, usable seat capacity, unique seat occupancy, and unique room occupancy per conflicting session.
- Allocate every candidate exactly once when a complete feasible plan is possible.
- Keep a course in one block as a soft preference. Split it when necessary and report the split.
- Avoid same-course candidates in up/down/left/right adjacent seats as a soft preference. Diagonal seats are permitted.
- Use empty seats to improve separation only when the plan still seats all candidates and hard constraints remain satisfied.
- Require exactly 3 invigilators for a room whose configured exam-usable capacity is greater than 100 seats; require 2 for a room at or below 100 seats. This is the initial fixed rule in planner code.
- Never assign an unavailable invigilator, double-book an invigilator, or assign an invigilator to a room containing a course they teach/are excluded from.
- Make the plan generator deterministic for identical inputs and settings.

### FR-6: Review, edit, and finalize

- Show session summaries, block allocations, room charts, course counts, and invigilation teams.
- Allow the administrator to manually reassign rooms, seats, or staff.
- Revalidate after edits and identify newly introduced violations.
- Save drafts and finalized plans as immutable versions with a concise plan change summary and finalization metadata.
- Mark room or staff attendance changes and identify affected finalized assignments.

### FR-7: Diagnostics and exports

- Distinguish hard blockers, soft preference violations, import problems, and unexpected software errors.
- Include session, block, room, seat, course, and student identifiers when available.
- Give a concrete cause and a possible correction for each issue.
- Export centre-wise room allocation, subject-wise room allocation, detailed room seating charts, session-wise and teacher-wise duty lists, and attendance sheets.
- Export session-level and filtered CSVs plus printable room charts (date/time, building/room, row/column seat labels, course-wise counts), course-wise centre/room summaries, attendance lists, invigilator sheets, and feasibility summaries. Keep source paper/course identifiers and room names visible in reports; include candidate counts and diagnostic codes where relevant.

## 8. Constraint priorities

### Hard constraints

- Each seat is used by at most one candidate in a room/session.
- Each candidate is allocated at most once in a session and all demand is accounted for before finalization.
- Allocations do not exceed usable capacity.
- Unavailable rooms and seats are not used.
- A room and an invigilator are not assigned to overlapping sessions.
- A staff member is not assigned to a room containing a course they teach or are explicitly excluded from.
- The fixed staffing requirement is met: 3 invigilators for configured exam-usable capacity >100, otherwise 2.
- If named registrations are present, a student is not assigned to simultaneous exams.

### Soft preferences

1. Keep each course in one building block when block capacity permits.
2. Avoid orthogonal same-course seat adjacency; diagonal adjacency is acceptable.
3. Prefer fewer course splits and fewer rooms when other priorities are satisfied.
4. In student-level mode, prefer the same student's prior seat where feasible.

The planner may relax soft preferences and must report the resulting cost and affected assignments. It must not silently relax a hard constraint.

## 9. Capacity and data freshness policy

- Use configured exam-usable capacity as the room's planning capacity.
- If a seat layout exists, use the smaller of configured exam capacity and the count of usable seat records.
- Estimated capacity is permitted for draft planning and always produces a warning. Finalization requires explicit administrator acknowledgment of estimated room data.
- If data used to generate a draft changes before the draft is saved, reject the stale save and ask the administrator to regenerate. If saved source data later changes, mark dependent drafts stale and prevent finalization until regenerated.

## 9.1 Provisional room inventory loaded by the dashboard

| Blocks | Floors | Rooms per floor | Estimated exam capacity per room | Provisional seat grid |
|---|---:|---:|---:|---:|
| G, B, C, D, E, F | 3 | 12 | 30 | 5 rows × 6 columns |
| LT | 4 | 2 | 200 | 10 rows × 20 columns |
| TAN | 3 | 2 | 200 | 10 rows × 20 columns |

This loads 230 provisional room records with an estimated aggregate capacity of 9,280 seats. The rectangular grids are starter layouts for initial chart previews, not verified room geometry. Room records, block names, and capacities live in the database and can be edited in the room dashboard. They are shown as unverified until actual room codes, layouts, and exam-usable seats are confirmed. Historical room names from prior allocation PDFs are suggestions for reconciliation, not proof of current capacity. If every seeded room were used in one session, the fixed rule would require up to 474 invigilators; the planner reports shortages against the actual available and eligible roster rather than assuming all rooms can be opened.

## 9.2 Supplied synthetic CSV fixtures

The maintained demo fixtures are synthetic development/import data, not official institutional records. See [`data/demo/README.md`](../data/demo/README.md) for file-level schemas, provenance, and import order. Do not silently load fixtures into a normal first-run database. Import them through the same staged preview and confirmation path as administrator data, preserving `DEMO` labels and batch-level provenance. Current scale: 10,000 synthetic students; 73,616 synthetic registrations across 386 course papers and 41 dated/time slots, using candidate counts extracted from the supplied 2026 count PDF; 500 synthetic invigilators; 1,000 synthetic teaching relationships; and 230 estimated room records with 9,280 assumed seats. `course_student_list.csv` is a flat import/export view of `enrollments.csv`; import one representation, not both. The 2022 date-sheet parser fixture is separate and has no matching 2026 roster. Keep the generated identities synthetic: source PDFs provide aggregate counts and schedule identifiers, not named roster data for this fixture. Room capacities remain provisional user assumptions and are not verified by the historical room reports. The sample `max_duties` field is not a first-release workload cap.

The public Thapar website currently states 13,000+ students, while the 2023 annual report states over 12,000. The 10,000-student cohort is retained as the requested development-fixture scale; it must not be described as current institutional enrollment. Public student totals are not exam-period candidate totals and do not establish simultaneous exam demand or room capacity. The 2022 date sheet and allocation reports inform legacy-format parsing and report/export layouts; because they describe a different period, their rows are not merged into the 2026 fixture. Historical room codes are references for reconciliation only, not proof that those rooms remain in service or have a current capacity.

## 10. Feasibility report requirements

Every diagnostic includes a stable code, severity, session, affected entity identifiers, observed values, required values, explanation, and suggested action. Examples include insufficient seat capacity, course split across blocks, same-course adjacent seats, invigilator subject conflict, staff shortage, room unavailable, and registration-count mismatch.

Unexpected exceptions show a safe message and reference ID in the UI. Detailed technical information is written to an application log.

## 11. Non-functional requirements

- Local-first operation with SQLite persistence.
- Responsive Swing UI; PDF/CSV parsing and plan generation run outside the Event Dispatch Thread.
- Transactional imports and plan saves.
- Referential integrity and unique constraints in the database.
- No personal student data required for count-based mode.
- Minimize and protect student/staff personal data when imported.
- Do not auto-delete student/staff records in the first release. Provide an administrator-initiated event-level purge/anonymization path; its retention interval must be confirmed before named student data is used operationally.
- Backups and export paths are visible and configurable.
- Plan generation should support a useful progress indicator and cancellation where safe.

## 12. Success criteria

- Both schedule PDFs can be imported and reconciled through a review flow.
- A session containing multiple courses can be allocated across configurable blocks and rooms.
- Hard constraint violations prevent finalization and produce actionable diagnostics.
- Soft adjacency and block-grouping violations are visible with affected assignments.
- Invigilator, room, and student dashboards are implemented in that order and support filtered bulk actions and CSV round-trips before date-sheet intake is built.
- A finalized plan can be reopened, printed/exported, and compared with later versions.
