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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import app.confuzion.backend.repository.NotificationRepository;

/**
 * Sends the reminder an hour before each event starts, to its host and its
 * confirmed attendees (D-014). Every task runs it. Each event is handled in its
 * own transaction under the event's row lock, and the unique key
 * {@code notification_reminder_key} lets only one reminder per person per event
 * through, so running tasks side by side, or a run that overlaps the next, adds
 * nothing twice.
 *
 * @author mcawful
 */
@Component
public class ReminderJob {
	/**
	 * The {@link Logger}.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ReminderJob.class);

	/**
	 * The {@link NotificationRepository}.
	 */
	private final NotificationRepository notificationRepository;

	/**
	 * The {@link NotificationService}.
	 */
	private final NotificationService notificationService;

	/**
	 * Creates the job.
	 *
	 * @param notificationRepository the {@link NotificationRepository}
	 * @param notificationService    the {@link NotificationService}
	 */
	public ReminderJob(NotificationRepository notificationRepository, NotificationService notificationService) {
		this.notificationRepository = notificationRepository;
		this.notificationService = notificationService;
	}

	/**
	 * Sends the reminders that are due. It runs a minute after the last run ended,
	 * or as often as {@code con-fuzion.notify.reminder-interval} (an ISO-8601
	 * duration) says. One failing event doesn't stop the others, and is tried again
	 * on the next run.
	 */
	@Scheduled(fixedDelayString = "${con-fuzion.notify.reminder-interval:PT1M}", initialDelayString = "${con-fuzion.notify.reminder-interval:PT1M}")
	public void sendDueReminders() {
		for (var eventId : notificationRepository.findEventsToRemind(NotificationService.REMINDER_LEAD)) {
			try {
				notificationService.remind(eventId);
			} catch (RuntimeException e) {
				LOGGER.warn("Reminders for event {} failed", eventId, e);
			}
		}
	}
}
