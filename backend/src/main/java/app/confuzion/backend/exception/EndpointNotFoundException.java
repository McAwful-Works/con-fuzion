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
 * Exception to handle cases when the API is directed to an endpoint that is not
 * mapped.
 *
 * @author mcawful
 */
public class EndpointNotFoundException extends RuntimeException {
	@Serial
	private static final long serialVersionUID = -3524909008372749037L;

	/**
	 * The default exception message.
	 */
	private static final String DEFAULT_MSG = "Endpoint not found";

	/**
	 * Constructs a {@code PageNotFoundException} with the default detail message.
	 */
	public EndpointNotFoundException() {
		super(DEFAULT_MSG);
	}

	/**
	 * Constructs a {@code PageNotFoundException} with the specified detail message
	 * and cause.
	 *
	 * @param message the detail message
	 * @param cause   the cause
	 */
	public EndpointNotFoundException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * Constructs a {@code PageNotFoundException} with the specified detail message.
	 *
	 * @param message the detail message
	 */
	public EndpointNotFoundException(String message) {
		super(message);
	}

	/**
	 * Constructs a {@code PageNotFoundException} with the default detail message
	 * and cause.
	 *
	 * @param cause the cause
	 */
	public EndpointNotFoundException(Throwable cause) {
		super(DEFAULT_MSG, cause);
	}
}
