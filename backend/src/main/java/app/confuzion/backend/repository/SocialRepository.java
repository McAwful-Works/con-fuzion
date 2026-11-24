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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import app.confuzion.backend.model.record.InvitePreviewResponse;
import app.confuzion.backend.model.record.PersonResponse;
import app.confuzion.backend.service.UserService;

/**
 * Native SQL for the social graph (D-011): friendships, invites, circles,
 * blocks and the pickable set. Every method is scoped by the user IDs it is
 * given; the services decide whose IDs those are. Friendship pairs are ordered
 * in SQL with {@code LEAST}/{@code GREATEST}, never in Java, because Postgres
 * and {@link UUID#compareTo} order UUIDs differently.
 *
 * @author mcawful
 */
@Repository
public class SocialRepository {
	/**
	 * The {@code app_user u} columns that {@link #PERSON} reads.
	 */
	private static final String PERSON_COLUMNS = "u.id, u.discord_id, u.username, u.global_name, u.avatar_hash";

	/**
	 * Sorts people by display name, ignoring case, then by ID so ties are stable.
	 */
	private static final String BY_DISPLAY_NAME = " ORDER BY lower(coalesce(u.global_name, u.username)), u.id";

	/**
	 * True when neither {@code :me} nor {@code u} has blocked the other.
	 */
	private static final String NOT_BLOCKED = """
			NOT EXISTS (SELECT 1 FROM block b WHERE (b.blocker_id = :me AND b.blocked_id = u.id)
				OR (b.blocker_id = u.id AND b.blocked_id = :me))""";

	/**
	 * The {@code candidate} CTE: {@code :me}'s friends, plus everyone who shares a
	 * server with {@code :me} where both are discoverable. Joined to
	 * {@code app_user u} and filtered by {@link #NOT_BLOCKED}, it is the pickable
	 * set, so search and the circle-member check share one definition.
	 */
	private static final String CANDIDATES = """
			WITH candidate AS (
				SELECT CASE WHEN f.user_a = :me THEN f.user_b ELSE f.user_a END AS id
				FROM friendship f WHERE f.user_a = :me OR f.user_b = :me
				UNION
				SELECT theirs.user_id FROM user_guild mine
				JOIN user_guild theirs ON theirs.guild_id = mine.guild_id AND theirs.discoverable
					AND theirs.user_id <> :me
				WHERE mine.user_id = :me AND mine.discoverable)
			""";

	/**
	 * Maps {@link #PERSON_COLUMNS} to a {@link PersonResponse}.
	 */
	private static final RowMapper<PersonResponse> PERSON = (rs, rowNum) -> new PersonResponse(
			rs.getObject("id", UUID.class),
			Objects.requireNonNullElse(rs.getString("global_name"), rs.getString("username")),
			UserService.avatarUrl(rs.getLong("discord_id"), rs.getString("avatar_hash")));

	/**
	 * Maps a circle row.
	 */
	private static final RowMapper<CircleRow> CIRCLE = (rs, rowNum) -> new CircleRow(rs.getLong("id"),
			rs.getObject("public_id", UUID.class), rs.getString("name"));

	/**
	 * The {@link JdbcClient}.
	 */
	private final JdbcClient jdbc;

