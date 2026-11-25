-- M5: roles, bans, reports, event moderation and the audit log (plan B3/B12, D-015).

-- Granted roles. USER is implicit and never stored. A role is in effect only while Discord reports MFA on at sign-in.
-- granted_by is NULL for a bootstrap grant from con-fuzion.admin.bootstrap-discord-ids.
CREATE TABLE user_role (
	user_id    uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
	role       varchar(10) NOT NULL,
	granted_by uuid        REFERENCES app_user (id) ON DELETE SET NULL,
	granted_at timestamptz NOT NULL DEFAULT now(),
	CONSTRAINT user_role_pkey PRIMARY KEY (user_id, role),
	CONSTRAINT user_role_role CHECK (role IN ('MODERATOR', 'ADMIN'))
);

-- Bans are by Discord ID, with no foreign key to app_user, so a ban outlives the account and stops a re-signup.
CREATE TABLE banned_account (
	discord_id bigint       PRIMARY KEY,
	reason     varchar(500) NOT NULL,
	banned_by  uuid         REFERENCES app_user (id) ON DELETE SET NULL,
	created_at timestamptz  NOT NULL DEFAULT now()
);

-- A report on an event (its public_id) or a user (their id). Both are UUIDs, so one target_id column holds either.
-- There is deliberately no foreign key on the target: a report outlives the 90-day purge of ended events and account
-- deletion, so the moderation history and a user's report count stay intact (D-015). A deleted reporter or
-- moderator leaves the report in place, anonymised.
CREATE TABLE report (
	id              bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	reporter_id     uuid          REFERENCES app_user (id) ON DELETE SET NULL,
	target_type     varchar(10)   NOT NULL,
	target_id       uuid          NOT NULL,
	reason          varchar(15)   NOT NULL,
	note            varchar(1000),
	status          varchar(10)   NOT NULL DEFAULT 'OPEN',
	handled_by      uuid          REFERENCES app_user (id) ON DELETE SET NULL,
	handled_at      timestamptz,
	resolution_note varchar(1000),
	created_at      timestamptz   NOT NULL DEFAULT now(),
	CONSTRAINT report_target_type CHECK (target_type IN ('EVENT', 'USER')),
	CONSTRAINT report_reason CHECK (reason IN ('HARASSMENT', 'UNSAFE', 'SPAM', 'INAPPROPRIATE', 'OTHER')),
	CONSTRAINT report_status CHECK (status IN ('OPEN', 'ACTIONED', 'DISMISSED')),
	-- A report is handled exactly when it's no longer open. handled_by can be NULL later, if the moderator is deleted.
	CONSTRAINT report_handled CHECK ((status = 'OPEN') = (handled_at IS NULL)),
	CONSTRAINT report_open_unresolved CHECK (status <> 'OPEN' OR (handled_by IS NULL AND resolution_note IS NULL))
);
-- The moderation queue, by status in ID order (oldest first for OPEN, newest first for the others).
CREATE INDEX report_queue_idx ON report (status, id);
-- One open report per reporter and target: a repeat returns the open one instead.
CREATE UNIQUE INDEX report_open_key ON report (reporter_id, target_type, target_id) WHERE status = 'OPEN';
-- The 10-per-day cap, and the reporter FK's SET NULL.
CREATE INDEX report_reporter_idx ON report (reporter_id, created_at);
-- The number of reports against a user (or an event).
CREATE INDEX report_target_idx ON report (target_id);

-- Insert-only: every moderator and admin action, refused sign-ins, role changes and session purges. actor_id and
-- target_id are plain values with no foreign keys, so nothing (an account deletion, the event purge) ever has to
-- update or delete a row. actor_id is NULL for the system (a bootstrap grant, a refused sign-in). target_id is a user
-- ID or event public ID (a UUID), a report ID, or a Discord ID, as text.
CREATE TABLE audit_log (
	id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	actor_id    uuid,
	action      varchar(20) NOT NULL,
	target_type varchar(15) NOT NULL,
	target_id   varchar(36) NOT NULL,
	details     jsonb       NOT NULL,
	created_at  timestamptz NOT NULL DEFAULT now(),
	CONSTRAINT audit_log_action CHECK (action IN ('ROLES_CHANGED', 'SESSIONS_PURGED', 'SIGN_IN_REFUSED',
		'REPORT_RESOLVED', 'EVENT_HIDDEN', 'EVENT_UNHIDDEN', 'EVENT_CANCELLED', 'USER_SUSPENDED', 'USER_UNSUSPENDED',
		'ACCOUNT_BANNED', 'ACCOUNT_UNBANNED')),
	CONSTRAINT audit_log_target_type CHECK (target_type IN ('USER', 'EVENT', 'REPORT', 'DISCORD_ACCOUNT')),
	CONSTRAINT audit_log_details CHECK (jsonb_typeof(details) = 'object')
);

CREATE FUNCTION audit_log_insert_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
	RAISE EXCEPTION 'audit_log is insert-only' USING ERRCODE = 'insufficient_privilege';
END
$$;
CREATE TRIGGER audit_log_no_update_or_delete BEFORE UPDATE OR DELETE ON audit_log
	FOR EACH ROW EXECUTE FUNCTION audit_log_insert_only();
CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON audit_log
	FOR EACH STATEMENT EXECUTE FUNCTION audit_log_insert_only();

-- A hidden event is invisible to everyone but its host and moderators; its RSVPs are kept. The reason is the
-- moderator's, shown to the host, and is set exactly while the event is hidden.
ALTER TABLE event
	ADD COLUMN hidden_by_moderation boolean NOT NULL DEFAULT false,
	ADD COLUMN moderation_reason    varchar(500),
	ADD CONSTRAINT event_moderation_reason CHECK (hidden_by_moderation = (moderation_reason IS NOT NULL));

-- The moderation notifications (D-015). Only EVENT_HIDDEN is about an event; a report notification never names its
-- target event, so it never links a moderator or reporter to it through the event's cascade.
ALTER TABLE notification
	DROP CONSTRAINT notification_type,
	ADD CONSTRAINT notification_type CHECK (type IN ('SPOT_OFFERED', 'SPOT_CONFIRMED', 'EVENT_CHANGED',
		'EVENT_CANCELLED', 'EVENT_REMINDER', 'FRIEND_ACCEPTED', 'REPORT_RECEIVED', 'REPORT_REVIEWED', 'EVENT_HIDDEN')),
	DROP CONSTRAINT notification_event,
	ADD CONSTRAINT notification_event CHECK (
		(type IN ('FRIEND_ACCEPTED', 'REPORT_RECEIVED', 'REPORT_REVIEWED')) = (event_id IS NULL));
