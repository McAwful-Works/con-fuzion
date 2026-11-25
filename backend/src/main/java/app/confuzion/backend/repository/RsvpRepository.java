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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import app.confuzion.backend.model.record.NotificationPayload;
import app.confuzion.backend.model.record.NotificationResponse;
import app.confuzion.backend.model.record.PersonResponse;
import app.confuzion.backend.model.record.RsvpResponse;

/**
 * Native SQL for RSVPs (plan B4, D-013). Every write that changes who holds a
 * spot runs in a transaction that first locks the event row ({@link #lock} or
 * {@link #lockForBlock}, or the {@code PATCH} lock), so writes to one event run
 * one after another and can't deadlock. The methods that write assume the
 * caller holds that lock.
 * <p>
 * A freed or added spot goes to the next person in line as an offer with a
 * deadline (D-014), or confirms them at once when the event starts within its
 * offer window. Either way the spot stays counted in {@code confirmed_count},
 * and the person's notification is written here, in the same transaction.
 *
 * @author mcawful
 */
@Repository
public class RsvpRepository {
	/**
	 * Whether RSVP {@code r} is active: confirmed, waitlisted, or an offer still
	 * inside its deadline. An offer past its deadline reads as no RSVP at once,
	 * whether or not the expiry job has ended it yet (D-014 rule 15). The deadline
	 * is compared with the statement's time, as {@link #NEXT_IN_LINE} compares the
	 * start.
	 */
	static final String ACTIVE = "(r.status IN ('CONFIRMED', 'WAITLISTED') OR (r.status = 'OFFERED' "
			+ "AND r.offer_expires_at > statement_timestamp()))";

	/**
	 * Selects the next {@code :count} waitlisted RSVPs of {@code :eventId}, in
	 * queue order, but only while the event is published and hasn't started: nobody
	 * is promoted after that (D-013 rule 2). The start is compared with the
	 * statement's time, not the transaction's, since a write may wait for locks
	 * across the start.
	 */
	private static final String NEXT_IN_LINE = """
			SELECT w.id FROM rsvp w JOIN event e ON e.id = w.event_id
			WHERE w.event_id = :eventId AND w.status = 'WAITLISTED' AND e.status = 'PUBLISHED'
				AND e.starts_at > statement_timestamp()
			ORDER BY w.id LIMIT :count""";

	/**
	 * Moves the next {@code :count} people in line on {@code :eventId} up into free
	 * spots (D-014 rule 2). Each is offered the spot until the event's window runs
	 * out, or is confirmed at once when the event is unlimited or starts within the
	 * window. Returns who moved up, for their notifications.
	 */
	private static final String PROMOTE = """
			UPDATE rsvp r SET status = CASE WHEN d.deadline IS NULL THEN 'CONFIRMED' ELSE 'OFFERED' END,
				offer_expires_at = d.deadline, updated_at = now()
			FROM (SELECT e.title, CASE WHEN e.capacity IS NOT NULL
					AND e.starts_at > statement_timestamp() + e.confirm_window_minutes * interval '1 minute'
					THEN statement_timestamp() + e.confirm_window_minutes * interval '1 minute' END AS deadline
				FROM event e WHERE e.id = :eventId) d
			WHERE r.id IN (""" + NEXT_IN_LINE + """
			)
			RETURNING r.user_id, r.offer_expires_at, d.title""";

	/**
	 * The {@link JdbcClient}.
	 */
	private final JdbcClient jdbc;

	/**
	 * The {@link NotificationRepository}, for the offers and confirmations that
	 * freed and added spots send.
	 */
	private final NotificationRepository notificationRepository;

