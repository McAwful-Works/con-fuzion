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
import java.util.Objects;

import org.hibernate.PropertyValueException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import app.confuzion.backend.exception.InvalidInputException;
import app.confuzion.backend.exception.RateLimitException;
import app.confuzion.backend.model.record.ErrorResponseBodyRecord;

/**
 * Global REST Controller advice exception handling.
 *
 * @author mcawful
 */
@ControllerAdvice
public class GlobalControllerExceptionHandler {
	@ExceptionHandler(RateLimitException.class)
	public ResponseEntity<ErrorResponseBodyRecord> handleTooManyRequests(Exception e) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
				.body(getErrorResponseRecord(e));
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
		return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
				.body(getErrorResponseRecord(e));
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

		return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).contentType(MediaType.APPLICATION_JSON)
				.body(getErrorResponseRecord(e));
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
		return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
				.body(getErrorResponseRecord(e));
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
		return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(getErrorResponseRecord(e));
	}

	/**
	 * Creates and return an {@link ErrorResponseBodyRecord} from the given
	 * {@link Exception}.
	 *
	 * @param e the base {@link Exception}
	 * @return an {@link ErrorResponseBodyRecord}
	 */
	private ErrorResponseBodyRecord getErrorResponseRecord(Exception e) {
		return new ErrorResponseBodyRecord(e.getClass().getSimpleName(), getRootCause(e).getMessage());
	}

	/**
	 * Returns the root cause {@link Throwable} from a base {@link Exception}.
	 *
	 * @param e the base {@link Exception}
	 * @return the root cause {@link Throwable}
	 */
	private Throwable getRootCause(Exception e) {
		Objects.requireNonNull(e);
		Throwable cause = e;
		while (cause.getCause() != null && cause.getCause() != cause)
			cause = cause.getCause();

		return cause;
	}
}
