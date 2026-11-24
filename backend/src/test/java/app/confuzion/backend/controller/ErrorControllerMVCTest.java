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
package app.confuzion.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import app.confuzion.backend.aspect.GlobalControllerExceptionHandler;
import jakarta.servlet.RequestDispatcher;

/**
 * Unit tests for the {@link ErrorControllerMVC} class.
 *
 * @author mcawful
 */
class ErrorControllerMVCTest {
	/**
	 * The {@link ErrorControllerMVC} to test.
	 */
	private final ErrorControllerMVC errorControllerMVC = new ErrorControllerMVC();

	/**
	 * Tests that a dispatched status, such as a filter's {@code sendError(404)}, is
	 * kept and gets a generic body with a correlation ID.
	 */
	@Test
	void testHandleErrorKeepsDispatchedStatus() {
		var request = new MockHttpServletRequest();
		request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);

		var response = errorControllerMVC.handleError(request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().errorType()).isEqualTo("NOT_FOUND");
		assertThat(response.getBody().correlationId()).isNotBlank()
				.isEqualTo(response.getHeaders().getFirst(GlobalControllerExceptionHandler.CORRELATION_ID_HEADER));
	}

	/**
	 * Tests that a filter exception without a status becomes a generic 500 that
	 * doesn't carry the exception's message.
	 */
	@Test
	void testHandleErrorWithExceptionAndNoStatus() {
		var request = new MockHttpServletRequest();
		request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("valkey host 10.0.0.9"));

		var response = errorControllerMVC.handleError(request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().errorMessage()).isEqualTo("Internal Server Error");
		assertThat(response.getBody().toString()).doesNotContain("valkey");
	}
}