	/**
	 * Creates the repository.
	 *
	 * @param jdbc                   the {@link JdbcClient}
	 * @param notificationRepository the {@link NotificationRepository}
	 */
	public RsvpRepository(JdbcClient jdbc, NotificationRepository notificationRepository) {
		this.jdbc = jdbc;
		this.notificationRepository = notificationRepository;
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
	 * Returns where a user stands on an event: their {@link #ACTIVE} RSVP, or
	 * {@code REMOVED} when the host removed them. A removed person can't rejoin, so
	 * their latest such row is the one that counts.
	 *
	 * @param eventId the event's internal ID
	 * @param userId  the user's ID
	 * @return the status, the 1-based position while waitlisted and the deadline
	 *         while offered; empty for {@link MyStatus#NONE}
	 */
	public Optional<Mine> findMine(long eventId, UUID userId) {
		return jdbc
				.sql("""
						SELECT r.status, r.offer_expires_at, CASE WHEN r.status = 'WAITLISTED' THEN (SELECT count(*) + 1
							FROM rsvp w WHERE w.event_id = r.event_id AND w.status = 'WAITLISTED' AND w.id < r.id)::int
							END AS position
						FROM rsvp r
						WHERE r.event_id = :eventId AND r.user_id = :userId AND (r.status = 'REMOVED' OR\s""" + ACTIVE
						+ ") ORDER BY r.id DESC LIMIT 1")
				.param("eventId", eventId).param("userId", userId)
				.query((rs, row) -> new Mine(MyStatus.valueOf(rs.getString("status")),
						rs.getObject("position", Integer.class), offerExpiresAt(rs)))
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
				SELECT DISTINCT ON (r.event_id) r.event_id, r.status FROM rsvp r
				WHERE r.user_id = :userId AND r.event_id IN (:eventIds) AND (r.status = 'REMOVED' OR\s""" + ACTIVE
				+ ") ORDER BY r.event_id, r.id DESC").param("userId", userId).param("eventIds", eventIds)
				.query((RowCallbackHandler) rs -> statuses.put(rs.getLong("event_id"),
						MyStatus.valueOf(rs.getString("status"))));
		return statuses;
	}

	/**
	 * Ends a user's active RSVP on an event, as {@code LEFT} or {@code REMOVED}. An
	 * offer past its deadline isn't active: it is left for {@link #expireDue}.
	 *
	 * @param eventId the event's internal ID
	 * @param userId  the user's ID
	 * @param status  {@code LEFT} or {@code REMOVED}
	 * @return the status the RSVP had, or empty when there was no active RSVP and
	 *         nothing was written
	 */
	public Optional<MyStatus> end(long eventId, UUID userId, String status) {
		return jdbc
				.sql("UPDATE rsvp u SET status = :status, offer_expires_at = NULL, updated_at = now() FROM (SELECT "
						+ "r.id, r.status FROM rsvp r WHERE r.event_id = :eventId AND r.user_id = :userId AND " + ACTIVE
						+ ") old WHERE u.id = old.id RETURNING old.status")
				.param("status", status).param("eventId", eventId).param("userId", userId)
				.query((rs, row) -> MyStatus.valueOf(rs.getString(1))).optional();
	}

	/**
	 * Frees a held spot, confirmed or offered: the next person in line is offered
	 * it, or confirmed at once near the start ({@link #PROMOTE}), so the count
	 * stays. When nobody is waiting, or the event has started or is cancelled, the
	 * count goes down instead.
	 *
	 * @param eventId the event's internal ID
	 */
	public void freeSpot(long eventId) {
		freeSpots(eventId, 1);
	}

	/**
	 * Moves waitlisted people in queue order up into the free spots, after any
	 * saved edit: a raised capacity, or a start moved back into the future, which
	 * reopens sign-up. Each is offered a spot, or confirmed at once near the start
	 * ({@link #PROMOTE}). Making the capacity unlimited confirms everyone
	 * waitlisted and every open offer instead (D-014 rule 17). Nobody moves up once
	 * the event has started.
	 *
	 * @param eventId   the event's internal ID
	 * @param freeSpots how many spots are free, {@link Integer#MAX_VALUE} for an
	 *                  unlimited event
	 */
	public void fillFreeSpots(long eventId, int freeSpots) {
		notifyPromoted(eventId,
				jdbc.sql("WITH promoted AS (" + PROMOTE + "), taken AS (UPDATE event "
						+ "SET confirmed_count = confirmed_count + (SELECT count(*) FROM promoted) WHERE id = :eventId "
						+ "AND EXISTS (SELECT 1 FROM promoted)) SELECT * FROM promoted").param("eventId", eventId)
						.param("count", freeSpots).query(RsvpRepository::promoted).list());
		// The spots these offers hold are already counted.
		notifyPromoted(eventId, jdbc.sql("""
				UPDATE rsvp r SET status = 'CONFIRMED', offer_expires_at = NULL, updated_at = now()
				FROM event e WHERE e.id = r.event_id AND r.event_id = :eventId AND r.status = 'OFFERED'
					AND r.offer_expires_at > statement_timestamp() AND e.capacity IS NULL AND e.status = 'PUBLISHED'
					AND e.starts_at > statement_timestamp()
				RETURNING r.user_id, r.offer_expires_at, e.title""").param("eventId", eventId)
				.query(RsvpRepository::promoted).list());
	}

	/**
	 * Ends the offers on an event that are past their deadline as {@code EXPIRED},
	 * and frees their spots as {@link #freeSpot} does. With {@code userId}, only
	 * that person's: a join by someone whose offer ran out ends it first (D-014
	 * rule 15).
	 *
	 * @param eventId the event's internal ID
	 * @param userId  the person whose offer to end, or {@code null} for every offer
	 *                past its deadline
	 * @return how many offers ended
	 */
	public int expireDue(long eventId, UUID userId) {
		var expired = jdbc.sql("""
				WITH expired AS (UPDATE rsvp SET status = 'EXPIRED', offer_expires_at = NULL, updated_at = now()
					WHERE event_id = :eventId AND status = 'OFFERED' AND offer_expires_at <= statement_timestamp()
						AND (CAST(:userId AS uuid) IS NULL OR user_id = :userId)
					RETURNING id)
				SELECT count(*) FROM expired""").param("eventId", eventId).param("userId", userId).query(Integer.class)
				.single();
		if (expired > 0)
			freeSpots(eventId, expired);
		return expired;
	}

	/**
	 * Returns up to {@code limit} events that have an offer past its deadline, for
	 * the expiry job, without locking them.
	 *
	 * @param limit the most events to return
	 * @return the events' internal IDs, in ID order
	 */
	public List<Long> findDueEvents(int limit) {
		return jdbc
				.sql("""
						SELECT DISTINCT event_id FROM rsvp WHERE status = 'OFFERED' AND offer_expires_at <= statement_timestamp()
						ORDER BY event_id LIMIT :limit""")
				.param("limit", limit).query(Long.class).list();
	}

	/**
	 * Locks an event row as {@link #lock} does, unless another transaction holds
	 * it: {@code SKIP LOCKED} lets every task run the expiry job without waiting on
	 * a request or on each other.
	 *
	 * @param eventId the event's internal ID
	 * @return whether this transaction now holds the lock
	 */
	public boolean tryLock(long eventId) {
		return jdbc.sql("SELECT id FROM event WHERE id = :eventId FOR NO KEY UPDATE SKIP LOCKED")
				.param("eventId", eventId).query(Long.class).optional().isPresent();
	}

	/**
	 * Returns a user's latest RSVP status on an event, for an accept (D-014 rule
	 * 4). An offer past its deadline reads as {@code EXPIRED}.
	 *
	 * @param eventId the event's internal ID
	 * @param userId  the user's ID
	 * @return the status, or empty when they never joined
	 */
	public Optional<String> findLatestStatus(long eventId, UUID userId) {
		return jdbc.sql("""
				SELECT CASE WHEN status = 'OFFERED' AND offer_expires_at <= statement_timestamp() THEN 'EXPIRED'
					ELSE status END
				FROM rsvp WHERE event_id = :eventId AND user_id = :userId ORDER BY id DESC LIMIT 1""")
				.param("eventId", eventId).param("userId", userId).query(String.class).optional();
	}

	/**
	 * Accepts a user's open offer: it becomes {@code CONFIRMED}, and the spot it
	 * held stays counted. The deadline is checked with the statement's time.
	 *
	 * @param eventId the event's internal ID
	 * @param userId  the user's ID
	 * @return whether an open offer was accepted
	 */
	public boolean acceptOffer(long eventId, UUID userId) {
		return jdbc.sql("""
				UPDATE rsvp SET status = 'CONFIRMED', offer_expires_at = NULL, updated_at = now()
				WHERE event_id = :eventId AND user_id = :userId AND status = 'OFFERED'
					AND offer_expires_at > statement_timestamp()""").param("eventId", eventId).param("userId", userId)
				.update() > 0;
	}

	/**
	 * Frees {@code count} held spots: the next people in line move up
	 * ({@link #PROMOTE}), and the count goes down by the spots nobody took.
	 *
	 * @param eventId the event's internal ID
	 * @param count   how many spots were freed
	 */
	private void freeSpots(long eventId, int count) {
		notifyPromoted(eventId,
				jdbc.sql("WITH promoted AS (" + PROMOTE + "), freed AS (UPDATE event "
						+ "SET confirmed_count = confirmed_count - (:count - (SELECT count(*) FROM promoted)) "
						+ "WHERE id = :eventId AND :count > (SELECT count(*) FROM promoted)) SELECT * FROM promoted")
						.param("eventId", eventId).param("count", count).query(RsvpRepository::promoted).list());
	}

	/**
	 * Tells each person who moved up (D-014 rule 8): {@code SPOT_OFFERED} with the
	 * deadline, or {@code SPOT_CONFIRMED}. A payload never holds a location or a
	 * name.
	 *
	 * @param eventId  the event's internal ID
	 * @param promoted who moved up
	 */
	private void notifyPromoted(long eventId, List<Promoted> promoted) {
		for (var person : promoted)
			if (person.offerExpiresAt() != null)
				notificationRepository.insert(person.userId(), NotificationResponse.Type.SPOT_OFFERED, eventId,
						new NotificationPayload(person.eventTitle(), person.offerExpiresAt(), null, null, null));
			else
				notificationRepository.insert(person.userId(), NotificationResponse.Type.SPOT_CONFIRMED, eventId,
						new NotificationPayload(person.eventTitle(), null, null, null, null));
	}

	/**
	 * Maps a row of who moved up.
	 *
	 * @param rs  the row
	 * @param row the row number
	 * @return the {@link Promoted}
	 * @throws SQLException when a column can't be read
	 */
	private static Promoted promoted(ResultSet rs, int row) throws SQLException {
		return new Promoted(rs.getObject("user_id", UUID.class), offerExpiresAt(rs), rs.getString("title"));
	}

	/**
	 * Reads a row's {@code offer_expires_at}.
	 *
	 * @param rs the row
	 * @return the deadline, or {@code null}
	 * @throws SQLException when the column can't be read
	 */
	private static Instant offerExpiresAt(ResultSet rs) throws SQLException {
		var deadline = rs.getObject("offer_expires_at", OffsetDateTime.class);
		return deadline == null ? null : deadline.toInstant();
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
					OR EXISTS (SELECT 1 FROM rsvp r WHERE r.event_id = e.id AND\s""" + ACTIVE + """
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
	 * anyone in a block with the viewer either way, and offers past their deadline.
	 *
	 * @param eventId the event's internal ID
	 * @param status  {@code CONFIRMED}, {@code WAITLISTED} or {@code OFFERED}
	 * @param me      the viewer's user ID
	 * @return the people
	 */
	public List<PersonResponse> findPeople(long eventId, String status, UUID me) {
		return jdbc
				.sql("SELECT " + PERSON_COLUMNS + " FROM rsvp r JOIN app_user u ON u.id = r.user_id "
						+ "WHERE r.event_id = :eventId AND r.status = :status AND " + ACTIVE + " AND " + NOT_BLOCKED
						+ " ORDER BY r.id")
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
	 * @param status         {@code CONFIRMED}, {@code WAITLISTED}, {@code OFFERED}
	 *                       or {@code REMOVED}
	 * @param position       the 1-based waitlist position, or {@code null} unless
	 *                       waitlisted
	 * @param offerExpiresAt the offer's deadline, or {@code null} unless offered
	 * @author mcawful
	 */
	public record Mine(MyStatus status, Integer position, Instant offerExpiresAt) {
	}

	/**
	 * Someone who moved up into a freed or added spot.
	 *
	 * @param userId         their user ID
	 * @param offerExpiresAt their offer's deadline, or {@code null} when they were
	 *                       confirmed at once
	 * @param eventTitle     the event's title, for their notification
	 * @author mcawful
	 */
	private record Promoted(UUID userId, Instant offerExpiresAt, String eventTitle) {
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
