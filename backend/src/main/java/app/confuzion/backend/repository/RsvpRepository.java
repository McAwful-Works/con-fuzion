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

import static app.confuzion.backend.repository.SocialRepository.NOT_BLOCKED;
import static app.confuzion.backend.repository.SocialRepository.PERSON;
import static app.confuzion.backend.repository.SocialRepository.PERSON_COLUMNS;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import app.confuzion.backend.model.record.MyStatus;
import app.confuzion.backend.model.record.PersonResponse;
import app.confuzion.backend.model.record.RsvpResponse;

/**
 * Native SQL for RSVPs (plan B4, D-013). Every write that changes who holds a
 * spot runs in a transaction that first locks the event row ({@link #lock} or
 * {@link #lockForBlock}, or the {@code PATCH} lock), so writes to one event run
 * one after another and can't deadlock. The methods that write assume the
 * caller holds that lock.
 *
 * @author mcawful
 */
@Repository
public class RsvpRepository {
	/**
	 * The statuses that hold a spot or a place in the waitlist.
	 */
	private static final String ACTIVE = "('CONFIRMED', 'WAITLISTED')";

	/**
	 * Selects waitlisted RSVP IDs of {@code :eventId} in queue order, but only
	 * while the event is published and hasn't started: nobody is promoted after
	 * that (D-013 rule 2). Ends with {@code LIMIT}, so callers append the count.
	 */
	private static final String NEXT_IN_LINE = """
			SELECT w.id FROM rsvp w JOIN event e ON e.id = w.event_id
			WHERE w.event_id = :eventId AND w.status = 'WAITLISTED' AND e.status = 'PUBLISHED' AND e.starts_at > now()
			ORDER BY w.id LIMIT\s""";

	/**
	 * The {@link JdbcClient}.
	 */
	private final JdbcClient jdbc;

	/**
	 * Creates the repository.
	 *
	 * @param jdbc the {@link JdbcClient}
	 */
	public RsvpRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Locks an event row until the transaction ends and reads it as it stands after
	 * any write the lock waited for. {@code NO KEY UPDATE} conflicts with itself
	 * but not with the key-share locks that inserting an RSVP takes.
	 *
	 * @param eventId the event's internal ID
	 * @return the event's status and counts
	 */
	public LockedEvent lock(long eventId) {
		return jdbc.sql("""
				SELECT host_id, status, capacity, confirmed_count FROM event WHERE id = :eventId FOR NO KEY UPDATE""")
				.param("eventId", eventId).query((rs, row) -> new LockedEvent(rs.getObject("host_id", UUID.class),
						rs.getString("status"), rs.getObject("capacity", Integer.class), rs.getInt("confirmed_count")))
				.single();
	}

	/**
	 * Adds a join's RSVP and, when it is confirmed, takes the spot, in one
	 * statement under the event row lock. Nothing is written when either person has
	 * blocked the other (a block that committed while the claim waited for the
	 * lock), when the host removed the user (a double tap's twin, removed after
	 * this join's checks ran), or when the user already holds an active RSVP
	 * ({@code rsvp_active_key} absorbs a double tap).
	 *
	 * @param eventId   the event's internal ID
	 * @param hostId    the event's host
	 * @param userId    the joining user's ID
	 * @param confirmed whether a spot is free, as {@link #lock} read it
	 * @return the new RSVP's ID, or empty when nothing was written
	 */
	public Optional<Long> insert(long eventId, UUID hostId, UUID userId, boolean confirmed) {
		return jdbc.sql("""
				WITH ins AS (
					INSERT INTO rsvp (event_id, user_id, status)
					SELECT :eventId, :userId, :status
					WHERE NOT EXISTS (SELECT 1 FROM block b WHERE (b.blocker_id = :hostId AND b.blocked_id = :userId)
						OR (b.blocker_id = :userId AND b.blocked_id = :hostId))
					AND NOT EXISTS (SELECT 1 FROM rsvp r WHERE r.event_id = :eventId AND r.user_id = :userId
						AND r.status = 'REMOVED')
					ON CONFLICT (event_id, user_id) WHERE status IN ('CONFIRMED', 'WAITLISTED') DO NOTHING
					RETURNING id, status),
				claim AS (
					UPDATE event SET confirmed_count = confirmed_count + 1
					WHERE id = :eventId AND EXISTS (SELECT 1 FROM ins WHERE status = 'CONFIRMED'))
				SELECT id FROM ins""").param("eventId", eventId).param("userId", userId).param("hostId", hostId)
				.param("status", (confirmed ? RsvpResponse.Status.CONFIRMED : RsvpResponse.Status.WAITLISTED).name())
				.query(Long.class).optional();
	}

