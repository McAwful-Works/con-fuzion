/**
 * MIT License
 * <p>
 * Copyright (c) 2024 McAwful-Works
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package app.confuzion.backend.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import app.confuzion.backend.model.record.NotificationPayload;
import app.confuzion.backend.model.record.NotificationResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Native SQL for notifications (plan B8, D-014). Every trigger writes its
 * notification through {@link #insert}, or {@link #insertForActiveRsvps} for an
 * event's whole audience, in the same transaction as the change it reports.
 *
 * @author mcawful
 */
@Repository
public class NotificationRepository {
	/**
	 * The {@code push_status} of a notification for the user {@code u}: queued
	 * ({@code PENDING}) when push is on and there is at least one subscription,
	 * inbox only ({@code NONE}) otherwise (D-014 rule 12).
	 */
	private static final String PUSH_STATUS = """
			CASE WHEN u.push_enabled AND EXISTS (SELECT 1 FROM push_subscription s WHERE s.user_id = u.id)
				THEN 'PENDING' ELSE 'NONE' END""";

	/**
	 * The {@link JdbcClient}.
	 */
	private final JdbcClient jdbc;

	/**
	 * The {@link JsonMapper}, to store payloads.
	 */
	private final JsonMapper jsonMapper;

	/**
	 * Creates the repository.
	 *
	 * @param jdbc       the {@link JdbcClient}
	 * @param jsonMapper the {@link JsonMapper}
	 */
	public NotificationRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
		this.jdbc = jdbc;
		this.jsonMapper = jsonMapper;
	}

	/**
	 * Adds a notification to a user's inbox. It's queued for push ({@code PENDING})
	 * when the user has push on and at least one subscription, and is inbox only
	 * ({@code NONE}) otherwise. The recipient must exist: pick them from rows that
	 * reference them, because a missing one throws, rolling back the change being
	 * reported too.
	 *
	 * @param userId  the recipient's user ID
	 * @param type    what happened
	 * @param eventId the event's internal ID, or {@code null} for
	 *                {@code FRIEND_ACCEPTED}
	 * @param payload what the notification says, with only the type's own fields
	 *                set (D-014)
	 * @return the new notification's ID
	 */
	public long insert(UUID userId, NotificationResponse.Type type, Long eventId, NotificationPayload payload) {
		return jdbc.sql("""
				INSERT INTO notification (user_id, type, event_id, payload, push_status)
				SELECT u.id, :type, CAST(:eventId AS bigint), CAST(:payload AS jsonb),
					""" + PUSH_STATUS + """

				FROM app_user u WHERE u.id = :userId
				RETURNING id""").param("userId", userId).param("type", type.name()).param("eventId", eventId)
				.param("payload", jsonMapper.writeValueAsString(payload)).query(Long.class).single();
	}

	/**
	 * Notifies, in one statement, everyone with an active RSVP on an event except
	 * its host: {@code CONFIRMED}, {@code WAITLISTED} and {@code OFFERED}. An offer
	 * past its deadline counts as no RSVP, as everywhere else (D-014 rule 15). Each
	 * row's {@code push_status} is set as in {@link #insert}.
	 *
	 * @param eventId the event's internal ID
	 * @param hostId  the host's user ID, who gets nothing
	 * @param type    what happened
	 * @param payload what the notification says
	 * @return how many notifications were written
	 */
	public int insertForActiveRsvps(long eventId, UUID hostId, NotificationResponse.Type type,
			NotificationPayload payload) {
		return jdbc.sql("""
				INSERT INTO notification (user_id, type, event_id, payload, push_status)
				SELECT u.id, :type, r.event_id, CAST(:payload AS jsonb),
					""" + PUSH_STATUS + """

				FROM rsvp r JOIN app_user u ON u.id = r.user_id
				WHERE r.event_id = :eventId AND r.user_id <> :hostId
					AND r.status IN ('CONFIRMED', 'WAITLISTED', 'OFFERED')
					AND (r.status <> 'OFFERED' OR r.offer_expires_at > statement_timestamp())""")
				.param("eventId", eventId).param("hostId", hostId).param("type", type.name())
				.param("payload", jsonMapper.writeValueAsString(payload)).update();
	}

	/**
	 * Returns a page of a user's notifications, newest first.
	 *
	 * @param userId   the user's ID
	 * @param beforeId only notifications with a lower ID, or {@code null} for the
	 *                 newest
	 * @param limit    the most to return
	 * @return the notifications, with the event's public ID
	 */
	public List<NotificationResponse> findPage(UUID userId, Long beforeId, int limit) {
		return jdbc.sql("""
				SELECT n.id, n.type, e.public_id, n.payload, n.created_at, n.read_at IS NOT NULL AS is_read
				FROM notification n LEFT JOIN event e ON e.id = n.event_id
				WHERE n.user_id = :userId AND (CAST(:beforeId AS bigint) IS NULL OR n.id < :beforeId)
				ORDER BY n.id DESC LIMIT :limit""").param("userId", userId).param("beforeId", beforeId)
				.param("limit", limit)
				.query((rs, row) -> new NotificationResponse(rs.getLong("id"),
						NotificationResponse.Type.valueOf(rs.getString("type")), rs.getObject("public_id", UUID.class),
						jsonMapper.readValue(rs.getString("payload"), NotificationPayload.class),
						rs.getTimestamp("created_at").toInstant(), rs.getBoolean("is_read")))
				.list();
	}

	/**
	 * Counts a user's unread notifications, stopping at a cap so it stays cheap.
	 *
	 * @param userId the user's ID
	 * @param cap    the most to count
	 * @return the unread count, at most {@code cap}
	 */
	public int countUnread(UUID userId, int cap) {
		return jdbc.sql("""
				SELECT count(*) FROM (SELECT 1 FROM notification WHERE user_id = :userId AND read_at IS NULL
					LIMIT :cap) unread""").param("userId", userId).param("cap", cap).query(Integer.class).single();
	}

	/**
	 * Marks a user's unread notifications read, up to and including an ID. Only the
	 * user's own rows can match.
	 *
	 * @param userId the user's ID
	 * @param upTo   the newest ID to mark
	 * @return how many were marked
	 */
	public int markRead(UUID userId, long upTo) {
		return jdbc.sql("UPDATE notification SET read_at = now() WHERE user_id = :userId AND id <= :upTo "
				+ "AND read_at IS NULL").param("userId", userId).param("upTo", upTo).update();
	}

	/**
	 * Returns the published events that start within the lead time, and still
	 * haven't, and have a confirmed attendee without a reminder yet. The host holds
	 * a confirmed RSVP from the start (D-013).
	 *
	 * @param lead how long before the start a reminder goes out
	 * @return the events' internal IDs, soonest first
	 */
	public List<Long> findEventsToRemind(Duration lead) {
		return jdbc
				.sql("""
						SELECT e.id FROM event e
						WHERE e.status = 'PUBLISHED' AND e.starts_at > now() AND e.starts_at <= now() + CAST(:leadSeconds AS bigint) * interval '1 second'
							AND EXISTS (SELECT 1 FROM rsvp r WHERE r.event_id = e.id AND r.status = 'CONFIRMED'
								AND NOT EXISTS (SELECT 1 FROM notification n WHERE n.user_id = r.user_id AND n.event_id = e.id
									AND n.type = 'EVENT_REMINDER'))
						ORDER BY e.starts_at, e.id""")
				.param("leadSeconds", lead.toSeconds()).query(Long.class).list();
	}

	/**
	 * Locks an event if it is still due a reminder: published, not started, and
	 * within the lead time. The lock lasts until the transaction ends, so a cancel,
	 * an edit or a block can't interleave with the reminders.
	 *
	 * @param eventId the event's internal ID
	 * @param lead    how long before the start a reminder goes out
	 * @return its title and start, or empty when it isn't due
	 */
	public Optional<DueEvent> lockDueEvent(long eventId, Duration lead) {
		return jdbc
				.sql("""
						SELECT title, starts_at FROM event
						WHERE id = :eventId AND status = 'PUBLISHED' AND starts_at > now() AND starts_at <= now() + CAST(:leadSeconds AS bigint) * interval '1 second'
						FOR NO KEY UPDATE""")
				.param("eventId", eventId).param("leadSeconds", lead.toSeconds())
				.query((rs, row) -> new DueEvent(rs.getString("title"), rs.getTimestamp("starts_at").toInstant()))
				.optional();
	}

	/**
	 * Writes an event's reminders, in one statement, to its host and every
	 * confirmed attendee. A person who already has one for the event gets nothing
	 * more: the unique key {@code notification_reminder_key} makes that hold even
	 * when several tasks run this at once.
	 *
	 * @param eventId the event's internal ID
	 * @param payload what the reminder says
	 * @return how many reminders were written
	 */
	public int insertReminders(long eventId, NotificationPayload payload) {
		return jdbc.sql("""
				INSERT INTO notification (user_id, type, event_id, payload, push_status)
				SELECT u.id, 'EVENT_REMINDER', :eventId, CAST(:payload AS jsonb),
					""" + PUSH_STATUS + """

				FROM app_user u
				WHERE u.id = (SELECT host_id FROM event WHERE id = :eventId)
					OR u.id IN (SELECT user_id FROM rsvp WHERE event_id = :eventId AND status = 'CONFIRMED')
				ON CONFLICT (user_id, event_id) WHERE type = 'EVENT_REMINDER' DO NOTHING""").param("eventId", eventId)
				.param("payload", jsonMapper.writeValueAsString(payload)).update();
	}

	/**
	 * Deletes the notifications a block must remove for one of the two people:
	 * those about the other's events, and their {@code FRIEND_ACCEPTED} for the
	 * other (D-014 rule 9). Call it once for each direction.
	 *
	 * @param userId  the person whose notifications are deleted
	 * @param otherId the other person
	 * @return how many were deleted
	 */
	public int deleteAbout(UUID userId, UUID otherId) {
		return jdbc.sql("""
				DELETE FROM notification
				WHERE user_id = :userId
					AND (event_id IN (SELECT id FROM event WHERE host_id = :otherId)
						OR (type = 'FRIEND_ACCEPTED' AND payload ->> 'friendId' = CAST(:otherId AS text)))""")
				.param("userId", userId).param("otherId", otherId).update();
	}

	/**
	 * An event that is due a reminder.
	 *
	 * @param title    its title
	 * @param startsAt its start
	 * @author mcawful
	 */
	public record DueEvent(String title, Instant startsAt) {
	}
}
