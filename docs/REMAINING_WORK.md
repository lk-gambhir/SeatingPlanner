# Project close-out and remaining work

## Current state

The first usable JavaFX/SQLite build is implemented across 41 Java source files. Its main flow includes staff, room, and student dashboards; reviewed CSV/PDF imports; session feasibility; room and seat planning; invigilator assignment; versioned plan review and finalization; and operational CSV exports.

The last clean Maven package build succeeded on 2026-11-16. It compiled the application and packaged the database migrations. No tests were run, as requested. The temporary test sources have been removed.

The task checklist currently records **301 complete and 26 unchecked items**. The unchecked items include deferred features, verification tasks, and the PDF row repair gap below.

## Completed implementation work

The main planned features are implemented. This review found and fixed several correctness issues in PDF corrections, room reconciliation, CSV round-tripping, and error handling.

1. **Repair malformed or unmatched PDF rows.** Parsed sessions can correct date, start time, reported total, course code/name, and course counts. These corrections are validated and retain the count-source row/page. Rows that cannot be parsed or attached to a parsed session remain blocking errors and require fixing the source PDF before import. There is no generic error-dismiss action.
2. **Allow row-level selection for CSV batches.** Interactive row-level exclusion is implemented across Room, Staff, Student, and Enrollment CSV previews. Excluded rows are marked as skipped with audit provenance (`Excluded by administrator`).
3. **Reconcile provisional rooms when authoritative inventory arrives.** The room dashboard has a reconciliation action. It now rejects configured capacity above the usable seat grid; official layout changes still need to be entered through room management.
4. **Operational privacy and error handling.** `UiFeedback` writes reference IDs and stack frames to `~/.thapar-exam-planner/application.log` without logging exception messages or IDs. Alerts show a safe message and reference ID. Demo badges (`[DEMO DATA]`) appear on room, staff, and student dashboards when synthetic data is present.
5. **Room source/note field.** `source_note` is persisted, editable, imported, and included in room CSV exports.

## Remaining implementation work

- Add editable controls for PDF rows that fail parsing or cannot be joined to a parsed session. Until then, such rows correctly block import.
- Update room grid dimensions when authoritative layouts differ from the provisional grid. Reconciliation prevents marking a capacity verified when it exceeds current usable seats.

## Verification still outstanding

- A JavaFX window could not be visually confirmed in the available desktop environment.
- The migration-failure dialog and the end-to-end dashboard workflows have not been manually exercised in this close-out pass.
- Automated and manual test tasks remain unchecked because test execution was explicitly skipped. The clean package build only establishes compilation and packaging; it does not prove runtime behavior.

## Deferred by the owner

- Printable PDF reports.
- SQLite backup and restore.
- Advanced filter combinations; the existing basic search and filters remain.
- A graphical seat grid; the existing picker-based seat editing remains.
- A separate `course_student_list.csv` import path; use students plus enrollments.
- The compact matrix date sheet as an optional cross-check.

## Settled product decisions

- Duplicate staff or room IDs in CSV keep the saved profile and skip the incoming row. Preview shows saved-versus-CSV differences; there is no update/replace action.
- A repeated event code is blocked; saved events are not replaced.
- Exams use the shared duration entered once. The application derives each end time and applies the configured 30-minute handover rule internally.
- Room staffing is 3 invigilators when configured room capacity is greater than 100, otherwise 2. Provisional room assumptions stay marked estimated until corrected.
- There is one local administrator/writer. Named data is not automatically expired. The agreed admin purge deletes the event and its plans, including finalized plans, and removes unreferenced student data.
- The user asked to skip tests for this close-out. Do not interpret the package build as a test pass.
- The previous generic diagnostic-dismiss action was removed because it allowed incomplete PDF data to be imported without repair.

See [TASKS.md](TASKS.md) for the itemized checklist and [DECISIONS.md](DECISIONS.md) for the settled rules.
