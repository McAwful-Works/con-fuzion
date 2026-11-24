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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import app.confuzion.backend.exception.InvalidInputException;
import app.confuzion.backend.exception.LimitExceededException;
import app.confuzion.backend.exception.RateLimitException;
import app.confuzion.backend.model.record.ErrorResponseBodyRecord;
import app.confuzion.backend.model.record.ResponseRecord;
import app.confuzion.backend.model.record.ResponseType;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletResponse;

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
	 * A {@link MockMvc} over {@link PresetContentTypeController}, so the envelope
	 * goes through Spring's real message converters.
	 */
	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new PresetContentTypeController())
			.setControllerAdvice(handler).build();

	/**
	 * Tests that the envelope is still written as JSON when the failing handler had
	 * already set a non-JSON content type, as a static resource does when Spring
	 * Session fails while the response is being written (#44).
	 */
	@Test
	void testEnvelopeOverridesPresetContentType() throws Exception {
		mockMvc.perform(get("/octet-stream")).andExpect(status().isInternalServerError())
				.andExpect(content().contentTypeCompatibleWith("application/json"))
				.andExpect(header().exists(GlobalControllerExceptionHandler.CORRELATION_ID_HEADER))
				.andExpect(jsonPath("$.responseType").value("ERROR"))
				.andExpect(jsonPath("$.body.errorType").value("INTERNAL_SERVER_ERROR"))
				.andExpect(content().string(not(containsString(SECRET))));
	}

	/**
	 * Tests that the handler itself replaces a preset non-JSON content type and
	 * drops a partly buffered body, for containers or dispatches where Spring MVC
	 * hasn't already cleared them.
	 */
	@Test
	void testRespondResetsPresetContentType() throws IOException {
		var servletResponse = new MockHttpServletResponse();
		servletResponse.setContentType("application/octet-stream");
		servletResponse.getOutputStream().write(new byte[] { 1, 2, 3 });

		assertGeneric(handler.handleUnexpected(new IllegalStateException(SECRET), servletResponse),
				HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(servletResponse.getContentType()).isEqualTo("application/json");
		assertThat(servletResponse.getContentAsByteArray()).isEmpty();
	}

	/**
	 * Tests that a committed response gets no envelope and no exception.
	 */
	@Test
	void testRespondLeavesCommittedResponseAlone() {
		var servletResponse = new MockHttpServletResponse();
		servletResponse.setContentType("application/octet-stream");
		servletResponse.setCommitted(true);

		assertThat(handler.handleUnexpected(new IllegalStateException(SECRET), servletResponse)).isNull();
		assertThat(servletResponse.getContentType()).isEqualTo("application/octet-stream");
	}

	/**
	 * Tests that a failure after the response was committed is logged and left
	 * alone: the bytes already sent stay as they are and nothing is thrown again.
	 */
	@Test
	void testCommittedResponseIsLeftAlone() throws Exception {
		mockMvc.perform(get("/committed")).andExpect(status().isOk())
				.andExpect(content().string(PresetContentTypeController.PARTIAL_BODY));
	}

	/**
	 * Tests the {@code handleTooManyRequests} method. Asserts a generic 429.
	 */
	@Test
	void testHandleTooManyRequests() {
		assertGeneric(handler.handleTooManyRequests(new RateLimitException(SECRET), new MockHttpServletResponse()),
				HttpStatus.TOO_MANY_REQUESTS);
	}

	/**
	 * Tests the {@code handleLimitExceeded} method. Asserts a 429 whose errorType
	 * is the cap's name, with the generic message and a correlation ID, logged at
	 * debug like the rate limit's 429s.
	 */
	@Test
	void testHandleLimitExceeded() {
		var appender = new ListAppender<ILoggingEvent>();
		appender.start();
		var logger = (Logger) LoggerFactory.getLogger(GlobalControllerExceptionHandler.class);
		var level = logger.getLevel();
		logger.setLevel(Level.DEBUG);
		logger.addAppender(appender);
		try {
			for (var limit : LimitExceededException.Limit.values()) {
				var response = handler.handleLimitExceeded(new LimitExceededException(limit),
						new MockHttpServletResponse());

				assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
				assertThat(response.getBody().responseType()).isEqualTo(ResponseType.ERROR);
				var body = response.getBody().body();
				assertThat(body.errorType()).isEqualTo(limit.name());
				assertThat(body.errorMessage()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase());
				assertThat(body.correlationId()).isNotBlank().isEqualTo(
						response.getHeaders().getFirst(GlobalControllerExceptionHandler.CORRELATION_ID_HEADER));
			}
		} finally {
			logger.detachAppender(appender);
			logger.setLevel(level);
		}

		assertThat(appender.list).hasSize(LimitExceededException.Limit.values().length)
				.allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.DEBUG));
	}

	/**
	 * Tests the {@code handleConflict} method with a nested root cause. Asserts a
	 * generic 409 that contains neither message.
	 */
	@Test
	void testHandleConflict() {
		var exception = new DataIntegrityViolationException("could not execute statement",
				new IllegalStateException(SECRET));

		assertGeneric(handler.handleConflict(exception, new MockHttpServletResponse()), HttpStatus.CONFLICT);
	}

	/**
	 * Tests the {@code handleMethodNotAllowed} method. Asserts a generic 405.
	 */
	@Test
	void testHandleMethodNotAllowed() {
		var exception = new HttpRequestMethodNotSupportedException(SECRET);

		assertGeneric(handler.handleMethodNotAllowed(exception, new MockHttpServletResponse()),
				HttpStatus.METHOD_NOT_ALLOWED);
	}

	/**
	 * Tests the {@code handleNotFound} method. Asserts a generic 404.
	 */
	@Test
	void testHandleNotFound() {
		assertGeneric(handler.handleNotFound(new NoSuchElementException(SECRET), new MockHttpServletResponse()),
				HttpStatus.NOT_FOUND);
	}

	/**
	 * Tests the {@code handleBadRequest} method with a nested root cause. Asserts a
	 * generic 400.
	 */
	@Test
	void testHandleBadRequest() {
		var exception = new InvalidInputException("Invalid input\r\nforged log line",
				new NumberFormatException(SECRET));

		assertGeneric(handler.handleBadRequest(exception, new MockHttpServletResponse()), HttpStatus.BAD_REQUEST);
	}

	/**
	 * Tests that unreadable bodies and mistyped arguments are 400s, not 500s.
	 */
	@Test
	void testHandleBadRequestForMalformedInput() throws NoSuchMethodException {
		var unreadable = new HttpMessageNotReadableException(SECRET, new MockHttpInputMessage(new byte[0]));
		var parameter = new MethodParameter(Object.class.getMethod("equals", Object.class), 0);
		var mismatch = new MethodArgumentTypeMismatchException(SECRET, Integer.class, "page", parameter, null);

		assertGeneric(handler.handleBadRequest(unreadable, new MockHttpServletResponse()), HttpStatus.BAD_REQUEST);
		assertGeneric(handler.handleBadRequest(mismatch, new MockHttpServletResponse()), HttpStatus.BAD_REQUEST);
	}

	/**
	 * Tests that status headers a Spring exception carries are kept.
	 */
	@Test
	void testRespondKeepsErrorResponseHeaders() {
		var exception = new HttpRequestMethodNotSupportedException("DELETE", List.of("GET", "POST"));

		var response = handler.handleMethodNotAllowed(exception, new MockHttpServletResponse());

		assertThat(response.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
	}

	/**
	 * Tests a response built without an exception, as for a bare error dispatch.
	 */
	@Test
	void testRespondWithoutException() {
		assertGeneric(
				GlobalControllerExceptionHandler.respond(HttpStatus.NOT_FOUND, null, new MockHttpServletResponse()),
				HttpStatus.NOT_FOUND);
	}

	/**
	 * Tests the catch-all with an unexpected exception. Asserts a generic 500.
	 */
	@Test
	void testHandleUnexpectedServerError() {
		assertGeneric(handler.handleUnexpected(new IllegalStateException(SECRET), new MockHttpServletResponse()),
				HttpStatus.INTERNAL_SERVER_ERROR);
	}

	/**
	 * Tests the catch-all with Spring MVC exceptions that carry their own status.
	 * Asserts the status is kept.
	 */
	@Test
	void testHandleUnexpectedKeepsErrorResponseStatus() {
		assertGeneric(handler.handleUnexpected(new NoResourceFoundException(HttpMethod.GET, "/api/x", SECRET),
				new MockHttpServletResponse()), HttpStatus.NOT_FOUND);
		assertGeneric(
				handler.handleUnexpected(new HttpMediaTypeNotSupportedException(SECRET), new MockHttpServletResponse()),
				HttpStatus.UNSUPPORTED_MEDIA_TYPE);
	}

	/**
	 * Tests the catch-all with a status that {@link HttpStatus} doesn't know.
	 * Asserts the generic fallback body.
	 */
	@Test
	void testHandleUnexpectedUnknownStatus() {
		var response = handler.handleUnexpected(new ResponseStatusException(HttpStatusCode.valueOf(499), SECRET),
				new MockHttpServletResponse());

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
		var first = handler.handleNotFound(new NoSuchElementException(), new MockHttpServletResponse());
		var second = handler.handleNotFound(new NoSuchElementException(), new MockHttpServletResponse());

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

	/**
	 * A controller whose handlers fail after touching the response.
	 *
	 * @author mcawful
	 */
	@RestController
	static class PresetContentTypeController {
		/**
		 * The bytes {@code /committed} sends before it fails.
		 */
		static final String PARTIAL_BODY = "partial";

		/**
		 * Sets a non-JSON content type, then fails.
		 *
		 * @param response the {@link HttpServletResponse}
		 */
		@GetMapping("/octet-stream")
		void octetStream(HttpServletResponse response) {
			response.setContentType("application/octet-stream");
			throw new IllegalStateException(SECRET);
		}

		/**
		 * Commits part of a body, then fails.
		 *
		 * @param response the {@link HttpServletResponse}
		 * @throws IOException when the write fails
		 */
		@GetMapping("/committed")
		void committed(HttpServletResponse response) throws IOException {
			response.setContentType("application/octet-stream");
			response.getWriter().write(PARTIAL_BODY);
			response.flushBuffer();
			throw new IllegalStateException(SECRET);
		}
	}
}