	/**
	 * Creates the repository.
	 *
	 * @param jdbc the {@link JdbcClient}
	 */
	public SocialRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Locks users' rows until the transaction ends, in Postgres's UUID order so two
	 * transactions locking the same pair can't deadlock. Accepting an invite,
	 * blocking, adding a circle member and making an invite all take this lock, so
	 * a block can't interleave with a new friendship or membership between the same
	 * two, and the invite cap can't be raced. It is {@code FOR NO KEY UPDATE}, not
	 * {@code FOR UPDATE}: the foreign-key checks of inserts and of the invite claim
	 * take {@code KEY SHARE} on these rows, and {@code FOR UPDATE} would deadlock
	 * against them (two people accepting each other's invites at once).
	 *
	 * @param a a user ID
	 * @param b another user ID, or the same one
	 * @return how many of the users exist
	 */
	public int lockUsers(UUID a, UUID b) {
		return jdbc.sql("SELECT id FROM app_user WHERE id IN (:a, :b) ORDER BY id FOR NO KEY UPDATE").param("a", a)
				.param("b", b).query(UUID.class).list().size();
	}

	/**
	 * Returns a user's profile.
	 *
	 * @param userId the user ID
	 * @return the {@link PersonResponse}, or empty when there is no such user
	 */
	public Optional<PersonResponse> findPerson(UUID userId) {
		return jdbc.sql("SELECT " + PERSON_COLUMNS + " FROM app_user u WHERE u.id = :id").param("id", userId)
				.query(PERSON).optional();
	}

	/**
	 * Counts a user's unused, unexpired invites.
	 *
	 * @param inviterId the inviter's ID
	 * @return the count
	 */
	public long countActiveInvites(UUID inviterId) {
		return jdbc.sql("""
				SELECT count(*) FROM friend_invite
				WHERE inviter_id = :inviter AND used_at IS NULL AND expires_at > now()""").param("inviter", inviterId)
				.query(Long.class).single();
	}

	/**
	 * Stores a new invite that expires 24 hours from now (D-011).
	 *
	 * @param tokenHash the SHA-256 of the token
	 * @param inviterId the inviter's ID
	 * @return when it expires
	 */
	public Instant insertInvite(byte[] tokenHash, UUID inviterId) {
		return jdbc.sql("""
				INSERT INTO friend_invite (token_hash, inviter_id, expires_at)
				VALUES (:hash, :inviter, now() + interval '24 hours') RETURNING expires_at""").param("hash", tokenHash)
				.param("inviter", inviterId).query((rs, rowNum) -> rs.getObject(1, OffsetDateTime.class).toInstant())
				.single();
	}

	/**
	 * Returns what the accept page shows for a usable invite: unused, unexpired,
	 * not the caller's own, and with no block either way.
	 *
	 * @param tokenHash the SHA-256 of the token
	 * @param me        the caller's ID
	 * @return the {@link InvitePreviewResponse}, or empty when it isn't usable
	 */
	public Optional<InvitePreviewResponse> findUsableInvite(byte[] tokenHash, UUID me) {
		return jdbc
				.sql("SELECT " + PERSON_COLUMNS + ", i.expires_at FROM friend_invite i "
						+ "JOIN app_user u ON u.id = i.inviter_id WHERE i.token_hash = :hash AND i.used_at IS NULL "
						+ "AND i.expires_at > now() AND i.inviter_id <> :me AND " + NOT_BLOCKED)
				.param("hash", tokenHash).param("me", me).query((rs, rowNum) -> {
					var person = PERSON.mapRow(rs, rowNum);
					return new InvitePreviewResponse(
							new InvitePreviewResponse.Inviter(person.displayName(), person.avatarUrl()),
							rs.getObject("expires_at", OffsetDateTime.class).toInstant());
				}).optional();
	}

	/**
	 * Uses an invite up, in one statement, so of two concurrent accepts only one
	 * can win. The caller's own invite never matches.
	 *
	 * @param tokenHash the SHA-256 of the token
	 * @param me        the accepting user's ID
	 * @return the inviter's ID, or empty when the invite is unknown, used, expired
	 *         or the caller's own
	 */
	public Optional<UUID> claimInvite(byte[] tokenHash, UUID me) {
		return jdbc.sql("""
				UPDATE friend_invite SET used_at = now(), accepted_by = :me
				WHERE token_hash = :hash AND used_at IS NULL AND expires_at > now() AND inviter_id <> :me
				RETURNING inviter_id""").param("me", me).param("hash", tokenHash).query(UUID.class).optional();
	}

	/**
	 * Returns whether either user has blocked the other.
	 *
	 * @param a a user ID
	 * @param b another user ID
	 * @return {@code true} when there is a block either way
	 */
	public boolean isBlockedEitherWay(UUID a, UUID b) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM block WHERE (blocker_id = :a AND blocked_id = :b)
					OR (blocker_id = :b AND blocked_id = :a))""").param("a", a).param("b", b).query(Boolean.class)
				.single();
	}

	/**
	 * Makes two users friends. Does nothing when they already are.
	 *
	 * @param a a user ID
	 * @param b another user ID
	 */
	public void insertFriendship(UUID a, UUID b) {
		jdbc.sql("INSERT INTO friendship (user_a, user_b) VALUES (LEAST(:a, :b), GREATEST(:a, :b)) "
				+ "ON CONFLICT DO NOTHING").param("a", a).param("b", b).update();
	}

	/**
	 * Ends a friendship.
	 *
	 * @param a a user ID
	 * @param b another user ID
	 * @return how many rows were deleted, 0 or 1
	 */
	public int deleteFriendship(UUID a, UUID b) {
		return jdbc.sql("DELETE FROM friendship WHERE user_a = LEAST(:a, :b) AND user_b = GREATEST(:a, :b)")
				.param("a", a).param("b", b).update();
	}

	/**
	 * Removes each user from the other's circles.
	 *
	 * @param a a user ID
	 * @param b another user ID
	 */
	public void deleteFromEachOthersCircles(UUID a, UUID b) {
		jdbc.sql("""
				DELETE FROM circle_member m USING circle c WHERE m.circle_id = c.id
					AND ((c.owner_id = :a AND m.user_id = :b) OR (c.owner_id = :b AND m.user_id = :a))""").param("a", a)
				.param("b", b).update();
	}

	/**
	 * Removes each user's {@code USER} audience entry from the other's events
	 * (D-012).
	 *
	 * @param a a user ID
	 * @param b another user ID
	 */
	public void deleteFromEachOthersEvents(UUID a, UUID b) {
		jdbc.sql("""
				DELETE FROM event_audience au USING event e WHERE au.event_id = e.id AND au.type = 'USER'
					AND ((e.host_id = :a AND au.user_id = :b) OR (e.host_id = :b AND au.user_id = :a))""").param("a", a)
				.param("b", b).update();
	}

	/**
	 * Returns a user's friends, sorted by display name.
	 *
	 * @param me the user's ID
	 * @return the friends
	 */
	public List<PersonResponse> findFriends(UUID me) {
		return jdbc
				.sql("SELECT " + PERSON_COLUMNS + " FROM friendship f "
						+ "JOIN app_user u ON u.id = CASE WHEN f.user_a = :me THEN f.user_b ELSE f.user_a END "
						+ "WHERE f.user_a = :me OR f.user_b = :me" + BY_DISPLAY_NAME)
				.param("me", me).query(PERSON).list();
	}

	/**
	 * Searches the people a user can pick whose username or display name starts
	 * with some text, ignoring case.
	 *
	 * @param me     the user's ID
	 * @param prefix the text, matched literally
	 * @param limit  the most results to return
	 * @return the matches, sorted by display name
	 */
	public List<PersonResponse> searchPickable(UUID me, String prefix, int limit) {
		var pattern = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
		return jdbc
				.sql(CANDIDATES + "SELECT " + PERSON_COLUMNS + " FROM candidate c JOIN app_user u ON u.id = c.id "
						+ "WHERE " + NOT_BLOCKED + " AND (lower(u.username) LIKE (lower(:prefix) || '%') ESCAPE '\\' "
						+ "OR lower(u.global_name) LIKE (lower(:prefix) || '%') ESCAPE '\\')" + BY_DISPLAY_NAME
						+ " LIMIT :limit")
				.param("me", me).param("prefix", pattern).param("limit", limit).query(PERSON).list();
	}

	/**
	 * Returns whether a user can pick someone (D-011).
	 *
	 * @param me    the user's ID
	 * @param other the other user's ID
	 * @return {@code true} when {@code other} is in {@code me}'s pickable set
	 */
	public boolean isPickable(UUID me, UUID other) {
		return jdbc
				.sql(CANDIDATES + "SELECT EXISTS (SELECT 1 FROM candidate c JOIN app_user u ON u.id = c.id "
						+ "WHERE u.id = :other AND " + NOT_BLOCKED + ")")
				.param("me", me).param("other", other).query(Boolean.class).single();
	}

	/**
	 * Returns a user's circles, sorted by name ignoring case.
	 *
	 * @param ownerId the owner's ID
	 * @return the circles
	 */
	public List<CircleRow> findCircles(UUID ownerId) {
		return jdbc.sql("SELECT id, public_id, name FROM circle WHERE owner_id = :owner ORDER BY lower(name), id")
				.param("owner", ownerId).query(CIRCLE).list();
	}

	/**
	 * Returns one of a user's circles.
	 *
	 * @param ownerId  the owner's ID
	 * @param publicId the circle's public ID
	 * @return the circle, or empty when it isn't the owner's
	 */
	public Optional<CircleRow> findCircle(UUID ownerId, UUID publicId) {
		return jdbc.sql("SELECT id, public_id, name FROM circle WHERE owner_id = :owner AND public_id = :publicId")
				.param("owner", ownerId).param("publicId", publicId).query(CIRCLE).optional();
	}

	/**
	 * Creates a circle.
	 *
	 * @param ownerId the owner's ID
	 * @param name    the name
	 * @return the new circle
	 */
	public CircleRow insertCircle(UUID ownerId, String name) {
		return jdbc.sql("INSERT INTO circle (owner_id, name) VALUES (:owner, :name) RETURNING id, public_id, name")
				.param("owner", ownerId).param("name", name).query(CIRCLE).single();
	}

	/**
	 * Renames one of a user's circles.
	 *
	 * @param ownerId  the owner's ID
	 * @param publicId the circle's public ID
	 * @param name     the new name
	 * @return the renamed circle, or empty when it isn't the owner's
	 */
	public Optional<CircleRow> renameCircle(UUID ownerId, UUID publicId, String name) {
		return jdbc
				.sql("UPDATE circle SET name = :name WHERE owner_id = :owner AND public_id = :publicId "
						+ "RETURNING id, public_id, name")
				.param("name", name).param("owner", ownerId).param("publicId", publicId).query(CIRCLE).optional();
	}

	/**
	 * Deletes one of a user's circles, with its members.
	 *
	 * @param ownerId  the owner's ID
	 * @param publicId the circle's public ID
	 * @return how many rows were deleted, 0 when it isn't the owner's
	 */
	public int deleteCircle(UUID ownerId, UUID publicId) {
		return jdbc.sql("DELETE FROM circle WHERE owner_id = :owner AND public_id = :publicId").param("owner", ownerId)
				.param("publicId", publicId).update();
	}

	/**
	 * Returns the members of every circle a user owns, by circle ID, each list
	 * sorted by display name.
	 *
	 * @param ownerId the owner's ID
	 * @return the members, keyed by circle ID; circles without members are absent
	 */
	public Map<Long, List<PersonResponse>> findMembersByOwner(UUID ownerId) {
		return jdbc.sql("SELECT m.circle_id, " + PERSON_COLUMNS + " FROM circle_member m "
				+ "JOIN circle c ON c.id = m.circle_id JOIN app_user u ON u.id = m.user_id WHERE c.owner_id = :owner"
				+ BY_DISPLAY_NAME).param("owner", ownerId)
				.query((rs, rowNum) -> Map.entry(rs.getLong("circle_id"), PERSON.mapRow(rs, rowNum))).list().stream()
				.collect(Collectors.groupingBy(Map.Entry::getKey, LinkedHashMap::new,
						Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
	}

	/**
	 * Returns a circle's members, sorted by display name.
	 *
	 * @param circleId the circle's internal ID
	 * @return the members
	 */
	public List<PersonResponse> findMembers(long circleId) {
		return jdbc
				.sql("SELECT " + PERSON_COLUMNS + " FROM circle_member m JOIN app_user u ON u.id = m.user_id "
						+ "WHERE m.circle_id = :circle" + BY_DISPLAY_NAME)
				.param("circle", circleId).query(PERSON).list();
	}

	/**
	 * Returns whether a user is in a circle.
	 *
	 * @param circleId the circle's internal ID
	 * @param userId   the user's ID
	 * @return {@code true} when they are a member
	 */
	public boolean isMember(long circleId, UUID userId) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM circle_member WHERE circle_id = :circle AND user_id = :user)")
				.param("circle", circleId).param("user", userId).query(Boolean.class).single();
	}

	/**
	 * Adds a user to a circle. Does nothing when they are already in it.
	 *
	 * @param circleId the circle's internal ID
	 * @param userId   the user's ID
	 */
	public void insertMember(long circleId, UUID userId) {
		jdbc.sql("INSERT INTO circle_member (circle_id, user_id) VALUES (:circle, :user) ON CONFLICT DO NOTHING")
				.param("circle", circleId).param("user", userId).update();
	}

	/**
	 * Removes a user from a circle.
	 *
	 * @param circleId the circle's internal ID
	 * @param userId   the user's ID
	 */
	public void deleteMember(long circleId, UUID userId) {
		jdbc.sql("DELETE FROM circle_member WHERE circle_id = :circle AND user_id = :user").param("circle", circleId)
				.param("user", userId).update();
	}

	/**
	 * Returns the users someone has blocked, sorted by display name.
	 *
	 * @param me the blocker's ID
	 * @return the blocked users
	 */
	public List<PersonResponse> findBlocked(UUID me) {
		return jdbc.sql("SELECT " + PERSON_COLUMNS + " FROM block b JOIN app_user u ON u.id = b.blocked_id "
				+ "WHERE b.blocker_id = :me" + BY_DISPLAY_NAME).param("me", me).query(PERSON).list();
	}

	/**
	 * Records a block. Does nothing when it already exists.
	 *
	 * @param blockerId the blocker's ID
	 * @param blockedId the blocked user's ID
	 */
	public void insertBlock(UUID blockerId, UUID blockedId) {
		jdbc.sql("INSERT INTO block (blocker_id, blocked_id) VALUES (:blocker, :blocked) ON CONFLICT DO NOTHING")
				.param("blocker", blockerId).param("blocked", blockedId).update();
	}

	/**
	 * Removes a block someone made.
	 *
	 * @param blockerId the blocker's ID
	 * @param blockedId the blocked user's ID
	 */
	public void deleteBlock(UUID blockerId, UUID blockedId) {
		jdbc.sql("DELETE FROM block WHERE blocker_id = :blocker AND blocked_id = :blocked").param("blocker", blockerId)
				.param("blocked", blockedId).update();
	}

	/**
	 * A circle row.
	 *
	 * @param id       the internal ID, never sent to clients
	 * @param publicId the public ID, used in URLs
	 * @param name     the name
	 * @author mcawful
	 */
	public record CircleRow(long id, UUID publicId, String name) {
	}
}
