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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import app.confuzion.backend.model.entity.Event;
import jakarta.persistence.LockModeType;

/**
 * The {@link Event} repository. Every read for someone other than the host goes
 * through {@link #VISIBLE}, the one visibility rule (D-012).
 *
 * @author mcawful
 */
public interface EventRepository extends JpaRepository<Event, Long> {
	/**
	 * Whether the viewer {@code :viewer} may see event {@code e}: the one
	 * visibility predicate that the detail view, the feed, My schedule and RSVP
	 * share. The host always sees their own events. Anyone else needs no block
	 * between them and the host, either way, and then either an active
	 * ({@code CONFIRMED} or {@code WAITLISTED}) RSVP, which keeps a cancelled event
	 * visible too (D-013 rule 10), or a published event with an audience entry that
	 * matches them: a {@code USER} entry naming them, a {@code FRIENDS} entry while
	 * they are the host's friend, a {@code CIRCLE} entry for one of the host's
	 * circles they are in, or a {@code GUILD} entry for a server where both of them
	 * are discoverable now.
	 */
	String VISIBLE = """
			(e.host_id = :viewer OR (NOT EXISTS (SELECT 1 FROM block b
					WHERE (b.blocker_id = e.host_id AND b.blocked_id = :viewer)
						OR (b.blocker_id = :viewer AND b.blocked_id = e.host_id))
				AND (EXISTS (SELECT 1 FROM rsvp r WHERE r.event_id = e.id AND r.user_id = :viewer
						AND r.status IN ('CONFIRMED', 'WAITLISTED'))
				OR e.status = 'PUBLISHED' AND EXISTS (SELECT 1 FROM event_audience a WHERE a.event_id = e.id AND (
					(a.type = 'USER' AND a.user_id = :viewer)
					OR (a.type = 'FRIENDS' AND EXISTS (SELECT 1 FROM friendship f
						WHERE f.user_a = LEAST(e.host_id, CAST(:viewer AS uuid))
							AND f.user_b = GREATEST(e.host_id, CAST(:viewer AS uuid))))
					OR (a.type = 'CIRCLE' AND EXISTS (SELECT 1 FROM circle c
						JOIN circle_member cm ON cm.circle_id = c.id AND cm.user_id = :viewer
						WHERE c.id = a.circle_id AND c.owner_id = e.host_id))
					OR (a.type = 'GUILD' AND EXISTS (SELECT 1 FROM user_guild vg
						JOIN user_guild hg ON hg.guild_id = vg.guild_id AND hg.user_id = e.host_id AND hg.discoverable
						WHERE vg.guild_id = a.guild_id AND vg.user_id = :viewer AND vg.discoverable)))))))
			""";

	/**
	 * Upcoming: the end, or the start plus 3 hours when there is none, hasn't
	 * passed. The first condition follows from the 7-day length limit and lets the
	 * query use {@code event_starts_at_idx} or {@code event_host_starts_at_idx}.
	 */
	String UPCOMING = """
			e.starts_at > now() - interval '7 days'
			AND coalesce(e.ends_at, e.starts_at + interval '3 hours') > now()
			""";

	/**
	 * After the cursor's position in {@code (starts_at, public_id)} order.
	 */
	String AFTER_CURSOR = """
			(e.starts_at, e.public_id) > (CAST(:afterStartsAt AS timestamptz), CAST(:afterId AS uuid))
			""";

	/**
	 * Returns an event if the viewer may see it.
	 *
	 * @param publicId the event's public ID
	 * @param viewer   the viewer's user ID
	 * @return the event, or empty when it doesn't exist or the viewer can't see it
	 */
	@Query(value = "SELECT e.* FROM event e WHERE e.public_id = :publicId AND " + VISIBLE, nativeQuery = true)
	Optional<Event> findVisible(@Param("publicId") UUID publicId, @Param("viewer") UUID viewer);

	/**
	 * Returns a page of the viewer's feed: upcoming published events they may see,
	 * their own included. The candidates come from the viewer's audience keys (plan
	 * B2): entries naming them, entries for circles they are in or servers where
	 * they are discoverable, events where they hold an active RSVP, and their own
	 * and their friends' recent events. {@link #VISIBLE} then decides.
	 *
	 * @param viewer        the viewer's user ID
	 * @param afterStartsAt the cursor's start time
	 * @param afterId       the cursor's event public ID
	 * @param limit         the most events to return
	 * @return the events, in {@code (starts_at, public_id)} order
	 */
	@Query(value = """
			SELECT e.* FROM event e
			WHERE e.id IN (
					SELECT a.event_id FROM event_audience a WHERE a.user_id = :viewer
					UNION ALL
					SELECT r.event_id FROM rsvp r WHERE r.user_id = :viewer AND r.status IN ('CONFIRMED', 'WAITLISTED')
					UNION ALL
					SELECT a.event_id FROM circle_member cm JOIN event_audience a ON a.circle_id = cm.circle_id
						WHERE cm.user_id = :viewer
					UNION ALL
					SELECT a.event_id FROM user_guild g JOIN event_audience a ON a.guild_id = g.guild_id
						WHERE g.user_id = :viewer AND g.discoverable
					UNION ALL
					SELECT h.id FROM event h WHERE h.host_id = :viewer AND h.starts_at > now() - interval '7 days'
					UNION ALL
					SELECT h.id FROM friendship f JOIN event h ON h.host_id = f.user_b
						WHERE f.user_a = :viewer AND h.starts_at > now() - interval '7 days'
					UNION ALL
					SELECT h.id FROM friendship f JOIN event h ON h.host_id = f.user_a
						WHERE f.user_b = :viewer AND h.starts_at > now() - interval '7 days')
				AND e.status = 'PUBLISHED'
				AND\s""" + UPCOMING + " AND " + AFTER_CURSOR + " AND " + VISIBLE + """
			ORDER BY e.starts_at, e.public_id
			LIMIT :limit
			""", nativeQuery = true)
	List<Event> findFeed(@Param("viewer") UUID viewer, @Param("afterStartsAt") Instant afterStartsAt,
			@Param("afterId") UUID afterId, @Param("limit") int limit);

