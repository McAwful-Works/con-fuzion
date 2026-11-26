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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.confuzion.backend.model.record.ReportRequest;
import app.confuzion.backend.service.ReportService;
import app.confuzion.backend.util.HttpUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * The {@link RestController} for reporting an event or a user to the moderators
 * (D-015).
 *
 * @author mcawful
 */
@RestController
@RequestMapping("/api/reports")
@Tag(name = "reports", description = "Reporting an event or a user to the moderators")
public class ReportController {
	/**
	 * The {@link ReportService}.
	 */
	private final ReportService reportService;

	/**
	 * Creates the controller.
	 *
	 * @param reportService the {@link ReportService}
	 */
	public ReportController(ReportService reportService) {
		this.reportService = reportService;
	}

	/**
	 * Reports an event or a user.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param request        the {@link ReportRequest}
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Report an event or a user", description = "Every moderator and admin is notified, except the "
			+ "reported user (or the event's host), who never learns of the report or who made it. The reporter "
			+ "later gets a REPORT_REVIEWED notification with no details. A block in either direction doesn't stop a report. "
			+ "Reporting the same target again while the caller's report on it is open is a 204 that changes "
			+ "nothing and doesn't count towards the daily limit (D-015).")
	@ApiResponse(responseCode = "204", description = "Reported, or already reported and still open.")
	@ApiResponse(responseCode = "400", description = "The request failed validation, or the caller reported "
			+ "themself or an event they host.")
	@ApiResponse(responseCode = "404", description = "No such user, or no such event that the caller can see or "
			+ "has ever had an RSVP on.")
	@ApiResponse(responseCode = "429", description = "The caller already made 10 reports in the last 24 hours "
			+ "(errorType REPORT_LIMIT), or is rate limited (errorType TOO_MANY_REQUESTS).")
	public void report(@Parameter(hidden = true) Authentication authentication,
			@Valid @RequestBody ReportRequest request) {
		reportService.report(HttpUtil.userId(authentication), request);
	}
}
