CREATE TABLE import_batch (
    import_batch_id TEXT PRIMARY KEY,
    batch_label     TEXT NOT NULL,
    status          TEXT NOT NULL CHECK (status IN ('STAGED','PREVIEWED','COMMITTED','REJECTED')),
    data_label      TEXT NOT NULL DEFAULT 'OPERATIONAL',
    created_at      TEXT NOT NULL,
    committed_at    TEXT,
    source_revision INTEGER NOT NULL DEFAULT 0 CHECK (source_revision >= 0)
);

CREATE TABLE import_source (
    import_source_id TEXT PRIMARY KEY,
    import_batch_id  TEXT NOT NULL REFERENCES import_batch(import_batch_id),
    file_name        TEXT NOT NULL,
    source_type      TEXT NOT NULL CHECK (source_type IN ('CSV','PDF','MANUAL')),
    sha256           TEXT,
    data_label       TEXT NOT NULL DEFAULT 'OPERATIONAL',
    parse_status     TEXT NOT NULL CHECK (parse_status IN ('PENDING','PARSED','REVIEW_REQUIRED','FAILED')),
    row_count        INTEGER CHECK (row_count IS NULL OR row_count >= 0),
    created_at       TEXT NOT NULL
);

CREATE TABLE import_diagnostic (
    diagnostic_id   TEXT PRIMARY KEY,
    import_batch_id TEXT NOT NULL REFERENCES import_batch(import_batch_id),
    import_source_id TEXT REFERENCES import_source(import_source_id),
    source_row      INTEGER CHECK (source_row IS NULL OR source_row > 0),
    severity        TEXT NOT NULL CHECK (severity IN ('INFO','WARNING','ERROR')),
    code            TEXT NOT NULL,
    message         TEXT NOT NULL,
    resolution      TEXT
);

CREATE TABLE import_staged_row (
    import_staged_row_id TEXT PRIMARY KEY,
    import_source_id TEXT NOT NULL REFERENCES import_source(import_source_id) ON DELETE CASCADE,
    source_row      INTEGER NOT NULL CHECK (source_row > 0),
    raw_values      TEXT,
    normalized_values TEXT,
    row_status      TEXT NOT NULL CHECK (row_status IN ('READY','WARNING','ERROR','ACCEPTED','SKIPPED')),
    UNIQUE (import_source_id, source_row)
);

CREATE TABLE building (
    building_id TEXT PRIMARY KEY,
    building_code TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    active       INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0,1)),
    data_label   TEXT NOT NULL DEFAULT 'OPERATIONAL'
);

CREATE TABLE reporting_centre (
    centre_id   TEXT PRIMARY KEY,
    centre_name TEXT NOT NULL UNIQUE,
    active      INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0,1))
);

CREATE TABLE room (
    room_id          TEXT PRIMARY KEY,
    building_id      TEXT NOT NULL REFERENCES building(building_id),
    centre_id        TEXT REFERENCES reporting_centre(centre_id),
    room_code        TEXT NOT NULL,
    floor_number     INTEGER NOT NULL CHECK (floor_number >= 0),
    room_type        TEXT NOT NULL DEFAULT 'CLASSROOM',
    row_count        INTEGER NOT NULL CHECK (row_count > 0),
    column_count     INTEGER NOT NULL CHECK (column_count > 0),
    usable_capacity  INTEGER NOT NULL CHECK (usable_capacity >= 0),
    capacity_status  TEXT NOT NULL CHECK (capacity_status IN ('ESTIMATED','VERIFIED','OVERRIDDEN')),
    active           INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0,1)),
    data_label       TEXT NOT NULL DEFAULT 'OPERATIONAL',
    UNIQUE (building_id, room_code)
);

CREATE TABLE seat (
    seat_id      INTEGER PRIMARY KEY,
    room_id      TEXT NOT NULL REFERENCES room(room_id) ON DELETE CASCADE,
    row_number   INTEGER NOT NULL CHECK (row_number > 0),
    column_number INTEGER NOT NULL CHECK (column_number > 0),
    seat_status  TEXT NOT NULL DEFAULT 'USABLE' CHECK (seat_status IN ('USABLE','BLOCKED','RESERVED')),
    reason       TEXT,
    UNIQUE (room_id, row_number, column_number),
    UNIQUE (room_id, seat_id)
);