	/**
	 * Returns where a user stands on an event: their active RSVP, or
	 * {@code REMOVED} when the host removed them. A removed person can't rejoin, so
	 * their latest such row is the one that counts.
	 *
	 * @param eventId the event's internal ID
	 * @param userId  the user's ID
	 * @return the status and, while waitlisted, the 1-based position; empty for
	 *         {@link MyStatus#NONE}
	 */
	public Optional<Mine> findMine(long eventId, UUID userId) {
		return jdbc
				.sql("""
						SELECT r.status, CASE WHEN r.status = 'WAITLISTED' THEN (SELECT count(*) + 1 FROM rsvp w
							WHERE w.event_id = r.event_id AND w.status = 'WAITLISTED' AND w.id < r.id)::int END AS position
						FROM rsvp r
						WHERE r.event_id = :eventId AND r.user_id = :userId AND r.status IN ('CONFIRMED', 'WAITLISTED', 'REMOVED')
						ORDER BY r.id DESC LIMIT 1""")
				.param("eventId", eventId).param("userId", userId)
				.query((rs, row) -> new Mine(MyStatus.valueOf(rs.getString("status")),
						rs.getObject("position", Integer.class)))
				.optional();
	}

	/**
	 * Returns where a user stands on each of several events, as {@link #findMine}
	 * does without the position.
	 *
	 * @param userId   the user's ID
	 * @param eventIds the events' internal IDs
	 * @return the status by event ID; events where they stand nowhere are missing
	 */
	public Map<Long, MyStatus> findStatuses(UUID userId, Collection<Long> eventIds) {
		var statuses = new HashMap<Long, MyStatus>();
		if (eventIds.isEmpty())
			return statuses;
		jdbc.sql("""
				SELECT DISTINCT ON (event_id) event_id, status FROM rsvp
				WHERE user_id = :userId AND event_id IN (:eventIds) AND status IN ('CONFIRMED', 'WAITLISTED', 'REMOVED')
				ORDER BY event_id, id DESC""").param("userId", userId).param("eventIds", eventIds)
				.query((RowCallbackHandler) rs -> statuses.put(rs.getLong("event_id"),
						MyStatus.valueOf(rs.getString("status"))));
		return statuses;
	}

	/**
	 * Ends a user's active RSVP on an event, as {@code LEFT} or {@code REMOVED}.
	 *
	 * @param eventId the event's internal ID
	 * @param userId  the user's ID
	 * @param status  {@code LEFT} or {@code REMOVED}
	 * @return the status the RSVP had, or empty when there was no active RSVP and
	 *         nothing was written
	 */
	public Optional<MyStatus> end(long eventId, UUID userId, String status) {
		return jdbc
				.sql("UPDATE rsvp r SET status = :status, updated_at = now() FROM (SELECT id, status FROM rsvp "
						+ "WHERE event_id = :eventId AND user_id = :userId AND status IN " + ACTIVE + ") old "
						+ "WHERE r.id = old.id RETURNING old.status")
				.param("status", status).param("eventId", eventId).param("userId", userId)
				.query((rs, row) -> MyStatus.valueOf(rs.getString(1))).optional();
	}

	/**
	 * Frees a confirmed spot: the next person in line takes it, confirmed at once
	 * (D-013; M4 turns this into an offer), so the count stays. When nobody is
	 * waiting, or the event has started or is cancelled, the count goes down
	 * instead.
	 *
	 * @param eventId the event's internal ID
	 */
	public void freeSpot(long eventId) {
		jdbc.sql("WITH next AS (UPDATE rsvp SET status = 'CONFIRMED', updated_at = now() WHERE id = (" + NEXT_IN_LINE
				+ "1) RETURNING id) UPDATE event SET confirmed_count = confirmed_count - 1 "
				+ "WHERE id = :eventId AND NOT EXISTS (SELECT 1 FROM next)").param("eventId", eventId).update();
	}

	/**
	 * Promotes waitlisted people in queue order into the free spots, after a
	 * capacity edit (D-013 rule 4). Nobody is promoted once the event has started.
	 *
	 * @param eventId   the event's internal ID
	 * @param freeSpots how many spots are free, {@link Integer#MAX_VALUE} for an
	 *                  unlimited event
	 */
	public void fillFreeSpots(long eventId, int freeSpots) {
		jdbc.sql("WITH promoted AS (UPDATE rsvp SET status = 'CONFIRMED', updated_at = now() WHERE id IN ("
				+ NEXT_IN_LINE + ":freeSpots) RETURNING id) UPDATE event SET confirmed_count = confirmed_count + "
				+ "(SELECT count(*) FROM promoted) WHERE id = :eventId").param("eventId", eventId)
				.param("freeSpots", freeSpots).update();
	}

