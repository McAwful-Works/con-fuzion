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
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a notification says (D-014, D-015), stored in
 * {@code notification.payload} and returned in the inbox. Each
 * {@link NotificationResponse.Type} sets only its own fields, and the others
 * are left out. A payload is safe for a lock screen: it never holds a location
 * or anyone's name. The one exception to "safe for a lock screen" is
 * {@code reason}, a moderator's text for the host: the push text never quotes
 * it, only the inbox shows it.
 *
 * @param eventTitle     the event's title when the notification was made, for
 *                       every type about an event
 * @param offerExpiresAt for {@code SPOT_OFFERED}, when the offer runs out
 * @param startsAt       for {@code EVENT_CHANGED} and {@code EVENT_REMINDER},
 *                       the event's start
 * @param changes        for {@code EVENT_CHANGED}, what changed
 * @param friendId       for {@code FRIEND_ACCEPTED}, the new friend's user ID
 * @param reportId       for {@code REPORT_RECEIVED}, the report's ID
 * @param targetType     for {@code REPORT_RECEIVED}, what was reported
 * @param reason         for {@code EVENT_HIDDEN}, and the host's
 *                       {@code EVENT_CANCELLED} after a moderator cancels, the
 *                       moderator's reason
 * @author mcawful
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationPayload(
		@Schema(description = "The event's title when the notification was made. Set for every type with an eventId.", example = "Cosplay photo walk") String eventTitle,
		@Schema(description = "SPOT_OFFERED only: when the offer runs out. Accept before then.") Instant offerExpiresAt,
		@Schema(description = "EVENT_CHANGED (the start after the change) and EVENT_REMINDER only.") Instant startsAt,
		@Schema(description = "EVENT_CHANGED only: what changed, at least one. The new location is never included.") List<Change> changes,
		@Schema(description = "FRIEND_ACCEPTED only: the new friend's user ID. Never their name: the client may look it up in GET /api/friends.") UUID friendId,
		@Schema(description = "REPORT_RECEIVED only: the report's ID, for GET /api/admin/reports/{reportId}. Never the reporter or the target.", minimum = "1") Long reportId,
		@Schema(description = "REPORT_RECEIVED only: whether an event or a user was reported.") ReportResponse.TargetType targetType,
		@Schema(description = "EVENT_HIDDEN, and the host's EVENT_CANCELLED when a moderator cancelled: the moderator's reason, as plain text. Shown in the inbox only, never in a push.", maxLength = 500) String reason) {
	/**
	 * What an {@code EVENT_CHANGED} notification reports.
	 *
	 * @author mcawful
	 */
	public enum Change {
		/**
		 * The start moved.
		 */
		START,

		/**
		 * The end moved, or was added or removed.
		 */
		END,

		/**
		 * The location changed.
		 */
		LOCATION
	}
}
