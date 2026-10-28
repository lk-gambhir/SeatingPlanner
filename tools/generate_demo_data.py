#!/usr/bin/env python3
"""Regenerate the synthetic CSV fixtures from the checked-in schedule extract.

Uses only Python's standard library. Source course codes and aggregate counts are
derived from the supplied 2026 date-sheet PDF; all person records are synthetic.
"""

from __future__ import annotations

import csv
import random
from collections import defaultdict
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data" / "demo"
SOURCE = DATA / "course_demand_source.csv"
STUDENT_COUNT = 10_000
INVIGILATOR_COUNT = 500
SESSION_ID = "DEMO-MST-2026-ODD"
SEED = 20261014


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as source:
        return list(csv.DictReader(source))


def write_csv(path: Path, headers: list[str], rows: list[list[object]]) -> None:
    with path.open("w", newline="", encoding="utf-8") as destination:
        writer = csv.writer(destination)
        writer.writerow(headers)
        writer.writerows(rows)


def time_24h(value: str) -> str:
    return datetime.strptime(value.strip(), "%I:%M %p").strftime("%H:%M")


def main() -> None:
    source_rows = read_csv(SOURCE)
    if not source_rows:
        raise SystemExit(f"No course demand rows found in {SOURCE}")

    course_codes = sorted({row["course_code"] for row in source_rows})
    slots: dict[str, list[dict[str, str]]] = defaultdict(list)
    for row in source_rows:
        slots[row["slot_id"]].append(row)

    # Source metadata remains separate so header totals, per-course counts, and
    # source page/row references can be reviewed without confusing them with IDs.
    session_start = min(row["exam_date"] for row in source_rows)
    session_end = max(row["exam_date"] for row in source_rows)
    write_csv(
        DATA / "exam_sessions.csv",
        ["session_id", "name", "start_date", "end_date", "status", "data_label"],
        [[SESSION_ID, "DEMO Mid-Semester Examination (MST-2026-ODD)",
          session_start, session_end, "DRAFT", "DEMO"]],
    )

    event_rows: list[list[object]] = []
    for row in source_rows:
        event_rows.append([
            row["event_id"], SESSION_ID, row["course_code"], row["course_code"],
            row["slot_id"], row["exam_date"], time_24h(row["start_time"]), "",
            row["candidate_count"], "NEEDS_SHARED_DURATION", "DEMO",
            row["source_page"], row["source_row"],
        ])
    write_csv(
        DATA / "exam_events.csv",
        ["event_id", "session_id", "course_id", "course_code", "slot_id",
         "exam_date", "start_time", "end_time", "candidate_count", "status",
         "data_label", "source_page", "source_row"],
        event_rows,
    )

    student_rows: list[list[object]] = []
    for number in range(1, STUDENT_COUNT + 1):
        student_rows.append([
            f"DEMO-STU-{number:05d}", f"DEMO-ROLL-{number:05d}",
            f"Demo Student {number:05d}",
            ("BE", "BTech", "MCA", "MSc", "ME", "MTech")[number % 6],
            str((number % 8) + 1), f"SEC-{(number % 12) + 1:02d}", "DEMO",
        ])
    write_csv(
        DATA / "students.csv",
        ["student_id", "roll_number", "full_name", "program", "semester",
         "section", "data_label"],
        student_rows,
    )

    # Assign a contiguous, non-repeating slice of the 10,000 synthetic IDs to
    # each paper in a slot. Slot demand is smaller than the cohort, so one
    # synthetic student never has two simultaneous papers. Across slots, IDs
    # rotate to distribute registrations over the whole synthetic cohort.
    roster_rows: list[list[object]] = []
    enrollment_rows: list[list[object]] = []
    roll_by_id = {row[0]: row[1] for row in student_rows}
    slot_order = sorted(slots)
    enrollment_number = 0
    for slot_index, slot_id in enumerate(slot_order):
        papers = sorted(slots[slot_id], key=lambda r: (int(r["source_row"]), r["course_code"]))
        slot_total = sum(int(row["candidate_count"]) for row in papers)
        if slot_total > STUDENT_COUNT:
            raise SystemExit(
                f"Slot {slot_id} has {slot_total} candidates, above the "
                f"synthetic cohort of {STUDENT_COUNT}; increase STUDENT_COUNT."
            )
        cursor = (slot_index * 1733) % STUDENT_COUNT
        for paper in papers:
            count = int(paper["candidate_count"])
            for offset in range(count):
                student_number = ((cursor + offset) % STUDENT_COUNT) + 1
                student_id = f"DEMO-STU-{student_number:05d}"
                roll_number = roll_by_id[student_id]
                enrollment_number += 1
                enrollment_id = f"DEMO-ENR-{enrollment_number:06d}"
                enrollment_rows.append([
                    enrollment_id, student_id, paper["course_code"], SESSION_ID,
                    "REGISTERED", "DEMO",
                ])
                roster_rows.append([
                    paper["event_id"], SESSION_ID, paper["course_code"],
                    student_id, roll_number, paper["exam_date"],
                    time_24h(paper["start_time"]), "REGISTERED", "DEMO",
                ])
            cursor = (cursor + count) % STUDENT_COUNT

    write_csv(
        DATA / "enrollments.csv",
        ["enrollment_id", "student_id", "course_id", "session_id", "status", "data_label"],
        enrollment_rows,
    )
    write_csv(
        DATA / "course_student_list.csv",
        ["event_id", "session_id", "course_code", "student_id", "roll_number",
         "exam_date", "start_time", "registration_status", "data_label"],
        roster_rows,
    )

    departments = ["CSE", "ECE", "ME", "CIVIL", "CHEM", "MATH", "PHYS", "HUMANITIES"]
    designations = ["Assistant Professor", "Associate Professor", "Professor", "Lecturer"]
    rng = random.Random(SEED)
    slot_ids = sorted(slots)
    staff_rows: list[list[object]] = []
    staff_course_rows: list[list[object]] = []
    for number in range(1, INVIGILATOR_COUNT + 1):
        staff_id = f"DEMO-INV-{number:04d}"
        unavailable_count = rng.randrange(0, 4)
        unavailable = ";".join(sorted(rng.sample(slot_ids, unavailable_count)))
        staff_rows.append([
            staff_id, f"Demo Invigilator {number:04d}",
            departments[(number - 1) % len(departments)],
            designations[(number - 1) % len(designations)], 6, unavailable, "DEMO",
        ])
        for code in rng.sample(course_codes, min(2, len(course_codes))):
            staff_course_rows.append([staff_id, code, "TEACHES", "DEMO"])

    write_csv(
        DATA / "invigilators.csv",
        ["invigilator_id", "name", "department", "designation", "max_duties",
         "unavailable_slot_ids", "data_label"],
        staff_rows,
    )
    write_csv(
        DATA / "invigilator_course_assignments.csv",
        ["invigilator_id", "course_id", "relationship_type", "data_label"],
        staff_course_rows,
    )

    # Flat roster is an import/export convenience view of enrollments.csv, not
    # a second source of truth; import only one of the two for a given batch.
    print(f"Generated {STUDENT_COUNT:,} synthetic students")
    print(f"Generated {len(source_rows):,} scheduled course papers in {len(slots)} slots")
    print(f"Generated {enrollment_number:,} active synthetic registrations")
    print(f"Generated {INVIGILATOR_COUNT} synthetic invigilators and {len(staff_course_rows):,} teaching exclusions")


if __name__ == "__main__":
    main()
