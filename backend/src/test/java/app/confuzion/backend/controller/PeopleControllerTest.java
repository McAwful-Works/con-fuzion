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

import static app.confuzion.backend.controller.ErrorEnvelopeAssert.assertError;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Unit tests for the {@link PeopleController} contract stub: a valid query
 * answers 501 in the error envelope, and a bad one is a 400.
 *
 * @author mcawful
 */
class PeopleControllerTest {
	/**
	 * The {@link MockMvc}.
	 */
	private final MockMvc mockMvc = ErrorEnvelopeAssert.mockMvc(new PeopleController());

	/**
	 * Tests that queries at both ends of the length limit reach the stub.
	 */
	@Test
	void testSearchIsNotImplemented() throws Exception {
		for (var q : new String[] { "ab", "a".repeat(PeopleController.MAX_QUERY) })
			assertError(mockMvc, get("/api/people").param("q", q), HttpStatus.NOT_IMPLEMENTED);
	}

	/**
	 * Tests that a query that is too short, too long or blank is a 400.
	 *
	 * @param q the bad query
	 */
	@ParameterizedTest
	@ValueSource(strings = { "", "a", "  ", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" })
	void testSearchRejectsBadQuery(String q) throws Exception {
		assertError(mockMvc, get("/api/people").param("q", q), HttpStatus.BAD_REQUEST);
	}

	/**
	 * Tests that the query is required.
	 */
	@Test
	void testSearchRequiresQuery() throws Exception {
		assertError(mockMvc, get("/api/people"), HttpStatus.BAD_REQUEST);
	}
}
