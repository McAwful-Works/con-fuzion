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
package app.confuzion.backend.service;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import app.confuzion.backend.exception.ConflictException;
import app.confuzion.backend.exception.ConflictException.Conflict;
import app.confuzion.backend.model.entity.Event;
import app.confuzion.backend.model.record.AttendeesResponse;
import app.confuzion.backend.model.record.EventResponse.Status;
import app.confuzion.backend.model.record.MyStatus;
import app.confuzion.backend.model.record.RsvpResponse;
import app.confuzion.backend.repository.EventRepository;
import app.confuzion.backend.repository.RsvpRepository;
import app.confuzion.backend.repository.RsvpRepository.Mine;

/**
 * Joining, leaving, removal and attendee lists (plan B4, D-013). Every
 * operation first needs the event to be visible to the caller
 * ({@link EventRepository#VISIBLE}); otherwise it is a 404
 * ({@link NoSuchElementException}). Every write that changes who holds a spot
 * locks the event row before it touches an RSVP (D-013).
 * <p>
 * TODO(M7, account deletion): leave every active RSVP first, freeing the spot
 * and promoting the next person, because the {@code rsvp.user_id} cascade
 * doesn't touch {@code confirmed_count} (D-013).
 *
 * @author mcawful
 */
@Service
public class RsvpService {
	/**
	 * The {@link EventRepository}, for the visibility check.
	 */
	private final EventRepository eventRepository;

	/**
	 * The {@link RsvpRepository}.
	 */
	private final RsvpRepository rsvpRepository;

	/**
	 * Runs the short claim transaction, after the read-only checks.
	 */
	private final TransactionTemplate transactionTemplate;

	/**
	 * Creates the service.
	 *
	 * @param eventRepository     the {@link EventRepository}
	 * @param rsvpRepository      the {@link RsvpRepository}
	 * @param transactionTemplate the {@link TransactionTemplate}
	 */
	public RsvpService(EventRepository eventRepository, RsvpRepository rsvpRepository,
			TransactionTemplate transactionTemplate) {
		this.eventRepository = eventRepository;
		this.rsvpRepository = rsvpRepository;
		this.transactionTemplate = transactionTemplate;
	}

	/**
	 * Joins an event. The checks run read-only, in D-013 rule 17's order, before
	 * one short transaction locks the event row, adds the RSVP and, when a spot is
	 * free, takes it.
	 *
	 * @param userId  the signed-in user's ID
	 * @param eventId the event's public ID
	 * @return the caller's status and, while waitlisted, their position
	 * @throws NoSuchElementException when the caller can't see the event, or it was
	 *                                cancelled or a block came between them and the
	 *                                host while joining
	 * @throws ConflictException      when the caller was removed, or sign-up isn't
	 *                                open
	 */
	public RsvpResponse join(UUID userId, UUID eventId) {
		var event = eventRepository.findVisible(eventId, userId).orElseThrow();
		var mine = rsvpRepository.findMine(event.getId(), userId);
		if (mine.isPresent() && mine.get().status() != MyStatus.REMOVED)
			return toRsvp(mine.get());
		if (mine.isPresent())
			throw new ConflictException(Conflict.RSVP_REMOVED);
		var now = Instant.now();
		if (event.getSignupOpensAt() != null && now.isBefore(event.getSignupOpensAt()))
			throw new ConflictException(Conflict.SIGNUP_NOT_OPEN);
		if (!now.isBefore(event.getStartsAt()))
			throw new ConflictException(Conflict.SIGNUP_CLOSED);

		transactionTemplate.executeWithoutResult(tx -> {
			var locked = rsvpRepository.lock(event.getId());
			if (!Status.PUBLISHED.name().equals(locked.status()))
				throw new NoSuchElementException();
			rsvpRepository.insert(event.getId(), locked.hostId(), userId, locked.hasFreeSpot());
		});
		// The new RSVP, or the one a double tap's twin made. None means a block came
		// between the caller and the host, and nothing was written.
		var current = rsvpRepository.findMine(event.getId(), userId).orElseThrow();
		if (current.status() == MyStatus.REMOVED)
			throw new ConflictException(Conflict.RSVP_REMOVED);
		return toRsvp(current);
	}

