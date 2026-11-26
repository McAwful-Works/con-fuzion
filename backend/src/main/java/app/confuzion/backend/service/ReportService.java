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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.confuzion.backend.exception.ConflictException;
import app.confuzion.backend.exception.ConflictException.Conflict;
import app.confuzion.backend.exception.InvalidInputException;
import app.confuzion.backend.exception.LimitExceededException;
import app.confuzion.backend.exception.LimitExceededException.Limit;
import app.confuzion.backend.model.record.AuditDetails;
import app.confuzion.backend.model.record.AuditEntryResponse;
import app.confuzion.backend.model.record.AuditEntryResponse.Action;
import app.confuzion.backend.model.record.NotificationPayload;
import app.confuzion.backend.model.record.NotificationResponse.Type;
import app.confuzion.backend.model.record.ReportPageResponse;
import app.confuzion.backend.model.record.ReportRequest;
import app.confuzion.backend.model.record.ReportResponse;
import app.confuzion.backend.model.record.ReportResponse.Status;
import app.confuzion.backend.model.record.ReportResponse.TargetType;
import app.confuzion.backend.model.record.ResolveReportRequest;
import app.confuzion.backend.repository.AppUserRepository;
import app.confuzion.backend.repository.AuditLogRepository;
import app.confuzion.backend.repository.EventRepository;
import app.confuzion.backend.repository.NotificationRepository;
import app.confuzion.backend.repository.ReportRepository;

/**
 * Reports and the moderation queue (D-015). Anyone signed in may report an
 * event they can see or were at, or another user; every moderator and admin is
 * told, and the reported user never learns of it. The queue is for moderators
 * only, which {@code SecurityConfig} checks, and never shows a moderator the
 * reports about themself or their own events.
 *
 * @author mcawful
 */
@Service
public class ReportService {
	/**
	 * The most reports a user may make in any 24 hours.
	 */
	static final int MAX_REPORTS_PER_DAY = 10;

	/**
	 * Separates a cursor's fields before encoding.
	 */
	private static final String CURSOR_SEPARATOR = ",";

	/**
	 * The {@link ReportRepository}.
	 */
	private final ReportRepository reportRepository;

	/**
	 * The {@link EventRepository}, for event targets.
	 */
	private final EventRepository eventRepository;

	/**
	 * The {@link AppUserRepository}, for user targets.
	 */
	private final AppUserRepository appUserRepository;

	/**
	 * The {@link NotificationRepository}.
	 */
	private final NotificationRepository notificationRepository;

	/**
	 * The {@link AuditLogRepository}.
	 */
	private final AuditLogRepository auditLogRepository;

	/**
	 * Creates the service.
	 *
	 * @param reportRepository       the {@link ReportRepository}
	 * @param eventRepository        the {@link EventRepository}
	 * @param appUserRepository      the {@link AppUserRepository}
	 * @param notificationRepository the {@link NotificationRepository}
	 * @param auditLogRepository     the {@link AuditLogRepository}
	 */
	public ReportService(ReportRepository reportRepository, EventRepository eventRepository,
			AppUserRepository appUserRepository, NotificationRepository notificationRepository,
			AuditLogRepository auditLogRepository) {
		this.reportRepository = reportRepository;
		this.eventRepository = eventRepository;
		this.appUserRepository = appUserRepository;
		this.notificationRepository = notificationRepository;
		this.auditLogRepository = auditLogRepository;
	}

	/**
	 * Reports an event or a user (D-015 rule 6). A {@code USER} target is any
	 * existing user but the caller; an {@code EVENT} target is one the caller can
	 * see or has ever had an RSVP on, but doesn't host. When the caller already has
	 * an open report on the target, nothing changes and it doesn't count towards
	 * the cap. Otherwise every moderator and admin but the reported user (or the
	 * event's host) gets {@code REPORT_RECEIVED}, in one statement. The duplicate
	 * check and the cap run under a lock on the reporter's row, so concurrent
	 * reports can't overshoot it. A report isn't audit-logged: it's no moderator's
	 * action.
	 *
	 * @param reporterId the signed-in user's ID
	 * @param request    the validated {@link ReportRequest}
	 * @throws InvalidInputException  when the caller reports themself or their own
	 *                                event
	 * @throws NoSuchElementException when the target doesn't exist or the caller
	 *                                may not report it
	 * @throws LimitExceededException when the caller already made
	 *                                {@value #MAX_REPORTS_PER_DAY} reports in the
	 *                                last 24 hours
	 */
	@Transactional
	public void report(UUID reporterId, ReportRequest request) {
		UUID reported;
		if (request.targetType() == TargetType.USER) {
			if (request.targetId().equals(reporterId))
				throw new InvalidInputException();
			if (!appUserRepository.existsById(request.targetId()))
				throw new NoSuchElementException();
			reported = request.targetId();
		} else {
			reported = eventRepository.findReportable(request.targetId(), reporterId).orElseThrow().getHostId();
			if (reported.equals(reporterId))
				throw new InvalidInputException();
		}
		reportRepository.lockReporter(reporterId);
		if (reportRepository.hasOpen(reporterId, request.targetType(), request.targetId()))
			return;
		if (reportRepository.countCreatedInLastDay(reporterId) >= MAX_REPORTS_PER_DAY)
			throw new LimitExceededException(Limit.REPORT_LIMIT);
		var note = request.note() == null || request.note().isBlank() ? null : request.note();
		var reportId = reportRepository.insert(reporterId, request.targetType(), request.targetId(), request.reason(),
				note);
		notificationRepository.insertForModerators(Type.REPORT_RECEIVED, reported,
				new NotificationPayload(null, null, null, null, null, reportId, request.targetType(), null));
	}

