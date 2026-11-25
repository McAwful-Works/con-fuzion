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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.confuzion.backend.exception.LimitExceededException;
import app.confuzion.backend.exception.LimitExceededException.Limit;
import app.confuzion.backend.model.record.InvitePreviewResponse;
import app.confuzion.backend.model.record.InviteResponse;
import app.confuzion.backend.model.record.NotificationPayload;
import app.confuzion.backend.model.record.NotificationResponse.Type;
import app.confuzion.backend.model.record.PersonListResponse;
import app.confuzion.backend.model.record.PersonResponse;
import app.confuzion.backend.repository.NotificationRepository;
import app.confuzion.backend.repository.SocialRepository;

/**
 * Friends, the invite links that make them, and people search (D-011). Every
 * method acts for the user ID it is given, which callers take from the session.
 * Every unusable invite token is the same {@link NoSuchElementException}, so a
 * caller can't tell a malformed, unknown, expired, used, own or blocked token
 * apart.
 *
 * @author mcawful
 */
@Service
public class FriendService {
	/**
	 * The most unused, unexpired invites a user can have.
	 */
	public static final int MAX_ACTIVE_INVITES = 20;

	/**
	 * The most people search returns.
	 */
	public static final int MAX_SEARCH_RESULTS = 20;

	/**
	 * The token's size: 128 bits.
	 */
	private static final int TOKEN_BYTES = 16;

	/**
	 * A well-formed token: 16 bytes in base64url without padding.
	 */
	private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{22}");

	/**
	 * The {@link SocialRepository}.
	 */
	private final SocialRepository socialRepository;

	/**
	 * The {@link NotificationRepository}.
	 */
	private final NotificationRepository notificationRepository;

	/**
	 * The CSPRNG for tokens.
	 */
	private final SecureRandom random = new SecureRandom();

	/**
	 * The configured app origin followed by the SPA's accept path. Never built from
	 * the request's {@code Host} or {@code X-Forwarded-Host}.
	 */
	private final String inviteUrlPrefix;

	/**
	 * Creates the service.
	 *
	 * @param socialRepository       the {@link SocialRepository}
	 * @param notificationRepository the {@link NotificationRepository}
	 * @param appOrigin              the app's public origin, such as
	 *                               {@code https://example.com}, with no path
	 * @throws IllegalArgumentException when the origin isn't an http(s) origin
	 */
	public FriendService(SocialRepository socialRepository, NotificationRepository notificationRepository,
			@Value("${con-fuzion.app.origin}") String appOrigin) {
		var origin = URI.create(appOrigin);
		if (!("https".equals(origin.getScheme()) || "http".equals(origin.getScheme())) || origin.getHost() == null
				|| !origin.getRawPath().isEmpty() || origin.getRawQuery() != null || origin.getRawFragment() != null)
			throw new IllegalArgumentException("con-fuzion.app.origin must be an http(s) origin with no path");
		this.socialRepository = socialRepository;
		this.notificationRepository = notificationRepository;
		this.inviteUrlPrefix = appOrigin + "/invite/";
	}

	/**
	 * Makes a new invite. The cap is counted under a lock on the inviter's row, so
	 * concurrent requests can't both pass it.
	 *
	 * @param me the inviter's ID
	 * @return the {@link InviteResponse}, the only place the raw token appears
	 * @throws LimitExceededException when the user already has
	 *                                {@value #MAX_ACTIVE_INVITES} active invites
	 * @throws NoSuchElementException when the user no longer exists
	 */
	@Transactional
	public InviteResponse createInvite(UUID me) {
		if (socialRepository.lockUsers(me, me) == 0)
			throw new NoSuchElementException();
		if (socialRepository.countActiveInvites(me) >= MAX_ACTIVE_INVITES)
			throw new LimitExceededException(Limit.INVITE_LIMIT);
		var bytes = new byte[TOKEN_BYTES];
		random.nextBytes(bytes);
		var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		var expiresAt = socialRepository.insertInvite(hash(token), me);
		return new InviteResponse(inviteUrlPrefix + token, expiresAt);
	}

	/**
	 * Returns what the accept page shows. Doesn't use the invite up.
	 *
	 * @param me    the caller's ID
	 * @param token the token from the link
	 * @return the {@link InvitePreviewResponse}
	 * @throws NoSuchElementException when the token is unusable
	 */
	@Transactional(readOnly = true)
	public InvitePreviewResponse previewInvite(UUID me, String token) {
		return socialRepository.findUsableInvite(hash(token), me).orElseThrow();
	}

	/**
	 * Accepts an invite. The claim is one atomic {@code UPDATE}, so only one of two
	 * concurrent accepts wins. The pair is then locked and checked for a block; a
	 * block rolls the claim back, so the invite isn't used up. Already being
	 * friends is fine: the invite is still used up. The inviter gets a
	 * {@code FRIEND_ACCEPTED} notification in the same transaction (D-014).
	 *
	 * @param me    the accepting user's ID
	 * @param token the token from the link
	 * @return the inviter, now a friend
	 * @throws NoSuchElementException when the token is unusable
	 */
	@Transactional
	public PersonResponse acceptInvite(UUID me, String token) {
		var inviter = socialRepository.claimInvite(hash(token), me).orElseThrow();
		socialRepository.lockUsers(me, inviter);
		if (socialRepository.isBlockedEitherWay(me, inviter))
			throw new NoSuchElementException();
		socialRepository.insertFriendship(me, inviter);
		notificationRepository.insert(inviter, Type.FRIEND_ACCEPTED, null,
				new NotificationPayload(null, null, null, null, me, null, null, null));
		return socialRepository.findPerson(inviter).orElseThrow();
	}

	/**
	 * Returns a user's friends.
	 *
	 * @param me the user's ID
	 * @return the {@link PersonListResponse}, sorted by display name
	 */
	@Transactional(readOnly = true)
	public PersonListResponse getFriends(UUID me) {
		return new PersonListResponse(socialRepository.findFriends(me));
	}

	/**
	 * Ends a friendship and, when there was one, removes each person from the
	 * other's circles (D-011). Does nothing when the two aren't friends.
	 *
	 * @param me     the user's ID
	 * @param friend the friend's ID
	 */
	@Transactional
	public void deleteFriend(UUID me, UUID friend) {
		// The pair lock keeps a concurrent circle add from re-adding the ex-friend.
		socialRepository.lockUsers(me, friend);
		if (socialRepository.deleteFriendship(me, friend) > 0)
			socialRepository.deleteFromEachOthersCircles(me, friend);
	}

	/**
	 * Searches the people a user can pick (D-011) by the start of their username or
	 * display name, ignoring case.
	 *
	 * @param me the user's ID
	 * @param q  the validated search text
	 * @return at most {@value #MAX_SEARCH_RESULTS} people, sorted by display name
	 */
	@Transactional(readOnly = true)
	public PersonListResponse searchPeople(UUID me, String q) {
		return new PersonListResponse(socialRepository.searchPickable(me, q, MAX_SEARCH_RESULTS));
	}

	/**
	 * Returns the SHA-256 of a well-formed token.
	 *
	 * @param token the token
	 * @return the hash
	 * @throws NoSuchElementException when the token is malformed, the same as any
	 *                                other unusable token
	 */
	private static byte[] hash(String token) {
		if (!TOKEN.matcher(token).matches())
			throw new NoSuchElementException();
		try {
			return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
