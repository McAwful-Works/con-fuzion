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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.test.web.servlet.RequestBuilder;

import app.confuzion.backend.model.record.AttendeesResponse;
import app.confuzion.backend.model.record.RsvpResponse;
import app.confuzion.backend.service.RsvpService;

/**
 * Unit tests for the {@link RsvpController}: each operation maps to the service
 * with the session's user ID, spot counts are still a 501 contract stub, and an
 * ID that isn't a UUID is a 400 that never reaches the service.
 *
 * @author mcawful
 */
class RsvpControllerTest {
	/**
	 * The signed-in user's ID.
	 */
	private static final UUID USER_ID = UUID.randomUUID();

	/**
	 * The event's public ID.
	 */
	private static final UUID EVENT_ID = UUID.randomUUID();

	/**
	 * An event's path.
	 */
	private static final String EVENT = "/api/events/" + EVENT_ID;

	/**
	 * The signed-in user.
	 */
	private static final Authentication AUTHENTICATION = UsernamePasswordAuthenticationToken
			.authenticated(USER_ID.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

	/**
	 * The mocked {@link RsvpService}.
	 */
	private final RsvpService rsvpService = mock(RsvpService.class);

	/**
	 * The {@link MockMvc}.
	 */
	private final MockMvc mockMvc = ErrorEnvelopeAssert.mockMvc(new RsvpController(rsvpService));

	/**
	 * Tests that each operation calls the service with the session's user and
	 * answers in the envelope.
	 */
	@Test
	void testOperationsMapToTheService() throws Exception {
		var other = UUID.randomUUID();
		when(rsvpService.join(USER_ID, EVENT_ID)).thenReturn(new RsvpResponse(RsvpResponse.Status.WAITLISTED, 4));
		when(rsvpService.getAttendees(USER_ID, EVENT_ID)).thenReturn(new AttendeesResponse(List.of(), null));

		mockMvc.perform(post(EVENT + "/rsvp").principal(AUTHENTICATION)).andExpect(status().isOk())
				.andExpect(jsonPath("$.responseType").value("RSVP"))
				.andExpect(jsonPath("$.body.status").value("WAITLISTED"))
				.andExpect(jsonPath("$.body.position").value(4));
		mockMvc.perform(delete(EVENT + "/rsvp").principal(AUTHENTICATION)).andExpect(status().isNoContent());
		mockMvc.perform(get(EVENT + "/attendees").principal(AUTHENTICATION)).andExpect(status().isOk())
				.andExpect(jsonPath("$.responseType").value("ATTENDEES"));
		mockMvc.perform(delete(EVENT + "/rsvps/" + other).principal(AUTHENTICATION)).andExpect(status().isNoContent());

		verify(rsvpService).leave(USER_ID, EVENT_ID);
		verify(rsvpService).remove(USER_ID, EVENT_ID, other);
	}

	/**
	 * Tests that spot counts are a 501 contract stub until a later part of #54.
	 */
	@Test
	void testSpotsIsNotImplemented() throws Exception {
		assertError(mockMvc, get(EVENT + "/spots").principal(AUTHENTICATION), HttpStatus.NOT_IMPLEMENTED);
		verifyNoInteractions(rsvpService);
	}

	/**
	 * Returns one request per operation with an event or user ID that isn't a UUID.
	 *
	 * @return the requests
	 */
	static Stream<RequestBuilder> badIds() {
		return Stream.of(post("/api/events/1/rsvp"), delete("/api/events/1/rsvp"), get("/api/events/1/attendees"),
				get("/api/events/1/spots"), delete("/api/events/1/rsvps/" + UUID.randomUUID()),
				delete(EVENT + "/rsvps/81384788765712384"));
	}

	/**
	 * Tests that an ID that isn't a UUID is a 400 that never reaches the service.
	 *
	 * @param request the request
	 */
	@ParameterizedTest
	@MethodSource("badIds")
	void testRejectsBadIds(RequestBuilder request) throws Exception {
		assertError(mockMvc, request, HttpStatus.BAD_REQUEST);
		verifyNoInteractions(rsvpService);
	}
}
