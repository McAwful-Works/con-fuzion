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
package app.confuzion.backend.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.confuzion.backend.model.record.InvitePreviewResponse;
import app.confuzion.backend.model.record.InviteResponse;
import app.confuzion.backend.model.record.PersonListResponse;
import app.confuzion.backend.model.record.PersonResponse;
import app.confuzion.backend.model.record.ResponseRecord;
import app.confuzion.backend.service.FriendService;
import app.confuzion.backend.util.HttpUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The {@link RestController} for in-app friends and the invite links that make
 * them (D-011). The service checks every token, and every unusable one is the
 * same 404.
 *
 * @author mcawful
 */
@RestController
@RequestMapping("/api/friends")
@Tag(name = "friends", description = "In-app friends and invite links")
public class FriendController {
	/**
	 * The description of the 404 that every unusable invite token gets.
	 */
	private static final String UNUSABLE_INVITE = "The token is unusable: malformed, unknown, expired, already used, "
			+ "the caller's own, or between two people where either has blocked the other. Every case is the same 404.";

	/**
	 * The {@link FriendService}.
	 */
	private final FriendService friendService;

	/**
	 * Creates the controller.
	 *
	 * @param friendService the {@link FriendService}
	 */
	public FriendController(FriendService friendService) {
		this.friendService = friendService;
	}

	/**
	 * Makes a new single-use invite link for the signed-in user.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @return the {@link InviteResponse} envelope
	 */
	@PostMapping("/invites")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Make a friend invite link", description = "The link holds a 128-bit random token, works once, "
			+ "and expires after 24 hours. This response is the only place the token appears: the server stores only "
			+ "its SHA-256. A user can have at most 20 unused, unexpired invites, and can't list or revoke them, so "
			+ "the client makes a new one only when the user asks for a new code, not each time a screen opens.")
	// Declaring the 429 drops springdoc's inferred 201, so the 201 is declared too.
	@ApiResponse(responseCode = "201", description = "Created")
	@ApiResponse(responseCode = "429", description = "The user already has 20 active invites (errorType INVITE_LIMIT), "
			+ "or is rate limited (errorType TOO_MANY_REQUESTS).")
	public ResponseRecord<InviteResponse> createInvite(@Parameter(hidden = true) Authentication authentication) {
		return new ResponseRecord<>(friendService.createInvite(HttpUtil.userId(authentication)));
	}

	/**
	 * Returns what the invite accept page shows.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param token          the invite token from the link
	 * @return the {@link InvitePreviewResponse} envelope
	 */
	@GetMapping("/invites/{token}")
	@Operation(summary = "Preview a friend invite before accepting it", description = "Doesn't use the invite up. A "
			+ "signed-out visitor to /invite/{token} gets a 401 here: the SPA keeps the token in sessionStorage, signs "
			+ "them in (and through onboarding), then returns to the accept page. A malformed token is a 404 too, never "
			+ "a 400.")
	// Declaring the 404 drops springdoc's inferred 200, so the 200 is declared too.
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "404", description = UNUSABLE_INVITE)
	public ResponseRecord<InvitePreviewResponse> previewInvite(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The invite token from the link") @PathVariable String token) {
		return new ResponseRecord<>(friendService.previewInvite(HttpUtil.userId(authentication), token));
	}

	/**
	 * Accepts an invite, making the signed-in user and the inviter friends.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param token          the invite token from the link
	 * @return the new friend's {@link PersonResponse} envelope
	 */
	@PostMapping("/invites/{token}/accept")
	@Operation(summary = "Accept a friend invite", description = "Uses the invite up and returns the new friend. If the "
			+ "two are already friends it still succeeds, returns the friend, and uses the invite up. A malformed "
			+ "token is a 404 too, never a 400.")
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "404", description = UNUSABLE_INVITE)
	public ResponseRecord<PersonResponse> acceptInvite(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The invite token from the link") @PathVariable String token) {
		return new ResponseRecord<>(friendService.acceptInvite(HttpUtil.userId(authentication), token));
	}

	/**
	 * Returns the signed-in user's friends.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @return the {@link PersonListResponse} envelope
	 */
	@GetMapping
	@Operation(summary = "List the signed-in user's friends", description = "Sorted by display name.")
	public ResponseRecord<PersonListResponse> getFriends(@Parameter(hidden = true) Authentication authentication) {
		return new ResponseRecord<>(friendService.getFriends(HttpUtil.userId(authentication)));
	}

	/**
	 * Ends a friendship, for both people.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param userId         the friend's user ID
	 */
	@DeleteMapping("/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Remove a friend", description = "Ends the friendship for both people and, in the same "
			+ "transaction, removes each from the other's circles. Always 204, also when the two aren't friends or the "
			+ "user doesn't exist, so it reveals nothing; the documented 404 never happens.")
	public void deleteFriend(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The friend's user ID") @PathVariable UUID userId) {
		friendService.deleteFriend(HttpUtil.userId(authentication), userId);
	}
}
