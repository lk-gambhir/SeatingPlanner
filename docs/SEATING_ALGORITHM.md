# Seating Assignment Algorithm

## 1. Purpose and scope

This document specifies the first seating planner: a deterministic, explainable greedy heuristic for assigning named students or anonymous candidate slots to rooms and seats. The method aims to avoid same-course students in the four orthogonal directions while seating all eligible demand. A diagonal neighbor is allowed. Same-course adjacency is a soft preference, not a hard validity rule; when a conflict cannot be avoided, keep the candidate seated and report the exact pair and location.

Room selection, seat assignment, and invigilator assignment are separate steps. Hard constraints are checked by validators and are never converted into a score penalty. The output is a draft until independent validation succeeds and an administrator finalizes it.

## 2. Inputs and normalized planning snapshot

The planner receives an immutable snapshot containing:

- selected exam date/time slots and their courses;
- named students plus active registrations, or course candidate counts for count-only mode;
- selected rooms, building/block, optional reporting centre, effective usable capacity, grid coordinates, and blocked seats;
- room availability and existing assignments that conflict under the same-date 30-minute handover rule;
- optional previous seat assignment for each named student;
- source-data revision and stable IDs for deterministic tie-breaking.

Only `REGISTERED` enrollment rows become named candidates. `WITHDRAWN` rows remain in import review/history but contribute no seating demand. A course count without roster identities produces anonymous candidate keys (course ID plus a stable sequence); it must never fabricate student names or IDs.

When one time slot contains multiple concurrent courses, combine all of their candidates into a single room/seat allocation problem. A room can contain candidates from several courses. A candidate with two active exams whose actual exam intervals overlap is a hard schedule conflict and is reported before seat placement. The 30-minute handover buffer applies to room and invigilator reuse, not student exam-clash detection. Count-only mode cannot detect an individual student's clash.

## 3. Hard-valid placement predicate

Implement a pure service method conceptually equivalent to:

```text
isValid(candidate, room, seat, planningState) -> ValidationResult
```

It returns invalid, with one or more stable diagnostic codes, if any of these conditions fails:

1. The room is selected, active, available for this slot, and not occupied by a conflicting session.
2. The seat belongs to the room and is usable (not blocked/reserved), and the room's assigned-candidate count is still below its effective capacity (`min(configured exam capacity, usable seat count)`).
3. The seat is not already occupied in this plan and slot.
4. The candidate is not already assigned in this slot.
5. In named mode, the candidate has exactly one valid active registration for the course being seated, and has no hard exam clash based on the actual exam intervals.
6. In anonymous mode, the candidate key belongs to the course demand and has not already been placed.

The predicate does **not** reject an orthogonal same-course neighbor. That condition is scored as a preference after all hard-valid candidates are found. Diagonal neighbors do not add a penalty.

Validate input duplicates before planning. Duplicate active enrollment keys, missing student/course references, or a candidate whose enrollment does not match the scheduled course are data blockers; do not guess which row is correct.

## 4. Planning sequence

### Step A — Preflight

1. Freeze the chosen sessions, registrations/counts, room selection, layouts, availability, and source-data revision.
2. Normalize IDs as strings and order records by stable identifiers.
3. Reconcile course demand. In named mode, compare active registrations with any imported course count and report mismatches for review.
4. Check registered student schedule clashes across actual same-date exam intervals without applying the room/staff handover buffer.
5. Calculate total usable seats per slot and compare with total demand. Remove no demand silently.
6. Validate that selected room geometry, blocked seats, and configured exam capacity can be reconciled; keep unresolved capacity ambiguity visible.

If a hard blocker prevents allocation, stop or create an explicitly incomplete draft with the affected unassigned counts. Do not claim a feasible plan.

### Step B — Assign course demand to blocks and rooms

For each slot, sort courses by descending candidate count, then course code. For each course:

1. Calculate remaining usable capacity in each selected block.
2. If any block can hold the whole course, choose a block that best preserves course cohesion; for a new course with no existing allocation, prefer the block that fits the course using the fewest rooms and least leftover capacity. Break ties by stable building/block ID.
3. Within the chosen block, select rooms by best fit: prefer a room whose remaining capacity can hold the remaining course demand with the least unused capacity, then prefer an already-open room when that does not create a course split. Break ties by stable room ID.
4. If no one block has enough capacity, allocate the remaining course demand across blocks, prioritizing the block with the most remaining compatible capacity. Record a soft `COURSE_SPLIT_ACROSS_BLOCKS` diagnostic with candidate counts and rooms for each part.
5. Allow different courses to share a room. The seat layer keeps their course identities so adjacency and later invigilator eligibility checks can inspect them.

The objective is to honor hard availability/capacity constraints first, then reduce course block splits and unnecessary room use. This is a greedy heuristic and does not promise globally minimum room count or optimal packing. The independent capacity validator catches any over-allocation.

### Step C — Greedy seat placement