	/**
	 * Leaves an event. A freed spot goes to the next person in line.
	 *
	 * @param userId  the signed-in user's ID
	 * @param eventId the event's public ID
	 * @throws NoSuchElementException when the caller can't see the event
	 * @throws ConflictException      when the caller is the host
	 */
	@Transactional
	public void leave(UUID userId, UUID eventId) {
		var event = eventRepository.findVisible(eventId, userId).orElseThrow();
		if (event.getHostId().equals(userId))
			throw new ConflictException(Conflict.HOST_CANNOT_LEAVE);
		end(event.getId(), userId, "LEFT");
	}

	/**
	 * Removes someone from an event the caller hosts. They can't rejoin. A freed
	 * spot goes to the next person in line. Removing someone without an active RSVP
	 * writes nothing.
	 *
	 * @param hostId  the signed-in user's ID
	 * @param eventId the event's public ID
	 * @param userId  the person to remove
	 * @throws NoSuchElementException when the caller isn't the host
	 * @throws ConflictException      when the caller removes themself
	 */
	@Transactional
	public void remove(UUID hostId, UUID eventId, UUID userId) {
		var event = hosted(hostId, eventId);
		if (userId.equals(hostId))
			throw new ConflictException(Conflict.HOST_CANNOT_LEAVE);
		end(event.getId(), userId, "REMOVED");
	}

	/**
	 * Returns who is going, to the host and confirmed attendees only. The host also
	 * gets the waitlist. Both lists come from one snapshot, so a promotion between
	 * the two reads can't put someone in both or neither.
	 *
	 * @param userId  the signed-in user's ID
	 * @param eventId the event's public ID
	 * @return the {@link AttendeesResponse}
	 * @throws NoSuchElementException when the caller is neither the host nor
	 *                                confirmed, or can't see the event
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public AttendeesResponse getAttendees(UUID userId, UUID eventId) {
		var event = eventRepository.findVisible(eventId, userId).orElseThrow();
		var isHost = event.getHostId().equals(userId);
		if (!isHost && rsvpRepository.findMine(event.getId(), userId).map(Mine::status)
				.filter(MyStatus.CONFIRMED::equals).isEmpty())
			throw new NoSuchElementException();
		return new AttendeesResponse(rsvpRepository.findPeople(event.getId(), MyStatus.CONFIRMED.name(), userId),
				isHost ? rsvpRepository.findPeople(event.getId(), MyStatus.WAITLISTED.name(), userId) : null);
	}

	/**
	 * Ends an active RSVP under the event row lock, freeing the spot when it was
	 * confirmed. The caller must be in a transaction.
	 *
	 * @param eventId the event's internal ID
	 * @param userId  the user whose RSVP ends
	 * @param status  {@code LEFT} or {@code REMOVED}
	 */
	private void end(long eventId, UUID userId, String status) {
		rsvpRepository.lock(eventId);
		if (rsvpRepository.end(eventId, userId, status).filter(MyStatus.CONFIRMED::equals).isPresent())
			rsvpRepository.freeSpot(eventId);
	}

	/**
	 * Returns an event the caller hosts, without locking it.
	 *
	 * @param hostId  the signed-in user's ID
	 * @param eventId the event's public ID
	 * @return the {@link Event}
	 * @throws NoSuchElementException when it doesn't exist or isn't theirs
	 */
	private Event hosted(UUID hostId, UUID eventId) {
		return eventRepository.findVisible(eventId, hostId).filter(event -> event.getHostId().equals(hostId))
				.orElseThrow();
	}

	/**
	 * Maps an active RSVP to a join's response.
	 *
	 * @param mine the caller's {@link Mine}, confirmed or waitlisted
	 * @return the {@link RsvpResponse}
	 */
	private static RsvpResponse toRsvp(Mine mine) {
		return new RsvpResponse(RsvpResponse.Status.valueOf(mine.status().name()), mine.position());
	}
}
