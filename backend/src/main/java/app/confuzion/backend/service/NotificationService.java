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
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.confuzion.backend.exception.InvalidInputException;
import app.confuzion.backend.model.record.NotificationPageResponse;
import app.confuzion.backend.model.record.NotificationPayload;
import app.confuzion.backend.repository.NotificationRepository;

/**
 * The in-app inbox and the event reminders (D-014). Every inbox query filters
 * by the caller's own ID, so nobody can read or mark another user's
 * notifications.
 *
 * @author mcawful
 */
@Service
public class NotificationService {
	/**
	 * How long before its start an event's reminder goes out.
	 */
	public static final Duration REMINDER_LEAD = Duration.ofHours(1);

	/**
	 * The most unread notifications the inbox counts.
	 */
	static final int UNREAD_CAP = 100;

	/**
	 * The {@link NotificationRepository}.
	 */
	private final NotificationRepository notificationRepository;

	/**
	 * Creates the service.
	 *
	 * @param notificationRepository the {@link NotificationRepository}
	 */
	public NotificationService(NotificationRepository notificationRepository) {
		this.notificationRepository = notificationRepository;
	}

	/**
	 * Returns a page of the caller's inbox, newest first.
	 *
	 * @param userId the caller's ID
	 * @param cursor the previous page's cursor, or {@code null} for the first page
	 * @param limit  the most notifications to return
	 * @return the {@link NotificationPageResponse}
	 * @throws InvalidInputException when the cursor isn't one this method issued
	 */
	@Transactional(readOnly = true)
	public NotificationPageResponse getInbox(UUID userId, String cursor, int limit) {
		var items = notificationRepository.findPage(userId, cursor == null ? null : decode(cursor), limit + 1);
		var unread = notificationRepository.countUnread(userId, UNREAD_CAP);
		if (items.size() <= limit)
			return new NotificationPageResponse(items, null, unread);
		var page = items.subList(0, limit);
		return new NotificationPageResponse(page, encode(page.getLast().id()), unread);
	}

	/**
	 * Marks the caller's notifications read, up to and including an ID. Another
	 * user's notifications, and IDs that don't exist, are left alone and aren't an
	 * error.
	 *
	 * @param userId the caller's ID
	 * @param upTo   the newest notification ID the caller has seen
	 */
	@Transactional
	public void markRead(UUID userId, long upTo) {
		notificationRepository.markRead(userId, upTo);
	}

	/**
	 * Sends an event's reminders to its host and confirmed attendees, once each.
	 * The event row is locked first, so this can't interleave with a cancel, an
	 * edit or a block: an event that has been cancelled, has started or isn't
	 * within the lead time any more gets nothing. A person who already has a
	 * reminder for the event, from this task or another, is skipped by the unique
	 * key, so concurrent runs add each reminder once.
	 *
	 * @param eventId the event's internal ID
	 * @return how many reminders were written
	 */
	@Transactional
	public int remind(long eventId) {
		return notificationRepository.lockDueEvent(eventId, REMINDER_LEAD).map(event -> notificationRepository
				.insertReminders(eventId, new NotificationPayload(event.title(), null, event.startsAt(), null, null)))
				.orElse(0);
	}

	/**
	 * Encodes a notification ID as an opaque cursor.
	 *
	 * @param id the last notification's ID on the page
	 * @return the cursor
	 */
	private static String encode(long id) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(Long.toString(id).getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Decodes a cursor.
	 *
	 * @param cursor the opaque cursor, already matching the base64url alphabet
	 * @return the notification ID it names
	 * @throws InvalidInputException when it isn't a cursor from {@link #encode}
	 */
	private static long decode(String cursor) {
		long id;
		try {
			id = Long.parseLong(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
		} catch (IllegalArgumentException e) {
			throw new InvalidInputException(e);
		}
		if (id < 1)
			throw new InvalidInputException();
		return id;
	}
}
