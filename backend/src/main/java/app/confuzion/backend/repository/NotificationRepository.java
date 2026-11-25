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

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import app.confuzion.backend.model.record.NotificationPayload;
import app.confuzion.backend.model.record.NotificationResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Native SQL for notifications (plan B8, D-014). Every trigger writes its
 * notification through {@link #insert}, in the same transaction as the change
 * it reports.
 *
 * @author mcawful
 */
@Repository
public class NotificationRepository {
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
					CASE WHEN u.push_enabled AND EXISTS (SELECT 1 FROM push_subscription s WHERE s.user_id = u.id)
						THEN 'PENDING' ELSE 'NONE' END
				FROM app_user u WHERE u.id = :userId
				RETURNING id""").param("userId", userId).param("type", type.name()).param("eventId", eventId)
				.param("payload", jsonMapper.writeValueAsString(payload)).query(Long.class).single();
	}
}
