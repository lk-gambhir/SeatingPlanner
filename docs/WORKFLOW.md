# Exam Seating and Invigilation Planner — User Workflow Guide

This guide describes the complete end-to-end operational workflow for examination coordinators and administrators using the **Automated Exam Seating and Invigilation Planner**.

---

## 1. Quick Start & Application Launch

### Prerequisites
- **Java Development Kit (JDK)**: Java 21 or later (Java 25 recommended).
- **Maven**: Bundled Maven wrapper (`./mvnw`) is included in the project root.

### Launching the Application
Execute the following command in the project root directory:

```bash
./mvnw clean javafx:run
```

Or run the pre-built application JAR:

```bash
java -jar target/exam-seating-planner-0.1.0-SNAPSHOT.jar
```

The application automatically verifies and bootstraps the local SQLite database at `~/.thapar-exam-planner/exam-planner.db` (or a custom path passed as the first CLI argument), applying all schema migrations (`V001` through `V005`) idempotently.

---

## 2. Operational Workflow Overview

The system operates across four primary tabs, following a structured 6-stage lifecycle:

```
┌──────────────────┐     ┌──────────────────┐     ┌──────────────────┐
│  1. Campus Rooms │ ──> │  2. Invigilators │ ──> │   3. Students    │
│    & Layouts     │     │   & Affiliations │     │  & Registrations │
└──────────────────┘     └──────────────────┘     └──────────────────┘
                                                            │
                                                            ▼
┌──────────────────┐     ┌──────────────────┐     ┌──────────────────┐
│  6. Operational  │ <── │ 5. Seating Plan  │ <── │ 4. Exam Schedule │
│    CSV Exports   │     │    & Finalize    │     │   & Feasibility  │
└──────────────────┘     └──────────────────┘     └──────────────────┘
```

---

## 3. Step-by-Step Instructions

### Step 1: Set Up Room Inventory (Rooms Tab)

1. **Import Master Rooms**:
   - Navigate to the **Rooms** tab.
   - Click **Import CSV** and select your room inventory file (e.g., `data/demo/rooms.csv`).
   - The **Review room import** preview dialog will appear displaying row numbers, building, room code, capacity, and layout grids.
2. **Interactive Row Exclusion**:
   - Select any row(s) you wish to skip from the import batch and click **Exclude / Re-include selected valid row(s)**. Excluded rows will be flagged as `EXCLUDE` and recorded in the audit log without halting the batch.
   - Click **Confirm and Import**.
3. **Review & Adjust Room Layouts**:
   - Select any room from the table to view its layout dimensions (rows × columns) and blocked seats.
   - To mark seats damaged or unusable, use the **Edit selected room** form and provide blocked coordinates (e.g., `1:3; 2:4`).
4. **Authoritative Room Reconciliation**:
   - For provisional or estimated rooms, select the room and click **Reconcile selected**.
   - Input the verified official room code, updated capacity, verification status (`VERIFIED`), and audit note in the **Source / note** field (e.g., *"Audited by Dean of Exams 2026-11-16"*).

---

### Step 2: Set Up Invigilators & Teaching Affiliations (Invigilators Tab)

1. **Import Staff Profiles**:
   - Navigate to the **Invigilators** tab.
   - Click **Import CSV** and choose your staff list (e.g., `data/demo/invigilators.csv`).
   - Review the preview table, apply any row exclusions if needed, and click **Confirm and Import**.
2. **Configure Course Affiliations & Conflicts**:
   - Select a staff member in the table.
   - Use **Add course link** to record course relationships:
     - `TEACHES`: Flags the staff member as teaching the course (the automated planner will ensure they are not assigned as room invigilators for their own course papers).
     - `EXCLUDED`: Explicitly disallows assigning this staff member to that course.
3. **Session Availability**:
   - Select an exam session from the **Exam session** dropdown.
   - Mark individual staff as `Available`, `Unavailable`, `Present`, or `Absent` directly or via **Import session statuses**.

---

### Step 3: Set Up Students & Exam Registrations (Students Tab)

