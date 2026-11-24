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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import app.confuzion.backend.model.record.EventRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for the {@link EventController} contract stubs: every operation
 * answers 501 in the error envelope, and every validation rule on create,
 * update and the list query turns a bad request into a 400.
 *
 * @author mcawful
 */
class EventControllerTest {
	/**
	 * An event's path.
	 */
	private static final String EVENT = "/api/events/" + UUID.randomUUID();

	/**
	 * A start time a week from now.
	 */
	private static final Instant STARTS_AT = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

	/**
	 * Serializes request bodies.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * The {@link MockMvc}.
	 */
	private final MockMvc mockMvc = ErrorEnvelopeAssert.mockMvc(new EventController());

	/**
	 * Returns one valid request per operation, plus edge cases that must pass
	 * validation.
	 *
	 * @return the requests
	 */
	static Stream<RequestBuilder> operations() {
		var everyField = Map.<String, Object>of("description", "Bring a camera.\nMeet by the fountain.", "tag",
				"Photoshoot", "endsAt", STARTS_AT.plus(2, ChronoUnit.HOURS).toString(), "capacity", 10000,
				"signupOpensAt", STARTS_AT.minus(1, ChronoUnit.DAYS).toString(), "audience", allAudienceTypes());
		return Stream.of(json(post("/api/events"), body(Map.of())), json(post("/api/events"), body(everyField)),
				json(post("/api/events"), body(Map.of("audience", audience(EventRequest.MAX_AUDIENCE)))),
				get("/api/events"), get("/api/events").param("scope", "hosting").param("limit", "50"),
				get("/api/events").param("scope", "feed").param("cursor", "eyJzIjoiMjAyNiJ9_-").param("limit", "1"),
				get(EVENT), json(patch(EVENT), body(everyField)),
				// An update may keep a start that has already passed.
				json(patch(EVENT), body(Map.of("startsAt", Instant.now().minus(1, ChronoUnit.HOURS).toString()))),
				delete(EVENT));
	}

	/**
	 * Tests that each stub answers 501 in the error envelope.
	 *
	 * @param request the request
	 */
	@ParameterizedTest
	@MethodSource("operations")
	void testStubsAreNotImplemented(RequestBuilder request) throws Exception {
		assertError(mockMvc, request, HttpStatus.NOT_IMPLEMENTED);
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
				Arguments.of("signupOpensAt", STARTS_AT.toString()), Arguments.of("timeZone", null),
				Arguments.of("timeZone", "Mars/Olympus_Mons"), Arguments.of("capacity", 0),
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
	@ValueSource(strings = { "scope=attending", "scope=FEED", "limit=0", "limit=51", "limit=ten", "cursor=a%20b",
			"cursor=abc%3D" })
	void testListRejectsBadQuery(String query) throws Exception {
		assertError(mockMvc, get("/api/events?" + query), HttpStatus.BAD_REQUEST);
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
