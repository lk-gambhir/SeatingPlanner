# Testing and Acceptance Strategy

## 1. Verification approach

The project owner requested that test source files be removed after verification. For a code change, add only the focused temporary checks needed to verify it, run them, then remove the test sources. `./mvnw clean package` is the final build check; it does not replace focused behavioral checks before cleanup.

Use manual UI acceptance for import review, dashboard filters, room-grid inspection, plan review, and exports. Inspect generated room charts and feasibility explanations with representative synthetic data.

## 2. Required scenarios

### Schedule import

- Valid tabular date sheet and optional compact-sheet cross-check with several sessions and multiple courses per session.
- Course/student-count sheet is matched to date-sheet rows by date, start time, and course code.
- One event duration derives every session end; verify sessions with different start times receive different end times but equal durations.
- Optional student/course roster can be skipped without creating fake student identities.
- A course code missing from one PDF.
- The current DEMO set imports with 10,000 students, 73,616 registrations, 386 course papers, 41 dated/time slots, 500 invigilators, 1,000 explicit staff/course relationships, and 230 estimated rooms; no fixture row is committed before preview confirmation.
- `course_demand_source.csv` and the 2026 schedule fixture reconcile to 73,616 candidates; synthetic registered roster counts match each paper's extracted aggregate count.
- Each slot's synthetic roster has no student with two simultaneous papers; date/time as well as `slot_id` define slot grouping.
- The 2022 parser fixture is recognized as separate from the 2026 event and does not match or import a 2026 roster automatically.
- `course_student_list.csv` and `enrollments.csv` are duplicate representations; importing both in one batch is blocked or explicitly deduplicated in preview.
- Room fixtures represent the 230 assumption-based records and 9,280 estimated seats; import reconciliation updates/matches stable IDs and does not append duplicates.
- Explicit synthetic staff/course teaching relationships drive subject exclusions; department is not used to infer exclusions, and fixture `max_duties` is not enforced.
- Duplicate course/session rows.
- Candidate count mismatch between total and per-course counts.
- Malformed date, time, or count.
- Re-import of the same source batch.
- UTF-8 BOM, LF and CRLF line endings, and comma/semicolon delimiter detection.
- IDs with leading zeroes and long numeric-looking identifiers remain exact strings.
- Identifiers displayed as scientific notation are flagged and never silently rewritten as guessed IDs.
- PDF that is scanned or has uncertain text order; the app must request review rather than silently trust bad rows.

### Capacity and rooms

- Exact-fit capacity.
- Capacity shortage and excess capacity.
- Unavailable room, blocked seat, and seat-layout/capacity mismatch.
- Course fits in one block, and course must split across blocks.
- Multiple concurrent sessions attempting to use one room.
- Same-day sessions whose intervals just touch, overlap exactly, or are separated by less than/equal to the fixed global 30-minute handover buffer.
- Same clock times on different dates do not conflict.
- Missing shared event duration blocks planning; providing it derives and previews all session end times.

### Seating preferences

- Same-course candidates separated by a different course.
- Same-course diagonal neighbors accepted.
- Same-course orthogonal neighbors reported as a soft violation.
- Sparse occupancy where gaps can improve separation.
- High occupancy where complete separation is impossible but every candidate must still be accounted for.
- `isValid` rejects blocked, occupied, unavailable, duplicate, and mismatched candidate/registration placements without treating adjacency as a hard failure.
- Seat score order is stable: adjacency count, prior-seat continuity, free orthogonal neighbors, then coordinates/seat ID.
- A prior seat that would introduce a same-course orthogonal conflict is not reused when a zero-conflict valid seat is available.
- The same soft adjacency pair is reported with both seat coordinates and does not block finalization when all hard constraints pass.
- Named same-student overlap is rejected before seat placement; count-only mode makes no claim to detect it.
- Student clash detection uses actual exam intervals and does not apply the room/staff 30-minute reuse buffer.
- Repeated generation from the same snapshot yields identical room/seat assignments and diagnostic ordering.

### Student-level mode

- Registration count matches imported count.
- Registration count differs from imported count.
- Student has simultaneous exams.
- One student is assigned twice in the same session.
- Prior seat preference is available and unavailable.

### Invigilation

- Capacity at 100 seats requires 2 staff; capacity at 101 seats requires 3.
- Staffing requirement met exactly for both sides of the capacity threshold.
- Too few eligible staff.
- Staff is unavailable or absent.
- Staff is excluded from a course in the assigned room.
- Staff is double-booked across overlapping sessions.
- Staff and room reuse obey the same-date 30-minute buffered interval rule.
- Room with multiple courses excludes staff for each relevant course.

### Persistence and errors

- Failed import rolls back without partial database changes.
- Failed plan save leaves previous plan intact.
- Saved plan versions reopen with their seat assignments, invigilator IDs, and diagnostics; changed source revisions are marked stale.
- Finalization revalidates the saved draft, requires warning acknowledgement, records the administrator and timestamp, and retains its original room/seat display snapshot after inventory changes.
- Plan CSV exports keep comma-containing IDs quoted and emit one row per seat assignment and invigilator duty.
- Finalized plan remains unchanged after a new draft is generated.
- Source data changes while planning runs; stale output is rejected rather than committed.
- Changed schedule data marks dependent draft plans stale while preserving their snapshots.
- Unexpected error exposes a safe reference ID and logs diagnostic context.

## 3. Acceptance checks

- Hard violations block finalization.
- Every candidate count is reconciled to assigned, unassigned, or explicitly excluded demand.
- Soft violations are visible and pinpointed.
- Same inputs and planning policy yield reproducible assignments.
- Bulk CSV imports show a preview and never silently drop invalid records.
- Room dashboard seeds the expected provisional inventory (230 rooms, 9,280 estimated seats), marks it unverified, and supports filtering/bulk selection without applying changes outside selected rows.
- Dashboard implementation order is invigilator, room, student, then schedule intake; each master-data dashboard opens with a useful empty/setup state when no event exists.
- Invigilator dashboard supports the 500-row demo roster and a larger imported roster through background CSV processing and pagination/filtering.
- JavaFX remains responsive during parsing and plan generation.
