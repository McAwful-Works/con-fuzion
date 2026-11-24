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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * Unit tests for the {@link FriendController} contract stubs: every operation
 * answers 501 in the error envelope, and a bad path parameter is a 400.
 *
 * @author mcawful
 */
class FriendControllerTest {
	/**
	 * The {@link MockMvc}.
	 */
	private final MockMvc mockMvc = ErrorEnvelopeAssert.mockMvc(new FriendController());

	/**
	 * Returns one valid request per operation.
	 *
	 * @return the requests
	 */
	static Stream<RequestBuilder> operations() {
		return Stream.of(post("/api/friends/invites"), get("/api/friends/invites/q3Jx0nV9cY2bT8pL1mR4wA"),
				post("/api/friends/invites/q3Jx0nV9cY2bT8pL1mR4wA/accept"), get("/api/friends"),
				delete("/api/friends/" + UUID.randomUUID()));
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
	 * Tests that a user ID that isn't a UUID is a 400.
	 */
	@Test
	void testDeleteFriendRejectsBadUserId() throws Exception {
		assertError(mockMvc, delete("/api/friends/81384788765712384"), HttpStatus.BAD_REQUEST);
	}
}
