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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import app.confuzion.backend.model.record.AdminUserListResponse;
import app.confuzion.backend.model.record.BanRequest;
import app.confuzion.backend.model.record.GuildResponse;
import app.confuzion.backend.model.record.ResponseRecord;
import app.confuzion.backend.model.record.SuspendRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The {@link RestController} for user search, suspensions and bans (D-015).
 * Search and suspensions need MODERATOR or ADMIN in effect, bans need ADMIN;
 * {@code SecurityConfig} checks both. Stubs until #86.
 *
 * @author mcawful
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "admin-users", description = "User search, suspensions (MODERATOR or ADMIN) and bans (ADMIN)")
public class AdminUserController {
	/**
	 * Finds users by name or Discord ID.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param q              the search text
	 * @return the {@link AdminUserListResponse} envelope
	 */
	@GetMapping("/users")
	@Operation(summary = "Find users (MODERATOR or ADMIN)", description = "Matches an exact user ID or Discord ID, "
			+ "or part of a username or display name, ignoring case. At most 20, by username. Returns only what "
			+ "moderators may see: never servers, friends, circles or RSVPs.")
	@ApiResponse(responseCode = "200", description = "OK")
	public ResponseRecord<AdminUserListResponse> findUsers(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "A user ID, a Discord ID, or 2 to 100 characters of a name") @RequestParam @NotBlank @Size(min = 2, max = 100) String q) {
		throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
	}

	/**
	 * Suspends a user.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param userId         the user's ID
	 * @param request        the {@link SuspendRequest}
	 */
	@PostMapping("/users/{userId}/suspend")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Suspend a user (MODERATOR or ADMIN)", description = "Until the given time they can't "
			+ "sign in, and their sessions are deleted at once. Their events and RSVPs are left alone. Suspending "
			+ "again replaces the end time. A moderator can suspend only users with no granted role; an admin can "
			+ "also suspend moderators. Audit-logged, as is the session purge.")
	@ApiResponse(responseCode = "204", description = "Suspended.")
	@ApiResponse(responseCode = "400", description = "The request failed validation, or the end is more than 365 days away.")
	@ApiResponse(responseCode = "404", description = "No such user.")
	@ApiResponse(responseCode = "409", description = "The user's granted role protects them (errorType TARGET_HAS_ROLE).")
	public void suspendUser(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The user's ID") @PathVariable UUID userId,
			@Valid @RequestBody SuspendRequest request) {
		throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
	}

	/**
	 * Lifts a user's suspension.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param userId         the user's ID
	 */
	@PostMapping("/users/{userId}/unsuspend")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Lift a suspension (MODERATOR or ADMIN)", description = "They can sign in again. A user "
			+ "who isn't suspended is a 204 that changes nothing. Audit-logged.")
	@ApiResponse(responseCode = "204", description = "Lifted, or wasn't suspended.")
	@ApiResponse(responseCode = "404", description = "No such user.")
	public void unsuspendUser(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The user's ID") @PathVariable UUID userId) {
		throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
	}

	/**
	 * Bans a Discord ID.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param request        the {@link BanRequest}
	 */
	@PostMapping("/bans")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Ban a Discord ID (ADMIN)", description = "It can never sign in, even after the account is "
			+ "deleted and made again, and it needn't have an account yet. If it has one, the account is kept, its "
			+ "sessions are deleted, its upcoming hosted events are cancelled (with the usual EVENT_CANCELLED) and "
			+ "its active RSVPs end as LEFT, freeing their spots. Banning a banned ID is a 204 that changes "
			+ "nothing. Audit-logged.")
	@ApiResponse(responseCode = "204", description = "Banned, or already banned.")
	@ApiResponse(responseCode = "409", description = "The account holds ADMIN (errorType TARGET_HAS_ROLE). Remove the role first.")
	public void ban(@Parameter(hidden = true) Authentication authentication, @Valid @RequestBody BanRequest request) {
		throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
	}

	/**
	 * Lifts a ban.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param discordId      the Discord user ID
	 */
	@DeleteMapping("/bans/{discordId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Lift a ban (ADMIN)", description = "The Discord ID can sign in again. Nothing the ban did "
			+ "is undone. An ID that isn't banned is a 204 that changes nothing. Audit-logged.")
	@ApiResponse(responseCode = "204", description = "Lifted, or wasn't banned.")
	public void unban(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The Discord user ID (a snowflake, as a string)") @PathVariable @Pattern(regexp = GuildResponse.SNOWFLAKE_PATTERN) String discordId) {
		throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
	}
}
