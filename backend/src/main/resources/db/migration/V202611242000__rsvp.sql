-- M3 RSVPs: first come, first served, with a waitlist (plan B3/B4, D-013).

-- The host takes a spot (D-013): capacity is the whole headcount, host included, so it is at least 2. Fix existing
-- rows before tightening the CHECKs. The offer window (used from M4) is 10 minutes to 24 hours.
UPDATE event SET
	capacity = GREATEST(capacity, 2),
	confirm_window_minutes = LEAST(GREATEST(confirm_window_minutes, 10), 1440)
WHERE capacity < 2 OR confirm_window_minutes NOT BETWEEN 10 AND 1440;

ALTER TABLE event
	DROP CONSTRAINT event_capacity_range,
	ADD CONSTRAINT event_capacity_range CHECK (capacity BETWEEN 2 AND 10000),
	DROP CONSTRAINT event_confirm_window_positive,
	ADD CONSTRAINT event_confirm_window_range CHECK (confirm_window_minutes BETWEEN 10 AND 1440),
	-- The atomic claim already guarantees this. The CHECK is a backstop: an overbooking bug fails loudly instead.
	ADD CONSTRAINT event_not_overbooked CHECK (confirmed_count <= capacity);

-- One row per join. The id is also the queue order: a lower id joined first. Leaving or being removed keeps the row
-- (LEFT or REMOVED), and joining again adds a new row at the back of the queue. M4 adds OFFERED and EXPIRED.
CREATE TABLE rsvp (
	id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	event_id   bigint      NOT NULL REFERENCES event (id) ON DELETE CASCADE,
	-- Account deletion must leave each active RSVP first (freeing the spot and promoting the next person), because
	-- this cascade alone doesn't touch event.confirmed_count (D-013).
	user_id    uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
	status     varchar(10) NOT NULL,
	created_at timestamptz NOT NULL DEFAULT now(),
	updated_at timestamptz NOT NULL DEFAULT now(),
	CONSTRAINT rsvp_status CHECK (status IN ('CONFIRMED', 'WAITLISTED', 'LEFT', 'REMOVED'))
);
-- At most one active RSVP per person per event, which also absorbs a double-tapped join.
CREATE UNIQUE INDEX rsvp_active_key ON rsvp (event_id, user_id) WHERE status IN ('CONFIRMED', 'WAITLISTED');
-- The waitlist in queue order: who is promoted next, and a waitlisted person's position.
CREATE INDEX rsvp_queue_idx ON rsvp (event_id, id) WHERE status = 'WAITLISTED';
-- My schedule (GET /api/events?scope=attending).
CREATE INDEX rsvp_user_active_idx ON rsvp (user_id) WHERE status IN ('CONFIRMED', 'WAITLISTED');
-- Every row of a person on an event, active or not (is there a REMOVED row?), and the event FK's cascade, which the
-- partial indexes can't serve.
CREATE INDEX rsvp_event_user_idx ON rsvp (event_id, user_id);

-- Every existing event gets its host's confirmed RSVP, dated when the event was made, and so one confirmed person.
-- No one else can have joined yet.
INSERT INTO rsvp (event_id, user_id, status, created_at, updated_at)
SELECT id, host_id, 'CONFIRMED', created_at, created_at FROM event ORDER BY id;
UPDATE event SET confirmed_count = 1;
