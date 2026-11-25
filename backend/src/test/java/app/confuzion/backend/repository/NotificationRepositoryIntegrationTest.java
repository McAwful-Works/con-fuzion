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

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import app.confuzion.backend.TestcontainersConfiguration;
import app.confuzion.backend.model.record.NotificationPayload;
import app.confuzion.backend.model.record.NotificationPayload.Change;
import app.confuzion.backend.model.record.NotificationResponse.Type;

/**
 * Tests {@link NotificationRepository#insert} against Postgres: the row it
 * writes, a payload with only the type's own fields, and whether it is queued
 * for push.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class NotificationRepositoryIntegrationTest {
	/**
	 * The {@link NotificationRepository}.
	 */
	@Autowired
	private NotificationRepository notificationRepository;

	/**
	 * The {@link JdbcTemplate}.
	 */
	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * Tests that an event notification is stored unread, for its event, with only
	 * the fields that were set, times as ISO-8601 strings, and is inbox only while
	 * the user has push off.
	 */
	@Test
	void insertStoresAnUnreadEventNotification() {
		var userId = insertUser(81384788765712384L, false);
		var eventId = insertEvent(userId);
		var expiresAt = Instant.now().plus(Duration.ofMinutes(30)).truncatedTo(ChronoUnit.MILLIS);

		var id = notificationRepository.insert(userId, Type.SPOT_OFFERED, eventId,
				new NotificationPayload("Photo walk", expiresAt, null, null, null, null, null, null));

		assertThat(jdbcTemplate.queryForMap("SELECT user_id, type, event_id, payload::text AS payload, "
				+ "read_at IS NULL AS unread, push_status, created_at IS NOT NULL AS has_created_at "
				+ "FROM notification WHERE id = ?", id)).containsEntry("user_id", userId)
				.containsEntry("type", "SPOT_OFFERED").containsEntry("event_id", eventId)
				.containsEntry("payload", "{\"eventTitle\": \"Photo walk\", \"offerExpiresAt\": \"" + expiresAt + "\"}")
				.containsEntry("unread", true).containsEntry("push_status", "NONE")
				.containsEntry("has_created_at", true);
	}

	/**
	 * Tests that a notification is queued for push only when the user has push on
	 * and at least one subscription.
	 */
	@Test
	void insertQueuesPushOnlyWithPushOnAndASubscription() {
		var off = insertUser(81384788765712384L, false);
		var noSubscription = insertUser(81384788765712385L, true);
		var on = insertUser(81384788765712386L, true);
		subscribe(off, "https://fcm.googleapis.com/fcm/send/off");
		subscribe(on, "https://fcm.googleapis.com/fcm/send/on");
		var eventId = insertEvent(on);
		var payload = new NotificationPayload("Photo walk", null, null, null, null, null, null, null);

		var ids = List.of(notificationRepository.insert(off, Type.EVENT_CANCELLED, eventId, payload),
				notificationRepository.insert(noSubscription, Type.EVENT_CANCELLED, eventId, payload),
				notificationRepository.insert(on, Type.EVENT_CANCELLED, eventId, payload));

		assertThat(ids.stream().map(id -> pushStatus(id)).toList()).containsExactly("NONE", "NONE", "PENDING");
	}

	/**
	 * Tests that a notification without an event, such as an accepted friend
	 * invite, is stored with no event and its own payload field only.
	 */
	@Test
	void insertStoresANotificationWithoutAnEvent() {
		var inviter = insertUser(81384788765712384L, false);
		var friend = insertUser(81384788765712385L, false);

		var id = notificationRepository.insert(inviter, Type.FRIEND_ACCEPTED, null,
				new NotificationPayload(null, null, null, null, friend, null, null, null));

		assertThat(jdbcTemplate.queryForMap("SELECT event_id, payload::text AS payload FROM notification WHERE id = ?",
				id)).containsEntry("event_id", null).containsEntry("payload", "{\"friendId\": \"" + friend + "\"}");
	}

	/**
	 * Tests that a list in the payload is stored as a JSON array of names, and that
	 * a later notification gets a higher ID.
	 */
	@Test
	void insertStoresChangesInOrder() {
		var userId = insertUser(81384788765712384L, false);
		var eventId = insertEvent(userId);
		var startsAt = Instant.parse("2027-01-02T15:00:00Z");
		var payload = new NotificationPayload("Photo walk", null, startsAt, List.of(Change.START, Change.LOCATION),
				null, null, null, null);

		var first = notificationRepository.insert(userId, Type.EVENT_CHANGED, eventId, payload);
		var second = notificationRepository.insert(userId, Type.EVENT_REMINDER, eventId,
				new NotificationPayload("Photo walk", null, startsAt, null, null, null, null, null));

		assertThat(second).isGreaterThan(first);
		assertThat(
				jdbcTemplate.queryForObject("SELECT payload::text FROM notification WHERE id = ?", String.class, first))
				.isEqualTo("{\"changes\": [\"START\", \"LOCATION\"], \"startsAt\": \"2027-01-02T15:00:00Z\", "
						+ "\"eventTitle\": \"Photo walk\"}");
	}

	/**
	 * Inserts a user with only the required columns and a push setting.
	 *
	 * @param discordId   the Discord ID
	 * @param pushEnabled whether push is on
	 * @return the generated user ID
	 */
	private UUID insertUser(long discordId, boolean pushEnabled) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO app_user (discord_id, username, push_enabled) VALUES (?, 'con_goer', ?) RETURNING id",
				UUID.class, discordId, pushEnabled);
	}

	/**
	 * Inserts an event that starts in a day, with only the required columns.
	 *
	 * @param host the host
	 * @return the event's internal ID
	 */
	private long insertEvent(UUID host) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO event (host_id, title, location_text, starts_at, time_zone) "
						+ "VALUES (?, 'Photo walk', 'Hall B', ?, 'America/Chicago') RETURNING id",
				Long.class, host, Timestamp.from(Instant.now().plus(Duration.ofDays(1))));
	}

	/**
	 * Adds a push subscription for a user.
	 *
	 * @param userId   the user
	 * @param endpoint the endpoint
	 */
	private void subscribe(UUID userId, String endpoint) {
		jdbcTemplate.update("INSERT INTO push_subscription (user_id, endpoint, p256dh, auth) VALUES (?, ?, 'k', 'a')",
				userId, endpoint);
	}

	/**
	 * Returns a notification's push status.
	 *
	 * @param id the notification's ID
	 * @return the status
	 */
	private String pushStatus(long id) {
		return jdbcTemplate.queryForObject("SELECT push_status FROM notification WHERE id = ?", String.class, id);
	}
}
