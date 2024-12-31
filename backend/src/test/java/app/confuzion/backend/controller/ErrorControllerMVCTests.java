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
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;

import app.confuzion.backend.exception.EndpointNotFoundException;
import app.confuzion.backend.model.record.ErrorResponseBodyRecord;
import app.confuzion.backend.model.record.ResponseRecord;

/**
 * Unit tests for the {@link ErrorControllerMVC} class.
 *
 * @author mcawful
 *
 */
@SpringBootTest
class ErrorControllerMVCTests {
	/**
	 * The {@link MockHttpServletRequest}.
	 */
	private static final MockHttpServletRequest MOCK_REQUEST = new MockHttpServletRequest();

	/**
	 * The expected response.
	 */
	private static final ResponseRecord EXPECTED_RESPONSE = new ResponseRecord(
			new ErrorResponseBodyRecord(new EndpointNotFoundException()));

	/**
	 * The {@link ErrorControllerMVC} component to test.
	 */
	@Autowired
	private ErrorControllerMVC errorControllerMVC;

	/**
	 * Tests that the {@link ErrorControllerMVC} has loaded. Asserts that the
	 * {@link ErrorControllerMVC} is not {@code null}.
	 */
	@Test
	void contextLoads() {
		assertThat(errorControllerMVC).isNotNull();
	}

	/**
	 * Tests that the {@code handleError} method of the {@link ErrorControllerMVC}
	 * returns the string 'Page not found'. Asserts that the returned result equals
	 * what is expected.
	 */
	@Test
	void handleErrorTest() {
		assertEquals(EXPECTED_RESPONSE, errorControllerMVC.handleError(MOCK_REQUEST));
	}
}