	/**
	 * Returns a page of the queue in one status: {@code OPEN} oldest first, the
	 * others newest first (D-015 rule 8).
	 *
	 * @param moderatorId the signed-in moderator's ID
	 * @param status      the status
	 * @param cursor      the previous page's cursor, or {@code null} for the first
	 * @param limit       the most reports to return, already validated
	 * @return the page
	 * @throws InvalidInputException when the cursor is malformed or was issued for
	 *                               another status
	 */
	@Transactional(readOnly = true)
	public ReportPageResponse getReports(UUID moderatorId, Status status, String cursor, int limit) {
		var after = cursor == null ? null : decode(status, cursor);
		var reports = reportRepository.findPage(moderatorId, status, after, limit + 1);
		if (reports.size() <= limit)
			return new ReportPageResponse(reports, null);
		var page = reports.subList(0, limit);
		return new ReportPageResponse(page, encode(status, page.getLast().id()));
	}

	/**
	 * Returns one report.
	 *
	 * @param moderatorId the signed-in moderator's ID
	 * @param reportId    the report's ID
	 * @return the report
	 * @throws NoSuchElementException when it doesn't exist or is about the
	 *                                moderator
	 */
	@Transactional(readOnly = true)
	public ReportResponse getReport(UUID moderatorId, long reportId) {
		return reportRepository.find(moderatorId, reportId).orElseThrow();
	}

	/**
	 * Resolves an open report as {@code ACTIONED} or {@code DISMISSED} (D-015 rule
	 * 9). Its reporter, if they still exist, gets a {@code REPORT_REVIEWED} that
	 * says nothing about the outcome. Audit-logged, in the same transaction.
	 *
	 * @param moderatorId the signed-in moderator's ID
	 * @param reportId    the report's ID
	 * @param request     the validated {@link ResolveReportRequest}
	 * @return the resolved report
	 * @throws NoSuchElementException when it doesn't exist or is about the
	 *                                moderator
	 * @throws ConflictException      when it is already resolved
	 */
	@Transactional
	public ReportResponse resolve(UUID moderatorId, long reportId, ResolveReportRequest request) {
		var note = request.resolutionNote() == null || request.resolutionNote().isBlank() ? null
				: request.resolutionNote();
		var resolved = reportRepository.resolve(moderatorId, reportId, request.resolution(), note);
		if (resolved.isEmpty()) {
			reportRepository.find(moderatorId, reportId).orElseThrow();
			throw new ConflictException(Conflict.REPORT_RESOLVED);
		}
		if (resolved.get().reporterId() != null)
			notificationRepository.insert(resolved.get().reporterId(), Type.REPORT_REVIEWED, null,
					new NotificationPayload(null, null, null, null, null, null, null, null));
		auditLogRepository.insert(moderatorId, Action.REPORT_RESOLVED, AuditEntryResponse.TargetType.REPORT,
				Long.toString(reportId), new AuditDetails(null, null, request.resolution(), note, null, null, null));
		return reportRepository.find(moderatorId, reportId).orElseThrow();
	}

	/**
	 * Encodes a queue cursor: the status it was issued for and the last ID, as an
	 * opaque base64url string.
	 *
	 * @param status the status
	 * @param id     the last report's ID
	 * @return the cursor
	 */
	private static String encode(Status status, long id) {
		var raw = status.name() + CURSOR_SEPARATOR + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Decodes a queue cursor that must have been issued for a status.
	 *
	 * @param status the status
	 * @param cursor the opaque cursor, already matching the base64url alphabet
	 * @return the last ID of the previous page
	 * @throws InvalidInputException when it is malformed or for another status
	 */
	private static long decode(Status status, String cursor) {
		try {
			var parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
					.split(CURSOR_SEPARATOR, -1);
			if (parts.length != 2 || !parts[0].equals(status.name()))
				throw new InvalidInputException();
			var id = Long.parseLong(parts[1]);
			if (id < 1)
				throw new InvalidInputException();
			return id;
		} catch (IllegalArgumentException e) {
			throw new InvalidInputException(e);
		}
	}
}
