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

import java.util.NoSuchElementException;
import java.util.UUID;

import org.hibernate.PropertyValueException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import app.confuzion.backend.exception.InvalidInputException;
import app.confuzion.backend.exception.RateLimitException;
import app.confuzion.backend.model.record.ErrorResponseBodyRecord;

/**
 * Global REST Controller advice exception handling.
 * <p>
 * Every response carries only a generic message for its status plus a fresh
 * correlation ID, which is also sent as the {@value #CORRELATION_ID_HEADER}
 * header. The exception details are logged server-side under that ID and never
 * sent to the client.
 *
 * @author mcawful
 */
@ControllerAdvice
public class GlobalControllerExceptionHandler {
	/**
	 * The response header that carries the correlation ID.
	 */
	public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

	/**
	 * The {@link Logger}.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(GlobalControllerExceptionHandler.class);

	/**
	 * Method to handle exceptions that should lead to an HTTP status response code
	 * of 429 {@code (TOO MANY REQUESTS)}.
	 *
	 * @param e the base {@link Exception}
	 * @return a {@link ResponseEntity} with an {@link HttpStatus} of
	 *         {@code TOO MANY REQUESTS}
	 */
	@ExceptionHandler(RateLimitException.class)
	public ResponseEntity<ErrorResponseBodyRecord> handleTooManyRequests(Exception e) {
		return respond(HttpStatus.TOO_MANY_REQUESTS, e);
	}

	/**
	 * Method to handle exceptions that should lead to an HTTP status response code
	 * of 409 {@code (CONFLICT)}.
	 *
	 * @param e the base {@link Exception}
	 * @return a {@link ResponseEntity} with an {@link HttpStatus} of
	 *         {@code CONFLICT}
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ErrorResponseBodyRecord> handleConflict(Exception e) {
		return respond(HttpStatus.CONFLICT, e);
	}

	/**
	 * Method to handle exceptions that should lead to an HTTP status response code
	 * of 405 {@code (METHOD NOT ALLOWED)}.
	 *
	 * @param e the base {@link Exception}
	 * @return a {@link ResponseEntity} with an {@link HttpStatus} of
	 *         {@code METHOD NOT ALLOWED}
	 */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorResponseBodyRecord> handleMethodNotAllowed(Exception e) {
		return respond(HttpStatus.METHOD_NOT_ALLOWED, e);
	}

	/**
	 * Method to handle exceptions that should lead to an HTTP status response code
	 * of 404 {@code (NOT FOUND)}.
	 *
	 * @param e the base {@link Exception}
	 * @return a {@link ResponseEntity} with an {@link HttpStatus} of
	 *         {@code NOT FOUND}
	 */
	@ExceptionHandler({ NoSuchElementException.class, EmptyResultDataAccessException.class })
	public ResponseEntity<ErrorResponseBodyRecord> handleNotFound(Exception e) {
		return respond(HttpStatus.NOT_FOUND, e);
	}

	/**
	 * Method to handle exceptions that should lead to an HTTP status response code
	 * of 400 {@code (BAD REQUEST)}.
	 *
	 * @param e the base {@link Exception}
	 * @return a {@link ResponseEntity} with an {@link HttpStatus} of
	 *         {@code BAD REQUEST}
	 */
	@ExceptionHandler({ IllegalArgumentException.class, MissingServletRequestParameterException.class,
			PropertyValueException.class, InvalidInputException.class })
	public ResponseEntity<ErrorResponseBodyRecord> handleBadRequest(Exception e) {
		return respond(HttpStatus.BAD_REQUEST, e);
	}

	/**
	 * Catch-all for every other exception. Spring MVC exceptions that already carry
	 * a status (an {@link ErrorResponse}, such as an unmapped path's 404 or an
	 * unsupported media type's 415) keep it; anything else is a 500
	 * {@code (INTERNAL SERVER ERROR)}.
	 *
	 * @param e the base {@link Exception}
	 * @return a {@link ResponseEntity} with the exception's own status, or
	 *         {@code INTERNAL SERVER ERROR}
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponseBodyRecord> handleUnexpected(Exception e) {
		var status = e instanceof ErrorResponse errorResponse ? errorResponse.getStatusCode()
				: HttpStatus.INTERNAL_SERVER_ERROR;
		return respond(status, e);
	}

	/**
	 * Logs the exception under a new correlation ID and builds the generic error
	 * response. Server errors are logged with the stack trace; client errors are
	 * logged as one line with the exception type and message.
	 *
	 * @param status the response {@link HttpStatusCode}
	 * @param e      the base {@link Exception}
	 * @return a {@link ResponseEntity} with a generic
	 *         {@link ErrorResponseBodyRecord}
	 */
	private static ResponseEntity<ErrorResponseBodyRecord> respond(HttpStatusCode status, Exception e) {
		var correlationId = UUID.randomUUID().toString();
		if (status.is5xxServerError())
			LOGGER.error("[{}] {} from {}", correlationId, status.value(), e.getClass().getName(), e);
		else
			LOGGER.warn("[{}] {} from {}: {}", correlationId, status.value(), e.getClass().getName(),
					singleLine(e.getMessage()));

		return ResponseEntity.status(status).header(CORRELATION_ID_HEADER, correlationId)
				.contentType(MediaType.APPLICATION_JSON).body(ErrorResponseBodyRecord.of(status, correlationId));
	}

	/**
	 * Replaces line breaks so an exception message that echoes request input can't
	 * forge extra log lines.
	 *
	 * @param message the exception message, possibly {@code null}
	 * @return the message on one line
	 */
	private static String singleLine(String message) {
		return message == null ? null : message.replaceAll("[\\r\\n]+", " ");
	}
}