1. **Import Student Profiles**:
   - Open the **Students** tab.
   - Click **Import CSV** and select the student master roster (e.g., `data/demo/students.csv`).
   - Use interactive row exclusions if certain students should not be loaded. Click **Confirm and Import**.
2. **Import Exam Enrollments**:
   - Select the target exam from the **Target exam** dropdown.
   - Click **Import enrollments CSV** (e.g., `data/demo/enrollments.csv`).
   - The preview will validate course codes and student IDs against the database.
   - Review any diagnostics and click **Confirm and Import**.
3. **Inspect Course Demand & Student Conflicts**:
   - Switch to the **Exam course demand** tab to verify that registered counts match expected candidate numbers.
   - Check the roster status (`Matched` vs. `Mismatch`).

---

### Step 4: Import Exam Date Sheet & Course Demand (Exam Planning Tab)

1. **Select Schedule & Count PDFs**:
   - In the **Exam Planning** tab under the **Import exam** sub-tab:
   - Click **Choose date sheet PDF** (the schedule PDF without totals).
   - Click **Choose count PDF** (the matching course-count PDF with totals).
   - Enter the shared **Exam name** (e.g., `Mid-Semester Examination`), optional **Academic period** (e.g., `2026-ODD`), and **Duration in minutes** (e.g., `120`).
2. **Preview and Validate**:
   - Click **Preview PDFs**.
   - The application parses both PDFs, extracts dates, start times, course codes, and candidate counts, and cross-checks them.
3. **Resolve Schedule & Count Diagnostics**:
   - If candidate totals or course codes differ between the two PDFs:
     - Select the session and click **Correct selected session** to add missing courses, remove surplus papers, or correct count discrepancies.
     - Click **Resolve diagnostic** to review any remaining blocking parsing errors, input an explanation note, and convert the blocker into a traceable warning.
4. **Commit Exam Event**:
   - Once all blocking errors are cleared, click **Confirm and import**.

---

### Step 5: Feasibility Analysis & Seat Assignment (Saved Exams Sub-tab)

1. **Preflight Feasibility Check**:
   - Under **Saved exams**, select the imported exam event.
   - Click **Check feasibility**.
   - The system calculates session-by-session room demand, effective seat capacity, and invigilator requirements (3 invigilators for rooms with capacity > 100, otherwise 2).
   - Review any shortfall warnings in the feasibility report.
2. **Student Conflict Check**:
   - Click **Check student conflicts** to ensure no student is scheduled for overlapping exams at the same date and time slot.
3. **Generate Seating Draft**:
   - Click **Generate seating draft**.
   - The deterministic planning algorithm:
     - Allocates campus rooms with building proximity preference.
     - Places candidates into seats using a **checkerboard alternating course pattern** (adjacent seats never share the same course paper, eliminating casual cheating).
     - Assigns eligible, available invigilators avoiding course teaching conflicts.

---

### Step 6: Plan Review, Finalization & CSV Exports

1. **Review Draft**:
   - The generated plan dialog displays room-by-room candidate allocations, seat assignments, and invigilator rosters.
   - Click **Save draft** to record the plan as version 1 (`v1`).
2. **Finalize Seating Plan**:
   - When reviewed and approved, open the saved plan and click **Finalize plan**.
   - Once finalized, plan display snapshots freeze the room and seat geometries, ensuring historical records remain immutable even if master campus inventory changes later.
3. **Export Operational CSV Files**:
   - Export door charts, candidate seat slips, invigilator duty allocations, and course block summaries using the export controls:
     - `seats.csv`: Door charts with room, row, seat, roll number, and course code.
     - `invigilator_roster.csv`: Staff duty assignment sheets.
     - `course_blocks.csv`: Summary of course numbers per room.

---

### Step 7: Event Purge (Clean Close-Out)

- If an exam cycle is completed and needs to be archived or cleared:
- Select the event and click **Purge event and plans**.
- The administrator-initiated purge safely deletes the event sessions, generated plans, and unreferenced enrollment records while strictly preserving the master room and staff records.
