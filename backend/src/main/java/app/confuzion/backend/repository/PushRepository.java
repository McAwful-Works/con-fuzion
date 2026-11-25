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

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import app.confuzion.backend.model.record.NotificationPayload;
import app.confuzion.backend.model.record.NotificationResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Native SQL for Web Push (D-014): browser subscriptions, and the sender's
 * queue of {@code PENDING} notifications. Endpoints work like bearer tokens, so
 * nothing here logs them.
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
	 * The {@link JsonMapper}, to read payloads.
	 */
	private final JsonMapper jsonMapper;

	/**
	 * Creates the repository.
	 *
	 * @param jdbc       the {@link JdbcClient}
	 * @param jsonMapper the {@link JsonMapper}
	 */
	public PushRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
		this.jdbc = jdbc;
		this.jsonMapper = jsonMapper;
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

	/**
	 * Deletes subscriptions by ID, for those the push service says are gone. One
	 * that was subscribed again since has a new ID, and stays.
	 *
	 * @param ids the subscription IDs
	 * @return how many were deleted
	 */
	public int deleteByIds(Collection<Long> ids) {
		if (ids.isEmpty())
			return 0;
		return jdbc.sql("DELETE FROM push_subscription WHERE id IN (:ids)").param("ids", ids).update();
	}

	/**
	 * Claims the oldest {@code PENDING} notifications after an ID, in one statement
	 * that marks them {@code SENT} as it returns them. Rows another sender is
	 * claiming are skipped ({@code SKIP LOCKED}) and the claim commits at once, so
	 * several senders never claim the same row, and no lock is held while the
	 * pushes go out. The sender then marks the ones that weren't sent. A row whose
	 * type or payload this version can't read comes back with a {@code null} type,
	 * so one bad row can't stop the queue.
	 *
	 * @param afterId only rows with a higher ID, so a run can walk past rows it put
	 *                back
	 * @param limit   the most to claim
	 * @return the claimed notifications, oldest first
	 */
	public List<Pending> claimPending(long afterId, int limit) {
		var rows = jdbc.sql("""
				WITH claimed AS (
					SELECT id FROM notification WHERE push_status = 'PENDING' AND id > :afterId
					ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED)
				UPDATE notification n SET push_status = 'SENT' FROM claimed c WHERE n.id = c.id
				RETURNING n.id, n.user_id, n.type, n.payload,
					(SELECT e.public_id FROM event e WHERE e.id = n.event_id) AS public_id,
					(SELECT u.push_enabled FROM app_user u WHERE u.id = n.user_id) AS push_enabled""")
				.param("afterId", afterId).param("limit", limit).query((rs, row) -> {
					NotificationResponse.Type type = null;
					NotificationPayload payload = null;
					try {
						type = NotificationResponse.Type.valueOf(rs.getString("type"));
						payload = jsonMapper.readValue(rs.getString("payload"), NotificationPayload.class);
					} catch (RuntimeException unreadable) {
						type = null;
					}
					return new Pending(rs.getLong("id"), rs.getObject("user_id", UUID.class), type,
							rs.getObject("public_id", UUID.class), payload, rs.getBoolean("push_enabled"));
				}).list();
		return rows.stream().sorted(Comparator.comparingLong(Pending::id)).toList();
	}

	/**
	 * Returns the subscriptions of some users.
	 *
	 * @param userIds the users' IDs
	 * @return their subscriptions
	 */
	public List<Subscription> findSubscriptions(Collection<UUID> userIds) {
		if (userIds.isEmpty())
			return List.of();
		return jdbc.sql("SELECT id, user_id, endpoint, p256dh, auth FROM push_subscription WHERE user_id IN (:userIds)")
				.param("userIds", userIds)
				.query((rs, row) -> new Subscription(rs.getLong("id"), rs.getObject("user_id", UUID.class),
						rs.getString("endpoint"), rs.getString("p256dh"), rs.getString("auth")))
				.list();
	}

	/**
	 * Sets the {@code push_status} of some notifications.
	 *
	 * @param ids    the notification IDs
	 * @param status {@code FAILED}, or {@code PENDING} to put a row back
	 * @return how many were updated
	 */
	public int markPushStatus(Collection<Long> ids, String status) {
		if (ids.isEmpty())
			return 0;
		return jdbc.sql("UPDATE notification SET push_status = :status WHERE id IN (:ids)").param("status", status)
				.param("ids", ids).update();
	}

	/**
	 * A notification claimed for pushing.
	 *
	 * @param id          the notification's ID
	 * @param userId      the recipient's ID
	 * @param type        what happened, or {@code null} when the row can't be read
	 * @param eventId     the event's public ID, or {@code null} for
	 *                    {@code FRIEND_ACCEPTED}
	 * @param payload     what the notification says, or {@code null} when the row
	 *                    can't be read
	 * @param pushEnabled whether the recipient still has push on
	 * @author mcawful
	 */
	public record Pending(long id, UUID userId, NotificationResponse.Type type, UUID eventId,
			NotificationPayload payload, boolean pushEnabled) {
	}

	/**
	 * A browser's push subscription. {@link #toString()} leaves the endpoint and
	 * keys out, so logging one never leaks them.
	 *
	 * @param id       the subscription's ID
	 * @param userId   the owner's ID
	 * @param endpoint the push service URL, which works like a bearer token
	 * @param p256dh   the browser's public key
	 * @param auth     the authentication secret
	 * @author mcawful
	 */
	public record Subscription(long id, UUID userId, String endpoint, String p256dh, String auth) {
		/**
		 * Returns the ID only.
		 *
		 * @return the text
		 */
		@Override
		public String toString() {
			return "Subscription[id=" + id + "]";
		}
	}
}
