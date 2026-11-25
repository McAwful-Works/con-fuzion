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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import app.confuzion.backend.exception.ConflictException;
import app.confuzion.backend.exception.EventNotEditableException;
import app.confuzion.backend.exception.InvalidInputException;
import app.confuzion.backend.exception.LimitExceededException;
import app.confuzion.backend.exception.RateLimitException;
import app.confuzion.backend.model.record.ErrorResponseBodyRecord;
import app.confuzion.backend.model.record.ResponseRecord;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Global REST Controller advice exception handling.
 * <p>
 * Every response is the standard {@link ResponseRecord} envelope around an
 * {@link ErrorResponseBodyRecord} (D-010). It carries only a generic message
 * for its status plus a fresh correlation ID, which is also sent as the
 * {@value #CORRELATION_ID_HEADER} header. The exception details are logged
 * server-side under that ID and never sent to the client.
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
	 * @param e        the base {@link Exception}
	 * @param response the {@link HttpServletResponse}
	 * @return a {@link ResponseEntity} with an {@link HttpStatus} of
	 *         {@code TOO MANY REQUESTS}
	 */
	@ExceptionHandler(RateLimitException.class)
	public ResponseEntity<ResponseRecord<ErrorResponseBodyRecord>> handleTooManyRequests(Exception e,
			HttpServletResponse response) {
		return respond(HttpStatus.TOO_MANY_REQUESTS, e, response);
	}

	/**
	 * Handles a user hitting a documented cap: a 429 whose errorType is the cap's
	 * {@link LimitExceededException.Limit} name instead of the status name (D-011).
	 * The message stays generic, and it is logged like any other 429.
	 *
	 * @param e        the {@link LimitExceededException}
	 * @param response the {@link HttpServletResponse}
	 * @return a {@link ResponseEntity} with an {@link HttpStatus} of
	 *         {@code TOO MANY REQUESTS}
	 */
	@ExceptionHandler(LimitExceededException.class)
	public ResponseEntity<ResponseRecord<ErrorResponseBodyRecord>> handleLimitExceeded(LimitExceededException e,
			HttpServletResponse response) {
		return respond(HttpStatus.TOO_MANY_REQUESTS, e, e.getLimit().name(), response);
	}

	/**
	 * Method to handle exceptions that should lead to an HTTP status response code
	 * of 409 {@code (CONFLICT)}.
	 *
	 * @param e        the base {@link Exception}
	 * @param response the {@link HttpServletResponse}
	 * @return a {@link ResponseEntity} with an {@link HttpStatus} of
	 *         {@code CONFLICT}
	 */
	@ExceptionHandler({ DataIntegrityViolationException.class, EventNotEditableException.class })
	public ResponseEntity<ResponseRecord<ErrorResponseBodyRecord>> handleConflict(Exception e,
			HttpServletResponse response) {
		return respond(HttpStatus.CONFLICT, e, response);
	}

	/**
	 * Handles a documented state conflict: a 409 whose errorType is the
	 * {@link ConflictException.Conflict} name instead of the status name (D-013).
	 * The message stays generic.
	 *
	 * @param e        the {@link ConflictException}
	 * @param response the {@link HttpServletResponse}
	 * @return a {@link ResponseEntity} with an {@link HttpStatus} of
	 *         {@code CONFLICT}
	 */
	@ExceptionHandler(ConflictException.class)
	public ResponseEntity<ResponseRecord<ErrorResponseBodyRecord>> handleDomainConflict(ConflictException e,
			HttpServletResponse response) {
		return respond(HttpStatus.CONFLICT, e, e.getConflict().name(), response);
	}

	/**
	 * Method to handle exceptions that should lead to an HTTP status response code
	 * of 405 {@code (METHOD NOT ALLOWED)}.
	 *
	 * @param e        the base {@link Exception}
	 * @param response the {@link HttpServletResponse}
	 * @return a {@link ResponseEntity} with an {@link HttpStatus} of
	 *         {@code METHOD NOT ALLOWED}
	 */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ResponseRecord<ErrorResponseBodyRecord>> handleMethodNotAllowed(Exception e,
			HttpServletResponse response) {
		return respond(HttpStatus.METHOD_NOT_ALLOWED, e, response);
	}

	/**
	 * Method to handle exceptions that should lead to an HTTP status response code
	 * of 404 {@code (NOT FOUND)}.
	 *
	 * @param e        the base {@link Exception}
	 * @param response the {@link HttpServletResponse}
	 * @return a {@link ResponseEntity} with an {@link HttpStatus} of
	 *         {@code NOT FOUND}
	 */
	@ExceptionHandler({ NoSuchElementException.class, EmptyResultDataAccessException.class })
	public ResponseEntity<ResponseRecord<ErrorResponseBodyRecord>> handleNotFound(Exception e,
			HttpServletResponse response) {
		return respond(HttpStatus.NOT_FOUND, e, response);
	}

	/**
	 * Method to handle exceptions that should lead to an HTTP status response code
	 * of 400 {@code (BAD REQUEST)}.
	 *
	 * @param e        the base {@link Exception}
	 * @param response the {@link HttpServletResponse}
	 * @return a {@link ResponseEntity} with an {@link HttpStatus} of
	 *         {@code BAD REQUEST}
	 */
	@ExceptionHandler({ IllegalArgumentException.class, MissingServletRequestParameterException.class,
			PropertyValueException.class, InvalidInputException.class, HttpMessageNotReadableException.class,
			MethodArgumentTypeMismatchException.class })
	public ResponseEntity<ResponseRecord<ErrorResponseBodyRecord>> handleBadRequest(Exception e,
			HttpServletResponse response) {
		return respond(HttpStatus.BAD_REQUEST, e, response);
	}

	/**
	 * Catch-all for every other exception. Spring MVC exceptions that already carry
	 * a status (an {@link ErrorResponse}, such as an unmapped path's 404 or an
	 * unsupported media type's 415) keep it; anything else is a 500
	 * {@code (INTERNAL SERVER ERROR)}.
	 *
	 * @param e        the base {@link Exception}
	 * @param response the {@link HttpServletResponse}
	 * @return a {@link ResponseEntity} with the exception's own status, or
	 *         {@code INTERNAL SERVER ERROR}
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ResponseRecord<ErrorResponseBodyRecord>> handleUnexpected(Exception e,
			HttpServletResponse response) {
		var status = e instanceof ErrorResponse errorResponse ? errorResponse.getStatusCode()
				: HttpStatus.INTERNAL_SERVER_ERROR;
		return respond(status, e, response);
	}

	/**
	 * Builds the generic error response for a status and logs the failure under a
	 * new correlation ID. {@code ErrorControllerMVC} uses it too, so errors raised
	 * outside Spring MVC (in filters or the container) look the same.
	 * <p>
	 * Server errors are logged with the stack trace. Client errors are logged as
	 * one line with the exception type only, because client-error messages echo
	 * request input (a validation failure's rejected value, say) and request bodies
	 * are never logged. 429s are logged only at debug, so a flood of throttled
	 * requests doesn't flood the log. Status headers that a Spring
	 * {@link ErrorResponse} carries (such as {@code Allow} on a 405) are kept.
	 * <p>
	 * The failing handler may already have described its own body (a static file's
	 * content type, length and year-long cache headers, say). The JSON envelope
	 * can't be written under that content type and must never be cached, so the
	 * buffer and those headers are reset first. When the response is already
	 * committed, nothing more can be sent: the failure is logged and {@code null}
	 * is returned, which Spring MVC treats as handled (#44).
	 *
	 * @param status   the response {@link HttpStatusCode}
	 * @param e        the failure, or {@code null} when there is none to log
	 * @param response the {@link HttpServletResponse}
	 * @return a {@link ResponseEntity} with a generic
	 *         {@link ErrorResponseBodyRecord} in the {@link ResponseRecord}
	 *         envelope, or {@code null} when the response is already committed
	 */
	public static ResponseEntity<ResponseRecord<ErrorResponseBodyRecord>> respond(HttpStatusCode status, Throwable e,
			HttpServletResponse response) {
		return respond(status, e, null, response);
	}

	/**
	 * Builds the generic error response as
	 * {@link #respond(HttpStatusCode, Throwable, HttpServletResponse)} does, with a
	 * documented domain errorType in place of the status name.
	 *
	 * @param status    the response {@link HttpStatusCode}
	 * @param e         the failure, or {@code null} when there is none to log
	 * @param errorType the domain errorType, or {@code null} for the status name
	 * @param response  the {@link HttpServletResponse}
	 * @return a {@link ResponseEntity} with a generic
	 *         {@link ErrorResponseBodyRecord} in the {@link ResponseRecord}
	 *         envelope, or {@code null} when the response is already committed
	 */
	private static ResponseEntity<ResponseRecord<ErrorResponseBodyRecord>> respond(HttpStatusCode status, Throwable e,
			String errorType, HttpServletResponse response) {
		var correlationId = UUID.randomUUID().toString();
		var type = e == null ? "an error dispatch" : e.getClass().getName();
		if (status.is5xxServerError())
			LOGGER.error("[{}] {} from {}", correlationId, status.value(), type, e);
		else if (status.value() == HttpStatus.TOO_MANY_REQUESTS.value())
			LOGGER.debug("[{}] {} from {}", correlationId, status.value(), type);
		else
			LOGGER.warn("[{}] {} from {}", correlationId, status.value(), type);

		if (response.isCommitted()) {
			LOGGER.warn("[{}] The response was already committed, so no error body was sent", correlationId);
			return null;
		}
		response.resetBuffer();
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setContentLengthLong(-1);
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.setHeader(HttpHeaders.ETAG, null);
		response.setHeader(HttpHeaders.LAST_MODIFIED, null);

		var headers = new HttpHeaders();
		if (e instanceof ErrorResponse errorResponse)
			headers.addAll(errorResponse.getHeaders());
		headers.set(CORRELATION_ID_HEADER, correlationId);
		headers.setContentType(MediaType.APPLICATION_JSON);
		var body = ErrorResponseBodyRecord.of(status, correlationId);
		if (errorType != null)
			body = new ErrorResponseBodyRecord(errorType, body.errorMessage(), correlationId);
		return ResponseEntity.status(status).headers(headers).body(new ResponseRecord<>(body));
	}
}
