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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
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
 * Unit tests for {@link AdminUserController}'s contract stubs: valid requests
 * are 501s until #86, and bad ones are 400s.
 *
 * @author mcawful
 */
class AdminUserControllerTest {
	/**
	 * An admin.
	 */
	private static final Authentication AUTHENTICATION = UsernamePasswordAuthenticationToken.authenticated(
			UUID.randomUUID().toString(), null,
			List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN")));

	/**
	 * A user's admin path.
	 */
	private static final String USER = "/api/admin/users/3f2c9a4e-6d1b-4f7a-9c3e-2b8d5e1a7f60";

	/**
	 * A time a week from now.
	 */
	private static final Instant NEXT_WEEK = Instant.now().plus(Duration.ofDays(7));

	/**
	 * The {@link MockMvc} for the controller.
	 */
	private final MockMvc mockMvc = ErrorEnvelopeAssert.mockMvc(new AdminUserController());

	/**
	 * Returns valid requests to every operation.
	 *
	 * @return the requests
	 */
	static Stream<MockHttpServletRequestBuilder> validRequests() {
		return Stream.of(get("/api/admin/users?q=co"), get("/api/admin/users?q=" + "x".repeat(100)),
				json(post(USER + "/suspend"), "{\"until\":\"" + NEXT_WEEK + "\",\"reason\":\"Spam\"}"),
				post(USER + "/unsuspend"),
				json(post("/api/admin/bans"), "{\"discordId\":\"80351110224678912\",\"reason\":\"Harassment\"}"),
				delete("/api/admin/bans/80351110224678912"));
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
	 * Returns requests with a bad search, end time, reason, user ID or Discord ID.
	 *
	 * @return the requests
	 */
	static Stream<MockHttpServletRequestBuilder> badRequests() {
		return Stream.of(get("/api/admin/users"), get("/api/admin/users?q=c"),
				get("/api/admin/users?q=" + "x".repeat(101)), get("/api/admin/users").param("q", "  "),
				json(post(USER + "/suspend"), "{\"reason\":\"Spam\"}"),
				json(post(USER + "/suspend"),
						"{\"until\":\"" + Instant.now().minus(Duration.ofDays(1)) + "\",\"reason\":\"Spam\"}"),
				json(post(USER + "/suspend"), "{\"until\":\"" + NEXT_WEEK + "\"}"),
				json(post(USER + "/suspend"),
						"{\"until\":\"" + NEXT_WEEK + "\",\"reason\":\"" + "x".repeat(501) + "\"}"),
				post("/api/admin/users/not-a-uuid/unsuspend"),
				json(post("/api/admin/bans"), "{\"discordId\":\"123\",\"reason\":\"Spam\"}"),
				json(post("/api/admin/bans"), "{\"discordId\":80351110224678912,\"reason\":\"\"}"),
				json(post("/api/admin/bans"), "{\"reason\":\"Spam\"}"), delete("/api/admin/bans/not-a-snowflake"));
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
