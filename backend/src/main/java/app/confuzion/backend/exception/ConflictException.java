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
package app.confuzion.backend.exception;

import java.io.Serial;

/**
 * Thrown when a request conflicts with the current state of an event, an RSVP,
 * a report or a user's roles in a way the client should be able to tell apart,
 * such as joining before sign-up opens. The
 * {@code GlobalControllerExceptionHandler} answers 409 with the
 * {@link Conflict} as the errorType (D-013), as {@link LimitExceededException}
 * does for a 429 cap. The message is never sent to the client.
 *
 * @author mcawful
 */
public class ConflictException extends RuntimeException {
	@Serial
	private static final long serialVersionUID = -4127305926518836601L;

	/**
	 * The conflict.
	 */
	private final Conflict conflict;

	/**
	 * Constructs a {@code ConflictException}.
	 *
	 * @param conflict the conflict
	 */
	public ConflictException(Conflict conflict) {
		super(conflict.name());
		this.conflict = conflict;
	}

	/**
	 * Returns the conflict.
	 *
	 * @return the {@link Conflict}
	 */
	public Conflict getConflict() {
		return conflict;
	}

	/**
	 * A documented 409. Each name is the errorType the 409 carries, and the
	 * operations that can return it name it in their OpenAPI description. Caps that
	 * answer 429 are {@link LimitExceededException.Limit} values instead.
	 *
	 * @author mcawful
	 */
	public enum Conflict {
		/**
		 * An edit would make the capacity smaller than the number already confirmed
		 * (D-013).
		 */
		CAPACITY_BELOW_CONFIRMED,

		/**
		 * A join before the event's sign-up opens.
		 */
		SIGNUP_NOT_OPEN,

		/**
		 * A join once the event has started.
		 */
		SIGNUP_CLOSED,

		/**
		 * A join by someone the host removed from the event. They can't rejoin.
		 */
		RSVP_REMOVED,

		/**
		 * The host leaving their own event, or removing themself from it. The host
		 * always holds a spot; they cancel the event instead.
		 */
		HOST_CANNOT_LEAVE,

		/**
		 * An accept after the caller's offer ran out (D-014). They may join again, at
		 * the back of the queue.
		 */
		OFFER_EXPIRED,

		/**
		 * An accept without an open offer: none was made, the caller declined it, or
		 * the event was cancelled (D-014).
		 */
		NO_OFFER,

		/**
		 * Resolving a report that a moderator has already resolved (D-015).
		 */
		REPORT_RESOLVED,

		/**
		 * Suspending or banning someone whose granted role protects them: a moderator
		 * can't suspend anyone with a role, and nobody can suspend or ban an ADMIN
		 * (D-015).
		 */
		TARGET_HAS_ROLE,

		/**
		 * A role change that would leave no user holding ADMIN (D-015).
		 */
		LAST_ADMIN
	}
}
