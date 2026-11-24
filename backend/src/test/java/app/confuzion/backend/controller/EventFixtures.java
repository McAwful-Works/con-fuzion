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
package app.confuzion.backend.controller;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;

/**
 * Seeds events and their audiences straight into the database for the event
 * integration tests, on top of {@link SocialFixtures} for the people, servers,
 * friendships, circles and blocks around them. Every user and server is new, so
 * tests don't see each other's rows.
 *
 * @param jdbcTemplate the {@link JdbcTemplate}
 * @param social       the {@link SocialFixtures}
 * @author mcawful
 */
record EventFixtures(JdbcTemplate jdbcTemplate, SocialFixtures social) {

	/**
	 * Creates the fixtures.
	 *
	 * @param jdbcTemplate the {@link JdbcTemplate}
	 */
	EventFixtures(JdbcTemplate jdbcTemplate) {
		this(jdbcTemplate, new SocialFixtures(jdbcTemplate));
	}

	/**
	 * Returns a signed-in principal for a user.
	 *
	 * @param userId the user ID
	 * @return the request post-processor
	 */
	static OAuth2LoginRequestPostProcessor login(UUID userId) {
		return SocialFixtures.login(userId);
	}

	/**
	 * Returns a server ID no other test uses.
	 *
	 * @return the server ID
	 */
	long newGuildId() {
		return social.guildId();
	}

	/**
	 * Inserts a user.
	 *
	 * @param name the display name
	 * @return the user ID
	 */
	UUID user(String name) {
		return social.user("seeded", name);
	}

	/**
	 * Puts a user in a server named {@code Con}.
	 *
	 * @param userId       the user ID
	 * @param guildId      the server ID
	 * @param discoverable whether they are discoverable there
	 */
	void guild(UUID userId, long guildId, boolean discoverable) {
		social.guild(userId, guildId, discoverable);
	}

	/**
	 * Makes two users friends.
	 *
	 * @param a one user
	 * @param b the other
	 */
	void friends(UUID a, UUID b) {
		social.friends(a, b);
	}

	/**
	 * Records a block.
	 *
	 * @param blocker the user who blocks
	 * @param blocked the user they block
	 */
	void block(UUID blocker, UUID blocked) {
		social.block(blocker, blocked);
	}

	/**
	 * Creates a circle with members.
	 *
	 * @param ownerId the owner
	 * @param members the members
	 * @return the circle's internal ID and public ID
	 */
	Circle circle(UUID ownerId, UUID... members) {
		var publicId = social.circle(ownerId, members);
		return new Circle(
				jdbcTemplate.queryForObject("SELECT id FROM circle WHERE public_id = ?", Long.class, publicId),
				publicId);
	}

	/**
	 * Inserts a published event starting in a day, with no audience.
	 *
	 * @param hostId the host
	 * @return the event's internal ID and public ID
	 */
	SeededEvent event(UUID hostId) {
		return event(hostId, "now() + interval '1 day'", "NULL");
	}

	/**
	 * Inserts a published event with SQL start and end expressions, with no
	 * audience.
	 *
	 * @param hostId   the host
	 * @param startsAt the start, as a SQL expression
	 * @param endsAt   the end, as a SQL expression
	 * @return the event's internal ID and public ID
	 */
	SeededEvent event(UUID hostId, String startsAt, String endsAt) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO event (host_id, title, location_text, starts_at, ends_at, "
						+ "time_zone) VALUES (?, 'Seeded', 'Hall A', " + startsAt + ", " + endsAt
						+ ", 'UTC') RETURNING id, public_id",
				(rs, row) -> new SeededEvent(rs.getLong(1), rs.getObject(2, UUID.class)), hostId);
	}

	/**
	 * Adds an audience entry to an event.
	 *
	 * @param eventId  the event's internal ID
	 * @param type     the entry type
	 * @param guildId  the server ID, or {@code null}
	 * @param circleId the circle's internal ID, or {@code null}
	 * @param userId   the user ID, or {@code null}
	 */
	void audience(long eventId, String type, Long guildId, Long circleId, UUID userId) {
		jdbcTemplate.update(
				"INSERT INTO event_audience (event_id, type, guild_id, circle_id, user_id) VALUES (?, ?, ?, ?, ?)",
				eventId, type, guildId, circleId, userId);
	}

	/**
	 * Cancels an event.
	 *
	 * @param eventId the event's internal ID
	 */
	void cancel(long eventId) {
		jdbcTemplate.update("UPDATE event SET status = 'CANCELLED' WHERE id = ?", eventId);
	}

	/**
	 * A seeded circle.
	 *
	 * @param id       the internal ID
	 * @param publicId the public ID
	 * @author mcawful
	 */
	record Circle(long id, UUID publicId) {
	}

	/**
	 * A seeded event.
	 *
	 * @param id       the internal ID
	 * @param publicId the public ID
	 * @author mcawful
	 */
	record SeededEvent(long id, UUID publicId) {
	}
}
