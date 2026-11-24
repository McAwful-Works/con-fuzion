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

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.confuzion.backend.model.record.PersonListResponse;
import app.confuzion.backend.model.record.ResponseRecord;
import app.confuzion.backend.service.FriendService;
import app.confuzion.backend.util.HttpUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import app.confuzion.backend.model.record.CircleRequest;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The {@link RestController} for finding the people the signed-in user can pick
 * for circles and event audiences. The user ID always comes from the session.
 *
 * @author mcawful
 */
@RestController
@RequestMapping("/api/people")
@Tag(name = "people", description = "Finding people to add to circles and audiences")
public class PeopleController {
	/**
	 * The shortest search query.
	 */
	public static final int MIN_QUERY = 2;

	/**
	 * The longest search query.
	 */
	public static final int MAX_QUERY = 50;

	/**
	 * The {@link FriendService}.
	 */
	private final FriendService friendService;

	/**
	 * Creates the controller.
	 *
	 * @param friendService the {@link FriendService}
	 */
	public PeopleController(FriendService friendService) {
		this.friendService = friendService;
	}

	/**
	 * Searches the people the signed-in user can pick.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param q              the search text
	 * @return the {@link PersonListResponse} envelope
	 */
	@GetMapping
	@Operation(summary = "Search the people the signed-in user can pick", description = "Pickable people (D-011) are "
			+ "the user's friends, plus people who share a Discord server where both of them are discoverable. Never "
			+ "the user themself, and never anyone either of them has blocked. Matches display names and usernames, "
			+ "ignoring case. At most 20 results, sorted by display name.")
	public ResponseRecord<PersonListResponse> searchPeople(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The search text: 2 to 50 characters, with no leading or trailing whitespace") @RequestParam @NotNull @Size(min = MIN_QUERY, max = MAX_QUERY) @Pattern(regexp = CircleRequest.TRIMMED) String q) {
		return new ResponseRecord<>(friendService.searchPeople(HttpUtil.userId(authentication), q));
	}
}
