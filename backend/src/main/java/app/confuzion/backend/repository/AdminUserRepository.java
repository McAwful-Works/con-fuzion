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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import app.confuzion.backend.model.record.AdminUserResponse;

/**
 * Native SQL for user search and suspensions (D-015). The writes lock the
 * user's {@code app_user} row first, in the order {@link UserRoleRepository}
 * describes, so they serialize with role changes and sign-ins of that user.
 *
 * @author mcawful
 */
@Repository
public class AdminUserRepository {
	/**
	 * The {@link JdbcClient}.
	 */
	private final JdbcClient jdbc;

	/**
	 * Creates the repository.
	 *
	 * @param jdbc the {@link JdbcClient}
	 */
	public AdminUserRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Finds users by an exact user ID or Discord ID, or by part of a username or
	 * display name, ignoring case (D-015 rule 14). The text is matched literally:
	 * {@code %}, {@code _} and the escape character {@code !} are escaped. Only the
	 * fields moderators may see are read, never servers, friends, circles or RSVPs.
	 * The viewer's own report count reads as 0, so a moderator never learns of a
	 * report about them.
	 *
	 * @param text      the search text
	 * @param id        the text as a user ID, or {@code null}
	 * @param discordId the text as a Discord ID, or {@code null}
	 * @param viewer    the moderator searching
	 * @param limit     the most users to return
	 * @return the matches, by username
	 */
	public List<AdminUserResponse> search(String text, UUID id, Long discordId, UUID viewer, int limit) {
		return jdbc.sql("""
				SELECT u.id, u.discord_id, u.username, u.global_name, u.created_at, u.last_login_at, u.suspended_until,
					b.reason AS ban_reason, b.created_at AS banned_at,
					CASE WHEN u.id = :viewer THEN 0 ELSE (SELECT count(*) FROM report r
						WHERE r.target_type = 'USER' AND r.target_id = u.id)::int END AS report_count
				FROM app_user u LEFT JOIN banned_account b ON b.discord_id = u.discord_id
				WHERE u.id = CAST(:id AS uuid) OR u.discord_id = CAST(:discordId AS bigint)
					OR u.username ILIKE :pattern ESCAPE '!' OR u.global_name ILIKE :pattern ESCAPE '!'
				ORDER BY u.username, u.id
				LIMIT :limit""").param("viewer", viewer).param("id", id).param("discordId", discordId)
				.param("pattern", "%" + escapeLike(text) + "%").param("limit", limit)
				.query(AdminUserRepository::adminUser).list();
	}

	/**
	 * Locks a user's {@code app_user} row as {@link UserRoleRepository#lockUser}
	 * does and reads their suspension.
	 *
	 * @param userId the user's ID
	 * @return the {@link Suspension}, or empty when there is no such user
	 */
	public Optional<Suspension> lockForSuspension(UUID userId) {
		return jdbc.sql("SELECT suspended_until FROM app_user WHERE id = :userId FOR NO KEY UPDATE")
				.param("userId", userId).query((rs, row) -> new Suspension(instant(rs, "suspended_until"))).optional();
	}

	/**
	 * Sets or clears a user's suspension.
	 *
	 * @param userId the user's ID
	 * @param until  when it ends, or {@code null} to lift it
	 */
	public void setSuspendedUntil(UUID userId, Instant until) {
		jdbc.sql("UPDATE app_user SET suspended_until = :until WHERE id = :userId").param("userId", userId)
				.param("until", until == null ? null : OffsetDateTime.ofInstant(until, ZoneOffset.UTC)).update();
	}

	/**
	 * Escapes the {@code LIKE} wildcards and the escape character itself.
	 *
	 * @param text the text to match literally
	 * @return the escaped text
	 */
	static String escapeLike(String text) {
		return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
	}

	/**
	 * Maps a search row.
	 *
	 * @param rs  the row
	 * @param row the row number
	 * @return the {@link AdminUserResponse}
	 * @throws SQLException when a column can't be read
	 */
	private static AdminUserResponse adminUser(ResultSet rs, int row) throws SQLException {
		var bannedAt = instant(rs, "banned_at");
		return new AdminUserResponse(rs.getObject("id", UUID.class), Long.toString(rs.getLong("discord_id")),
				rs.getString("username"), rs.getString("global_name"), instant(rs, "created_at"),
				instant(rs, "last_login_at"), instant(rs, "suspended_until"),
				bannedAt == null ? null : new AdminUserResponse.Ban(rs.getString("ban_reason"), bannedAt),
				rs.getInt("report_count"));
	}

	/**
	 * Reads a nullable {@code timestamptz} column.
	 *
	 * @param rs     the row
	 * @param column the column
	 * @return the time, or {@code null}
	 * @throws SQLException when the column can't be read
	 */
	private static Instant instant(ResultSet rs, String column) throws SQLException {
		var time = rs.getObject(column, OffsetDateTime.class);
		return time == null ? null : time.toInstant();
	}

	/**
	 * A user's suspension, as {@link #lockForSuspension} read it.
	 *
	 * @param until when it ends, or {@code null} when they were never suspended or
	 *              it was lifted
	 * @author mcawful
	 */
	public record Suspension(Instant until) {
		/**
		 * Returns whether the suspension is still running.
		 *
		 * @param now the current time
		 * @return {@code true} when it ends after {@code now}
		 */
		public boolean isActive(Instant now) {
			return until != null && until.isAfter(now);
		}
	}
}
