-- M2 events and their audiences (plan B3, D-012). URLs use public_id, so event IDs can't be enumerated.

CREATE TABLE event (
	id                     bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	public_id              uuid          NOT NULL DEFAULT gen_random_uuid(),
	-- No cascade: account deletion has to cancel the host's events (notifying attendees) and then delete them
	-- (D-012), so a bare user delete fails instead of silently dropping other people's plans.
	host_id                uuid          NOT NULL REFERENCES app_user (id),
	title                  varchar(100)  NOT NULL,
	description            varchar(2000),
	location_text          varchar(200)  NOT NULL,
	tag                    varchar(30),
	starts_at              timestamptz   NOT NULL,
	ends_at                timestamptz,
	-- IANA ID such as America/Chicago: the zone the host shows times in.
	time_zone              varchar(64)   NOT NULL,
	-- NULL means unlimited.
	capacity               int,
	confirmed_count        int           NOT NULL DEFAULT 0,
	-- NULL means sign-up is open as soon as the event is published.
	signup_opens_at        timestamptz,
	confirm_window_minutes int           NOT NULL DEFAULT 30,
	location_visibility    varchar(10)   NOT NULL DEFAULT 'VIEWERS',
	status                 varchar(10)   NOT NULL DEFAULT 'PUBLISHED',
	created_at             timestamptz   NOT NULL DEFAULT now(),
	updated_at             timestamptz   NOT NULL DEFAULT now(),
	CONSTRAINT event_public_id_key UNIQUE (public_id),
	CONSTRAINT event_ends_after_start CHECK (ends_at > starts_at),
	CONSTRAINT event_signup_before_start CHECK (signup_opens_at < starts_at),
	CONSTRAINT event_capacity_range CHECK (capacity BETWEEN 1 AND 10000),
	CONSTRAINT event_confirmed_count_non_negative CHECK (confirmed_count >= 0),
	CONSTRAINT event_confirm_window_positive CHECK (confirm_window_minutes > 0),
	CONSTRAINT event_location_visibility CHECK (location_visibility IN ('VIEWERS', 'CONFIRMED')),
	CONSTRAINT event_status CHECK (status IN ('PUBLISHED', 'CANCELLED'))
);
-- The feed's time window, and "events this user hosts" (the hosting list and the 20-per-day cap).
CREATE INDEX event_starts_at_idx ON event (starts_at);
CREATE INDEX event_host_starts_at_idx ON event (host_id, starts_at);

-- Who an event is for: one row per entry, with a typed reference instead of a free-text ref_id, so every reference
-- has a real foreign key (or, for a Discord server, a bigint snowflake). Deleting a circle or user removes their
-- entries. The CHECK sets exactly the column the type names; FRIENDS (the host's friends) sets none.
CREATE TABLE event_audience (
	id        bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	event_id  bigint      NOT NULL REFERENCES event (id) ON DELETE CASCADE,
	type      varchar(10) NOT NULL,
	guild_id  bigint,
	circle_id bigint      REFERENCES circle (id) ON DELETE CASCADE,
	user_id   uuid        REFERENCES app_user (id) ON DELETE CASCADE,
	CONSTRAINT event_audience_typed_ref CHECK (
		(type = 'GUILD'   AND guild_id IS NOT NULL AND circle_id IS NULL     AND user_id IS NULL) OR
		(type = 'FRIENDS' AND guild_id IS NULL     AND circle_id IS NULL     AND user_id IS NULL) OR
		(type = 'CIRCLE'  AND guild_id IS NULL     AND circle_id IS NOT NULL AND user_id IS NULL) OR
		(type = 'USER'    AND guild_id IS NULL     AND circle_id IS NULL     AND user_id IS NOT NULL)),
	-- Each entry once per event. Its index leads with event_id, so it also serves "this event's audience".
	CONSTRAINT event_audience_entry_key UNIQUE NULLS NOT DISTINCT (event_id, type, guild_id, circle_id, user_id)
);
-- The feed starts from the viewer's audience keys: their discoverable servers, their circles and their own ID.
CREATE INDEX event_audience_guild_idx ON event_audience (guild_id) WHERE guild_id IS NOT NULL;
CREATE INDEX event_audience_circle_idx ON event_audience (circle_id) WHERE circle_id IS NOT NULL;
CREATE INDEX event_audience_user_idx ON event_audience (user_id) WHERE user_id IS NOT NULL;
