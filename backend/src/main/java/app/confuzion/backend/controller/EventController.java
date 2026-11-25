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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.confuzion.backend.model.record.EventPageResponse;
import app.confuzion.backend.model.record.EventRequest;
import app.confuzion.backend.model.record.EventResponse;
import app.confuzion.backend.model.record.ResponseRecord;
import app.confuzion.backend.service.EventService;
import app.confuzion.backend.util.HttpUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;

/**
 * The {@link RestController} for events. An event the caller can't see is a
 * 404, and so is changing one the caller doesn't host (D-012). The user ID
 * always comes from the session, never from the request.
 *
 * @author mcawful
 */
@RestController
@RequestMapping("/api/events")
@Tag(name = "events", description = "Meetups, who they're for, and the feed")
public class EventController {
	/**
	 * The description of the 404 for an audience entry the host can't use.
	 */
	private static final String UNUSABLE_AUDIENCE = "An audience entry names a server where the host isn't "
			+ "discoverable, a circle that isn't the host's, or a person the host can't pick (D-011). Nothing is "
			+ "saved.";

	/**
	 * The {@link EventService}.
	 */
	private final EventService eventService;

	/**
	 * Creates the controller.
	 *
	 * @param eventService the {@link EventService}
	 */
	public EventController(EventService eventService) {
		this.eventService = eventService;
	}

	/**
	 * Creates an event hosted by the signed-in user.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param request        the {@link EventRequest}
	 * @return the new {@link EventResponse} envelope
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create an event", description = "Publishes it at once, hosted by the signed-in user, who "
			+ "holds its first spot (D-013). A host can create at most 20 events in any 24 hours.")
	// Declaring the 404 drops springdoc's inferred 201, so the 201 is declared too.
	@ApiResponse(responseCode = "201", description = "Created")
	@ApiResponse(responseCode = "404", description = UNUSABLE_AUDIENCE)
	@ApiResponse(responseCode = "429", description = "The host already created 20 events in the last 24 hours "
			+ "(errorType EVENT_LIMIT), or is rate limited (errorType TOO_MANY_REQUESTS).")
	public ResponseRecord<EventResponse> createEvent(@Parameter(hidden = true) Authentication authentication,
			@Validated({ Default.class, EventRequest.Create.class }) @RequestBody EventRequest request) {
		return new ResponseRecord<>(eventService.createEvent(HttpUtil.userId(authentication), request));
	}

	/**
	 * Returns a page of upcoming events: the signed-in user's feed, the events they
	 * host, or the events they are signed up for.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param scope          {@code feed}, {@code hosting} or {@code attending}
	 * @param cursor         the {@code nextCursor} from the previous page, or
	 *                       {@code null} for the first page
	 * @param limit          the most events to return
	 * @return the {@link EventPageResponse} envelope
	 */
	@GetMapping
	@Operation(summary = "List upcoming events", description = "Upcoming means the end (or, without one, the start plus "
			+ "3 hours) is still in the future. feed: published events visible to the user, their own included. "
			+ "hosting: the user's own events, cancelled ones included. attending (My schedule): events where the user "
			+ "is confirmed or waitlisted, their own included (the host always holds a spot), and cancelled ones where "
			+ "they still are. Sorted by start time, then ID.")
	public ResponseRecord<EventPageResponse> getEvents(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "feed, hosting or attending", schema = @Schema(type = "string", allowableValues = {
					"feed", "hosting",
					"attending" }, defaultValue = "feed")) @RequestParam(defaultValue = "feed") @Pattern(regexp = "^(feed|hosting|attending)$") String scope,
			@Parameter(description = "The previous page's nextCursor. Opaque; a cursor that wasn't issued for this scope is a 400.") @RequestParam(required = false) @Size(max = 200) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String cursor,
			@Parameter(description = "Events per page, 1 to 50") @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
		return new ResponseRecord<>(eventService.getEvents(HttpUtil.userId(authentication), scope, cursor, limit));
	}

	/**
	 * Returns one event the signed-in user can see.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param eventId        the event's public ID
	 * @return the {@link EventResponse} envelope
	 */
	@GetMapping("/{eventId}")
	@Operation(summary = "Get an event", description = "404 unless the user can see it (D-012). Once it's cancelled, "
			+ "the host and anyone still confirmed or waitlisted can see it (D-013). A CONFIRMED-only location is "
			+ "withheld unless the user is the host or confirmed.")
	public ResponseRecord<EventResponse> getEvent(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The event's public ID") @PathVariable UUID eventId) {
		return new ResponseRecord<>(eventService.getEvent(HttpUtil.userId(authentication), eventId));
	}

	/**
	 * Replaces the editable fields of an event the signed-in user hosts.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param eventId        the event's public ID
	 * @param request        the {@link EventRequest}
	 * @return the updated {@link EventResponse} envelope
	 */
	@PatchMapping("/{eventId}")
	@Operation(summary = "Edit an event the user hosts", description = "Send the whole event, as for create: every "
			+ "editable field is replaced, and the audience too. Entries already on the event are kept without a "
			+ "re-check, so sending back the audience as received never fails; only new entries are checked. 404 "
			+ "unless the user is the host.")
	// Declaring the 409 drops springdoc's inferred 200, so the 200 is declared too.
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "400", description = "The request failed validation, or it moves the start into the "
			+ "past.")
	@ApiResponse(responseCode = "404", description = "The user isn't the host, or a newly added audience entry "
			+ "can't be used. " + UNUSABLE_AUDIENCE)
	@ApiResponse(responseCode = "409", description = "The event is cancelled or has ended, so it can't be edited. "
			+ "Or errorType CAPACITY_BELOW_CONFIRMED: the new capacity is smaller than the number already confirmed, "
			+ "the host included (D-013). Nothing is saved.")
	public ResponseRecord<EventResponse> updateEvent(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The event's public ID") @PathVariable UUID eventId,
			@Valid @RequestBody EventRequest request) {
		return new ResponseRecord<>(eventService.updateEvent(HttpUtil.userId(authentication), eventId, request));
	}

	/**
	 * Cancels an event the signed-in user hosts. The event isn't deleted.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param eventId        the event's public ID
	 */
	@DeleteMapping("/{eventId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Cancel an event the user hosts", description = "Sets its status to CANCELLED and keeps it; "
			+ "it drops out of everyone's feed. Cancelling a cancelled event is also a 204. 404 unless the user is the "
			+ "host.")
	public void cancelEvent(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The event's public ID") @PathVariable UUID eventId) {
		eventService.cancelEvent(HttpUtil.userId(authentication), eventId);
	}
}
