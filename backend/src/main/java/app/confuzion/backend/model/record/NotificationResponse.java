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
package app.confuzion.backend.model.record;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * One notification in the caller's inbox (D-014).
 *
 * @param id        the notification's ID, higher for newer ones
 * @param type      what happened
 * @param eventId   the event's public ID, or {@code null} for
 *                  {@code FRIEND_ACCEPTED}, {@code REPORT_RECEIVED} and
 *                  {@code REPORT_REVIEWED}
 * @param payload   what the notification says
 * @param createdAt when it was made
 * @param read      whether the caller has marked it read
 * @author mcawful
 */
public record NotificationResponse(
		@Schema(requiredMode = RequiredMode.REQUIRED, minimum = "1", description = "Higher for newer notifications. Send it as upTo to POST /api/notifications/read.") long id,
		@Schema(requiredMode = RequiredMode.REQUIRED) Type type,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "The event's public ID. Null for FRIEND_ACCEPTED, REPORT_RECEIVED and REPORT_REVIEWED.") UUID eventId,
		@Schema(requiredMode = RequiredMode.REQUIRED) NotificationPayload payload,
		@Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
		@Schema(requiredMode = RequiredMode.REQUIRED) boolean read) {
	/**
	 * What a notification is about (D-014, D-015). Nothing is sent when the host
	 * removes someone.
	 *
	 * @author mcawful
	 */
	public enum Type {
		/**
		 * A freed spot is held for the recipient until {@code offerExpiresAt}.
		 */
		SPOT_OFFERED,

		/**
		 * The recipient got a spot without accepting an offer: one freed close to the
		 * start, or a capacity raise close to the start.
		 */
		SPOT_CONFIRMED,

		/**
		 * The start, end or location of an event the recipient is signed up for
		 * changed.
		 */
		EVENT_CHANGED,

		/**
		 * An event the recipient is signed up for was cancelled.
		 */
		EVENT_CANCELLED,

		/**
		 * An event the recipient hosts or is confirmed for starts in an hour.
		 */
		EVENT_REMINDER,

		/**
		 * Someone accepted the recipient's friend invite.
		 */
		FRIEND_ACCEPTED,

		/**
		 * A new report is in the moderation queue. Sent to every user whose MODERATOR
		 * or ADMIN role is in effect (D-015).
		 */
		REPORT_RECEIVED,

		/**
		 * A moderator resolved the recipient's report. It says nothing about the
		 * outcome (D-015).
		 */
		REPORT_REVIEWED,

		/**
		 * Moderators hid an event the recipient hosts, with their reason. Attendees
		 * aren't told (D-015).
		 */
		EVENT_HIDDEN
	}
}
