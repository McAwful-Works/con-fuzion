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
 * One row of the insert-only audit log, as an admin sees it (D-015).
 *
 * @param id         the row's ID, higher for newer rows
 * @param actorId    the moderator or admin who acted, or {@code null} for the
 *                   system
 * @param action     what happened
 * @param targetType what it happened to
 * @param targetId   the target's ID, as text
 * @param details    the action's own fields
 * @param createdAt  when it happened
 * @author mcawful
 */
public record AuditEntryResponse(@Schema(requiredMode = RequiredMode.REQUIRED, minimum = "1") long id,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "Who acted. Null for the system: a bootstrap grant or a refused sign-in. Kept after the account is deleted.") UUID actorId,
		@Schema(requiredMode = RequiredMode.REQUIRED) Action action,
		@Schema(requiredMode = RequiredMode.REQUIRED) TargetType targetType,
		@Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 36, description = "A user ID or event public ID (UUID), a report ID, or a Discord ID, by targetType.") String targetId,
		@Schema(requiredMode = RequiredMode.REQUIRED) AuditDetails details,
		@Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt) {
	/**
	 * What an audit row records (D-015). A request that changes nothing, such as
	 * hiding an event that is already hidden, writes no row.
	 *
	 * @author mcawful
	 */
	public enum Action {
		/**
		 * A user's granted roles were set (target {@code USER}); {@code roles} is the
		 * new set. The system grants ADMIN to a bootstrap ID.
		 */
		ROLES_CHANGED,

		/**
		 * A user's sessions were deleted (target {@code USER}); {@code cause} is the
		 * action that did it and {@code sessions} how many.
		 */
		SESSIONS_PURGED,

		/**
		 * The system refused a sign-in (target {@code DISCORD_ACCOUNT}); {@code cause}
		 * is {@code ACCOUNT_BANNED} or {@code USER_SUSPENDED}.
		 */
		SIGN_IN_REFUSED,

		/**
		 * A report was resolved (target {@code REPORT}), with {@code resolution} and
		 * {@code note}.
		 */
		REPORT_RESOLVED,

		/**
		 * An event was hidden (target {@code EVENT}), with {@code reason}.
		 */
		EVENT_HIDDEN,

		/**
		 * An event was unhidden (target {@code EVENT}).
		 */
		EVENT_UNHIDDEN,

		/**
		 * A moderator cancelled an event (target {@code EVENT}), with {@code reason}.
		 */
		EVENT_CANCELLED,

		/**
		 * A user was suspended (target {@code USER}), with {@code until} and
		 * {@code reason}.
		 */
		USER_SUSPENDED,

		/**
		 * A user's suspension was lifted (target {@code USER}).
		 */
		USER_UNSUSPENDED,

		/**
		 * A Discord ID was banned (target {@code DISCORD_ACCOUNT}), with
		 * {@code reason}.
		 */
		ACCOUNT_BANNED,

		/**
		 * A Discord ID's ban was lifted (target {@code DISCORD_ACCOUNT}).
		 */
		ACCOUNT_UNBANNED
	}

	/**
	 * What an audit row's {@code targetId} names.
	 *
	 * @author mcawful
	 */
	public enum TargetType {
		/**
		 * A user ID.
		 */
		USER,

		/**
		 * An event's public ID.
		 */
		EVENT,

		/**
		 * A report ID.
		 */
		REPORT,

		/**
		 * A Discord user ID, which may have no account.
		 */
		DISCORD_ACCOUNT
	}
}
