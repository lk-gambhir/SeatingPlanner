-- Keep the values shown in saved plans stable when master data changes later.
ALTER TABLE plan_session ADD COLUMN session_date_snapshot TEXT;
ALTER TABLE plan_session ADD COLUMN start_time_snapshot TEXT;
ALTER TABLE plan_session ADD COLUMN end_time_snapshot TEXT;
ALTER TABLE plan_session ADD COLUMN candidate_demand_snapshot INTEGER;

ALTER TABLE room_assignment ADD COLUMN building_code_snapshot TEXT;
ALTER TABLE room_assignment ADD COLUMN room_code_snapshot TEXT;
ALTER TABLE room_assignment ADD COLUMN capacity_snapshot INTEGER;
ALTER TABLE room_assignment ADD COLUMN capacity_status_snapshot TEXT;
ALTER TABLE room_assignment ADD COLUMN seat_grid_snapshot INTEGER NOT NULL DEFAULT 0 CHECK (seat_grid_snapshot IN (0,1));

ALTER TABLE seat_assignment ADD COLUMN row_number_snapshot INTEGER;
ALTER TABLE seat_assignment ADD COLUMN column_number_snapshot INTEGER;
ALTER TABLE seat_assignment ADD COLUMN seat_status_snapshot TEXT;
ALTER TABLE seat_assignment ADD COLUMN candidate_label_snapshot TEXT;
ALTER TABLE seat_assignment ADD COLUMN course_code_snapshot TEXT;

CREATE TABLE room_assignment_seat (
    room_assignment_id TEXT NOT NULL REFERENCES room_assignment(room_assignment_id) ON DELETE CASCADE,
    seat_id INTEGER NOT NULL,
    row_number INTEGER NOT NULL,
    column_number INTEGER NOT NULL,
    seat_status TEXT NOT NULL CHECK (seat_status IN ('USABLE','BLOCKED','RESERVED')),
    PRIMARY KEY (room_assignment_id, seat_id)
);
