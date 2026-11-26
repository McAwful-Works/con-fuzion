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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.confuzion.backend.model.record.AttendeesResponse;
import app.confuzion.backend.model.record.ResponseRecord;
import app.confuzion.backend.model.record.RsvpResponse;
import app.confuzion.backend.model.record.SpotsResponse;
import app.confuzion.backend.service.RsvpService;
import app.confuzion.backend.util.HttpUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The {@link RestController} for joining events, the waitlist, attendee lists
 * and spot counts (D-013). An event the caller can't see is a 404 (D-012), and
 * the user ID always comes from the session, never from the request.
 *
 * @author mcawful
 */
@RestController
@RequestMapping("/api/events/{eventId}")
@Tag(name = "rsvp", description = "Joining, the waitlist, attendees and spot counts")
public class RsvpController {
	/**
	 * The description of the 404 for an event the caller can't see.
	 */
	private static final String NOT_VISIBLE = "The event doesn't exist, or the caller can't see it (D-012). The host, "
			+ "and anyone confirmed or waitlisted, can still see it once it's cancelled.";

	/**
	 * The description of the 409 for the host leaving.
	 */
	private static final String HOST_CANNOT_LEAVE = "errorType HOST_CANNOT_LEAVE: the host always holds a spot. They "
			+ "cancel the event instead.";

	/**
	 * {@code /spots} may be kept only by the caller's own browser, which must
	 * revalidate it every time (D-013 rule 16).
	 */
	private static final CacheControl SPOTS_CACHE_CONTROL = CacheControl.noCache().cachePrivate();

	/**
	 * The {@link RsvpService}.
	 */
	private final RsvpService rsvpService;

	/**
	 * Creates the controller.
	 *
	 * @param rsvpService the {@link RsvpService}
	 */
	public RsvpController(RsvpService rsvpService) {
		this.rsvpService = rsvpService;
	}