	/**
	 * Returns a page of the upcoming events a user hosts, cancelled ones included.
	 *
	 * @param hostId        the host's user ID
	 * @param afterStartsAt the cursor's start time
	 * @param afterId       the cursor's event public ID
	 * @param limit         the most events to return
	 * @return the events, in {@code (starts_at, public_id)} order
	 */
	@Query(value = "SELECT e.* FROM event e WHERE e.host_id = :hostId AND " + UPCOMING + " AND " + AFTER_CURSOR
			+ " ORDER BY e.starts_at, e.public_id LIMIT :limit", nativeQuery = true)
	List<Event> findHosting(@Param("hostId") UUID hostId, @Param("afterStartsAt") Instant afterStartsAt,
			@Param("afterId") UUID afterId, @Param("limit") int limit);

	/**
	 * Returns a page of My schedule (D-013 rule 12): upcoming events where the
	 * viewer is confirmed or waitlisted, their own included, and cancelled ones
	 * while they still hold an RSVP.
	 *
	 * @param viewer        the viewer's user ID
	 * @param afterStartsAt the cursor's start time
	 * @param afterId       the cursor's event public ID
	 * @param limit         the most events to return
	 * @return the events, in {@code (starts_at, public_id)} order
	 */
	@Query(value = """
			SELECT e.* FROM event e
			WHERE e.id IN (SELECT r.event_id FROM rsvp r
					WHERE r.user_id = :viewer AND r.status IN ('CONFIRMED', 'WAITLISTED'))
				AND\s""" + UPCOMING + " AND " + AFTER_CURSOR + " AND " + VISIBLE + """
			ORDER BY e.starts_at, e.public_id
			LIMIT :limit
			""", nativeQuery = true)
	List<Event> findAttending(@Param("viewer") UUID viewer, @Param("afterStartsAt") Instant afterStartsAt,
			@Param("afterId") UUID afterId, @Param("limit") int limit);

	/**
	 * Returns an event the user hosts, locked until the transaction ends so that an
	 * edit and a cancel can't interleave.
	 *
	 * @param publicId the event's public ID
	 * @param hostId   the user ID that must be the host
	 * @return the event, or empty when it doesn't exist or isn't theirs
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Event> findByPublicIdAndHostId(UUID publicId, UUID hostId);

	/**
	 * Cancels an event the user hosts. Cancelling a cancelled event changes nothing
	 * but still matches.
	 *
	 * @param publicId the event's public ID
	 * @param hostId   the user ID that must be the host
	 * @return 1, or 0 when the event doesn't exist or isn't theirs
	 */
	@Modifying
	@Query(value = """
			UPDATE event SET status = 'CANCELLED',
				updated_at = CASE WHEN status = 'CANCELLED' THEN updated_at ELSE now() END
			WHERE public_id = :publicId AND host_id = :hostId
			""", nativeQuery = true)
	int cancel(@Param("publicId") UUID publicId, @Param("hostId") UUID hostId);

	/**
	 * Gives a new event its host's confirmed RSVP, so the host holds the first spot
	 * from the start (D-013).
	 *
	 * @param eventId the event's internal ID
	 * @return 1
	 */
	@Modifying
	@Query(value = """
			WITH host_rsvp AS (INSERT INTO rsvp (event_id, user_id, status)
				SELECT id, host_id, 'CONFIRMED' FROM event WHERE id = :eventId)
			UPDATE event SET confirmed_count = 1 WHERE id = :eventId
			""", nativeQuery = true)
	int addHostRsvp(@Param("eventId") long eventId);

	/**
	 * Locks the host's {@code app_user} row until the transaction ends, so that two
	 * creates by one host count their recent events one after the other.
	 * {@code NO KEY UPDATE} still conflicts with itself but not with the key-share
	 * locks that foreign-key checks take, so creates that name each other in their
	 * audiences can't deadlock.
	 *
	 * @param hostId the host's user ID
	 * @return the host's user ID, or {@code null} when the user doesn't exist
	 */
	@Query(value = "SELECT id FROM app_user WHERE id = :hostId FOR NO KEY UPDATE", nativeQuery = true)
	UUID lockHost(@Param("hostId") UUID hostId);

	/**
	 * Counts the events a host created in the last 24 hours, cancelled ones
	 * included.
	 *
	 * @param hostId the host's user ID
	 * @return the count
	 */
	@Query(value = "SELECT count(*) FROM event WHERE host_id = :hostId AND created_at > now() - interval '24 hours'", nativeQuery = true)
	long countCreatedInLastDay(@Param("hostId") UUID hostId);
}
