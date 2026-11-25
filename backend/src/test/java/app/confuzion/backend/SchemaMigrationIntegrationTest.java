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
 * M3's RSVPs. Each test rolls back, except the backfill test, which migrates a
 * schema of its own and drops it.
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
				"updated_at");
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
				"updated_at");

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

		assertRejected(() -> insertRsvp(eventId, guest, "OFFERED"), DataIntegrityViolationException.class);
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
	 * Tests the RSVP indexes: the active-RSVP key, the waitlist queue, My schedule
	 * and the per-event lookup that also serves the event cascade.
	 */
	@Test
	void rsvpIndexes() {
		var active = "WHERE ((status)::text = ANY ((ARRAY['CONFIRMED'::character varying, "
				+ "'WAITLISTED'::character varying])::text[]))";
		assertThat(indexDefinition("rsvp_active_key")).startsWith("CREATE UNIQUE INDEX")
				.endsWith("(event_id, user_id) " + active);
		assertThat(indexDefinition("rsvp_queue_idx"))
				.endsWith("(event_id, id) WHERE ((status)::text = 'WAITLISTED'::text)");
		assertThat(indexDefinition("rsvp_user_active_idx")).endsWith("(user_id) " + active);
		assertThat(indexDefinition("rsvp_event_user_idx")).endsWith("(event_id, user_id)");
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
