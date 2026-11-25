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

import app.confuzion.backend.model.ResponseBodyObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * The caller's RSVP after {@code POST /api/events/{eventId}/rsvp}.
 *
 * @param status   whether the caller got a spot or a place in the waitlist
 * @param position the caller's place in the waitlist, 1 for next in line, or
 *                 {@code null} when confirmed
 * @author mcawful
 */
public record RsvpResponse(@Schema(requiredMode = RequiredMode.REQUIRED) Status status,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, minimum = "1", description = "1-based place in the waitlist: 1 + the number of people waitlisted ahead of the caller. Null when CONFIRMED.") Integer position)
		implements ResponseBodyObject {
	/**
	 * The outcome of a join.
	 *
	 * @author mcawful
	 */
	public enum Status {
		/**
		 * The caller holds a spot.
		 */
		CONFIRMED,

		/**
		 * The event is full, so the caller is in the waitlist.
		 */
		WAITLISTED
	}
}
