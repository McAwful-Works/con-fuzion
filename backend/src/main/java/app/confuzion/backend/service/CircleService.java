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

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.confuzion.backend.model.record.CircleListResponse;
import app.confuzion.backend.model.record.CircleResponse;
import app.confuzion.backend.model.record.PersonResponse;
import app.confuzion.backend.repository.SocialRepository;
import app.confuzion.backend.repository.SocialRepository.CircleRow;

/**
 * A user's circles. Circles are private to their owner: every method is scoped
 * to the owner ID it is given, which callers take from the session, and a
 * circle that isn't the owner's is a {@link NoSuchElementException} (404),
 * whether or not it exists. A name the owner already uses breaks a unique
 * index, which the exception handler answers with a 409.
 *
 * @author mcawful
 */
@Service
public class CircleService {
	/**
	 * The {@link SocialRepository}.
	 */
	private final SocialRepository socialRepository;

	/**
	 * Creates the service.
	 *
	 * @param socialRepository the {@link SocialRepository}
	 */
	public CircleService(SocialRepository socialRepository) {
		this.socialRepository = socialRepository;
	}

	/**
	 * Returns the owner's circles, each with its members.
	 *
	 * @param me the owner's ID
	 * @return the {@link CircleListResponse}, sorted by name
	 */
	@Transactional(readOnly = true)
	public CircleListResponse getCircles(UUID me) {
		var members = socialRepository.findMembersByOwner(me);
		return new CircleListResponse(socialRepository.findCircles(me).stream()
				.map(circle -> toResponse(circle, members.getOrDefault(circle.id(), List.of()))).toList());
	}

	/**
	 * Creates an empty circle.
	 *
	 * @param me   the owner's ID
	 * @param name the validated name
	 * @return the new {@link CircleResponse}
	 */
	@Transactional
	public CircleResponse createCircle(UUID me, String name) {
		return toResponse(socialRepository.insertCircle(me, name), List.of());
	}

	/**
	 * Returns one of the owner's circles.
	 *
	 * @param me       the owner's ID
	 * @param circleId the circle's public ID
	 * @return the {@link CircleResponse}
	 * @throws NoSuchElementException when it isn't the owner's
	 */
	@Transactional(readOnly = true)
	public CircleResponse getCircle(UUID me, UUID circleId) {
		var circle = socialRepository.findCircle(me, circleId).orElseThrow();
		return toResponse(circle, socialRepository.findMembers(circle.id()));
	}

	/**
	 * Renames one of the owner's circles.
	 *
	 * @param me       the owner's ID
	 * @param circleId the circle's public ID
	 * @param name     the validated new name
	 * @return the updated {@link CircleResponse}
	 * @throws NoSuchElementException when it isn't the owner's
	 */
	@Transactional
	public CircleResponse renameCircle(UUID me, UUID circleId, String name) {
		var circle = socialRepository.renameCircle(me, circleId, name).orElseThrow();
		return toResponse(circle, socialRepository.findMembers(circle.id()));
	}

	/**
	 * Deletes one of the owner's circles.
	 *
	 * @param me       the owner's ID
	 * @param circleId the circle's public ID
	 * @throws NoSuchElementException when it isn't the owner's
	 */
	@Transactional
	public void deleteCircle(UUID me, UUID circleId) {
		if (socialRepository.deleteCircle(me, circleId) == 0)
			throw new NoSuchElementException();
	}

	/**
	 * Adds someone to one of the owner's circles. An existing member stays without
	 * a re-check; anyone else must be pickable now (D-011). The pair is locked
	 * first, so a concurrent block can't slip between the check and the insert.
	 *
	 * @param me       the owner's ID
	 * @param circleId the circle's public ID
	 * @param userId   the person to add
	 * @throws NoSuchElementException when the circle isn't the owner's or the
	 *                                person isn't pickable
	 */
	@Transactional
	public void addMember(UUID me, UUID circleId, UUID userId) {
		var circle = socialRepository.findCircle(me, circleId).orElseThrow();
		if (socialRepository.isMember(circle.id(), userId))
			return;
		socialRepository.lockUsers(me, userId);
		if (!socialRepository.isPickable(me, userId))
			throw new NoSuchElementException();
		socialRepository.insertMember(circle.id(), userId);
	}

	/**
	 * Removes someone from one of the owner's circles. Removing a non-member does
	 * nothing.
	 *
	 * @param me       the owner's ID
	 * @param circleId the circle's public ID
	 * @param userId   the person to remove
	 * @throws NoSuchElementException when the circle isn't the owner's
	 */
	@Transactional
	public void removeMember(UUID me, UUID circleId, UUID userId) {
		socialRepository.deleteMember(socialRepository.findCircle(me, circleId).orElseThrow().id(), userId);
	}

	/**
	 * Maps a circle and its members to the {@link CircleResponse}.
	 *
	 * @param circle  the {@link CircleRow}
	 * @param members the members, sorted by display name
	 * @return the {@link CircleResponse}
	 */
	private static CircleResponse toResponse(CircleRow circle, List<PersonResponse> members) {
		return new CircleResponse(circle.publicId(), circle.name(), members);
	}
}
