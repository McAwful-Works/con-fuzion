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

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import app.confuzion.backend.model.record.NotificationPageResponse;
import app.confuzion.backend.model.record.NotificationReadRequest;
import app.confuzion.backend.model.record.ResponseRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The {@link RestController} for the caller's in-app inbox (D-014). A caller
 * only ever sees or changes their own notifications.
 *
 * @author mcawful
 */
@RestController
@RequestMapping("/api/notifications")
@Tag(name = "notifications", description = "The signed-in user's inbox")
public class NotificationController {
	/**
	 * Returns a page of the caller's inbox, newest first.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param cursor         the {@code nextCursor} from the previous page, or
	 *                       {@code null} for the first page
	 * @param limit          the most notifications to return
	 * @return the {@link NotificationPageResponse} envelope
	 */
	@GetMapping
	@Operation(summary = "List the signed-in user's notifications", description = "Newest first, with the unread "
			+ "count for the whole inbox. Nothing is ever purged in M4, so page with nextCursor. Each type sets only "
			+ "its own payload fields, and no payload holds a location or anyone's name (D-014).")
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "400", description = "A cursor that this endpoint didn't issue, or a limit out of range.")
	public ResponseRecord<NotificationPageResponse> getNotifications(
			@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The previous page's nextCursor. Opaque.") @RequestParam(required = false) @Size(max = 200) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String cursor,
			@Parameter(description = "Notifications per page, 1 to 50") @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
		throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
	}

	/**
	 * Marks the caller's notifications read, up to and including one ID.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param request        the {@link NotificationReadRequest}
	 */
	@PostMapping("/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Mark notifications read", description = "Marks every one of the caller's notifications with "
			+ "an ID up to and including upTo as read, so send the newest ID the user has seen. Newer ones stay "
			+ "unread. Other users' notifications are never touched, and an ID that isn't the caller's, or is "
			+ "already read, is fine: it's still a 204.")
	@ApiResponse(responseCode = "204", description = "Marked, or nothing to mark.")
	public void markRead(@Parameter(hidden = true) Authentication authentication,
			@Valid @RequestBody NotificationReadRequest request) {
		throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
	}
}
