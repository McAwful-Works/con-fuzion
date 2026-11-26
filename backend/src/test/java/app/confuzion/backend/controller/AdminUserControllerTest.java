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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import app.confuzion.backend.model.record.AdminUserListResponse;
import app.confuzion.backend.model.record.AdminUserResponse;
import app.confuzion.backend.service.AdminUserService;

/**
 * Unit tests for {@link AdminUserController}: valid requests reach the
 * service with the caller from the session, and bad ones are 400s that never
 * do.
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
	 * A moderator.
	 */
	private static final Authentication MODERATOR = UsernamePasswordAuthenticationToken.authenticated(
			UUID.randomUUID().toString(), null,
			List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_MODERATOR")));

	/**
	 * The user in {@link #USER}.
	 */
	private static final UUID USER_ID = UUID.fromString("3f2c9a4e-6d1b-4f7a-9c3e-2b8d5e1a7f60");

	/**
	 * A user's admin path.
	 */
	private static final String USER = "/api/admin/users/3f2c9a4e-6d1b-4f7a-9c3e-2b8d5e1a7f60";

	/**
	 * A time a week from now.
	 */
	private static final Instant NEXT_WEEK = Instant.now().plus(Duration.ofDays(7));

	/**
	 * The mocked {@link AdminUserService}.
	 */
	private final AdminUserService adminUserService = mock(AdminUserService.class);

	/**
	 * The {@link MockMvc} for the controller.
	 */
	private final MockMvc mockMvc = ErrorEnvelopeAssert.mockMvc(new AdminUserController(adminUserService));

	/**
	 * Tests that each operation passes the caller from the session, whether they
	 * are an admin, and the request on to the service, and wraps the search's
	 * answer.
	 */
	@Test
	void testDelegatesToTheService() throws Exception {
		var admin = UUID.fromString(AUTHENTICATION.getName());
		var moderator = UUID.fromString(MODERATOR.getName());
		var found = new AdminUserResponse(USER_ID, "80351110224678912", "con_goer", null, NEXT_WEEK, NEXT_WEEK, null,
				null, 2);
		when(adminUserService.findUsers(moderator, "con")).thenReturn(new AdminUserListResponse(List.of(found)));

		mockMvc.perform(get("/api/admin/users?q=con").principal(MODERATOR)).andExpect(status().isOk())
				.andExpect(jsonPath("$.responseType").value("ADMIN_USER_LIST"))
				.andExpect(jsonPath("$.body.items[0].discordId").value("80351110224678912"))
				.andExpect(jsonPath("$.body.items[0].reportCount").value(2));
		var suspend = "{\"until\":\"" + NEXT_WEEK + "\",\"reason\":\"Spam\"}";
		mockMvc.perform(json(post(USER + "/suspend"), suspend).principal(MODERATOR))
				.andExpect(status().isNoContent());
		verify(adminUserService).suspend(moderator, false, USER_ID, NEXT_WEEK, "Spam");
		mockMvc.perform(json(post(USER + "/suspend"), suspend).principal(AUTHENTICATION))
				.andExpect(status().isNoContent());
		verify(adminUserService).suspend(eq(admin), eq(true), eq(USER_ID), eq(NEXT_WEEK), eq("Spam"));
		mockMvc.perform(post(USER + "/unsuspend").principal(MODERATOR)).andExpect(status().isNoContent());
		verify(adminUserService).unsuspend(moderator, USER_ID);
		mockMvc.perform(json(post("/api/admin/bans"), "{\"discordId\":\"80351110224678912\",\"reason\":\"Harassment\"}")
				.principal(AUTHENTICATION)).andExpect(status().isNoContent());
		verify(adminUserService).ban(admin, "80351110224678912", "Harassment");
		mockMvc.perform(delete("/api/admin/bans/80351110224678912").principal(AUTHENTICATION))
				.andExpect(status().isNoContent());
		verify(adminUserService).unban(admin, "80351110224678912");
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
		verifyNoInteractions(adminUserService);
	}
}
