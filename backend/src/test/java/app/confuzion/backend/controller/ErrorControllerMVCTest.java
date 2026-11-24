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
import org.springframework.mock.web.MockHttpServletRequest;

import app.confuzion.backend.model.record.ErrorResponseBodyRecord;
import app.confuzion.backend.model.record.ResponseType;

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
	 * Tests that the {@code handleError} method of the {@link ErrorControllerMVC}
	 * returns the generic not-found error envelope.
	 */
	@Test
	void testHandleErrorWithDefaultRequest() {
		var actual = errorControllerMVC.handleError(new MockHttpServletRequest());

		assertThat(actual.responseType()).isEqualTo(ResponseType.ERROR);
		assertThat(actual.body()).isEqualTo(new ErrorResponseBodyRecord("NOT_FOUND", "Not Found", null));
	}
}
