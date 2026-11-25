-- #66: an event reminder is sent at most once per person per event, however many tasks run the reminder job. The job
-- writes its rows with ON CONFLICT DO NOTHING against this key, so a second task, or a later run, adds nothing.
CREATE UNIQUE INDEX notification_reminder_key ON notification (user_id, event_id) WHERE type = 'EVENT_REMINDER';