	/**
	 * Joins an event: a spot if one is free, otherwise the back of the waitlist.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param eventId        the event's public ID
	 * @return the {@link RsvpResponse} envelope
	 */
	@PostMapping("/rsvp")
	@Operation(summary = "Join an event", description = "Takes a free spot (CONFIRMED) or, when the event is full, the "
			+ "back of the waitlist (WAITLISTED, with a position). First come, first served: the claim is one atomic "
			+ "update, so the event is never overbooked. An unlimited event always confirms. Joining again while "
			+ "confirmed, waitlisted or offered returns the current status and changes nothing, so a double tap is "
			+ "safe. Someone who left, declined an offer or let one expire may join again, at the back of the queue "
			+ "(D-014). Checks run in this order: visibility (404), "
			+ "an existing RSVP (200 with its status, so the host always gets CONFIRMED), removal (RSVP_REMOVED), "
			+ "then the sign-up window (SIGNUP_NOT_OPEN, SIGNUP_CLOSED).")
	// Declaring the 409 drops springdoc's inferred 200, so the 200 is declared too.
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "404", description = NOT_VISIBLE)
	@ApiResponse(responseCode = "409", description = "errorType SIGNUP_NOT_OPEN: signupOpensAt hasn't come yet. "
			+ "SIGNUP_CLOSED: the event has started. RSVP_REMOVED: the host removed the caller, who can't rejoin.")
	@ApiResponse(responseCode = "429", description = "Rate limited (errorType TOO_MANY_REQUESTS). Joins are limited to "
			+ "about one attempt per second per user, so wait before retrying.")
	public ResponseRecord<RsvpResponse> join(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The event's public ID") @PathVariable UUID eventId) {
		return new ResponseRecord<>(rsvpService.join(HttpUtil.userId(authentication), eventId));
	}

	/**
	 * Leaves an event, freeing the caller's spot or place in the waitlist.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param eventId        the event's public ID
	 */
	@DeleteMapping("/rsvp")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Leave an event", description = "Gives up the caller's spot, place in the waitlist or open "
			+ "offer: while the caller holds an offer, this declines it. A freed spot is offered to the next person "
			+ "in the waitlist for the event's confirmWindowMinutes, or confirms them at once when the event starts "
			+ "sooner than that (D-014). Nobody is offered a spot once the event has started or is cancelled. "
			+ "Leaving without an RSVP is also a 204.")
	@ApiResponse(responseCode = "204", description = "Left, or wasn't signed up.")
	@ApiResponse(responseCode = "404", description = NOT_VISIBLE)
	@ApiResponse(responseCode = "409", description = HOST_CANNOT_LEAVE)
	public void leave(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The event's public ID") @PathVariable UUID eventId) {
		rsvpService.leave(HttpUtil.userId(authentication), eventId);
	}

	/**
	 * Accepts the spot the caller has been offered.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param eventId        the event's public ID
	 * @return the {@link RsvpResponse} envelope
	 */
	@PostMapping("/rsvp/accept")
	@Operation(summary = "Accept an offered spot", description = "Turns the caller's open offer into a confirmed spot "
			+ "(D-014). It works until offerExpiresAt, even if the expiry job hasn't run yet or the event has since "
			+ "started. Accepting again while confirmed returns CONFIRMED and changes nothing. Checks run in this "
			+ "order: visibility (404), already confirmed (200), an offer past its deadline or expired (OFFER_EXPIRED), "
			+ "then CONFIRMED for an open offer on a published event, and NO_OFFER otherwise.")
	@ApiResponse(responseCode = "200", description = "OK: status is CONFIRMED.")
	@ApiResponse(responseCode = "404", description = NOT_VISIBLE)
	@ApiResponse(responseCode = "409", description = "errorType OFFER_EXPIRED: the caller's offer ran out; they may "
			+ "join again, at the back of the queue. NO_OFFER: the caller holds no open offer: none was made, they "
			+ "declined it, or the event was cancelled.")
	public ResponseRecord<RsvpResponse> accept(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The event's public ID") @PathVariable UUID eventId) {
		return new ResponseRecord<>(rsvpService.accept(HttpUtil.userId(authentication), eventId));
	}

	/**
	 * Returns who is going: the confirmed list, and for the host also the waitlist
	 * and open offers.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param eventId        the event's public ID
	 * @return the {@link AttendeesResponse} envelope
	 */
	@GetMapping("/attendees")
	@Operation(summary = "List who is going", description = "Only the host and confirmed attendees can call this. "
			+ "Both get the confirmed list; the host also gets the waitlist and the people holding an open offer, in "
			+ "queue order. Nobody else ever sees who is waitlisted or offered.")
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "404", description = "The caller is neither the host nor confirmed, or can't see the "
			+ "event.")
	public ResponseRecord<AttendeesResponse> getAttendees(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The event's public ID") @PathVariable UUID eventId) {
		return new ResponseRecord<>(rsvpService.getAttendees(HttpUtil.userId(authentication), eventId));
	}

	/**
	 * Removes someone from an event the caller hosts.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param eventId        the event's public ID
	 * @param userId         the person's user ID
	 */
	@DeleteMapping("/rsvps/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Remove someone from an event the user hosts", description = "Takes away their spot or place "
			+ "in the waitlist, or their open offer, and they can't rejoin. They aren't notified. A freed spot goes "
			+ "to the next person in the waitlist, as for leaving. Removing someone who isn't signed up is also a 204, and writes nothing: they can still join. "
			+ "404 unless the user is the host.")
	@ApiResponse(responseCode = "204", description = "Removed, or wasn't signed up.")
	@ApiResponse(responseCode = "404", description = "The user isn't the event's host.")
	@ApiResponse(responseCode = "409", description = HOST_CANNOT_LEAVE)
	public void removeAttendee(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The event's public ID") @PathVariable UUID eventId,
			@Parameter(description = "The person's user ID") @PathVariable UUID userId) {
		rsvpService.remove(HttpUtil.userId(authentication), eventId, userId);
	}

	/**
	 * Returns an event's spot counts and the caller's own place, for polling.
	 * Spring answers a matching {@code If-None-Match} with a bodiless 304 that
	 * keeps these headers.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param eventId        the event's public ID
	 * @return the {@link SpotsResponse} envelope, with its {@code ETag}
	 */
	@GetMapping("/spots")
	@Operation(summary = "Get spot counts", description = "A small response for polling: about every 3 seconds in "
			+ "the 2 minutes around signupOpensAt, every 30 seconds otherwise, and not while the page is hidden. "
			+ "Anyone who can see the event can call it. myStatus, myPosition and offerExpiresAt are always the "
			+ "caller's own. Send "
			+ "the last ETag in If-None-Match to get a bodiless 304 while nothing the caller would see has changed. "
			+ "Responses are sent with Cache-Control: private, no-cache.")
	@Parameter(in = ParameterIn.HEADER, name = HttpHeaders.IF_NONE_MATCH, description = "The ETag of the caller's "
			+ "last 200 from this endpoint.", schema = @Schema(type = "string"))
	@ApiResponse(responseCode = "200", description = "OK", headers = @Header(name = HttpHeaders.ETAG, description = "A "
			+ "tag for this response, which is specific to the caller. Send it back in If-None-Match.", schema = @Schema(type = "string")))
	@ApiResponse(responseCode = "304", description = "Not modified: If-None-Match matches. No body.", content = @Content)
	@ApiResponse(responseCode = "404", description = NOT_VISIBLE)
	public ResponseEntity<ResponseRecord<SpotsResponse>> getSpots(
			@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The event's public ID") @PathVariable UUID eventId) {
		var spots = rsvpService.getSpots(HttpUtil.userId(authentication), eventId,
				HttpUtil.isModerator(authentication));
		return ResponseEntity.ok().eTag(etag(spots)).cacheControl(SPOTS_CACHE_CONTROL)
				.body(new ResponseRecord<>(spots));
	}

	/**
	 * Returns a tag for a {@code /spots} body: a hash of every field, so it changes
	 * whenever anything the caller sees does, and is the same on every task.
	 *
	 * @param spots the {@link SpotsResponse}
	 * @return the tag, without quotes
	 */
	static String etag(SpotsResponse spots) {
		var fields = String.join(",", Objects.toString(spots.capacity()), Integer.toString(spots.confirmed()),
				Integer.toString(spots.waitlisted()), Objects.toString(spots.spotsLeft()), spots.myStatus().name(),
				Objects.toString(spots.myPosition()), Objects.toString(spots.offerExpiresAt()));
		try {
			var digest = MessageDigest.getInstance("SHA-256").digest(fields.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 22);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