CREATE TABLE exam_event (
    event_id          TEXT PRIMARY KEY,
    event_code        TEXT NOT NULL UNIQUE,
    name              TEXT NOT NULL,
    academic_period   TEXT,
    shared_duration_minutes INTEGER CHECK (shared_duration_minutes IS NULL OR shared_duration_minutes > 0),
    status            TEXT NOT NULL CHECK (status IN ('DRAFT','ACTIVE','ARCHIVED')),
    data_label        TEXT NOT NULL DEFAULT 'OPERATIONAL',
    source_revision   INTEGER NOT NULL DEFAULT 0 CHECK (source_revision >= 0),
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL
);

CREATE TABLE exam_session (
    exam_session_id TEXT PRIMARY KEY,
    event_id        TEXT NOT NULL REFERENCES exam_event(event_id) ON DELETE CASCADE,
    session_date    TEXT NOT NULL,
    start_time      TEXT NOT NULL,
    end_time        TEXT,
    reported_candidate_total INTEGER CHECK (reported_candidate_total IS NULL OR reported_candidate_total >= 0),
    status          TEXT NOT NULL CHECK (status IN ('DRAFT','SCHEDULED','CANCELLED')),
    source_row      TEXT,
    UNIQUE (event_id, session_date, start_time),
    CHECK (end_time IS NULL OR end_time > start_time)
);

CREATE TABLE course (
    course_id   TEXT PRIMARY KEY,
    course_code TEXT NOT NULL UNIQUE,
    course_name TEXT,
    active      INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0,1)),
    data_label  TEXT NOT NULL DEFAULT 'OPERATIONAL'
);

CREATE TABLE session_course (
    session_course_id TEXT PRIMARY KEY,
    exam_session_id   TEXT NOT NULL REFERENCES exam_session(exam_session_id) ON DELETE CASCADE,
    course_id         TEXT NOT NULL REFERENCES course(course_id),
    candidate_count   INTEGER NOT NULL CHECK (candidate_count >= 0),
    count_provenance  TEXT NOT NULL CHECK (count_provenance IN ('PDF_IMPORTED','CSV_IMPORTED','REGISTRATION_DERIVED','MANUAL','CONFIRMED')),
    source_page       INTEGER CHECK (source_page IS NULL OR source_page > 0),
    source_row        TEXT,
    import_batch_id   TEXT REFERENCES import_batch(import_batch_id),
    UNIQUE (exam_session_id, course_id),
    UNIQUE (session_course_id, exam_session_id)
);

CREATE TABLE student (
    student_id  TEXT PRIMARY KEY,
    roll_number TEXT NOT NULL UNIQUE,
    full_name   TEXT NOT NULL,
    program     TEXT,
    semester    INTEGER CHECK (semester IS NULL OR semester > 0),
    section     TEXT,
    active      INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0,1)),
    data_label  TEXT NOT NULL DEFAULT 'OPERATIONAL'
);

CREATE TABLE enrollment (
    enrollment_id     TEXT PRIMARY KEY,
    student_id        TEXT NOT NULL REFERENCES student(student_id),
    session_course_id TEXT NOT NULL REFERENCES session_course(session_course_id) ON DELETE CASCADE,
    registration_status TEXT NOT NULL CHECK (registration_status IN ('REGISTERED','WITHDRAWN','INACTIVE')),
    import_batch_id   TEXT REFERENCES import_batch(import_batch_id),
    data_label        TEXT NOT NULL DEFAULT 'OPERATIONAL',
    UNIQUE (student_id, session_course_id)
);

CREATE TABLE staff (
    staff_id    TEXT PRIMARY KEY,
    full_name   TEXT NOT NULL,
    department  TEXT,
    designation TEXT,
    active      INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0,1)),
    data_label  TEXT NOT NULL DEFAULT 'OPERATIONAL'
);

-- A linked staff member must not invigilate a room in which that course is examined.
CREATE TABLE staff_course_relationship (
    staff_id          TEXT NOT NULL REFERENCES staff(staff_id) ON DELETE CASCADE,
    course_id         TEXT NOT NULL REFERENCES course(course_id),
    import_batch_id   TEXT REFERENCES import_batch(import_batch_id),
    data_label        TEXT NOT NULL DEFAULT 'OPERATIONAL',
    PRIMARY KEY (staff_id, course_id)
);

