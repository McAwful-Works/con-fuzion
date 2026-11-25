-- M4: offers with a deadline, the in-app inbox and Web Push subscriptions (plan B3/B4/B8, D-014).

-- An offer holds a freed spot for the next person in line until offer_expires_at. The held spot counts in
-- event.confirmed_count. Declining is LEFT; an offer that runs out ends as EXPIRED, and that person may join again.
ALTER TABLE rsvp
	ADD COLUMN offer_expires_at timestamptz,
	DROP CONSTRAINT rsvp_status,
	ADD CONSTRAINT rsvp_status CHECK (status IN ('CONFIRMED', 'WAITLISTED', 'OFFERED', 'LEFT', 'REMOVED', 'EXPIRED')),
	ADD CONSTRAINT rsvp_offer_expiry CHECK ((status = 'OFFERED') = (offer_expires_at IS NOT NULL));

-- An open offer is an active RSVP: it counts towards the one active RSVP per person per event, and towards My
-- schedule. A narrower ON CONFLICT or WHERE (CONFIRMED, WAITLISTED) still infers and uses these indexes.
DROP INDEX rsvp_active_key;
CREATE UNIQUE INDEX rsvp_active_key ON rsvp (event_id, user_id) WHERE status IN ('CONFIRMED', 'WAITLISTED', 'OFFERED');
DROP INDEX rsvp_user_active_idx;
CREATE INDEX rsvp_user_active_idx ON rsvp (user_id) WHERE status IN ('CONFIRMED', 'WAITLISTED', 'OFFERED');
-- The expiry job: open offers, soonest deadline first.
CREATE INDEX rsvp_offer_expiry_idx ON rsvp (offer_expires_at) WHERE status = 'OFFERED';

-- The inbox, and the push queue: the sender pushes PENDING rows and marks them SENT or FAILED. NONE means the user had
-- push off or no subscription when the row was written, so it is inbox only. The payload is safe for a lock screen:
-- no location and no names (D-014).
CREATE TABLE notification (
	id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	user_id     uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
	type        varchar(20) NOT NULL,
	event_id    bigint      REFERENCES event (id) ON DELETE CASCADE,
	payload     jsonb       NOT NULL,
	read_at     timestamptz,
	push_status varchar(10) NOT NULL,
	created_at  timestamptz NOT NULL DEFAULT now(),
	CONSTRAINT notification_type CHECK (type IN ('SPOT_OFFERED', 'SPOT_CONFIRMED', 'EVENT_CHANGED', 'EVENT_CANCELLED',
		'EVENT_REMINDER', 'FRIEND_ACCEPTED')),
	-- Every type but FRIEND_ACCEPTED is about an event.
	CONSTRAINT notification_event CHECK ((type = 'FRIEND_ACCEPTED') = (event_id IS NULL)),
	CONSTRAINT notification_payload CHECK (jsonb_typeof(payload) = 'object'),
	CONSTRAINT notification_push_status CHECK (push_status IN ('NONE', 'PENDING', 'SENT', 'FAILED'))
);
-- The inbox, newest first, and the unread count.
CREATE INDEX notification_inbox_idx ON notification (user_id, id DESC);
-- The sender's queue.
CREATE INDEX notification_push_idx ON notification (id) WHERE push_status = 'PENDING';
-- The event FK's cascade, and a block's cleanup of the notifications about the other person's events.
CREATE INDEX notification_event_idx ON notification (event_id);

-- A browser's Web Push subscription. The endpoint belongs to whoever subscribed with it last, so a shared browser
-- never gets the previous user's pushes. At most 10 per user (D-014).
CREATE TABLE push_subscription (
	id         bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	user_id    uuid          NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
	endpoint   varchar(2048) NOT NULL,
	p256dh     varchar(100)  NOT NULL,
	auth       varchar(50)   NOT NULL,
	created_at timestamptz   NOT NULL DEFAULT now(),
	CONSTRAINT push_subscription_endpoint_key UNIQUE (endpoint)
);
-- A user's subscriptions, oldest first (the cap drops the oldest), and the user FK's cascade.
CREATE INDEX push_subscription_user_idx ON push_subscription (user_id, id);
