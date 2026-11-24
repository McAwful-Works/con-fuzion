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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;

import app.confuzion.backend.service.DiscordOAuth2UserService;

/**
 * Seeds social-graph rows straight into Postgres for the integration tests, and
 * signs requests in as a seeded user. Every user, server and token is fresh, so
 * tests sharing the database never see each other's rows.
 *
 * @author mcawful
 */
final class SocialFixtures {
	/**
	 * The next Discord ID, for users and servers alike.
	 */
	private static final AtomicLong NEXT_ID = new AtomicLong(
			600000000000000000L + new SecureRandom().nextLong(1L << 40) * 1000);

	/**
	 * The CSPRNG for tokens.
	 */
	private static final SecureRandom RANDOM = new SecureRandom();

	/**
	 * The {@link JdbcTemplate}.
	 */
	private final JdbcTemplate jdbc;

	/**
	 * Creates the fixtures.
	 *
	 * @param jdbc the {@link JdbcTemplate}
	 */
	SocialFixtures(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Inserts a user.
	 *
	 * @param username   the username
	 * @param globalName the display name, or {@code null}
	 * @return the user ID
	 */
	UUID user(String username, String globalName) {
		return jdbc.queryForObject(
				"INSERT INTO app_user (discord_id, username, global_name) VALUES (?, ?, ?) " + "RETURNING id",
				UUID.class, NEXT_ID.incrementAndGet(), username, globalName);
	}

	/**
	 * Returns a fresh server ID.
	 *
	 * @return the ID
	 */
	long guildId() {
		return NEXT_ID.incrementAndGet();
	}

	/**
	 * Puts a user in a server.
	 *
	 * @param userId       the user ID
	 * @param guildId      the server ID
	 * @param discoverable whether the user is discoverable there
	 */
	void guild(UUID userId, long guildId, boolean discoverable) {
		jdbc.update("INSERT INTO user_guild (user_id, guild_id, guild_name, discoverable) VALUES (?, ?, 'Con', ?)",
				userId, guildId, discoverable);
	}

	/**
	 * Makes two users friends.
	 *
	 * @param a a user ID
	 * @param b another user ID
	 */
	void friends(UUID a, UUID b) {
		jdbc.update(
				"INSERT INTO friendship (user_a, user_b) VALUES (LEAST(?::uuid, ?::uuid), GREATEST(?::uuid, ?::uuid))",
				a, b, a, b);
	}

	/**
	 * Returns whether two users are friends.
	 *
	 * @param a a user ID
	 * @param b another user ID
	 * @return {@code true} when they are
	 */
	boolean areFriends(UUID a, UUID b) {
		return jdbc.queryForObject(
				"SELECT count(*) FROM friendship WHERE (user_a = ? AND user_b = ?) " + "OR (user_a = ? AND user_b = ?)",
				Long.class, a, b, b, a) > 0;
	}

	/**
	 * Records a block.
	 *
	 * @param blocker the blocker
	 * @param blocked the blocked user
	 */
	void block(UUID blocker, UUID blocked) {
		jdbc.update("INSERT INTO block (blocker_id, blocked_id) VALUES (?, ?)", blocker, blocked);
	}

	/**
	 * Creates a circle with members.
	 *
	 * @param owner   the owner
	 * @param members the members
	 * @return the circle's public ID
	 */
	UUID circle(UUID owner, UUID... members) {
		var circle = jdbc.queryForMap("INSERT INTO circle (owner_id, name) VALUES (?, ?) RETURNING id, public_id",
				owner, "Circle " + NEXT_ID.incrementAndGet());
		for (var member : members)
			jdbc.update("INSERT INTO circle_member (circle_id, user_id) VALUES (?, ?)", circle.get("id"), member);
		return (UUID) circle.get("public_id");
	}

	/**
	 * Returns the members of a circle.
	 *
	 * @param circleId the circle's public ID
	 * @return the member IDs
	 */
	List<UUID> members(UUID circleId) {
		return jdbc.queryForList(
				"SELECT m.user_id FROM circle_member m JOIN circle c ON c.id = m.circle_id " + "WHERE c.public_id = ?",
				UUID.class, circleId);
	}

	/**
	 * Creates an upcoming event with a FRIENDS entry and a USER entry per person.
	 *
	 * @param host  the host
	 * @param users the people in its audience
	 * @return the event's internal ID
	 */
	long event(UUID host, UUID... users) {
		var id = jdbc.queryForObject(
				"INSERT INTO event (host_id, title, location_text, starts_at, time_zone) "
						+ "VALUES (?, 'Meetup', 'Hall B', now() + interval '1 day', 'UTC') RETURNING id",
				Long.class, host);
		jdbc.update("INSERT INTO event_audience (event_id, type) VALUES (?, 'FRIENDS')", id);
		for (var user : users)
			jdbc.update("INSERT INTO event_audience (event_id, type, user_id) VALUES (?, 'USER', ?)", id, user);
		return id;
	}

	/**
	 * Returns an event's audience entries as {@code TYPE} or {@code USER:id}.
	 *
	 * @param eventId the event's internal ID
	 * @return the entries
	 */
	List<String> audience(long eventId) {
		return jdbc.queryForList("SELECT type || coalesce(':' || user_id, '') FROM event_audience WHERE event_id = ?",
				String.class, eventId);
	}

	/**
	 * Stores an invite directly and returns its raw token.
	 *
	 * @param inviter     the inviter
	 * @param expiresIn   the interval until it expires, such as {@code 1 day} or
	 *                    {@code -1 hour}
	 * @param usedByOther whether it is already used
	 * @return the token
	 */
	String invite(UUID inviter, String expiresIn, boolean usedByOther) {
		var bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		jdbc.update(
				"INSERT INTO friend_invite (token_hash, inviter_id, created_at, expires_at, used_at) "
						+ "VALUES (?, ?, now() - interval '2 days', now() + ?::interval, CASE WHEN ? THEN now() END)",
				sha256(token), inviter, expiresIn, usedByOther);
		return token;
	}

	/**
	 * Returns the SHA-256 of a token, as the server stores it.
	 *
	 * @param token the token
	 * @return the hash
	 */
	static byte[] sha256(String token) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Returns a signed-in principal for a user, as the login would build it.
	 *
	 * @param userId the user ID
	 * @return the request post-processor
	 */
	static OAuth2LoginRequestPostProcessor login(UUID userId) {
		return oauth2Login().oauth2User(new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
				Map.of(DiscordOAuth2UserService.USER_ID_ATTRIBUTE, userId.toString()),
				DiscordOAuth2UserService.USER_ID_ATTRIBUTE));
	}
}
