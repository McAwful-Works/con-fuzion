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

import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import app.confuzion.backend.exception.InvalidInputException;
import app.confuzion.backend.exception.RateLimitException;

/**
 * Unit tests for the {@link GlobalControllerExceptionHandler} class.
 * 
 * @author mcawful
 */
@SpringBootTest
class GlobalControllerExceptionHandlerTest {
	@Autowired
	private GlobalControllerExceptionHandler handler;

	/**
	 * Tests that the {@link GlobalControllerExceptionHandler} has loaded. Asserts
	 * that the {@link GlobalControllerExceptionHandler} is not {@code null}.
	 */
	@Test
	void contextLoads() {
		assertThat(handler).isNotNull();
	}

	/**
	 * Tests the {@code handleTooManyRequests} method of the
	 * {@link GlobalControllerExceptionHandler}. Asserts that the response is a 429
	 * with the expected field values.
	 */
	@Test
	void handleTooManyRequestsTest_shouldReturn429() {
		var exception = new RateLimitException("Too many requests");

		var response = handler.handleTooManyRequests(exception);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(response.getHeaders().getContentType()).hasToString("application/json");
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().errorType()).isEqualTo("RateLimitException");
		assertThat(response.getBody().errorMessage()).isEqualTo("Too many requests");
	}

	/**
	 * Tests the {@code handleConflict} method of the
	 * {@link GlobalControllerExceptionHandler}. Asserts that the response is a 409
	 * with the expected field values.
	 */
	@Test
	void handleConflictTest_shouldReturn409() {
		var exception = new DataIntegrityViolationException("Conflict occurred");

		var response = handler.handleConflict(exception);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getHeaders().getContentType()).hasToString("application/json");
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().errorType()).isEqualTo("DataIntegrityViolationException");
		assertThat(response.getBody().errorMessage()).isEqualTo("Conflict occurred");
	}

	/**
	 * Tests the {@code handleMethodNotAllowed} method of the
	 * {@link GlobalControllerExceptionHandler}. Asserts that the response is a 405
	 * with the expected field values.
	 */
	@Test
	void handleMethodNotAllowedTest_shouldReturn405() {
		var methodName = "testMethod(testArg)";
		var exception = new HttpRequestMethodNotSupportedException(methodName);

		var response = handler.handleMethodNotAllowed(exception);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
		assertThat(response.getHeaders().getContentType()).hasToString("application/json");
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().errorType()).isEqualTo("HttpRequestMethodNotSupportedException");
		assertThat(response.getBody().errorMessage())
				.isEqualTo(String.format("Request method '%s' is not supported", methodName));
	}

	/**
	 * Tests the {@code handleNotFound} method of the
	 * {@link GlobalControllerExceptionHandler}. Asserts that the response is a 404
	 * with the expected field values.
	 */
	@Test
	void handleNotFoundTest_shouldReturn404() {
		var exception = new NoSuchElementException("Resource not found");

		var response = handler.handleNotFound(exception);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getHeaders().getContentType()).hasToString("application/json");
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().errorType()).isEqualTo("NoSuchElementException");
		assertThat(response.getBody().errorMessage()).isEqualTo("Resource not found");
	}

	/**
	 * Tests the {@code handleBadRequest} method of the
	 * {@link GlobalControllerExceptionHandler}. Asserts that the response is a 400
	 * with the expected field values.
	 */
	@Test
	void handleBadRequestTest_shouldReturn400() {
		var rootMessage = "Input is not a number";
		var innerException = new NumberFormatException(rootMessage);
		var exception = new InvalidInputException("Invalid input provided", innerException);

		var response = handler.handleBadRequest(exception);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getHeaders().getContentType()).hasToString("application/json");
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().errorType()).isEqualTo("InvalidInputException");
		assertThat(response.getBody().errorMessage()).isEqualTo(rootMessage);
	}
}
