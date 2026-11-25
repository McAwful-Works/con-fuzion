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
 * Thrown when a user hits a documented per-user cap, such as active friend
 * invites. The {@code GlobalControllerExceptionHandler} answers 429 with the
 * cap's {@link Limit} as the errorType (D-011), so a client can tell a cap from
 * the rate limit. The message is never sent to the client.
 *
 * @author mcawful
 */
public class LimitExceededException extends RuntimeException {
	@Serial
	private static final long serialVersionUID = 2961582313540218034L;

	/**
	 * The cap that was hit.
	 */
	private final Limit limit;

	/**
	 * Constructs a {@code LimitExceededException} for a cap.
	 *
	 * @param limit the cap that was hit
	 */
	public LimitExceededException(Limit limit) {
		super(limit.name());
		this.limit = limit;
	}

	/**
	 * Returns the cap that was hit.
	 *
	 * @return the {@link Limit}
	 */
	public Limit getLimit() {
		return limit;
	}

	/**
	 * A documented per-user cap. Each name is the errorType the 429 carries, and
	 * the operations that can hit it name it in their OpenAPI description.
	 *
	 * @author mcawful
	 */
	public enum Limit {
		/**
		 * At most 20 active friend invites per user (D-011).
		 */
		INVITE_LIMIT,

		/**
		 * At most 20 events created per host in any 24 hours (D-012).
		 */
		EVENT_LIMIT,

		/**
		 * A user already made 10 reports in the last 24 hours (D-015).
		 */
		REPORT_LIMIT
	}
}