For each room in stable room-ID order, place the course groups assigned to it in descending group size, then course-code order. Within a named course group, order students by stable student ID; anonymous candidates use their stable sequence number.

For each candidate:

1. Enumerate unoccupied usable seats in the room in stable row/column order.
2. Call `isValid(candidate, room, seat, state)` for every candidate seat. Keep only hard-valid choices.
3. Score each remaining choice lexicographically:
   - **First:** number of already-seated same-course candidates immediately up, down, left, or right from the seat (lower is better).
   - **Second:** prefer the same room/seat used by this student in a prior exam when it remains valid.
   - **Third:** prefer a seat with more currently free orthogonal neighbors, preserving future spacing options.
   - **Last:** row, then column, then stable seat ID for deterministic tie-breaking.
4. Place the candidate in the minimum-score seat and update the in-memory occupancy state.
5. If all remaining choices add an orthogonal same-course neighbor, still choose the lowest-scoring hard-valid seat. Record a soft diagnostic with both candidate identities (or anonymous keys), course, room, and seat coordinates.
6. If no hard-valid seat exists, leave that candidate explicitly unassigned and report the predicate failures; never overwrite another assignment or silently drop demand.

This scoring makes avoiding a same-course neighbor the dominant seating preference. A same student's prior seat is reused only when doing so does not worsen that higher-priority preference or violate a hard rule. Diagonal same-course neighbors contribute zero adjacency penalty.

## 5. Why `valid()` and `score()` stay separate

`isValid` answers “may this assignment exist?” It enforces identity, eligibility, capacity, availability, and uniqueness. `score` answers “which valid assignment is preferred?” It ranks orthogonal adjacency, prior-seat continuity, spacing opportunity, and deterministic ties. Keeping these methods separate prevents a large penalty from accidentally allowing an illegal plan and makes diagnostics directly testable.

Suggested service boundary:

```text
SeatPlanner.generate(snapshot) -> DraftPlan
PlacementValidator.validate(snapshot, draft) -> List<Diagnostic>
```

The planner may return a draft with soft warnings; the independent validator must still confirm every hard constraint before the draft can proceed to finalization.

## 6. Complexity and deterministic behavior

Let `N` be candidate demand and `S` the number of usable seats in selected rooms. A straightforward greedy seat pass may evaluate up to `N × S` placements, with constant-degree neighbor checks for rectangular/coordinate layouts. Use precomputed neighbor lists and occupancy maps so each score evaluation checks at most four orthogonal neighbors. The input scale in the provided fixtures is suitable for this simple pass.

Determinism requires stable ordering for slots, courses, rooms, students/candidate keys, and seats; no iteration over unordered maps may determine a tie. Identical snapshots and policy values must produce identical assignments. Store the planner version with each plan draft.

## 7. Diagnostics and incomplete plans

Use structured diagnostics, including stable code, severity, session/slot, course, room, seat coordinates, candidate key where available, observed/required values, explanation, and suggested action.

Examples:

- `SEAT_CAPACITY_SHORTFALL` — demand exceeds selected usable seats; report demand, capacity, and unassigned count.
- `STUDENT_SESSION_CLASH` — a named student is registered in overlapping papers; identify both papers and intervals.
- `INVALID_OR_DUPLICATE_ENROLLMENT` — registration references are missing, duplicated, or inconsistent.
- `SAME_COURSE_ORTHOGONAL_ADJACENCY` — soft warning naming both adjacent assignments and coordinates.
- `COURSE_SPLIT_ACROSS_BLOCKS` — soft warning with counts per block.
- `PRIOR_SEAT_NOT_REUSED` — optional low-priority note when the previous seat could not be retained; include the reason.
- `ROOM_LAYOUT_CAPACITY_MISMATCH` — room dimensions, blocked seats, and configured capacity disagree.
- `UNASSIGNED_CANDIDATE` — a candidate remains without a seat; this blocks finalization.

When the greedy result has soft violations but all candidates are seated and hard constraints pass, save a reviewable draft and let the administrator decide whether to accept the preference trade-off. Do not run local search, random retries, or pair swaps in the first release; revisit only after measured results show a material quality gap.

## 8. Acceptance examples

- Same-course students in diagonal seats: valid, zero adjacency conflict.
- Same-course students directly left/right or above/below: valid placement with a soft diagnostic if no better valid seat was available.
- A blocked or already occupied seat: rejected by `isValid`.
- One named student assigned to two simultaneous courses: preflight blocker; no seat is assigned for either conflicting registration until corrected.
- Capacity exactly equal to demand: all candidates must be seated; adjacency warnings may remain.
- Capacity below demand: return the assigned subset and exact unassigned count; the plan cannot be finalized.
- Same student in a later, non-conflicting exam: may reuse the prior physical room/seat when valid, but must still be placed according to current course spacing.
- Same inputs and configuration run twice: identical room and seat assignments and identical diagnostics.