CREATE TABLE staff_availability (
    staff_id        TEXT NOT NULL REFERENCES staff(staff_id) ON DELETE CASCADE,
    exam_session_id TEXT NOT NULL REFERENCES exam_session(exam_session_id) ON DELETE CASCADE,
    availability    TEXT NOT NULL CHECK (availability IN ('AVAILABLE','UNAVAILABLE')),
    reason          TEXT,
    PRIMARY KEY (staff_id, exam_session_id)
);

CREATE TABLE room_availability (
    room_id         TEXT NOT NULL REFERENCES room(room_id) ON DELETE CASCADE,
    exam_session_id TEXT NOT NULL REFERENCES exam_session(exam_session_id) ON DELETE CASCADE,
    available       INTEGER NOT NULL CHECK (available IN (0,1)),
    reason          TEXT,
    PRIMARY KEY (room_id, exam_session_id)
);

CREATE TABLE application_setting (
    setting_key TEXT PRIMARY KEY,
    setting_value TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE plan (
    plan_id           TEXT PRIMARY KEY,
    event_id          TEXT NOT NULL REFERENCES exam_event(event_id),
    version_number    INTEGER NOT NULL CHECK (version_number > 0),
    status            TEXT NOT NULL CHECK (status IN ('DRAFT','REVIEWED','FINALIZED','SUPERSEDED')),
    source_revision   INTEGER NOT NULL CHECK (source_revision >= 0),
    planner_version   TEXT NOT NULL,
    random_seed       INTEGER,
    created_at        TEXT NOT NULL,
    finalized_at      TEXT,
    UNIQUE (event_id, version_number)
);

CREATE TABLE plan_session (
    plan_session_id TEXT PRIMARY KEY,
    plan_id         TEXT NOT NULL REFERENCES plan(plan_id) ON DELETE CASCADE,
    exam_session_id TEXT NOT NULL REFERENCES exam_session(exam_session_id),
    UNIQUE (plan_id, exam_session_id),
    UNIQUE (plan_session_id, exam_session_id)
);

CREATE TABLE room_assignment (
    room_assignment_id TEXT PRIMARY KEY,
    plan_session_id    TEXT NOT NULL REFERENCES plan_session(plan_session_id) ON DELETE CASCADE,
    room_id            TEXT NOT NULL REFERENCES room(room_id),
    candidate_count    INTEGER NOT NULL CHECK (candidate_count >= 0),
    required_staff     INTEGER NOT NULL CHECK (required_staff IN (2,3)),
    assignment_status  TEXT NOT NULL CHECK (assignment_status IN ('PROPOSED','CONFIRMED','REMOVED')),
    UNIQUE (plan_session_id, room_id),
    UNIQUE (room_assignment_id, room_id),
    UNIQUE (room_assignment_id, plan_session_id)
);

CREATE TABLE seat_assignment (
    seat_assignment_id TEXT PRIMARY KEY,
    room_assignment_id TEXT NOT NULL,
    plan_session_id    TEXT NOT NULL,
    exam_session_id    TEXT NOT NULL,
    room_id            TEXT NOT NULL,
    seat_id            INTEGER NOT NULL,
    session_course_id  TEXT NOT NULL REFERENCES session_course(session_course_id),
    student_id         TEXT REFERENCES student(student_id),
    anonymous_candidate_number INTEGER CHECK (anonymous_candidate_number IS NULL OR anonymous_candidate_number > 0),
    assignment_origin  TEXT NOT NULL CHECK (assignment_origin IN ('GENERATED','MANUAL')),
    locked             INTEGER NOT NULL DEFAULT 0 CHECK (locked IN (0,1)),
    FOREIGN KEY (room_assignment_id, room_id) REFERENCES room_assignment(room_assignment_id, room_id),
    FOREIGN KEY (room_assignment_id, plan_session_id) REFERENCES room_assignment(room_assignment_id, plan_session_id),
    FOREIGN KEY (plan_session_id, exam_session_id) REFERENCES plan_session(plan_session_id, exam_session_id),
    FOREIGN KEY (session_course_id, exam_session_id) REFERENCES session_course(session_course_id, exam_session_id),
    FOREIGN KEY (room_id, seat_id) REFERENCES seat(room_id, seat_id),
    CHECK ((student_id IS NOT NULL AND anonymous_candidate_number IS NULL) OR
           (student_id IS NULL AND anonymous_candidate_number IS NOT NULL)),
    UNIQUE (room_assignment_id, seat_id),
    UNIQUE (room_assignment_id, session_course_id, anonymous_candidate_number)
);

CREATE UNIQUE INDEX ux_named_student_once_per_session
    ON seat_assignment(plan_session_id, student_id)
    WHERE student_id IS NOT NULL;

CREATE TABLE invigilation_assignment (
    invigilation_assignment_id TEXT PRIMARY KEY,
    room_assignment_id TEXT NOT NULL REFERENCES room_assignment(room_assignment_id) ON DELETE CASCADE,
    staff_id TEXT NOT NULL REFERENCES staff(staff_id),
    assignment_status TEXT NOT NULL CHECK (assignment_status IN ('PROPOSED','CONFIRMED','REMOVED')),
    UNIQUE (room_assignment_id, staff_id)
);

CREATE TABLE plan_diagnostic (
    diagnostic_id TEXT PRIMARY KEY,
    plan_id       TEXT NOT NULL REFERENCES plan(plan_id) ON DELETE CASCADE,
    plan_session_id TEXT REFERENCES plan_session(plan_session_id),
    code          TEXT NOT NULL,
    severity      TEXT NOT NULL CHECK (severity IN ('INFO','WARNING','ERROR')),
    classification TEXT NOT NULL CHECK (classification IN ('HARD','SOFT','FEASIBILITY')),
    entity_type   TEXT,
    entity_id     TEXT,
    observed_value TEXT,
    required_value TEXT,
    explanation   TEXT NOT NULL,
    suggested_action TEXT,
    resolved      INTEGER NOT NULL DEFAULT 0 CHECK (resolved IN (0,1))
);

-- SQLite does not automatically create indexes for foreign-key child columns.
CREATE INDEX ix_import_source_batch ON import_source(import_batch_id);
CREATE INDEX ix_import_diagnostic_batch ON import_diagnostic(import_batch_id, severity);
CREATE INDEX ix_import_diagnostic_source ON import_diagnostic(import_source_id, severity, source_row);
CREATE INDEX ix_import_staged_row_status ON import_staged_row(import_source_id, row_status, source_row);
CREATE INDEX ix_room_building_active ON room(building_id, active);
CREATE INDEX ix_room_centre ON room(centre_id);
CREATE INDEX ix_seat_room_status ON seat(room_id, seat_status);
CREATE INDEX ix_session_course_course ON session_course(course_id, exam_session_id);
CREATE INDEX ix_session_course_batch ON session_course(import_batch_id);
CREATE INDEX ix_enrollment_course_status ON enrollment(session_course_id, registration_status, student_id);
CREATE INDEX ix_staff_active_department ON staff(active, department);
CREATE INDEX ix_staff_course_course ON staff_course_relationship(course_id, staff_id);
CREATE INDEX ix_staff_availability_session ON staff_availability(exam_session_id, availability, staff_id);
CREATE INDEX ix_room_availability_session ON room_availability(exam_session_id, available, room_id);
CREATE INDEX ix_plan_session_exam_session ON plan_session(exam_session_id, plan_id);
CREATE INDEX ix_room_assignment_room ON room_assignment(room_id, plan_session_id);
CREATE INDEX ix_seat_assignment_room_seat ON seat_assignment(room_id, seat_id);
CREATE INDEX ix_seat_assignment_session_course ON seat_assignment(session_course_id, exam_session_id, room_assignment_id);
CREATE INDEX ix_seat_assignment_plan_session ON seat_assignment(plan_session_id, exam_session_id);
CREATE INDEX ix_seat_assignment_student ON seat_assignment(student_id, room_assignment_id) WHERE student_id IS NOT NULL;
CREATE INDEX ix_invigilation_staff ON invigilation_assignment(staff_id, room_assignment_id);
CREATE INDEX ix_plan_diagnostic_lookup ON plan_diagnostic(plan_id, severity, code);

INSERT INTO application_setting(setting_key, setting_value, updated_at)
VALUES ('room_staff_handover_minutes', '30', CURRENT_TIMESTAMP);
