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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import app.confuzion.backend.model.record.EventPageResponse;
import app.confuzion.backend.model.record.EventRequest;
import app.confuzion.backend.model.record.EventResponse;
import app.confuzion.backend.model.record.EventResponse.LocationVisibility;
import app.confuzion.backend.model.record.EventResponse.Status;
import app.confuzion.backend.model.record.FeedFilter;
import app.confuzion.backend.model.record.MyStatus;
import app.confuzion.backend.model.record.PersonResponse;
import app.confuzion.backend.service.EventService;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link EventController}: valid requests reach the
 * {@link EventService} with the session's user ID, and every validation rule on
 * create, update and the list query turns a bad request into a 400 before the
 * service is called.
 *
 * @author mcawful
 */
class EventControllerTest {
	/**
	 * An event's path.
	 */
	private static final String EVENT = "/api/events/" + UUID.randomUUID();

	/**
	 * The signed-in user's ID.
	 */
	private static final UUID USER_ID = UUID.randomUUID();

	/**
	 * A list request with no search and no filters.
	 */
	private static final FeedFilter NO_FILTER = new FeedFilter(null, null, null, null);

	/**
	 * The signed-in user's {@link Authentication}.
	 */
	private static final Authentication AUTHENTICATION = UsernamePasswordAuthenticationToken
			.authenticated(USER_ID.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

	/**
	 * A start time a week from now.
	 */
	private static final Instant STARTS_AT = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

	/**
	 * Serializes request bodies.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * An event the mocked service returns.
	 */
	private static final EventResponse RESPONSE = new EventResponse(UUID.randomUUID(),
			new PersonResponse(USER_ID, "Host", "https://cdn.discordapp.com/embed/avatars/0.png"), "Walk", null,
			"Hall B", false, LocationVisibility.VIEWERS, null, STARTS_AT, null, "UTC", null, null, null, 30,
			Status.PUBLISHED, true, MyStatus.CONFIRMED, List.of(), null, null);

	/**
	 * The mocked {@link EventService}.
	 */
	private final EventService eventService = mock(EventService.class);

	/**
	 * The {@link MockMvc}.
	 */
	private final MockMvc mockMvc = ErrorEnvelopeAssert.mockMvc(new EventController(eventService));

	/**
	 * Returns one valid request per operation, plus edge cases that must pass
	 * validation.
	 *
	 * @return the requests
	 */
	static Stream<RequestBuilder> operations() {
		var everyField = Map.<String, Object>of("description", "Bring a camera.\nMeet by the fountain.", "tag",
				"Photoshoot", "endsAt", STARTS_AT.plus(EventRequest.MAX_LENGTH).toString(), "capacity", 10000,
				"signupOpensAt", STARTS_AT.minus(EventRequest.MAX_HORIZON).toString(), "audience", allAudienceTypes());
		return Stream.<MockHttpServletRequestBuilder>of(json(post("/api/events"), body(Map.of())),
				json(post("/api/events"), body(everyField)),
				json(post("/api/events"), body(Map.of("audience", audience(EventRequest.MAX_AUDIENCE)))),
				// The smallest capacity: the host and one guest.
				json(post("/api/events"), body(Map.of("capacity", 2))), get("/api/events"),
				get("/api/events").param("scope", "hosting").param("limit", "50"),
				get("/api/events").param("scope", "feed").param("cursor", "eyJzIjoiMjAyNiJ9_-").param("limit", "1"),
				get(EVENT), json(patch(EVENT), body(everyField)),
				// An update may keep a start that has already passed.
				json(patch(EVENT), body(Map.of("startsAt", Instant.now().minus(1, ChronoUnit.HOURS).toString()))),
				delete(EVENT)).map(request -> request.principal(AUTHENTICATION));
	}

	/**
	 * Tests that each valid request reaches the service and succeeds.
	 *
	 * @param request the request
	 */
	@ParameterizedTest
	@MethodSource("operations")
	void testValidRequestsReachService(RequestBuilder request) throws Exception {
		when(eventService.createEvent(eq(USER_ID), any())).thenReturn(RESPONSE);
		when(eventService.getEvent(eq(USER_ID), any(), eq(false))).thenReturn(RESPONSE);
		when(eventService.updateEvent(eq(USER_ID), any(), any())).thenReturn(RESPONSE);
		when(eventService.getEvents(eq(USER_ID), any(), any(), anyInt(), any()))
				.thenReturn(new EventPageResponse(List.of(RESPONSE), null));

		mockMvc.perform(request).andExpect(status().is2xxSuccessful());
	}

	/**
	 * Tests that create answers 201 with the event, and the list passes its
	 * defaults on.
	 */
	@Test
	void testCreateAndListDefaults() throws Exception {
		when(eventService.createEvent(eq(USER_ID), any())).thenReturn(RESPONSE);
		when(eventService.getEvents(USER_ID, "feed", null, 20, NO_FILTER))
				.thenReturn(new EventPageResponse(List.of(), null));

		mockMvc.perform(json(post("/api/events"), body(Map.of())).principal(AUTHENTICATION))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.responseType").value("EVENT"))
				.andExpect(jsonPath("$.body.isHost").value(true));
		mockMvc.perform(get("/api/events").principal(AUTHENTICATION)).andExpect(status().isOk())
				.andExpect(jsonPath("$.responseType").value("EVENT_PAGE"));
		verify(eventService).getEvents(USER_ID, "feed", null, 20, NO_FILTER);
	}

	/**
	 * Returns field changes that make an event body invalid on both create and
	 * update.
	 *
	 * @return the field and its bad value ({@code null} removes the field)
	 */
	static Stream<Arguments> badFields() {
		return Stream.of(Arguments.of("title", null), Arguments.of("title", ""), Arguments.of("title", " Walk"),
				Arguments.of("title", "a".repeat(101)), Arguments.of("location", null),
				Arguments.of("location", "Hall B "), Arguments.of("location", "a".repeat(201)),
				Arguments.of("description", "a".repeat(2001)), Arguments.of("tag", ""),
				Arguments.of("tag", "a".repeat(31)), Arguments.of("startsAt", null),
				Arguments.of("startsAt", "next week"), Arguments.of("endsAt", STARTS_AT.toString()),
				Arguments.of("endsAt", STARTS_AT.minus(1, ChronoUnit.MINUTES).toString()),
				Arguments.of("endsAt", STARTS_AT.plus(EventRequest.MAX_LENGTH).plusSeconds(1).toString()),
				Arguments.of("startsAt",
						Instant.now().plus(EventRequest.MAX_HORIZON).plus(1, ChronoUnit.DAYS).toString()),
				Arguments.of("startsAt", "+1000000000-01-01T00:00:00Z"),
				Arguments.of("signupOpensAt", STARTS_AT.minus(EventRequest.MAX_HORIZON).minusSeconds(1).toString()),
				Arguments.of("signupOpensAt", STARTS_AT.toString()), Arguments.of("timeZone", null),
				Arguments.of("timeZone", "Mars/Olympus_Mons"), Arguments.of("capacity", 0), Arguments.of("capacity", 1),
				Arguments.of("capacity", 10001), Arguments.of("locationVisibility", null),
				Arguments.of("locationVisibility", "EVERYONE"), Arguments.of("audience", null),
				Arguments.of("audience", List.of()), Arguments.of("audience", audience(EventRequest.MAX_AUDIENCE + 1)),
				Arguments.of("audience", Collections.singletonList(null)),
				Arguments.of("audience", List.of(Map.of("type", "FRIENDS"), Map.of("type", "FRIENDS"))),
				Arguments.of("audience", List.of(Map.of())), Arguments.of("audience", List.of(Map.of("type", "TEAM"))),
				Arguments.of("audience", List.of(Map.of("type", "GUILD"))),
				Arguments.of("audience", List.of(Map.of("type", "GUILD", "guildId", "123"))),
				Arguments.of("audience", List.of(Map.of("type", "FRIENDS", "userId", UUID.randomUUID().toString()))),
				Arguments.of("audience", List.of(Map.of("type", "CIRCLE", "guildId", "81384788765712384"))),
				Arguments.of("audience",
						List.of(Map.of("type", "CIRCLE", "circleId", UUID.randomUUID().toString(), "userId",
								UUID.randomUUID().toString()))),
				Arguments.of("audience", List.of(Map.of("type", "USER", "userId", "not-a-uuid"))));
	}

	/**
	 * Tests that create and update both reject an invalid field.
	 *
	 * @param field the field
	 * @param value its bad value, or {@code null} to leave it out
	 */
	@ParameterizedTest
	@MethodSource("badFields")
	void testRejectsBadField(String field, Object value) throws Exception {
		var change = new LinkedHashMap<String, Object>();
		change.put(field, value);
		assertError(mockMvc, json(post("/api/events"), body(change)), HttpStatus.BAD_REQUEST);
		assertError(mockMvc, json(patch(EVENT), body(change)), HttpStatus.BAD_REQUEST);
		verifyNoInteractions(eventService);
	}

	/**
	 * Tests that create rejects a start that isn't in the future.
	 */
	@Test
	void testCreateRejectsPastStart() throws Exception {
		assertError(mockMvc,
				json(post("/api/events"),
						body(Map.of("startsAt", Instant.now().minus(1, ChronoUnit.MINUTES).toString()))),
				HttpStatus.BAD_REQUEST);
	}

	/**
	 * Tests that the list rejects a bad scope, limit or cursor.
	 *
	 * @param query the bad query string
	 */
	@ParameterizedTest
	@ValueSource(strings = { "scope=going", "scope=ATTENDING", "scope=FEED", "limit=0", "limit=51", "limit=ten",
			"cursor=a%20b", "cursor=abc%3D", "q=a", "guild=123", "guild=abc", "friends=maybe", "hasSpots=2" })
	void testListRejectsBadQuery(String query) throws Exception {
		assertError(mockMvc, get("/api/events?" + query), HttpStatus.BAD_REQUEST);
	}

	/**
	 * Tests that the feed's search and filters reach the service as sent, and that
	 * a search longer than 100 characters doesn't.
	 */
	@Test
	void testFeedFiltersReachService() throws Exception {
		var filter = new FeedFilter("50%_off", "81384788765712384", true, false);
		when(eventService.getEvents(USER_ID, "feed", null, 20, filter))
				.thenReturn(new EventPageResponse(List.of(), null));
		mockMvc.perform(get("/api/events").param("q", "50%_off").param("guild", "81384788765712384")
				.param("friends", "true").param("hasSpots", "false").principal(AUTHENTICATION))
				.andExpect(status().isOk());
		verify(eventService).getEvents(USER_ID, "feed", null, 20, filter);

		assertError(mockMvc, get("/api/events").param("q", "x".repeat(101)), HttpStatus.BAD_REQUEST);
	}

	/**
	 * Tests that My schedule passes its scope on to the service.
	 */
	@Test
	void testAttendingScope() throws Exception {
		when(eventService.getEvents(USER_ID, "attending", null, 20, NO_FILTER))
				.thenReturn(new EventPageResponse(List.of(), null));
		mockMvc.perform(get("/api/events").param("scope", "attending").principal(AUTHENTICATION))
				.andExpect(status().isOk()).andExpect(jsonPath("$.responseType").value("EVENT_PAGE"));
		verify(eventService).getEvents(USER_ID, "attending", null, 20, NO_FILTER);
	}

	/**
	 * Tests that the list rejects a cursor longer than any it issues.
	 */
	@Test
	void testListRejectsLongCursor() throws Exception {
		assertError(mockMvc, get("/api/events").param("cursor", "a".repeat(201)), HttpStatus.BAD_REQUEST);
	}

	/**
	 * Tests that an event ID that isn't a UUID is a 400.
	 */
	@Test
	void testRejectsBadEventId() throws Exception {
		assertError(mockMvc, get("/api/events/1"), HttpStatus.BAD_REQUEST);
		assertError(mockMvc, json(patch("/api/events/1"), body(Map.of())), HttpStatus.BAD_REQUEST);
		assertError(mockMvc, delete("/api/events/1"), HttpStatus.BAD_REQUEST);
	}

	/**
	 * Builds a valid event body with some fields changed.
	 *
	 * @param changes the fields to set; a {@code null} value removes the field
	 * @return the JSON body
	 */
	private static String body(Map<String, Object> changes) {
		var body = new LinkedHashMap<String, Object>();
		body.put("title", "Cosplay photo walk");
		body.put("location", "Hall B fountain");
		body.put("startsAt", STARTS_AT.toString());
		body.put("timeZone", "America/Chicago");
		body.put("locationVisibility", "CONFIRMED");
		body.put("audience", List.of(Map.of("type", "FRIENDS")));
		changes.forEach((field, value) -> {
			if (value == null)
				body.remove(field);
			else
				body.put(field, value);
		});
		return JSON.writeValueAsString(body);
	}

	/**
	 * Returns one well-formed entry of each audience type.
	 *
	 * @return the entries
	 */
	private static List<Map<String, String>> allAudienceTypes() {
		return List.of(Map.of("type", "GUILD", "guildId", "81384788765712384"), Map.of("type", "FRIENDS"),
				Map.of("type", "CIRCLE", "circleId", UUID.randomUUID().toString()),
				Map.of("type", "USER", "userId", UUID.randomUUID().toString()));
	}

	/**
	 * Returns distinct user entries.
	 *
	 * @param count the number of entries
	 * @return the entries
	 */
	private static List<Map<String, String>> audience(int count) {
		return IntStream.range(0, count).mapToObj(i -> Map.of("type", "USER", "userId", UUID.randomUUID().toString()))
				.toList();
	}
}
