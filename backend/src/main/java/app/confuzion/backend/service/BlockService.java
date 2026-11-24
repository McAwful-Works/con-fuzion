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
import app.confuzion.backend.model.record.PersonListResponse;
import app.confuzion.backend.repository.SocialRepository;

/**
 * Blocks. A block is a full mutual cutoff, whichever of the two made it
 * (D-011): blocking ends the friendship, circle memberships and individual
 * event audience entries both ways, and from then on the pickable set, invite
 * preview and invite accept all exclude the pair in both directions.
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
	 * Creates the service.
	 *
	 * @param socialRepository the {@link SocialRepository}
	 */
	public BlockService(SocialRepository socialRepository) {
		this.socialRepository = socialRepository;
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
	 * both ways. The blocker's own unused invites are also used up, so one they
	 * already showed the other can't be accepted after an unblock. The blocked
	 * user's invites are left alone: invites aren't addressed to anyone, and
	 * blocking someone mustn't be a way to cancel their invites to others.
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
		socialRepository.insertBlock(me, userId);
		socialRepository.deleteFriendship(me, userId);
		socialRepository.deleteFromEachOthersCircles(me, userId);
		socialRepository.deleteFromEachOthersEvents(me, userId);
		socialRepository.useUpInvites(me);
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
