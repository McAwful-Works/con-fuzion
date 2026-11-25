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

import app.confuzion.backend.model.ResponseBodyObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * A report as moderators see it (D-015). The reported user never sees a report
 * or who made it.
 *
 * @param id             the report's ID
 * @param targetType     whether an event or a user was reported
 * @param targetId       the event's public ID or the user's ID
 * @param targetLabel    the event's title or the user's display name, or
 *                       {@code null} once the target is gone
 * @param reason         why it was reported
 * @param note           the reporter's note, or {@code null}
 * @param status         where the report stands
 * @param reporterId     the reporter's user ID, or {@code null} once their
 *                       account is gone
 * @param createdAt      when it was made
 * @param handledBy      the moderator who resolved it, or {@code null} while it
 *                       is open or once their account is gone
 * @param handledAt      when it was resolved, or {@code null} while open
 * @param resolutionNote the moderator's note, or {@code null}
 * @author mcawful
 */
public record ReportResponse(@Schema(requiredMode = RequiredMode.REQUIRED, minimum = "1") long id,
		@Schema(requiredMode = RequiredMode.REQUIRED) TargetType targetType,
		@Schema(requiredMode = RequiredMode.REQUIRED, description = "The event's public ID (moderators can open a reported event with GET /api/events/{eventId}, hidden or not), or the user's ID.") UUID targetId,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, example = "Cosplay photo walk", description = "The event's title or the user's display name. Null once the event was purged or the account deleted: the report outlives its target.") String targetLabel,
		@Schema(requiredMode = RequiredMode.REQUIRED) Reason reason,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, maxLength = 1000, description = "The reporter's note, as plain text.") String note,
		@Schema(requiredMode = RequiredMode.REQUIRED) Status status,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "The reporter's user ID, for moderators only. Null once their account is gone.") UUID reporterId,
		@Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "The moderator who resolved it. Null while open, or once their account is gone.") UUID handledBy,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "Null while open.") Instant handledAt,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, maxLength = 1000, description = "The resolving moderator's note, as plain text. Never sent to the reporter.") String resolutionNote)
		implements ResponseBodyObject {
	/**
	 * What a report is about.
	 *
	 * @author mcawful
	 */
	public enum TargetType {
		/**
		 * An event.
		 */
		EVENT,

		/**
		 * A user.
		 */
		USER
	}

	/**
	 * Why something was reported.
	 *
	 * @author mcawful
	 */
	public enum Reason {
		/**
		 * Harassment of the reporter or someone else.
		 */
		HARASSMENT,

		/**
		 * A risk to someone's safety at a meetup.
		 */
		UNSAFE,

		/**
		 * Spam or advertising.
		 */
		SPAM,

		/**
		 * Content that doesn't belong on Con-Fuzion.
		 */
		INAPPROPRIATE,

		/**
		 * Anything else; the note says what.
		 */
		OTHER
	}

	/**
	 * Where a report stands.
	 *
	 * @author mcawful
	 */
	public enum Status {
		/**
		 * In the moderation queue.
		 */
		OPEN,

		/**
		 * A moderator acted on it.
		 */
		ACTIONED,

		/**
		 * A moderator closed it without acting.
		 */
		DISMISSED
	}
}
