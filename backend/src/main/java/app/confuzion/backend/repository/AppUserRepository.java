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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import app.confuzion.backend.model.entity.AppUser;

/**
 * The {@link AppUser} repository.
 *
 * @author mcawful
 */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
	/**
	 * Creates the user on their first login, or refreshes their Discord profile and
	 * {@code last_login_at} on a later one, in one statement. The row stays locked
	 * until the transaction ends, so two logins of the same user run their guild
	 * syncs one after the other. {@code xmax = 0} is true only for a row this
	 * statement inserted.
	 *
	 * @param discordId  the Discord user ID
	 * @param username   the Discord username
	 * @param globalName the display name, or {@code null}
	 * @param avatarHash the avatar hash, or {@code null}
	 * @param mfaEnabled whether the account has MFA on
	 * @return the user ID and whether this was the first login
	 */
	@Query(value = """
			INSERT INTO app_user (discord_id, username, global_name, avatar_hash, mfa_enabled)
			VALUES (:discordId, :username, CAST(:globalName AS varchar), CAST(:avatarHash AS varchar), :mfaEnabled)
			ON CONFLICT (discord_id) DO UPDATE SET username = EXCLUDED.username, global_name = EXCLUDED.global_name,
				avatar_hash = EXCLUDED.avatar_hash, mfa_enabled = EXCLUDED.mfa_enabled, last_login_at = now()
			RETURNING id, (xmax = 0) AS inserted""", nativeQuery = true)
	LoginRow upsertFromDiscord(@Param("discordId") long discordId, @Param("username") String username,
			@Param("globalName") String globalName, @Param("avatarHash") String avatarHash,
			@Param("mfaEnabled") boolean mfaEnabled);

	/**
	 * The result of {@link AppUserRepository#upsertFromDiscord}.
	 *
	 * @author mcawful
	 */
	interface LoginRow {
		/**
		 * Returns the user ID.
		 *
		 * @return the user ID
		 */
		UUID getId();

		/**
		 * Returns whether the row was just created.
		 *
		 * @return {@code true} on the user's first login
		 */
		Boolean getInserted();
	}
}
