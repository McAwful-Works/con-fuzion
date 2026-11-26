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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.confuzion.backend.model.record.ModerationRequest;
import app.confuzion.backend.model.record.ReportPageResponse;
import app.confuzion.backend.model.record.ReportResponse;
import app.confuzion.backend.model.record.ResolveReportRequest;
import app.confuzion.backend.model.record.ResponseRecord;
import app.confuzion.backend.service.EventService;
import app.confuzion.backend.service.ReportService;
import app.confuzion.backend.util.HttpUtil;
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
 * The {@link RestController} for the moderation queue and event moderation
 * (D-015). Every route needs MODERATOR or ADMIN in effect, which
 * {@code SecurityConfig} checks.
 *
 * @author mcawful
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "moderation", description = "The report queue and event moderation (MODERATOR or ADMIN)")
public class ModerationController {
	/**
	 * The {@link ReportService}.
	 */
	private final ReportService reportService;

	/**
	 * The {@link EventService}.
	 */
	private final EventService eventService;

	/**
	 * Creates the controller.
	 *
	 * @param reportService the {@link ReportService}
	 * @param eventService  the {@link EventService}
	 */
	public ModerationController(ReportService reportService, EventService eventService) {
		this.reportService = reportService;
		this.eventService = eventService;
	}

	/**
	 * Returns a page of reports in one status.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param status         the status to list
	 * @param cursor         the {@code nextCursor} from the previous page, or
	 *                       {@code null} for the first page
	 * @param limit          the most reports to return
	 * @return the {@link ReportPageResponse} envelope
	 */
	@GetMapping("/reports")
	@Operation(summary = "List reports (MODERATOR or ADMIN)", description = "OPEN reports oldest first, so the "
			+ "queue is worked in order; ACTIONED and DISMISSED ones newest first.")
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "400", description = "A cursor that this endpoint didn't issue, or a limit out of range.")
	public ResponseRecord<ReportPageResponse> getReports(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "Which reports to list") @RequestParam(defaultValue = "OPEN") ReportResponse.Status status,
			@Parameter(description = "The previous page's nextCursor. Opaque.") @RequestParam(required = false) @Size(max = 200) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String cursor,
			@Parameter(description = "Reports per page, 1 to 50") @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
		return new ResponseRecord<>(reportService.getReports(HttpUtil.userId(authentication), status, cursor, limit));
	}

	/**
	 * Returns one report.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param reportId       the report's ID
	 * @return the {@link ReportResponse} envelope
	 */
	@GetMapping("/reports/{reportId}")
	@Operation(summary = "Get a report (MODERATOR or ADMIN)")
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "404", description = "No such report, or it's about the caller or an event they "
			+ "host: the reported user never sees a report, whatever their role.")
	public ResponseRecord<ReportResponse> getReport(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The report's ID") @PathVariable @Min(1) long reportId) {
		return new ResponseRecord<>(reportService.getReport(HttpUtil.userId(authentication), reportId));
	}

	/**
	 * Resolves an open report.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param reportId       the report's ID
	 * @param request        the {@link ResolveReportRequest}
	 * @return the resolved {@link ReportResponse} envelope
	 */
	@PostMapping("/reports/{reportId}/resolve")
	@Operation(summary = "Resolve a report (MODERATOR or ADMIN)", description = "Closes this one report as ACTIONED "
			+ "or DISMISSED, and sends the reporter a REPORT_REVIEWED notification that says nothing about the "
			+ "outcome. Act on the target first (hide or cancel the event, suspend the user, or ask an admin to ban), "
			+ "then resolve. Audit-logged.")
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "404", description = "No such report, or it's about the caller or an event they "
			+ "host: the reported user never sees a report, whatever their role.")
	@ApiResponse(responseCode = "409", description = "The report is already resolved (errorType REPORT_RESOLVED).")
	public ResponseRecord<ReportResponse> resolveReport(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The report's ID") @PathVariable @Min(1) long reportId,
			@Valid @RequestBody ResolveReportRequest request) {
		return new ResponseRecord<>(reportService.resolve(HttpUtil.userId(authentication), reportId, request));
	}

	/**
	 * Hides an event.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param eventId        the event's public ID
	 * @param request        the {@link ModerationRequest}
	 */
	@PostMapping("/events/{eventId}/hide")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Hide an event (MODERATOR or ADMIN)", description = "Silent for attendees: the event "
			+ "becomes a 404 for everyone but its host and moderators, and its RSVPs are kept. While it's hidden, "
			+ "attendees get no change or reminder notifications and nobody is offered a spot; a cancel still tells "
			+ "them. The host gets EVENT_HIDDEN with the reason and sees "
			+ "the reason as hiddenReason on the event. Hiding a hidden event is a 204 that changes nothing. "
			+ "Audit-logged.")
	@ApiResponse(responseCode = "204", description = "Hidden, or already hidden.")
	@ApiResponse(responseCode = "404", description = "No such event.")
	public void hideEvent(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The event's public ID") @PathVariable UUID eventId,
			@Valid @RequestBody ModerationRequest request) {
		eventService.hide(HttpUtil.userId(authentication), eventId, request.reason());
	}

	/**
	 * Unhides an event.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param eventId        the event's public ID
	 */
	@PostMapping("/events/{eventId}/unhide")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Unhide an event (MODERATOR or ADMIN)", description = "The event is visible again to its "
			+ "audience and clears its reason. Nobody is notified. Unhiding a visible event is a 204 that changes "
			+ "nothing. Audit-logged.")
	@ApiResponse(responseCode = "204", description = "Unhidden, or wasn't hidden.")
	@ApiResponse(responseCode = "404", description = "No such event.")
	public void unhideEvent(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The event's public ID") @PathVariable UUID eventId) {
		eventService.unhide(HttpUtil.userId(authentication), eventId);
	}

	/**
	 * Cancels an event as a moderator.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param eventId        the event's public ID
	 * @param request        the {@link ModerationRequest}
	 */
	@PostMapping("/events/{eventId}/cancel")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Cancel an event (MODERATOR or ADMIN)", description = "As if the host cancelled it: "
			+ "everyone with an active RSVP but the host gets the usual EVENT_CANCELLED, with no reason. The host "
			+ "gets EVENT_CANCELLED with the reason. Cancelling a cancelled or ended event is a 204 that changes "
			+ "nothing, as for a host. Audit-logged.")
	@ApiResponse(responseCode = "204", description = "Cancelled, or nothing to cancel.")
	@ApiResponse(responseCode = "404", description = "No such event.")
	public void cancelEvent(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The event's public ID") @PathVariable UUID eventId,
			@Valid @RequestBody ModerationRequest request) {
		eventService.cancelAsModerator(HttpUtil.userId(authentication), eventId, request.reason());
	}
}
