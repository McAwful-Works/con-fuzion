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

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.confuzion.backend.exception.InvalidInputException;
import app.confuzion.backend.model.record.MyStatus;
import app.confuzion.backend.model.record.PersonListResponse;
import app.confuzion.backend.repository.NotificationRepository;
import app.confuzion.backend.repository.RsvpRepository;
import app.confuzion.backend.repository.SocialRepository;

/**
 * Blocks. A block is a full mutual cutoff, whichever of the two made it
 * (D-011): blocking ends the friendship, circle memberships, individual event
 * audience entries and RSVPs on each other's events (D-013 rule 11) both ways,
 * and from then on the pickable set, invite preview and invite accept all
 * exclude the pair in both directions.
 *
 * @author mcawful
 */
@Service
public class BlockService {
	/**
	 * The {@link SocialRepository}.
	 */
	private final SocialRepository socialRepository;

	/**
	 * The {@link RsvpRepository}.
	 */
	private final RsvpRepository rsvpRepository;

	/**
	 * The {@link NotificationRepository}.
	 */
	private final NotificationRepository notificationRepository;

	/**
	 * Creates the service.
	 *
	 * @param socialRepository       the {@link SocialRepository}
	 * @param rsvpRepository         the {@link RsvpRepository}
	 * @param notificationRepository the {@link NotificationRepository}
	 */
	public BlockService(SocialRepository socialRepository, RsvpRepository rsvpRepository,
			NotificationRepository notificationRepository) {
		this.socialRepository = socialRepository;
		this.rsvpRepository = rsvpRepository;
		this.notificationRepository = notificationRepository;
	}

	/**
	 * Returns the users someone has blocked. Never who has blocked them.
	 *
	 * @param me the user's ID
	 * @return the {@link PersonListResponse}, sorted by display name
	 */
	@Transactional(readOnly = true)
	public PersonListResponse getBlocks(UUID me) {
		return new PersonListResponse(socialRepository.findBlocked(me));
	}

	/**
	 * Blocks a user and applies the cutoff, in one transaction under a lock on the
	 * pair. An unknown user ID does nothing, so the caller learns nothing. While
	 * the block exists, preview and accept refuse every invite between the pair,
	 * both ways, so no invite row changes: the blocker's links to other people keep
	 * working (D-011).
	 * <p>
	 * Each person's active RSVP on the other's events ends as {@code LEFT}, and a
	 * freed spot goes to the next person in line, as for leaving. The event rows
	 * are locked right after the pair, before any other row is touched, so this
	 * can't deadlock with a join or an edit of those events (D-013).
	 *
	 * <p>
	 * Each person's notifications about the other's events, and their
	 * {@code FRIEND_ACCEPTED} for the other, are deleted (D-014).
	 *
	 * @param me     the blocker's ID
	 * @param userId the user to block
	 * @throws InvalidInputException when the user is the caller
	 */
	@Transactional
	public void block(UUID me, UUID userId) {
		if (me.equals(userId))
			throw new InvalidInputException();
		if (socialRepository.lockUsers(me, userId) < 2)
			return;
		var events = rsvpRepository.lockForBlock(me, userId);
		socialRepository.insertBlock(me, userId);
		notificationRepository.deleteAbout(me, userId);
		notificationRepository.deleteAbout(userId, me);
		socialRepository.deleteFriendship(me, userId);
		socialRepository.deleteFromEachOthersCircles(me, userId);
		socialRepository.deleteFromEachOthersEvents(me, userId);
		for (var event : events) {
			var attendee = event.hostId().equals(me) ? userId : me;
			if (rsvpRepository.end(event.id(), attendee, "LEFT")
					.filter(held -> held == MyStatus.CONFIRMED || held == MyStatus.OFFERED).isPresent())
				rsvpRepository.freeSpot(event.id());
		}
	}

	/**
	 * Removes a block the caller made. Restores nothing, and a block the other user
	 * made stays.
	 *
	 * @param me     the blocker's ID
	 * @param userId the blocked user
	 */
	@Transactional
	public void unblock(UUID me, UUID userId) {
		socialRepository.deleteBlock(me, userId);
	}
}
