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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Unit tests for {@link ModerationController}'s contract stubs: valid requests
 * are 501s until #85, and bad ones are 400s. {@code SecurityConfig}'s role
 * checks are in {@code SecurityConfigIntegrationTest}.
 *
 * @author mcawful
 */
class ModerationControllerTest {
	/**
	 * A moderator.
	 */
	private static final Authentication AUTHENTICATION = UsernamePasswordAuthenticationToken.authenticated(
			UUID.randomUUID().toString(), null,
			List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_MODERATOR")));

	/**
	 * An event's public ID.
	 */
	private static final String EVENT = "/api/admin/events/3f2c9a4e-6d1b-4f7a-9c3e-2b8d5e1a7f60";

	/**
	 * The {@link MockMvc} for the controller.
	 */
	private final MockMvc mockMvc = ErrorEnvelopeAssert.mockMvc(new ModerationController());

	/**
	 * Returns valid requests to every operation.
	 *
	 * @return the requests
	 */
	static Stream<MockHttpServletRequestBuilder> validRequests() {
		return Stream.of(get("/api/admin/reports"), get("/api/admin/reports?status=DISMISSED&cursor=abc_1-X&limit=50"),
				get("/api/admin/reports/7"),
				json(post("/api/admin/reports/7/resolve"), "{\"resolution\":\"ACTIONED\"}"),
				json(post("/api/admin/reports/7/resolve"),
						"{\"resolution\":\"DISMISSED\",\"resolutionNote\":\"" + "x".repeat(1000) + "\"}"),
				json(post(EVENT + "/hide"), "{\"reason\":\"" + "x".repeat(500) + "\"}"), post(EVENT + "/unhide"),
				json(post(EVENT + "/cancel"), "{\"reason\":\"Unsafe location\"}"));
	}

	/**
	 * Tests that a valid request is a 501 in the error envelope.
	 *
	 * @param request the request
	 */
	@ParameterizedTest
	@MethodSource("validRequests")
	void testValidRequestsAreNotImplementedYet(MockHttpServletRequestBuilder request) throws Exception {
		assertError(mockMvc, request.principal(AUTHENTICATION), HttpStatus.NOT_IMPLEMENTED);
	}

	/**
	 * Returns requests with a bad status, cursor, limit, ID, resolution, note or
	 * reason.
	 *
	 * @return the requests
	 */
	static Stream<MockHttpServletRequestBuilder> badRequests() {
		return Stream.of(get("/api/admin/reports?status=CLOSED"), get("/api/admin/reports?limit=0"),
				get("/api/admin/reports?limit=51"), get("/api/admin/reports?cursor=not a cursor"),
				get("/api/admin/reports/0"), get("/api/admin/reports/seven"),
				json(post("/api/admin/reports/7/resolve"), "{}"),
				json(post("/api/admin/reports/7/resolve"), "{\"resolution\":\"OPEN\"}"),
				json(post("/api/admin/reports/7/resolve"),
						"{\"resolution\":\"ACTIONED\",\"resolutionNote\":\"" + "x".repeat(1001) + "\"}"),
				json(post(EVENT + "/hide"), "{}"), json(post(EVENT + "/hide"), "{\"reason\":\" \"}"),
				json(post(EVENT + "/cancel"), "{\"reason\":\"" + "x".repeat(501) + "\"}"), post(EVENT + "/cancel"),
				post("/api/admin/events/not-a-uuid/unhide"));
	}

	/**
	 * Tests that a bad request is a 400.
	 *
	 * @param request the request
	 */
	@ParameterizedTest
	@MethodSource("badRequests")
	void testRejectsBadRequests(MockHttpServletRequestBuilder request) throws Exception {
		assertError(mockMvc, request.principal(AUTHENTICATION), HttpStatus.BAD_REQUEST);
	}
}