	/**
	 * Locks, in ID order, every event of either user that a block must clean up:
	 * those where the other holds an active RSVP, and those the other could still
	 * be joining. A join takes the same lock and checks for a block under it, so
	 * once this returns no join on these events can slip past the block. Joining
	 * closes at the start, and the hour's margin covers any join still finishing.
	 *
	 * @param a a user ID
	 * @param b the other user ID
	 * @return the events' internal IDs and hosts
	 */
	public List<BlockedEvent> lockForBlock(UUID a, UUID b) {
		return jdbc.sql("""
				SELECT e.id, e.host_id FROM event e
				WHERE e.host_id IN (:a, :b) AND ((e.status = 'PUBLISHED' AND e.starts_at > now() - interval '1 hour')
					OR EXISTS (SELECT 1 FROM rsvp r WHERE r.event_id = e.id AND r.status IN ('CONFIRMED', 'WAITLISTED')
						AND r.user_id = CASE WHEN e.host_id = :a THEN CAST(:b AS uuid) ELSE CAST(:a AS uuid) END))
				ORDER BY e.id FOR NO KEY UPDATE OF e""").param("a", a).param("b", b)
				.query((rs, row) -> new BlockedEvent(rs.getLong("id"), rs.getObject("host_id", UUID.class))).list();
	}

	/**
	 * Returns an event's shared spot counts.
	 *
	 * @param eventId the event's internal ID
	 * @return the {@link Counts}
	 */
	public Counts findCounts(long eventId) {
		return jdbc.sql("""
				SELECT e.capacity, e.confirmed_count,
					(SELECT count(*) FROM rsvp w WHERE w.event_id = e.id AND w.status = 'WAITLISTED') AS waitlisted
				FROM event e WHERE e.id = :eventId""").param("eventId", eventId)
				.query((rs, row) -> new Counts(rs.getObject("capacity", Integer.class), rs.getInt("confirmed_count"),
						rs.getInt("waitlisted")))
				.single();
	}

	/**
	 * Returns the people with one status on an event, in queue order, leaving out
	 * anyone in a block with the viewer either way.
	 *
	 * @param eventId the event's internal ID
	 * @param status  {@code CONFIRMED} or {@code WAITLISTED}
	 * @param me      the viewer's user ID
	 * @return the people
	 */
	public List<PersonResponse> findPeople(long eventId, String status, UUID me) {
		return jdbc
				.sql("SELECT " + PERSON_COLUMNS + " FROM rsvp r JOIN app_user u ON u.id = r.user_id "
						+ "WHERE r.event_id = :eventId AND r.status = :status AND " + NOT_BLOCKED + " ORDER BY r.id")
				.param("eventId", eventId).param("status", status).param("me", me).query(PERSON).list();
	}

	/**
	 * An event row as {@link #lock} read it.
	 *
	 * @param hostId         the host's user ID
	 * @param status         {@code PUBLISHED} or {@code CANCELLED}
	 * @param capacity       the headcount, or {@code null} for unlimited
	 * @param confirmedCount how many people hold a spot
	 * @author mcawful
	 */
	public record LockedEvent(UUID hostId, String status, Integer capacity, int confirmedCount) {
		/**
		 * Returns whether a spot is free.
		 *
		 * @return {@code true} when the event is unlimited or not full
		 */
		public boolean hasFreeSpot() {
			return capacity == null || confirmedCount < capacity;
		}
	}

	/**
	 * An event that {@link #lockForBlock} locked.
	 *
	 * @param id     the event's internal ID
	 * @param hostId the host's user ID
	 * @author mcawful
	 */
	public record BlockedEvent(long id, UUID hostId) {
	}

	/**
	 * Where a user stands on an event.
	 *
	 * @param status   {@code CONFIRMED}, {@code WAITLISTED} or {@code REMOVED}
	 * @param position the 1-based waitlist position, or {@code null} unless
	 *                 waitlisted
	 * @author mcawful
	 */
	public record Mine(MyStatus status, Integer position) {
	}

	/**
	 * An event's spot counts, the same for everyone who can see it.
	 *
	 * @param capacity   the headcount, or {@code null} for unlimited
	 * @param confirmed  how many people hold a spot, the host included
	 * @param waitlisted how many people are waitlisted
	 * @author mcawful
	 */
	public record Counts(Integer capacity, int confirmed, int waitlisted) {
	}
}
