# Product Decisions

This file records the settled first-release behavior and the small set of owner inputs that still affect policy or safe operation. Implementation details that can be configured in the database are not treated as open product decisions.

## Settled behavior

1. **Application:** a local Java desktop application using Swing and SQLite, with one active local administrator/writer in the first release.
2. **Schedule source:** a date sheet plus a course/student-count source; named student/course registration data is optional. Imports are staged, validated, previewed, and confirmed before changing operational data.
3. **Session timing:** every session must have a date and start time. Sessions in an event use one shared exam duration supplied once during setup; the app derives each end time from that session's start time. Generation is blocked until the duration and derived schedule are reviewed.
4. **Overlap:** on the same date, a session reserves rooms and invigilators over `[start, end + 30 minutes)`. Intersecting intervals conflict. The system-managed global buffer applies to both room and staff reuse and is not entered during event intake.
5. **Building terminology:** “block” in the user interface means a configured building group. Rooms belong directly to a building/block; there is no separate block hierarchy.
6. **Course cohesion:** prefer placing a course in one block when feasible, then split when needed to satisfy hard constraints and capacity.
7. **Seating:** students taking the same course should not be orthogonal neighbors. Diagonal placement is permitted. Empty seats may be used as spacing when overall demand and room capacity allow it.
8. **Room capacity:** use configured exam-usable capacity. When seat-level layout data is available, effective capacity is the lower of configured exam capacity and usable seats. Estimated/unverified capacity may support a draft with a warning; finalization requires administrator acknowledgement.
9. **Staff eligibility and team size:** a staff member is ineligible for a room if they teach or are explicitly excluded from any course assigned to that room. Availability is whole-session. The fixed initial team-size rule is 3 invigilators for configured exam-usable room capacity greater than 100 seats and 2 for capacity of 100 or less. The dashboard must support the expected 300–400 real staff records without seeding fake people.
10. **Room seed:** G, B, C, D, E, and F each start with 3 floors × 12 rooms/floor × estimated 30 seats/room; LT starts with 4 floors × 2 rooms/floor × estimated 200 seats/room; TAN starts with 3 floors × 2 rooms/floor × estimated 200 seats/room. Starter grids are 5×6 for 30-seat rooms and 10×20 for 200-seat rooms. Seeded room codes, capacities, and grids are editable database records marked unverified. Opening all 230 rooms at once would require 474 invigilators under the fixed rule.
11. **Exam-event workflow:** import/review date sheet → import/review course/student counts → optionally reconcile a named roster → select/review rooms and invigilators from the already available dashboards → generate and review the plan. Supplied `DEMO` CSVs are import fixtures, not automatic production startup data. `data/demo/rooms.csv` is the 230-row provisional inventory fixture; load or reconcile it once in a demo database, not in addition to a second copy of the same provisional seed.
12. **Build sequence:** implement the invigilator dashboard first, then room dashboard, then student dashboard; date-sheet/course-count intake and planning follow. This development order does not prevent administrators from using the master-data dashboards without an event.
13. **Re-imports and edits:** changes to source schedule, room, staff, or policy data mark dependent drafts stale and block finalization. A generation running against an older source-data revision cannot save its result.
14. **Plan changes:** manual plan edits create a new plan version and concise change summary. Finalized versions remain immutable. The first release does not keep a general-purpose audit event for every database edit.
15. **CSV:** accept UTF-8 with optional BOM, LF/CRLF, quoted fields, and comma or semicolon separators with preview confirmation. Treat identifiers as strings. Flag spreadsheet-converted scientific notation rather than guessing the original identifier.
16. **Data removal:** no automatic expiry. Provide an administrator-initiated event-level purge/anonymization path; the retention interval needs owner confirmation before named data is used operationally.

## Explicitly out of first-release scope

- Multi-user/server deployment and concurrent administrator synchronization.
- Accessibility accommodation matching and seat-feature allocation.
- Partial-session staff availability windows.
- Automated workload balancing across staff.
- Local pair-swap or other iterative seating optimization until a baseline planner is measured.
- A session-coordinator role or a general-purpose CRUD audit log.

## Owner inputs required

The following privacy policy should be confirmed before named student/staff data is used operationally:

1. **Personal-data retention:** how long should named student/staff data and finalized named seating/invigilation lists be retained after the last exam in an event? After that period, should the app purge names/IDs and retain only anonymized plan data and aggregate counts, or must named finalized exports remain available longer?

The current seeded room figures are estimates from the user-provided assumptions. Verified room layouts/codes and the actual staff/course roster remain operational data to update through their dashboards. The global 30-minute handover buffer and 100-seat staffing cutoff are fixed first-release rules.
