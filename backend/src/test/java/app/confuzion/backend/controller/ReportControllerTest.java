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
import static app.confuzion.backend.controller.ErrorEnvelopeAssert.json;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Unit tests for {@link ReportController}'s contract stub: valid reports are
 * 501s until #85, and bad ones are 400s.
 *
 * @author mcawful
 */
class ReportControllerTest {
	/**
	 * The caller.
	 */
	private static final Authentication AUTHENTICATION = UsernamePasswordAuthenticationToken
			.authenticated(UUID.randomUUID().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

	/**
	 * A target ID.
	 */
	private static final String TARGET = "3f2c9a4e-6d1b-4f7a-9c3e-2b8d5e1a7f60";

	/**
	 * The {@link MockMvc} for the controller.
	 */
	private final MockMvc mockMvc = ErrorEnvelopeAssert.mockMvc(new ReportController());

	/**
	 * Returns valid reports: each target type, every reason, and a note of up to
	 * 1,000 characters.
	 *
	 * @return the requests
	 */
	static Stream<MockHttpServletRequestBuilder> validRequests() {
		return Stream.of(report("{\"targetType\":\"EVENT\",\"targetId\":\"" + TARGET + "\",\"reason\":\"UNSAFE\"}"),
				report("{\"targetType\":\"USER\",\"targetId\":\"" + TARGET + "\",\"reason\":\"HARASSMENT\","
						+ "\"note\":\"" + "x".repeat(1000) + "\"}"),
				report("{\"targetType\":\"USER\",\"targetId\":\"" + TARGET + "\",\"reason\":\"OTHER\",\"note\":null}"));
	}

	/**
	 * Tests that a valid report is a 501 in the error envelope.
	 *
	 * @param request the request
	 */
	@ParameterizedTest
	@MethodSource("validRequests")
	void testValidRequestsAreNotImplementedYet(MockHttpServletRequestBuilder request) throws Exception {
		assertError(mockMvc, request.principal(AUTHENTICATION), HttpStatus.NOT_IMPLEMENTED);
	}

	/**
	 * Returns reports with a missing or unknown field, or an over-long note.
	 *
	 * @return the requests
	 */
	static Stream<MockHttpServletRequestBuilder> badRequests() {
		return Stream
				.of(report("{}"), report("{\"targetType\":\"EVENT\",\"targetId\":\"" + TARGET + "\"}"),
						report("{\"targetType\":\"CIRCLE\",\"targetId\":\"" + TARGET + "\",\"reason\":\"SPAM\"}"),
						report("{\"targetType\":\"USER\",\"targetId\":\"not-a-uuid\",\"reason\":\"SPAM\"}"),
						report("{\"targetType\":\"USER\",\"targetId\":\"" + TARGET + "\",\"reason\":\"RUDE\"}"),
						report("{\"targetType\":\"USER\",\"targetId\":\"" + TARGET
								+ "\",\"reason\":\"SPAM\",\"note\":\"" + "x".repeat(1001) + "\"}"),
						post("/api/reports"));
	}

	/**
	 * Tests that a bad report is a 400.
	 *
	 * @param request the request
	 */
	@ParameterizedTest
	@MethodSource("badRequests")
	void testRejectsBadRequests(MockHttpServletRequestBuilder request) throws Exception {
		assertError(mockMvc, request.principal(AUTHENTICATION), HttpStatus.BAD_REQUEST);
	}

	/**
	 * Returns a report request.
	 *
	 * @param body the JSON body
	 * @return the request
	 */
	private static MockHttpServletRequestBuilder report(String body) {
		return json(post("/api/reports"), body);
	}
}
