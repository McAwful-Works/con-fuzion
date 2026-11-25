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
package app.confuzion.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import app.confuzion.backend.TestcontainersConfiguration;

/**
 * Integration tests for the {@link ReminderJob} against Postgres: who is
 * reminded of what, which events are skipped, and that each person is reminded
 * at most once however many runs overlap (D-014).
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReminderJobIntegrationTest {
	/**
	 * A source of Discord IDs.
	 */
	private static final AtomicLong DISCORD_IDS = new AtomicLong(System.nanoTime());

	/**
	 * The {@link ReminderJob}.
	 */
	@Autowired
	private ReminderJob reminderJob;

	/**
	 * The {@link JdbcTemplate}.
	 */
	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * Tests that the host and every confirmed attendee get one reminder with the
	 * title and start only, a waitlisted person, an offered one and a person who
	 * left get none, and a second run adds nothing.
	 */
	@Test
	void testRemindsTheHostAndConfirmedAttendeesOnce() {
		var host = user(true);
		var confirmed = user(true);
		var quiet = user(false);
		var waitlisted = user(false);
		var offered = user(false);
		var left = user(false);
		var event = event(host, "now() + interval '45 minutes'", "PUBLISHED");
		rsvp(event, host, "CONFIRMED");
		rsvp(event, confirmed, "CONFIRMED");
		rsvp(event, quiet, "CONFIRMED");
		rsvp(event, waitlisted, "WAITLISTED");
		jdbcTemplate.update("INSERT INTO rsvp (event_id, user_id, status, offer_expires_at) "
				+ "VALUES (?, ?, 'OFFERED', now() + interval '10 minutes')", event, offered);
		rsvp(event, left, "LEFT");
		jdbcTemplate.update("INSERT INTO push_subscription (user_id, endpoint, p256dh, auth) VALUES (?, ?, 'k', 'a')",
				confirmed, "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID());

		reminderJob.sendDueReminders();
		reminderJob.sendDueReminders();

		for (var user : List.of(host, confirmed, quiet))
			assertThat(count(user, event)).isEqualTo(1);
		for (var user : List.of(waitlisted, offered, left))
			assertThat(count(user, event)).isZero();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT payload::text FROM notification WHERE user_id = ? AND event_id = ?", String.class, host, event))
				.contains("\"eventTitle\": \"Reminder walk\"").contains("startsAt").doesNotContain("Hall A");
		assertThat(pushStatus(confirmed, event)).isEqualTo("PENDING");
		assertThat(pushStatus(quiet, event)).isEqualTo("NONE");
	}

	/**
	 * Tests that an event more than an hour away, one that has started and a
	 * cancelled one get no reminders.
	 */
	@Test
	void testSkipsEventsNotDue() {
		var host = user(false);
		var far = event(host, "now() + interval '3 hours'", "PUBLISHED");
		var started = event(host, "now() - interval '5 minutes'", "PUBLISHED");
		var cancelled = event(host, "now() + interval '30 minutes'", "CANCELLED");
		for (var event : List.of(far, started, cancelled))
			rsvp(event, host, "CONFIRMED");

		reminderJob.sendDueReminders();

		assertThat(count(host, far) + count(host, started) + count(host, cancelled)).isZero();
	}

	/**
	 * Tests that a person who joins after the first run, but before the start,
	 * still gets a reminder, and nobody gets a second one.
	 */
	@Test
	void testALateJoinerIsRemindedToo() {
		var host = user(false);
		var late = user(false);
		var event = event(host, "now() + interval '20 minutes'", "PUBLISHED");
		rsvp(event, host, "CONFIRMED");
		reminderJob.sendDueReminders();

		rsvp(event, late, "CONFIRMED");
		reminderJob.sendDueReminders();
		reminderJob.sendDueReminders();

		assertThat(count(host, event)).isEqualTo(1);
		assertThat(count(late, event)).isEqualTo(1);
	}

	/**
	 * Tests that several runs at once, as when several tasks run the job, write
	 * exactly one reminder per person.
	 */
	@Test
	void testConcurrentRunsRemindOnce() throws Exception {
		var host = user(false);
		var guests = new ArrayList<UUID>();
		var event = event(host, "now() + interval '30 minutes'", "PUBLISHED");
		rsvp(event, host, "CONFIRMED");
		for (var i = 0; i < 20; i++) {
			var guest = user(false);
			guests.add(guest);
			rsvp(event, guest, "CONFIRMED");
		}
		var runs = 6;
		var start = new CountDownLatch(1);

		try (var executor = Executors.newFixedThreadPool(runs)) {
			var futures = new ArrayList<Future<?>>();
			for (var i = 0; i < runs; i++)
				futures.add(executor.submit(() -> {
					start.await();
					reminderJob.sendDueReminders();
					return null;
				}));
			start.countDown();
			for (var future : futures)
				future.get();
		}

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM notification WHERE event_id = ? AND type = 'EVENT_REMINDER'", Integer.class,
				event)).isEqualTo(21);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(DISTINCT user_id) FROM notification WHERE event_id = ? AND type = 'EVENT_REMINDER'",
				Integer.class, event)).isEqualTo(21);
	}

	/**
	 * Tests that the database itself refuses a second reminder for one person and
	 * event, so a bug in the job can't send it twice.
	 */
	@Test
	void testTheKeyRefusesADuplicateReminder() {
		var host = user(false);
		var event = event(host, "now() + interval '30 minutes'", "PUBLISHED");
		var insert = "INSERT INTO notification (user_id, type, event_id, payload, push_status) "
				+ "VALUES (?, 'EVENT_REMINDER', ?, '{}', 'NONE')";
		jdbcTemplate.update(insert, host, event);

		assertThatThrownBy(() -> jdbcTemplate.update(insert, host, event)).isInstanceOf(DuplicateKeyException.class);
	}

	/**
	 * Inserts a user.
	 *
	 * @param pushEnabled whether push is on
	 * @return the user's ID
	 */
	private UUID user(boolean pushEnabled) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO app_user (discord_id, username, push_enabled) VALUES (?, 'reminded', ?) RETURNING id",
				UUID.class, DISCORD_IDS.incrementAndGet(), pushEnabled);
	}

	/**
	 * Inserts an event.
	 *
	 * @param host     the host
	 * @param startsAt a SQL expression for the start
	 * @param status   the event's status
	 * @return the event's internal ID
	 */
	private long event(UUID host, String startsAt, String status) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO event (host_id, title, location_text, starts_at, time_zone, status) "
						+ "VALUES (?, 'Reminder walk', 'Hall A', " + startsAt + ", 'UTC', ?) RETURNING id",
				Long.class, host, status);
	}

	/**
	 * Adds an RSVP.
	 *
	 * @param event  the event
	 * @param userId the user
	 * @param status the status
	 */
	private void rsvp(long event, UUID userId, String status) {
		jdbcTemplate.update("INSERT INTO rsvp (event_id, user_id, status) VALUES (?, ?, ?)", event, userId, status);
	}

	/**
	 * Counts a user's reminders for an event.
	 *
	 * @param userId the user
	 * @param event  the event
	 * @return the count
	 */
	private int count(UUID userId, long event) {
		return jdbcTemplate.queryForObject(
				"SELECT count(*) FROM notification WHERE user_id = ? AND event_id = ? AND type = 'EVENT_REMINDER'",
				Integer.class, userId, event);
	}

	/**
	 * Returns the push status of a user's reminder.
	 *
	 * @param userId the user
	 * @param event  the event
	 * @return the status
	 */
	private String pushStatus(UUID userId, long event) {
		return jdbcTemplate.queryForObject("SELECT push_status FROM notification WHERE user_id = ? AND event_id = ?",
				String.class, userId, event);
	}
}
