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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import app.confuzion.backend.model.record.NotificationPageResponse;
import app.confuzion.backend.service.NotificationService;

/**
 * Unit tests for the {@link NotificationController}: a valid request reaches
 * the service for the caller only, and a bad one is a 400 before it gets there.
 *
 * @author mcawful
 */
class NotificationControllerTest {
	/**
	 * The signed-in user's ID.
	 */
	private static final UUID USER_ID = UUID.randomUUID();

	/**
	 * The signed-in user.
	 */
	private static final Authentication AUTHENTICATION = UsernamePasswordAuthenticationToken
			.authenticated(USER_ID.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

	/**
	 * The mocked {@link NotificationService}.
	 */
	private final NotificationService notificationService = mock(NotificationService.class);

	/**
	 * The {@link MockMvc}.
	 */
	private final MockMvc mockMvc = ErrorEnvelopeAssert.mockMvc(new NotificationController(notificationService));

	/**
	 * Tests that a page request passes the caller, cursor and limit to the service
	 * and returns its page in the envelope.
	 */
	@Test
	void testGetNotificationsReturnsThePage() throws Exception {
		var page = new NotificationPageResponse(List.of(), "MTI", 3);
		when(notificationService.getInbox(USER_ID, "abc_123-X", 50)).thenReturn(page);

		mockMvc.perform(get("/api/notifications?cursor=abc_123-X&limit=50").principal(AUTHENTICATION))
				.andExpect(status().isOk()).andExpect(jsonPath("$.responseType").value("NOTIFICATION_PAGE"))
				.andExpect(jsonPath("$.body.unreadCount").value(3))
				.andExpect(jsonPath("$.body.nextCursor").value("MTI"));
	}

	/**
	 * Tests that the first page defaults to 20 notifications and no cursor.
	 */
	@Test
	void testGetNotificationsDefaults() throws Exception {
		when(notificationService.getInbox(USER_ID, null, 20))
				.thenReturn(new NotificationPageResponse(List.of(), null, 0));

		mockMvc.perform(get("/api/notifications").principal(AUTHENTICATION)).andExpect(status().isOk());
	}

	/**
	 * Tests that marking read is a 204 and marks only the caller's own.
	 */
	@Test
	void testMarkReadIsNoContent() throws Exception {
		mockMvc.perform(json(post("/api/notifications/read"), "{\"upTo\":42}").principal(AUTHENTICATION))
				.andExpect(status().isNoContent());

		verify(notificationService).markRead(USER_ID, 42L);
	}

	/**
	 * Returns requests with a bad cursor, limit or {@code upTo}.
	 *
	 * @return the requests
	 */
	static Stream<MockHttpServletRequestBuilder> badRequests() {
		return Stream.of(get("/api/notifications?limit=0"), get("/api/notifications?limit=51"),
				get("/api/notifications?cursor=not a cursor"), get("/api/notifications?cursor=" + "a".repeat(201)),
				json(post("/api/notifications/read"), "{}"), json(post("/api/notifications/read"), "{\"upTo\":0}"),
				json(post("/api/notifications/read"), "{\"upTo\":\"newest\"}"), post("/api/notifications/read"));
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
