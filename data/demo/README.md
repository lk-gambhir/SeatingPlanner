# Synthetic demo data

These files are development fixtures, not Thapar's operational student, room, or staff records. All person identities, roll numbers, room codes, staff affiliations, and staff-course relationships are synthetic. IDs must be imported as strings. The PDFs are not copied into the repository.

## Fixture contents

| File | Records | Purpose |
|---|---:|---|
| `students.csv` | 10,000 | Synthetic student dashboard cohort (`DEMO-STU-*`, `DEMO-ROLL-*`). |
| `enrollments.csv` | 73,616 | Normalized synthetic registered-course rows, tied to the 2026 assessment period. |
| `course_student_list.csv` | 73,616 | Retained demo reference view of `enrollments.csv`; there is no separate import path in the current release. |
| `exam_sessions.csv` | 1 | Synthetic assessment-period record for `MST-2026-ODD`. |
| `exam_events.csv` | 386 | One row per course paper, with date, start time, source count, source page/row, and shared-slot ID. |
| `course_demand_source.csv` | 386 | PDF-derived aggregate candidate counts and source references; no identities. |
| `date_sheet_parser_2022_courses.csv` | 25 | Course-code-only parser sample from the older `2223ODDMS2` date sheet; do not combine with the 2026 roster. |
| `rooms.csv` | 230 | Provisional editable inventory using the user-provided building/floor/room/capacity assumptions; all marked estimated. |
| `invigilators.csv` | 500 | Synthetic roster for dashboard scale and availability filtering. |
| `invigilator_course_assignments.csv` | 1,000 | Synthetic `TEACHES` relationships to exercise course-exclusion filters. |

## Provenance and limitations

- `Course_Schedule_Final_With_Total_Students.pdf` supplies the 2026 schedule and aggregate counts. `Course_Schedule_Final_Without_Total_Students.pdf` provides a count-free parser comparison for the same schedule.
- `DateSheet (23).pdf` and `DateSheetTabular (69).pdf` provide two formats for an older `2223ODDMS2` schedule. Their codes are kept in a parser-only fixture and are not joined to the 2026 event.
- `CentreWiseRoomAllocation`, `SubjectWiseCentreRoom`, and `SubjectWiseSeatAllocation` show historical report groupings by centre, subject, room, and allocation. They inform report/dashboard fields and formats; their listed rooms and capacities are not treated as current inventory.
- `SeatingPlan` informs the printable seat-chart/report shape. No person names, roll numbers, or seat-to-student mappings from any seating/allocation report are copied into the synthetic roster.
- The files named with a `-1` suffix were byte-identical duplicate copies of their unsuffixed counterparts and did not create duplicate data.
- The 2026 schedule fixture uses the supplied `Course_Schedule_Final_With_Total_Students.pdf`: 41 dated/time slots, 386 course-paper rows, and 73,616 total candidate registrations. Counts are extracted aggregate values, not identities. The schedule's per-course counts reconcile with the session totals.
- The paired 2026 schedule-without-counts PDF can be used for parser comparison. Its course codes must be matched to the count sheet during preview; it does not add a second event or a second set of registrations.
- `DateSheet (23).pdf` and `DateSheetTabular (69).pdf` are an older 2022 schedule (`2223ODDMS2`). Their 25 course codes are an independent parser fixture and are not matched to the 2026 roster.
- Historical allocation and seating PDFs provide examples of report formats and room-code styles. They do not establish current room inventory or verified capacity. The 230 room rows remain assumptions: G/B/C/D/E/F have 12 rooms on each of 3 floors at 30 seats; LT has 2 rooms on each of 4 floors at 200 seats; TAN has 2 rooms on each of 3 floors at 200 seats. Total: 9,280 estimated seats.
- Thapar's [public website](https://thapar.edu/) currently describes 13,000+ students, and its [2023 annual report](https://www.thapar.edu/upload/files/Annual%2520Report2023.pdf) describes over 12,000. The requested 10,000-person fixture is a reproducible development cohort, not an estimate of current enrollment. Student headcount, exam candidates, room capacity, and simultaneous demand are separate quantities.
- Staff departments, availability, max-duty sample values, and teaching relationships are artificial. Do not treat them as an official roster or an adopted duty cap. Apply the project staffing rule from configured room capacity: 3 invigilators above 100 seats, otherwise 2.
- Dates and counts reflect the provided source document, including its 2026 schedule dates. Regenerate the fixtures only when intentionally refreshing the sample source.

## Import guidance

1. Import `invigilators.csv` into the staff dashboard and preview `invigilator_course_assignments.csv` as a separate teaching/exclusion relationship file.
2. Import `rooms.csv` into an empty demo inventory or reconcile by stable `room_id`; do not append duplicate room rows on every import.
3. Import `students.csv`, then `enrollments.csv`. Treat `course_student_list.csv` as a reference/export example only.
4. For schedule workflow, import the date sheet and count sheet as one reviewed event. `exam_events.csv` is the normalized synthetic schedule fixture; `course_demand_source.csv` preserves the source-derived counts. The shared event duration remains an application input, so `end_time` is intentionally blank in the event fixture.
5. Keep the app's normal preview, row diagnostics, and deliberate commit confirmation enabled for all fixtures.

## Regeneration

The derived student, roster, event, and invigilator CSVs were generated from `course_demand_source.csv` using a fixed random seed. The generator script is maintained outside the GitHub repository, so the checked-in fixtures can be used as-is but cannot be regenerated from a fresh clone. The PDF extraction that created `course_demand_source.csv` is also not part of application startup; this keeps runtime independent from local PDF tooling and leaves source extraction reviewable.
