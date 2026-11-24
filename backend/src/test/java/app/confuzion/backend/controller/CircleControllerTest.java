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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import app.confuzion.backend.model.record.CircleRequest;

/**
 * Unit tests for the {@link CircleController} contract stubs: every operation
 * answers 501 in the error envelope, and a bad name or ID is a 400.
 *
 * @author mcawful
 */
class CircleControllerTest {
	/**
	 * A circle's path.
	 */
	private static final String CIRCLE = "/api/circles/" + UUID.randomUUID();

	/**
	 * The {@link MockMvc}.
	 */
	private final MockMvc mockMvc = ErrorEnvelopeAssert.mockMvc(new CircleController());

	/**
	 * Returns one valid request per operation, with names at both ends of the
	 * length limit.
	 *
	 * @return the requests
	 */
	static Stream<RequestBuilder> operations() {
		var longest = "{\"name\":\"" + "a".repeat(CircleRequest.MAX_NAME) + "\"}";
		return Stream.of(get("/api/circles"), json(post("/api/circles"), "{\"name\":\"a\"}"),
				json(post("/api/circles"), longest), get(CIRCLE), json(patch(CIRCLE), longest), delete(CIRCLE),
				put(CIRCLE + "/members/" + UUID.randomUUID()), delete(CIRCLE + "/members/" + UUID.randomUUID()));
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
	 * Tests that creating or renaming a circle rejects a missing, blank, too long
	 * or malformed name.
	 *
	 * @param body the bad JSON body
	 */
	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\"   \"}",
			"{\"name\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}", "{\"name\":" })
	void testRejectsBadName(String body) throws Exception {
		assertError(mockMvc, json(post("/api/circles"), body), HttpStatus.BAD_REQUEST);
		assertError(mockMvc, json(patch(CIRCLE), body), HttpStatus.BAD_REQUEST);
	}

	/**
	 * Returns requests with a circle or user ID that isn't a UUID.
	 *
	 * @return the requests
	 */
	static Stream<RequestBuilder> badIds() {
		return Stream.of(get("/api/circles/1"), delete("/api/circles/1"),
				json(patch("/api/circles/1"), "{\"name\":\"a\"}"), put(CIRCLE + "/members/81384788765712384"),
				delete("/api/circles/1/members/" + UUID.randomUUID()));
	}

	/**
	 * Tests that a circle or user ID that isn't a UUID is a 400.
	 *
	 * @param request the request
	 */
	@ParameterizedTest
	@MethodSource("badIds")
	void testRejectsBadIds(RequestBuilder request) throws Exception {
		assertError(mockMvc, request, HttpStatus.BAD_REQUEST);
	}
}
