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

/**
 * Native SQL for Web Push subscriptions (D-014 rules 13 and 18). Endpoints work
 * like bearer tokens, so nothing here logs them.
 *
 * @author mcawful
 */
@Repository
public class PushRepository {
	/**
	 * The {@link JdbcClient}.
	 */
	private final JdbcClient jdbc;

	/**
	 * Creates the repository.
	 *
	 * @param jdbc the {@link JdbcClient}
	 */
	public PushRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Locks a user's row until the transaction ends, so their subscribes run one at
	 * a time and the cap holds.
	 *
	 * @param userId the user's ID
	 */
	public void lockUser(UUID userId) {
		jdbc.sql("SELECT 1 FROM app_user WHERE id = :userId FOR NO KEY UPDATE").param("userId", userId)
				.query(Integer.class).optional();
	}

	/**
	 * Saves a subscription for a user. An endpoint that's already saved, for
	 * anyone, moves to this user with the new keys and a new ID, so it counts as
	 * their newest (D-014 rule 13).
	 *
	 * @param userId   the user's ID
	 * @param endpoint the push service URL
	 * @param p256dh   the browser's public key
	 * @param auth     the authentication secret
	 */
	public void upsert(UUID userId, String endpoint, String p256dh, String auth) {
		jdbc.sql(
				"""
						INSERT INTO push_subscription (user_id, endpoint, p256dh, auth) VALUES (:userId, :endpoint, :p256dh, :auth)
						ON CONFLICT (endpoint) DO UPDATE SET user_id = EXCLUDED.user_id, p256dh = EXCLUDED.p256dh,
							auth = EXCLUDED.auth, id = DEFAULT, created_at = now()""")
				.param("userId", userId).param("endpoint", endpoint).param("p256dh", p256dh).param("auth", auth)
				.update();
	}

	/**
	 * Deletes a user's oldest subscriptions beyond a cap.
	 *
	 * @param userId the user's ID
	 * @param keep   how many of the newest to keep
	 * @return how many were deleted
	 */
	public int trim(UUID userId, int keep) {
		return jdbc.sql("""
				DELETE FROM push_subscription WHERE user_id = :userId AND id NOT IN (
					SELECT id FROM push_subscription WHERE user_id = :userId ORDER BY id DESC LIMIT :keep)""")
				.param("userId", userId).param("keep", keep).update();
	}

	/**
	 * Deletes one of a user's subscriptions. Another user's is never touched.
	 *
	 * @param userId   the user's ID
	 * @param endpoint the subscription's endpoint
	 * @return how many were deleted, 0 or 1
	 */
	public int delete(UUID userId, String endpoint) {
		return jdbc.sql("DELETE FROM push_subscription WHERE user_id = :userId AND endpoint = :endpoint")
				.param("userId", userId).param("endpoint", endpoint).update();
	}

	/**
	 * Deletes every subscription a user has, for "log out everywhere" (D-014 rule
	 * 18).
	 *
	 * @param userId the user's ID
	 * @return how many were deleted
	 */
	public int deleteAll(UUID userId) {
		return jdbc.sql("DELETE FROM push_subscription WHERE user_id = :userId").param("userId", userId).update();
	}
}
