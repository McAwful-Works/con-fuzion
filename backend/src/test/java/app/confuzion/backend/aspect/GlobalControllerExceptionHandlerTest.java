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
package app.confuzion.backend.aspect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import app.confuzion.backend.exception.InvalidInputException;
import app.confuzion.backend.exception.RateLimitException;
import app.confuzion.backend.model.record.ErrorResponseBodyRecord;
import app.confuzion.backend.model.record.ResponseRecord;
import app.confuzion.backend.model.record.ResponseType;

/**
 * Unit tests for the {@link GlobalControllerExceptionHandler} class. Every
 * exception here carries a {@link #SECRET} that must never reach the body.
 *
 * @author mcawful
 */
class GlobalControllerExceptionHandlerTest {
	/**
	 * Text that stands in for SQL, constraint names or other internals.
	 */
	private static final String SECRET = "constraint uk_secret_internal on table app_user";

	/**
	 * The {@link GlobalControllerExceptionHandler} to test.
	 */
	private final GlobalControllerExceptionHandler handler = new GlobalControllerExceptionHandler();

	/**
	 * Tests the {@code handleTooManyRequests} method. Asserts a generic 429.
	 */
	@Test
	void testHandleTooManyRequests() {
		assertGeneric(handler.handleTooManyRequests(new RateLimitException(SECRET)), HttpStatus.TOO_MANY_REQUESTS);
	}

	/**
	 * Tests the {@code handleConflict} method with a nested root cause. Asserts a
	 * generic 409 that contains neither message.
	 */
	@Test
	void testHandleConflict() {
		var exception = new DataIntegrityViolationException("could not execute statement",
				new IllegalStateException(SECRET));

		assertGeneric(handler.handleConflict(exception), HttpStatus.CONFLICT);
	}

	/**
	 * Tests the {@code handleMethodNotAllowed} method. Asserts a generic 405.
	 */
	@Test
	void testHandleMethodNotAllowed() {
		var exception = new HttpRequestMethodNotSupportedException(SECRET);

		assertGeneric(handler.handleMethodNotAllowed(exception), HttpStatus.METHOD_NOT_ALLOWED);
	}

	/**
	 * Tests the {@code handleNotFound} method. Asserts a generic 404.
	 */
	@Test
	void testHandleNotFound() {
		assertGeneric(handler.handleNotFound(new NoSuchElementException(SECRET)), HttpStatus.NOT_FOUND);
	}

	/**
	 * Tests the {@code handleBadRequest} method with a nested root cause. Asserts a
	 * generic 400.
	 */
	@Test
	void testHandleBadRequest() {
		var exception = new InvalidInputException("Invalid input\r\nforged log line",
				new NumberFormatException(SECRET));

		assertGeneric(handler.handleBadRequest(exception), HttpStatus.BAD_REQUEST);
	}

	/**
	 * Tests that unreadable bodies and mistyped arguments are 400s, not 500s.
	 */
	@Test
	void testHandleBadRequestForMalformedInput() throws NoSuchMethodException {
		var unreadable = new HttpMessageNotReadableException(SECRET, new MockHttpInputMessage(new byte[0]));
		var parameter = new MethodParameter(Object.class.getMethod("equals", Object.class), 0);
		var mismatch = new MethodArgumentTypeMismatchException(SECRET, Integer.class, "page", parameter, null);

		assertGeneric(handler.handleBadRequest(unreadable), HttpStatus.BAD_REQUEST);
		assertGeneric(handler.handleBadRequest(mismatch), HttpStatus.BAD_REQUEST);
	}

	/**
	 * Tests that status headers a Spring exception carries are kept.
	 */
	@Test
	void testRespondKeepsErrorResponseHeaders() {
		var exception = new HttpRequestMethodNotSupportedException("DELETE", List.of("GET", "POST"));

		var response = handler.handleMethodNotAllowed(exception);

		assertThat(response.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
	}

	/**
	 * Tests a response built without an exception, as for a bare error dispatch.
	 */
	@Test
	void testRespondWithoutException() {
		assertGeneric(GlobalControllerExceptionHandler.respond(HttpStatus.NOT_FOUND, null), HttpStatus.NOT_FOUND);
	}

	/**
	 * Tests the catch-all with an unexpected exception. Asserts a generic 500.
	 */
	@Test
	void testHandleUnexpectedServerError() {
		assertGeneric(handler.handleUnexpected(new IllegalStateException(SECRET)), HttpStatus.INTERNAL_SERVER_ERROR);
	}

	/**
	 * Tests the catch-all with Spring MVC exceptions that carry their own status.
	 * Asserts the status is kept.
	 */
	@Test
	void testHandleUnexpectedKeepsErrorResponseStatus() {
		assertGeneric(handler.handleUnexpected(new NoResourceFoundException(HttpMethod.GET, "/api/x", SECRET)),
				HttpStatus.NOT_FOUND);
		assertGeneric(handler.handleUnexpected(new HttpMediaTypeNotSupportedException(SECRET)),
				HttpStatus.UNSUPPORTED_MEDIA_TYPE);
	}

	/**
	 * Tests the catch-all with a status that {@link HttpStatus} doesn't know.
	 * Asserts the generic fallback body.
	 */
	@Test
	void testHandleUnexpectedUnknownStatus() {
		var response = handler.handleUnexpected(new ResponseStatusException(HttpStatusCode.valueOf(499), SECRET));

		assertThat(response.getStatusCode().value()).isEqualTo(499);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().body().errorType()).isEqualTo("ERROR");
		assertThat(response.getBody().body().errorMessage()).isEqualTo("Error");
	}

	/**
	 * Tests that every response gets its own correlation ID.
	 */
	@Test
	void testCorrelationIdsAreUnique() {
		var first = handler.handleNotFound(new NoSuchElementException());
		var second = handler.handleNotFound(new NoSuchElementException());

		assertThat(first.getBody()).isNotNull();
		assertThat(second.getBody()).isNotNull();
		assertThat(first.getBody().body().correlationId()).isNotEqualTo(second.getBody().body().correlationId());
	}

	/**
	 * Asserts a response has the given status, a JSON content type, the generic
	 * body for that status, and a correlation ID that matches its header.
	 *
	 * @param response the {@link ResponseEntity}
	 * @param status   the expected {@link HttpStatus}
	 */
	private static void assertGeneric(ResponseEntity<ResponseRecord<ErrorResponseBodyRecord>> response,
			HttpStatus status) {
		assertThat(response.getStatusCode()).isEqualTo(status);
		assertThat(response.getHeaders().getContentType()).hasToString("application/json");
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().responseType()).isEqualTo(ResponseType.ERROR);
		var body = response.getBody().body();
		assertThat(body.errorType()).isEqualTo(status.name());
		assertThat(body.errorMessage()).isEqualTo(status.getReasonPhrase());
		assertThat(body.correlationId()).isNotBlank()
				.isEqualTo(response.getHeaders().getFirst(GlobalControllerExceptionHandler.CORRELATION_ID_HEADER));
		assertThat(body.toString()).doesNotContain(SECRET).doesNotContain("Exception");
	}
}
