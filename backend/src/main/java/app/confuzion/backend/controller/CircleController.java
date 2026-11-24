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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.confuzion.backend.model.record.CircleListResponse;
import app.confuzion.backend.model.record.CircleRequest;
import app.confuzion.backend.model.record.CircleResponse;
import app.confuzion.backend.model.record.ResponseRecord;
import app.confuzion.backend.service.CircleService;
import app.confuzion.backend.util.HttpUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * The {@link RestController} for the signed-in user's circles. A circle is
 * private to its owner, and a circle ID that isn't one of the caller's own is a
 * 404. The user ID always comes from the session.
 *
 * @author mcawful
 */
@RestController
@RequestMapping("/api/circles")
@Tag(name = "circles", description = "Named groups of people, private to their owner")
public class CircleController {
	/**
	 * The description of the 409 for a name the owner already uses.
	 */
	private static final String NAME_TAKEN = "The caller already has a circle with this name, ignoring case.";

	/**
	 * The {@link CircleService}.
	 */
	private final CircleService circleService;

	/**
	 * Creates the controller.
	 *
	 * @param circleService the {@link CircleService}
	 */
	public CircleController(CircleService circleService) {
		this.circleService = circleService;
	}

	/**
	 * Returns the signed-in user's circles.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @return the {@link CircleListResponse} envelope
	 */
	@GetMapping
	@Operation(summary = "List the signed-in user's circles", description = "Sorted by name, each with its members.")
	public ResponseRecord<CircleListResponse> getCircles(@Parameter(hidden = true) Authentication authentication) {
		return new ResponseRecord<>(circleService.getCircles(HttpUtil.userId(authentication)));
	}

	/**
	 * Creates an empty circle.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param request        the {@link CircleRequest}
	 * @return the new {@link CircleResponse} envelope
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create a circle", description = "The new circle has no members.")
	// Declaring the 409 drops springdoc's inferred 201, so the 201 is declared too.
	@ApiResponse(responseCode = "201", description = "Created")
	@ApiResponse(responseCode = "409", description = NAME_TAKEN)
	public ResponseRecord<CircleResponse> createCircle(@Parameter(hidden = true) Authentication authentication,
			@Valid @RequestBody CircleRequest request) {
		return new ResponseRecord<>(circleService.createCircle(HttpUtil.userId(authentication), request.name()));
	}

	/**
	 * Returns one of the signed-in user's circles.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param circleId       the circle's public ID
	 * @return the {@link CircleResponse} envelope
	 */
	@GetMapping("/{circleId}")
	@Operation(summary = "Get one of the signed-in user's circles")
	public ResponseRecord<CircleResponse> getCircle(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The circle's public ID") @PathVariable UUID circleId) {
		return new ResponseRecord<>(circleService.getCircle(HttpUtil.userId(authentication), circleId));
	}

	/**
	 * Renames one of the signed-in user's circles.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param circleId       the circle's public ID
	 * @param request        the {@link CircleRequest}
	 * @return the updated {@link CircleResponse} envelope
	 */
	@PatchMapping("/{circleId}")
	@Operation(summary = "Rename one of the signed-in user's circles")
	// Declaring the 409 drops springdoc's inferred 200, so the 200 is declared too.
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "409", description = NAME_TAKEN)
	public ResponseRecord<CircleResponse> updateCircle(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The circle's public ID") @PathVariable UUID circleId,
			@Valid @RequestBody CircleRequest request) {
		return new ResponseRecord<>(
				circleService.renameCircle(HttpUtil.userId(authentication), circleId, request.name()));
	}

	/**
	 * Deletes one of the signed-in user's circles.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param circleId       the circle's public ID
	 */
	@DeleteMapping("/{circleId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Delete one of the signed-in user's circles")
	public void deleteCircle(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The circle's public ID") @PathVariable UUID circleId) {
		circleService.deleteCircle(HttpUtil.userId(authentication), circleId);
	}

	/**
	 * Adds a person to one of the signed-in user's circles. Repeating it changes
	 * nothing.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param circleId       the circle's public ID
	 * @param userId         the person to add
	 */
	@PutMapping("/{circleId}/members/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Add a person to a circle", description = "Idempotent. The person must be pickable (D-011): "
			+ "a friend, or someone who shares a Discord server where both are discoverable, and not blocked either "
			+ "way. They aren't told. Adding someone who is already a member is a 204 without re-checking.")
	// Declaring the 404 drops springdoc's inferred 204, so the 204 is declared too.
	@ApiResponse(responseCode = "204", description = "No Content")
	@ApiResponse(responseCode = "404", description = "The circle isn't one of the caller's, or the person isn't "
			+ "pickable. Both are the same 404.")
	public void addMember(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The circle's public ID") @PathVariable UUID circleId,
			@Parameter(description = "The person's user ID") @PathVariable UUID userId) {
		circleService.addMember(HttpUtil.userId(authentication), circleId, userId);
	}

	/**
	 * Removes a person from one of the signed-in user's circles.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param circleId       the circle's public ID
	 * @param userId         the person to remove
	 */
	@DeleteMapping("/{circleId}/members/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Remove a person from a circle", description = "Also 204 when the person isn't a member. "
			+ "404 only when the circle isn't one of the caller's.")
	public void removeMember(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The circle's public ID") @PathVariable UUID circleId,
			@Parameter(description = "The person's user ID") @PathVariable UUID userId) {
		circleService.removeMember(HttpUtil.userId(authentication), circleId, userId);
	}
}
