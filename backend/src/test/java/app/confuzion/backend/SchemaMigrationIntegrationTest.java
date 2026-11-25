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
package app.confuzion.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for the migrations on Testcontainers Postgres: M1's
 * {@code app_user} and {@code user_guild}, M2's social graph and events, and
 * M3's RSVPs, M4's notifications and M5's moderation. Each test rolls back,
 * except the backfill test, which migrates a schema of its own and drops it.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SchemaMigrationIntegrationTest {
	/**
	 * The {@link JdbcTemplate}.
	 */
	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * The {@link DataSource}, for savepoints on the test's connection.
	 */
	@Autowired
	private DataSource dataSource;

	/**
	 * Tests that Flyway applied the migration.
	 */
	@Test
	void migrationApplied() {
		assertThat(jdbcTemplate.queryForObject(
				"SELECT success FROM flyway_schema_history WHERE version = '202611241300'", Boolean.class)).isTrue();
	}

	/**
	 * Tests that Flyway applied the social graph migration, with the planned
	 * columns.
	 */
	@Test
	void socialGraphMigrationApplied() {
		assertThat(jdbcTemplate.queryForObject(
				"SELECT success FROM flyway_schema_history WHERE version = '202611241700'", Boolean.class)).isTrue();
		assertThat(columns("friendship")).containsExactlyInAnyOrder("user_a", "user_b", "created_at");
		assertThat(columns("friend_invite")).containsExactlyInAnyOrder("token_hash", "inviter_id", "created_at",
				"expires_at", "used_at", "accepted_by");
		assertThat(columns("circle")).containsExactlyInAnyOrder("id", "public_id", "owner_id", "name", "created_at");
		assertThat(columns("circle_member")).containsExactlyInAnyOrder("circle_id", "user_id");
		assertThat(columns("block")).containsExactlyInAnyOrder("blocker_id", "blocked_id", "created_at");
	}

	/**
	 * Tests that a friendship is stored once, smaller ID first, and goes when
	 * either user is deleted.
	 */
	@Test
	void friendshipIsOrderedAndCascades() throws SQLException {
		var ids = sortedUsers();

		assertRejected(() -> befriend(ids.get(1), ids.get(0)), DataIntegrityViolationException.class);
		assertRejected(() -> befriend(ids.get(0), ids.get(0)), DataIntegrityViolationException.class);
		befriend(ids.get(0), ids.get(1));
		assertRejected(() -> befriend(ids.get(0), ids.get(1)), DuplicateKeyException.class);

		jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", ids.get(1));

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM friendship", Integer.class)).isZero();
	}

	/**
	 * Tests that an invite stores a 32-byte hash, can't be accepted without being
	 * used or by its own inviter, and stays used when the accepting account is
	 * deleted.
	 */
	@Test
	void friendInviteStoresHashAndKeepsUse() throws SQLException {
		var inviter = insertUser(81384788765712384L);
		var friend = insertUser(81384788765712385L);
		var insert = "INSERT INTO friend_invite (token_hash, inviter_id, expires_at, used_at, accepted_by) "
				+ "VALUES (?, ?, now() + interval '24 hours', ?, ?)";

		assertRejected(() -> jdbcTemplate.update(insert, new byte[16], inviter, null, null),
				DataIntegrityViolationException.class);
		assertRejected(() -> jdbcTemplate.update(insert, new byte[32], inviter, null, friend),
				DataIntegrityViolationException.class);
		assertRejected(() -> jdbcTemplate.update(insert, new byte[32], inviter, Timestamp.from(Instant.now()), inviter),
				DataIntegrityViolationException.class);
		jdbcTemplate.update(insert, new byte[32], inviter, Timestamp.from(Instant.now()), friend);

		jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", friend);

		assertThat(jdbcTemplate.queryForObject("SELECT used_at IS NOT NULL AND accepted_by IS NULL FROM friend_invite",
				Boolean.class)).isTrue();
	}

	/**
	 * Tests that a circle gets a public ID, that names are unique per owner
	 * ignoring case but free across owners, and that deleting a circle removes its
	 * members.
	 */
	@Test
	void circleNamesAndMembers() throws SQLException {
		var owner = insertUser(81384788765712384L);
		var other = insertUser(81384788765712385L);
		var circleId = insertCircle(owner, "Cosplay crew");

		assertThat(jdbcTemplate.queryForObject("SELECT public_id FROM circle WHERE id = ?", UUID.class, circleId))
				.isNotNull();
		assertRejected(() -> insertCircle(owner, "COSPLAY CREW"), DuplicateKeyException.class);
		assertRejected(() -> insertCircle(owner, "  "), DataIntegrityViolationException.class);
		insertCircle(other, "Cosplay crew");

		jdbcTemplate.update("INSERT INTO circle_member (circle_id, user_id) VALUES (?, ?)", circleId, other);
		jdbcTemplate.update("DELETE FROM circle WHERE id = ?", circleId);

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM circle_member", Integer.class)).isZero();
	}

	/**
	 * Tests that a user can't block themself.
	 */
	@Test
	void blockIsNeverSelf() throws SQLException {
		var ids = sortedUsers();
		jdbcTemplate.update("INSERT INTO block (blocker_id, blocked_id) VALUES (?, ?)", ids.get(1), ids.get(0));

		assertRejected(() -> jdbcTemplate.update("INSERT INTO block (blocker_id, blocked_id) VALUES (?, ?)", ids.get(0),
				ids.get(0)), DataIntegrityViolationException.class);
	}

	/**
	 * Tests the social graph's indexes.
	 */
	@Test
	void socialGraphIndexes() {
		assertThat(indexDefinition("friendship_pkey")).endsWith("(user_a, user_b)");
		assertThat(indexDefinition("friendship_user_b_idx")).endsWith("(user_b)");
		assertThat(indexDefinition("friend_invite_inviter_unused_idx"))
				.endsWith("(inviter_id, expires_at) WHERE (used_at IS NULL)");
		assertThat(indexDefinition("circle_public_id_key")).startsWith("CREATE UNIQUE INDEX").endsWith("(public_id)");
		assertThat(indexDefinition("circle_owner_name_key")).startsWith("CREATE UNIQUE INDEX")
				.endsWith("(owner_id, lower((name)::text))");
		assertThat(indexDefinition("circle_member_pkey")).endsWith("(circle_id, user_id)");
		assertThat(indexDefinition("circle_member_user_idx")).endsWith("(user_id)");
		assertThat(indexDefinition("block_pkey")).endsWith("(blocker_id, blocked_id)");
		assertThat(indexDefinition("block_blocked_idx")).endsWith("(blocked_id)");
	}

	/**
	 * Tests that Flyway applied the events migration, with the planned columns and
	 * defaults.
	 */
	@Test
	void eventsMigrationApplied() {
		assertThat(jdbcTemplate.queryForObject(
				"SELECT success FROM flyway_schema_history WHERE version = '202611241710'", Boolean.class)).isTrue();
		assertThat(columns("event")).containsExactlyInAnyOrder("id", "public_id", "host_id", "title", "description",
				"location_text", "tag", "starts_at", "ends_at", "time_zone", "capacity", "confirmed_count",
				"signup_opens_at", "confirm_window_minutes", "location_visibility", "status", "created_at",
				"updated_at", "hidden_by_moderation", "moderation_reason");
		assertThat(columns("event_audience")).containsExactlyInAnyOrder("id", "event_id", "type", "guild_id",
				"circle_id", "user_id");

		var eventId = insertEvent(insertUser(81384788765712384L), null, null);

		assertThat(jdbcTemplate
				.queryForMap("SELECT public_id IS NOT NULL AS has_public_id, confirmed_count, confirm_window_minutes, "
						+ "location_visibility, status FROM event WHERE id = ?", eventId))
				.containsEntry("has_public_id", true).containsEntry("confirmed_count", 0)
				.containsEntry("confirm_window_minutes", 30).containsEntry("location_visibility", "VIEWERS")
				.containsEntry("status", "PUBLISHED");
	}

	/**
	 * Tests the event CHECKs: times in order, capacity 2 to 10,000 (D-013), a count
	 * between 0 and the capacity, an offer window of 10 minutes to a day, and known
	 * enum values.
	 */
	@Test
	void eventChecks() throws SQLException {
		var host = insertUser(81384788765712384L);

		assertRejected(() -> insertEvent(host, Duration.ZERO, null), DataIntegrityViolationException.class);
		assertRejected(() -> insertEvent(host, null, 0), DataIntegrityViolationException.class);
		assertRejected(() -> insertEvent(host, null, 1), DataIntegrityViolationException.class);
		assertRejected(() -> insertEvent(host, null, 10001), DataIntegrityViolationException.class);
		insertEvent(host, Duration.ofHours(25), 10000);
		var eventId = insertEvent(host, null, 2);
		jdbcTemplate.update("UPDATE event SET confirmed_count = 2, confirm_window_minutes = 10 WHERE id = ?", eventId);
		jdbcTemplate.update("UPDATE event SET confirm_window_minutes = 1440 WHERE id = ?", eventId);
		for (var update : List.of("UPDATE event SET ends_at = starts_at WHERE id = ?",
				"UPDATE event SET signup_opens_at = starts_at WHERE id = ?",
				"UPDATE event SET confirmed_count = -1 WHERE id = ?",
				"UPDATE event SET confirmed_count = 3 WHERE id = ?", "UPDATE event SET capacity = 1 WHERE id = ?",
				"UPDATE event SET confirm_window_minutes = 9 WHERE id = ?",
				"UPDATE event SET confirm_window_minutes = 1441 WHERE id = ?",
				"UPDATE event SET status = 'DELETED' WHERE id = ?",
				"UPDATE event SET location_visibility = 'EVERYONE' WHERE id = ?"))
			assertRejected(() -> jdbcTemplate.update(update, eventId), DataIntegrityViolationException.class);
	}

	/**
	 * Tests that each audience entry sets exactly the column its type names, that
	 * an entry appears once per event, and that deleting a circle or user removes
	 * their entries while deleting a host with events is refused.
	 */
	@Test
	void eventAudienceIsTyped() throws SQLException {
		var host = insertUser(81384788765712384L);
		var guest = insertUser(81384788765712385L);
		var circleId = insertCircle(host, "Cosplay crew");
		var eventId = insertEvent(host, null, null);
		var insert = "INSERT INTO event_audience (event_id, type, guild_id, circle_id, user_id) VALUES (?, ?, ?, ?, ?)";

		jdbcTemplate.update(insert, eventId, "GUILD", 175928847299117063L, null, null);
		jdbcTemplate.update(insert, eventId, "FRIENDS", null, null, null);
		jdbcTemplate.update(insert, eventId, "CIRCLE", null, circleId, null);
		jdbcTemplate.update(insert, eventId, "USER", null, null, guest);
		assertRejected(() -> jdbcTemplate.update(insert, eventId, "GUILD", null, null, null),
				DataIntegrityViolationException.class);
		assertRejected(() -> jdbcTemplate.update(insert, eventId, "FRIENDS", null, null, guest),
				DataIntegrityViolationException.class);
		assertRejected(() -> jdbcTemplate.update(insert, eventId, "CIRCLE", 175928847299117063L, circleId, null),
				DataIntegrityViolationException.class);
		assertRejected(() -> jdbcTemplate.update(insert, eventId, "TEAM", null, null, null),
				DataIntegrityViolationException.class);
		assertRejected(() -> jdbcTemplate.update(insert, eventId, "FRIENDS", null, null, null),
				DuplicateKeyException.class);
		assertRejected(() -> jdbcTemplate.update(insert, eventId, "USER", null, null, guest),
				DuplicateKeyException.class);

		jdbcTemplate.update("DELETE FROM circle WHERE id = ?", circleId);
		jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", guest);

		assertThat(
				jdbcTemplate.queryForList("SELECT type FROM event_audience WHERE event_id = ?", String.class, eventId))
				.containsExactlyInAnyOrder("GUILD", "FRIENDS");
		assertRejected(() -> jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", host),
				DataIntegrityViolationException.class);
	}

	/**
	 * Tests the events' indexes.
	 */
	@Test
	void eventIndexes() {
		assertThat(indexDefinition("event_public_id_key")).startsWith("CREATE UNIQUE INDEX").endsWith("(public_id)");
		assertThat(indexDefinition("event_starts_at_idx")).endsWith("(starts_at)");
		assertThat(indexDefinition("event_host_starts_at_idx")).endsWith("(host_id, starts_at)");
		assertThat(indexDefinition("event_audience_entry_key")).startsWith("CREATE UNIQUE INDEX")
				.endsWith("(event_id, type, guild_id, circle_id, user_id) NULLS NOT DISTINCT");
		assertThat(indexDefinition("event_audience_guild_idx")).endsWith("(guild_id) WHERE (guild_id IS NOT NULL)");
		assertThat(indexDefinition("event_audience_circle_idx")).endsWith("(circle_id) WHERE (circle_id IS NOT NULL)");
		assertThat(indexDefinition("event_audience_user_idx")).endsWith("(user_id) WHERE (user_id IS NOT NULL)");
	}

	/**
	 * Tests that Flyway applied the RSVP migration, with the planned columns and
	 * defaults.
	 */
	@Test
	void rsvpMigrationApplied() {
		assertThat(jdbcTemplate.queryForObject(
				"SELECT success FROM flyway_schema_history WHERE version = '202611242000'", Boolean.class)).isTrue();
		assertThat(columns("rsvp")).containsExactlyInAnyOrder("id", "event_id", "user_id", "status", "created_at",
				"updated_at", "offer_expires_at");

		var host = insertUser(81384788765712384L);
		var rsvpId = insertRsvp(insertEvent(host, null, null), host, "CONFIRMED");

		assertThat(jdbcTemplate.queryForMap("SELECT created_at IS NOT NULL AS has_created_at, "
				+ "updated_at IS NOT NULL AS has_updated_at FROM rsvp WHERE id = ?", rsvpId))
				.containsEntry("has_created_at", true).containsEntry("has_updated_at", true);
	}

	/**
	 * Tests the RSVP rules the database enforces: known statuses, at most one
	 * active RSVP per person per event while past ones pile up, and cascades from
	 * the event and the user.
	 */
	@Test
	void rsvpChecks() throws SQLException {
		var host = insertUser(81384788765712384L);
		var guest = insertUser(81384788765712385L);
		var eventId = insertEvent(host, null, null);
		var otherEventId = insertEvent(host, null, null);

		assertRejected(() -> insertRsvp(eventId, guest, "PENDING"), DataIntegrityViolationException.class);
		insertRsvp(eventId, guest, "LEFT");
		insertRsvp(eventId, guest, "LEFT");
		insertRsvp(eventId, guest, "WAITLISTED");
		assertRejected(() -> insertRsvp(eventId, guest, "CONFIRMED"), DuplicateKeyException.class);
		assertRejected(() -> insertRsvp(eventId, guest, "WAITLISTED"), DuplicateKeyException.class);
		insertRsvp(eventId, guest, "REMOVED");
		insertRsvp(otherEventId, guest, "CONFIRMED");

		jdbcTemplate.update("DELETE FROM event WHERE id = ?", otherEventId);
		assertThat(
				jdbcTemplate.queryForObject("SELECT count(*) FROM rsvp WHERE event_id = ?", Long.class, otherEventId))
				.isZero();
		jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", guest);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM rsvp WHERE user_id = ?", Long.class, guest))
				.isZero();
	}

	/**
	 * Tests the RSVP indexes: the active-RSVP key and My schedule (which count an
	 * open offer from M4), the waitlist queue, the per-event lookup that also
	 * serves the event cascade, and the offer expiry job's.
	 */
	@Test
	void rsvpIndexes() {
		var active = "WHERE ((status)::text = ANY ((ARRAY['CONFIRMED'::character varying, "
				+ "'WAITLISTED'::character varying, 'OFFERED'::character varying])::text[]))";
		assertThat(indexDefinition("rsvp_active_key")).startsWith("CREATE UNIQUE INDEX")
				.endsWith("(event_id, user_id) " + active);
		assertThat(indexDefinition("rsvp_queue_idx"))
				.endsWith("(event_id, id) WHERE ((status)::text = 'WAITLISTED'::text)");
		assertThat(indexDefinition("rsvp_user_active_idx")).endsWith("(user_id) " + active);
		assertThat(indexDefinition("rsvp_event_user_idx")).endsWith("(event_id, user_id)");
		assertThat(indexDefinition("rsvp_offer_expiry_idx"))
				.endsWith("(offer_expires_at) WHERE ((status)::text = 'OFFERED'::text)");
	}

	/**
	 * Tests that Flyway applied the notifications migration (D-014), with the
	 * planned columns.
	 */
	@Test
	void notificationsMigrationApplied() {
		assertThat(jdbcTemplate.queryForObject(
				"SELECT success FROM flyway_schema_history WHERE version = '202611251200'", Boolean.class)).isTrue();
		assertThat(columns("notification")).containsExactlyInAnyOrder("id", "user_id", "type", "event_id", "payload",
				"read_at", "push_status", "created_at");
		assertThat(columns("push_subscription")).containsExactlyInAnyOrder("id", "user_id", "endpoint", "p256dh",
				"auth", "created_at");
	}

	/**
	 * Tests the offer rules the database enforces: a deadline exactly while
	 * {@code OFFERED}, an open offer as the one active RSVP, and expired offers
	 * piling up like past RSVPs.
	 */
	@Test
	void offerChecks() throws SQLException {
		var host = insertUser(81384788765712384L);
		var guest = insertUser(81384788765712385L);
		var eventId = insertEvent(host, null, null);
		var offer = "INSERT INTO rsvp (event_id, user_id, status, offer_expires_at) VALUES (?, ?, ?, ?)";
		var deadline = Timestamp.from(Instant.now().plus(Duration.ofMinutes(30)));

		assertRejected(() -> jdbcTemplate.update(offer, eventId, guest, "OFFERED", null),
				DataIntegrityViolationException.class);
		assertRejected(() -> jdbcTemplate.update(offer, eventId, guest, "WAITLISTED", deadline),
				DataIntegrityViolationException.class);
		insertRsvp(eventId, guest, "EXPIRED");
		insertRsvp(eventId, guest, "EXPIRED");
		jdbcTemplate.update(offer, eventId, guest, "OFFERED", deadline);
		assertRejected(() -> insertRsvp(eventId, guest, "WAITLISTED"), DuplicateKeyException.class);
		assertRejected(() -> jdbcTemplate.update(offer, eventId, guest, "OFFERED", deadline),
				DuplicateKeyException.class);
	}

	/**
	 * Tests the notification rules the database enforces: known types and push
	 * statuses, an event for every type but {@code FRIEND_ACCEPTED}, an object
	 * payload, and cascades from the user and the event.
	 */
	@Test
	void notificationChecks() throws SQLException {
		var host = insertUser(81384788765712384L);
		var guest = insertUser(81384788765712385L);
		var eventId = insertEvent(host, null, null);
		var otherEventId = insertEvent(host, null, null);
		var insert = "INSERT INTO notification (user_id, type, event_id, payload, push_status) "
				+ "VALUES (?, ?, ?, CAST(? AS jsonb), ?)";

		assertRejected(() -> jdbcTemplate.update(insert, guest, "SPOT_REMOVED", eventId, "{}", "NONE"),
				DataIntegrityViolationException.class);
		assertRejected(() -> jdbcTemplate.update(insert, guest, "SPOT_OFFERED", eventId, "{}", "QUEUED"),
				DataIntegrityViolationException.class);
		assertRejected(() -> jdbcTemplate.update(insert, guest, "SPOT_OFFERED", null, "{}", "NONE"),
				DataIntegrityViolationException.class);
		assertRejected(() -> jdbcTemplate.update(insert, host, "FRIEND_ACCEPTED", eventId, "{}", "NONE"),
				DataIntegrityViolationException.class);
		assertRejected(() -> jdbcTemplate.update(insert, guest, "SPOT_OFFERED", eventId, "[]", "NONE"),
				DataIntegrityViolationException.class);
		for (var type : List.of("SPOT_OFFERED", "SPOT_CONFIRMED", "EVENT_CHANGED", "EVENT_CANCELLED", "EVENT_REMINDER",
				"EVENT_HIDDEN"))
			jdbcTemplate.update(insert, guest, type, eventId, "{}", "PENDING");
		jdbcTemplate.update(insert, guest, "EVENT_REMINDER", otherEventId, "{}", "SENT");
		jdbcTemplate.update(insert, host, "FRIEND_ACCEPTED", null, "{}", "FAILED");

		jdbcTemplate.update("DELETE FROM event WHERE id = ?", otherEventId);
		assertThat(
				jdbcTemplate.queryForObject("SELECT count(*) FROM notification WHERE user_id = ?", Long.class, guest))
				.isEqualTo(6);
		jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", guest);
		assertThat(jdbcTemplate.queryForList("SELECT user_id FROM notification WHERE user_id IN (?, ?)", UUID.class,
				host, guest)).containsExactly(host);
	}

	/**
	 * Tests that a push endpoint is stored once, whoever subscribed with it, and
	 * that subscriptions go with their user.
	 */
	@Test
	void pushSubscriptionChecks() throws SQLException {
		var first = insertUser(81384788765712384L);
		var second = insertUser(81384788765712385L);
		var insert = "INSERT INTO push_subscription (user_id, endpoint, p256dh, auth) VALUES (?, ?, 'k', 'a')";
		var endpoint = "https://fcm.googleapis.com/fcm/send/abc";

		jdbcTemplate.update(insert, first, endpoint);
		assertRejected(() -> jdbcTemplate.update(insert, second, endpoint), DuplicateKeyException.class);
		jdbcTemplate.update(insert, first, endpoint + "d");

		jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", first);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM push_subscription WHERE user_id = ?", Long.class,
				first)).isZero();
	}

	/**
	 * Tests the notification and push indexes: the inbox, the sender's queue, the
	 * event cascade, and a user's subscriptions oldest first.
	 */
	@Test
	void notificationIndexes() {
		assertThat(indexDefinition("notification_inbox_idx")).endsWith("(user_id, id DESC)");
		assertThat(indexDefinition("notification_push_idx"))
				.endsWith("(id) WHERE ((push_status)::text = 'PENDING'::text)");
		assertThat(indexDefinition("notification_event_idx")).endsWith("(event_id)");
		assertThat(indexDefinition("push_subscription_endpoint_key")).startsWith("CREATE UNIQUE INDEX")
				.endsWith("(endpoint)");
		assertThat(indexDefinition("push_subscription_user_idx")).endsWith("(user_id, id)");
	}

	/**
	 * Tests that Flyway applied the moderation migration (D-015), with the planned
	 * columns and defaults.
	 */
	@Test
	void moderationMigrationApplied() {
		assertThat(jdbcTemplate.queryForObject(
				"SELECT success FROM flyway_schema_history WHERE version = '202611251800'", Boolean.class)).isTrue();
		assertThat(columns("user_role")).containsExactlyInAnyOrder("user_id", "role", "granted_by", "granted_at");
		assertThat(columns("banned_account")).containsExactlyInAnyOrder("discord_id", "reason", "banned_by",
				"created_at");
		assertThat(columns("report")).containsExactlyInAnyOrder("id", "reporter_id", "target_type", "target_id",
				"reason", "note", "status", "handled_by", "handled_at", "resolution_note", "created_at");
		assertThat(columns("audit_log")).containsExactlyInAnyOrder("id", "actor_id", "action", "target_type",
				"target_id", "details", "created_at");

		var eventId = insertEvent(insertUser(81384788765712384L), null, null);
		assertThat(jdbcTemplate.queryForMap("SELECT hidden_by_moderation, moderation_reason FROM event WHERE id = ?",
				eventId)).containsEntry("hidden_by_moderation", false).containsEntry("moderation_reason", null);
	}

	/**
	 * Tests the role and ban rules: known roles, one row per role, a grant that
	 * goes with its user but survives its granter, and a ban that outlives the
	 * account.
	 */
	@Test
	void roleAndBanChecks() throws SQLException {
		var admin = insertUser(81384788765712384L);
		var user = insertUser(81384788765712385L);
		var grant = "INSERT INTO user_role (user_id, role, granted_by) VALUES (?, ?, ?)";

		assertRejected(() -> jdbcTemplate.update(grant, user, "USER", admin), DataIntegrityViolationException.class);
		jdbcTemplate.update(grant, admin, "ADMIN", null);
		jdbcTemplate.update(grant, user, "MODERATOR", admin);
		jdbcTemplate.update(grant, user, "ADMIN", admin);
		assertRejected(() -> jdbcTemplate.update(grant, user, "MODERATOR", admin), DuplicateKeyException.class);
		jdbcTemplate.update("INSERT INTO banned_account (discord_id, reason, banned_by) VALUES (?, 'Spam', ?)",
				81384788765712385L, admin);
		assertRejected(
				() -> jdbcTemplate
						.update("INSERT INTO banned_account (discord_id, reason) VALUES (81384788765712385, 'Again')"),
				DuplicateKeyException.class);

		jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", admin);
		assertThat(jdbcTemplate.queryForList("SELECT granted_by FROM user_role WHERE user_id = ?", UUID.class, user))
				.hasSize(2).containsOnlyNulls();
		jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", user);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM user_role", Long.class)).isZero();
		assertThat(jdbcTemplate.queryForMap("SELECT discord_id, banned_by FROM banned_account"))
				.containsEntry("discord_id", 81384788765712385L).containsEntry("banned_by", null);
	}

	/**
	 * Tests the report rules: known targets, reasons and statuses, handled exactly
	 * when not open, one open report per reporter and target, and a report that
	 * outlives its target, its reporter and its moderator.
	 */
	@Test
	void reportChecks() throws SQLException {
		var reporter = insertUser(81384788765712384L);
		var target = insertUser(81384788765712385L);
		var moderator = insertUser(81384788765712386L);
		var report = "INSERT INTO report (reporter_id, target_type, target_id, reason) VALUES (?, ?, ?, ?)";

		assertRejected(() -> jdbcTemplate.update(report, reporter, "CIRCLE", target, "SPAM"),
				DataIntegrityViolationException.class);
		assertRejected(() -> jdbcTemplate.update(report, reporter, "USER", target, "RUDE"),
				DataIntegrityViolationException.class);
		assertRejected(
				() -> jdbcTemplate.update("INSERT INTO report (reporter_id, target_type, target_id, reason, "
						+ "note) VALUES (?, 'USER', ?, 'SPAM', ?)", reporter, target, "x".repeat(1001)),
				DataIntegrityViolationException.class);
		assertRejected(
				() -> jdbcTemplate.update("INSERT INTO report (reporter_id, target_type, target_id, reason, "
						+ "status) VALUES (?, 'USER', ?, 'SPAM', 'ACTIONED')", reporter, target),
				DataIntegrityViolationException.class);
		var first = jdbcTemplate.queryForObject(report + " RETURNING id", Long.class, reporter, "USER", target,
				"HARASSMENT");
		assertRejected(() -> jdbcTemplate.update(report, reporter, "USER", target, "SPAM"),
				DuplicateKeyException.class);
		assertRejected(() -> jdbcTemplate
				.update("UPDATE report SET handled_by = ?, resolution_note = 'Noted' WHERE id = ?", moderator, first),
				DataIntegrityViolationException.class);
		jdbcTemplate.update("UPDATE report SET status = 'DISMISSED', handled_by = ?, handled_at = now() WHERE id = ?",
				moderator, first);
		jdbcTemplate.update(report, reporter, "USER", target, "SPAM");
		jdbcTemplate.update(report, reporter, "EVENT", target, "UNSAFE");

		for (var user : List.of(target, reporter, moderator))
			jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", user);
		assertThat(jdbcTemplate.queryForList("SELECT target_id FROM report WHERE reporter_id IS NULL", UUID.class))
				.hasSize(3).containsOnly(target);
		assertThat(jdbcTemplate.queryForObject("SELECT handled_by FROM report WHERE id = ?", UUID.class, first))
				.isNull();
	}

	/**
	 * Tests that the audit log takes known actions and targets with an object of
	 * details, and refuses every update, delete and truncate, even of a row whose
	 * actor has gone.
	 */
	@Test
	void auditLogIsInsertOnly() throws SQLException {
		var actor = insertUser(81384788765712384L);
		var insert = "INSERT INTO audit_log (actor_id, action, target_type, target_id, details) "
				+ "VALUES (?, ?, ?, ?, CAST(? AS jsonb))";

		assertRejected(() -> jdbcTemplate.update(insert, actor, "EVENT_DELETED", "EVENT", "x", "{}"),
				DataIntegrityViolationException.class);
		assertRejected(() -> jdbcTemplate.update(insert, actor, "EVENT_HIDDEN", "CIRCLE", "x", "{}"),
				DataIntegrityViolationException.class);
		assertRejected(() -> jdbcTemplate.update(insert, actor, "EVENT_HIDDEN", "EVENT", "x", "[]"),
				DataIntegrityViolationException.class);
		jdbcTemplate.update(insert, actor, "EVENT_HIDDEN", "EVENT", UUID.randomUUID().toString(),
				"{\"reason\":\"Unsafe\"}");
		jdbcTemplate.update(insert, null, "SIGN_IN_REFUSED", "DISCORD_ACCOUNT", "81384788765712385",
				"{\"cause\":\"ACCOUNT_BANNED\"}");
		jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", actor);

		for (var statement : List.of("UPDATE audit_log SET details = '{}'", "UPDATE audit_log SET actor_id = NULL",
				"DELETE FROM audit_log", "TRUNCATE audit_log"))
			assertRejectedWith(statement, "audit_log is insert-only");
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE actor_id = ?", Long.class, actor))
				.isOne();
	}

	/**
	 * Tests the moderation rules on events and notifications: a reason of at most
	 * 500 characters exactly while hidden, and an event for {@code EVENT_HIDDEN}
	 * but none for the report notifications.
	 */
	@Test
	void eventModerationAndNotificationChecks() throws SQLException {
		var host = insertUser(81384788765712384L);
		var eventId = insertEvent(host, null, null);
		var notify = "INSERT INTO notification (user_id, type, event_id, payload, push_status) "
				+ "VALUES (?, ?, ?, '{}', 'NONE')";

		assertRejected(() -> jdbcTemplate.update("UPDATE event SET hidden_by_moderation = true WHERE id = ?", eventId),
				DataIntegrityViolationException.class);
		assertRejected(() -> jdbcTemplate.update("UPDATE event SET moderation_reason = 'r' WHERE id = ?", eventId),
				DataIntegrityViolationException.class);
		assertRejected(() -> jdbcTemplate.update(
				"UPDATE event SET hidden_by_moderation = true, moderation_reason = ? WHERE id = ?", "x".repeat(501),
				eventId), DataIntegrityViolationException.class);
		jdbcTemplate.update("UPDATE event SET hidden_by_moderation = true, moderation_reason = 'Unsafe' WHERE id = ?",
				eventId);

		assertRejected(() -> jdbcTemplate.update(notify, host, "EVENT_HIDDEN", null),
				DataIntegrityViolationException.class);
		jdbcTemplate.update(notify, host, "EVENT_HIDDEN", eventId);
		for (var type : List.of("REPORT_RECEIVED", "REPORT_REVIEWED")) {
			assertRejected(() -> jdbcTemplate.update(notify, host, type, eventId),
					DataIntegrityViolationException.class);
			jdbcTemplate.update(notify, host, type, null);
		}
	}

	/**
	 * Tests the report indexes: the queue, one open report per reporter and target,
	 * the daily cap, and a target's reports.
	 */
	@Test
	void reportIndexes() {
		assertThat(indexDefinition("report_queue_idx")).endsWith("(status, id)");
		assertThat(indexDefinition("report_open_key")).startsWith("CREATE UNIQUE INDEX")
				.endsWith("(reporter_id, target_type, target_id) WHERE ((status)::text = 'OPEN'::text)");
		assertThat(indexDefinition("report_reporter_idx")).endsWith("(reporter_id, created_at)");
		assertThat(indexDefinition("report_target_idx")).endsWith("(target_id)");
	}

	/**
	 * Tests the RSVP migration's backfill on events made before it: each gets its
	 * host's confirmed RSVP, dated when the event was made, and a confirmed count
	 * of 1, cancelled events included. A capacity of 1 becomes 2, and other
	 * capacities are kept. The test migrates a schema of its own up to the events
	 * migration, adds the events, migrates the rest, and drops the schema.
	 */
	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void rsvpBackfill() {
		var schema = "rsvp_backfill_check";
		var flyway = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration");
		try {
			flyway.target("202611241710").load().migrate();
			var host = jdbcTemplate.queryForObject(
					"INSERT INTO " + schema
							+ ".app_user (discord_id, username) VALUES (81384788765712384, 'host') RETURNING id",
					UUID.class);
			var insert = "INSERT INTO " + schema + ".event (host_id, title, location_text, starts_at, time_zone, "
					+ "capacity, status, created_at) VALUES (?, 'Photo walk', 'Hall B', now() + interval '1 day', "
					+ "'UTC', ?, ?, ?) RETURNING id";
			var createdAt = Timestamp.from(Instant.parse("2026-11-20T12:00:00Z"));
			var unlimited = jdbcTemplate.queryForObject(insert, Long.class, host, null, "PUBLISHED", createdAt);
			var single = jdbcTemplate.queryForObject(insert, Long.class, host, 1, "PUBLISHED", createdAt);
			var cancelled = jdbcTemplate.queryForObject(insert, Long.class, host, 50, "CANCELLED", createdAt);

			flyway.target("latest").load().migrate();

			assertThat(jdbcTemplate
					.queryForList("SELECT event_id, user_id, status, created_at FROM " + schema + ".rsvp ORDER BY id"))
					.containsExactly(rsvpRow(unlimited, host, createdAt), rsvpRow(single, host, createdAt),
							rsvpRow(cancelled, host, createdAt));
			assertThat(jdbcTemplate
					.queryForList("SELECT id, capacity, confirmed_count FROM " + schema + ".event ORDER BY id"))
					.containsExactly(eventRow(unlimited, null), eventRow(single, 2), eventRow(cancelled, 50));
		} finally {
			jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		}
	}

	/**
	 * Tests that {@code app_user} has exactly the planned columns, and no email.
	 */
	@Test
	void appUserColumnsHaveNoEmail() {
		assertThat(columns("app_user")).containsExactlyInAnyOrder("id", "discord_id", "username", "global_name",
				"avatar_hash", "mfa_enabled", "time_zone", "push_enabled", "suspended_until", "created_at",
				"last_login_at");
		assertThat(columns("user_guild")).containsExactlyInAnyOrder("user_id", "guild_id", "guild_name", "icon_hash",
				"discoverable", "synced_at");
	}

	/**
	 * Tests the defaults: a generated ID, settings off, and a server that isn't
	 * discoverable until the user opts in.
	 */
	@Test
	void defaultsArePrivate() {
		var userId = insertUser(81384788765712384L);
		jdbcTemplate.update("INSERT INTO user_guild (user_id, guild_id, guild_name) VALUES (?, ?, ?)", userId,
				175928847299117063L, "Example Con Community");

		assertThat(jdbcTemplate.queryForObject("SELECT mfa_enabled OR push_enabled FROM app_user WHERE id = ?",
				Boolean.class, userId)).isFalse();
		assertThat(jdbcTemplate.queryForObject("SELECT discoverable FROM user_guild WHERE user_id = ?", Boolean.class,
				userId)).isFalse();
	}

	/**
	 * Tests that a Discord account maps to one user.
	 */
	@Test
	void discordIdIsUnique() {
		insertUser(81384788765712384L);

		assertThatThrownBy(() -> insertUser(81384788765712384L)).isInstanceOf(DuplicateKeyException.class);
	}

	/**
	 * Tests that deleting a user deletes their servers.
	 */
	@Test
	void deletingUserDeletesTheirGuilds() {
		var userId = insertUser(81384788765712384L);
		jdbcTemplate.update("INSERT INTO user_guild (user_id, guild_id, guild_name) VALUES (?, ?, ?)", userId,
				175928847299117063L, "Example Con Community");

		jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM user_guild", Integer.class)).isZero();
	}

	/**
	 * Tests the indexes: the primary key leads with {@code user_id}, and the guild
	 * index covers discoverable rows only.
	 */
	@Test
	void indexes() {
		assertThat(indexDefinition("user_guild_pkey")).endsWith("(user_id, guild_id)");
		assertThat(indexDefinition("user_guild_discoverable_guild_idx"))
				.endsWith("(guild_id, user_id) WHERE discoverable");
		assertThat(indexDefinition("app_user_discord_id_key")).startsWith("CREATE UNIQUE INDEX")
				.endsWith("(discord_id)");
	}

	/**
	 * Inserts a user with only the required columns.
	 *
	 * @param discordId the Discord ID
	 * @return the generated user ID
	 */
	private UUID insertUser(long discordId) {
		return jdbcTemplate.queryForObject("INSERT INTO app_user (discord_id, username) VALUES (?, ?) RETURNING id",
				UUID.class, discordId, "con_goer");
	}

	/**
	 * Asserts that the database rejects a statement. It runs in a savepoint, so the
	 * test's transaction stays usable afterwards.
	 *
	 * @param statement the statement
	 * @param type      the expected exception type
	 */
	private void assertRejected(Runnable statement, Class<? extends Throwable> type) throws SQLException {
		var connection = DataSourceUtils.getConnection(dataSource);
		var savepoint = connection.setSavepoint();
		try {
			assertThatThrownBy(statement::run).isInstanceOf(type);
		} finally {
			connection.rollback(savepoint);
		}
	}

	/**
	 * Asserts that the database rejects a statement with a message. It runs in a
	 * savepoint, so the test's transaction stays usable afterwards.
	 *
	 * @param sql     the statement
	 * @param message part of the expected message
	 */
	private void assertRejectedWith(String sql, String message) throws SQLException {
		var connection = DataSourceUtils.getConnection(dataSource);
		var savepoint = connection.setSavepoint();
		try {
			assertThatThrownBy(() -> jdbcTemplate.execute(sql)).as(sql).rootCause().hasMessageContaining(message);
		} finally {
			connection.rollback(savepoint);
		}
	}

	/**
	 * Inserts two users.
	 *
	 * @return their IDs, smaller first
	 */
	private List<UUID> sortedUsers() {
		return jdbcTemplate.queryForList("SELECT id FROM app_user WHERE id IN (?, ?) ORDER BY id", UUID.class,
				insertUser(81384788765712384L), insertUser(81384788765712385L));
	}

	/**
	 * Inserts a friendship.
	 *
	 * @param userA the first user
	 * @param userB the second user
	 */
	private void befriend(UUID userA, UUID userB) {
		jdbcTemplate.update("INSERT INTO friendship (user_a, user_b) VALUES (?, ?)", userA, userB);
	}

	/**
	 * Inserts a circle.
	 *
	 * @param owner the owner
	 * @param name  the name
	 * @return the circle's internal ID
	 */
	private long insertCircle(UUID owner, String name) {
		return jdbcTemplate.queryForObject("INSERT INTO circle (owner_id, name) VALUES (?, ?) RETURNING id", Long.class,
				owner, name);
	}

	/**
	 * Inserts an event that starts in a day, with only the required columns plus an
	 * end and a capacity.
	 *
	 * @param host     the host
	 * @param endsIn   how long from now it ends, or {@code null}
	 * @param capacity the capacity, or {@code null}
	 * @return the event's internal ID
	 */
	private long insertEvent(UUID host, Duration endsIn, Integer capacity) {
		var now = Instant.now();
		return jdbcTemplate.queryForObject(
				"INSERT INTO event (host_id, title, location_text, starts_at, ends_at, time_zone, capacity) "
						+ "VALUES (?, 'Photo walk', 'Hall B', ?, ?, 'America/Chicago', ?) RETURNING id",
				Long.class, host, Timestamp.from(now.plus(Duration.ofDays(1))),
				endsIn == null ? null : Timestamp.from(now.plus(endsIn)), capacity);
	}

	/**
	 * Inserts an RSVP.
	 *
	 * @param eventId the event's internal ID
	 * @param userId  the user
	 * @param status  the status
	 * @return the RSVP's ID
	 */
	private long insertRsvp(long eventId, UUID userId, String status) {
		return jdbcTemplate.queryForObject("INSERT INTO rsvp (event_id, user_id, status) VALUES (?, ?, ?) RETURNING id",
				Long.class, eventId, userId, status);
	}

	/**
	 * Returns the row the backfill should have written for an event.
	 *
	 * @param eventId   the event's internal ID
	 * @param host      the host
	 * @param createdAt when the event was made
	 * @return the expected {@code rsvp} row
	 */
	private static Map<String, Object> rsvpRow(long eventId, UUID host, Timestamp createdAt) {
		return Map.of("event_id", eventId, "user_id", host, "status", "CONFIRMED", "created_at", createdAt);
	}

	/**
	 * Returns an event's expected capacity and count after the backfill.
	 *
	 * @param eventId  the event's internal ID
	 * @param capacity the capacity, or {@code null}
	 * @return the expected {@code event} row
	 */
	private static Map<String, Object> eventRow(long eventId, Integer capacity) {
		var row = new HashMap<String, Object>();
		row.put("id", eventId);
		row.put("capacity", capacity);
		row.put("confirmed_count", 1);
		return row;
	}

	/**
	 * Returns a table's column names.
	 *
	 * @param table the table name
	 * @return the column names
	 */
	private List<String> columns(String table) {
		return jdbcTemplate.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = ?",
				String.class, table);
	}

	/**
	 * Returns an index's {@code CREATE INDEX} statement.
	 *
	 * @param index the index name
	 * @return the definition
	 */
	private String indexDefinition(String index) {
		return jdbcTemplate.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = ?", String.class, index);
	}
}
