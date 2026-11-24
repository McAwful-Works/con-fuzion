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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import app.confuzion.backend.aspect.GlobalControllerExceptionHandler;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the contract stubs in {@link MeController} and
 * {@link AuthController}: each answers 501 in the error envelope, and request
 * validation already turns a bad request into a 400 in the same envelope.
 *
 * @author mcawful
 */
class MeControllerTest {
	/**
	 * A valid Discord server ID.
	 */
	private static final String GUILD_ID = "81384788765712384";

	/**
	 * The {@link MockMvc}, with the real exception handler and Bean Validation.
	 */
	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new MeController(), new AuthController())
			.setControllerAdvice(new GlobalControllerExceptionHandler()).build();

	/**
	 * Tests that {@code GET /api/me} is a 501 stub.
	 */
	@Test
	void testGetMeIsNotImplemented() throws Exception {
		assertError(get("/api/me"), HttpStatus.NOT_IMPLEMENTED);
	}

	/**
	 * Tests that a valid {@code PATCH /api/me} is a 501 stub.
	 */
	@Test
	void testUpdateMeIsNotImplemented() throws Exception {
		assertError(json(patch("/api/me"), "{\"timeZone\":\"America/Chicago\",\"pushEnabled\":true}"),
				HttpStatus.NOT_IMPLEMENTED);
		assertError(json(patch("/api/me"), "{}"), HttpStatus.NOT_IMPLEMENTED);
	}

	/**
	 * Tests that {@code PATCH /api/me} rejects a time zone that isn't an IANA ID.
	 *
	 * @param timeZone the bad time zone
	 */
	@ParameterizedTest
	@ValueSource(strings = { "Mars/Olympus_Mons", "america/chicago", "", "CST",
			"America/Chicago America/Chicago America/Chicago America/Chicago" })
	void testUpdateMeRejectsBadTimeZone(String timeZone) throws Exception {
		assertError(json(patch("/api/me"), "{\"timeZone\":\"" + timeZone + "\"}"), HttpStatus.BAD_REQUEST);
	}

	/**
	 * Tests that {@code PATCH /api/me} rejects a body that isn't JSON.
	 */
	@Test
	void testUpdateMeRejectsMalformedBody() throws Exception {
		assertError(json(patch("/api/me"), "{\"pushEnabled\":"), HttpStatus.BAD_REQUEST);
	}

	/**
	 * Tests that {@code GET /api/me/guilds} is a 501 stub.
	 */
	@Test
	void testGetGuildsIsNotImplemented() throws Exception {
		assertError(get("/api/me/guilds"), HttpStatus.NOT_IMPLEMENTED);
	}

	/**
	 * Tests that a valid {@code PUT /api/me/guilds/{guildId}} is a 501 stub.
	 */
	@Test
	void testUpdateGuildIsNotImplemented() throws Exception {
		assertError(json(put("/api/me/guilds/" + GUILD_ID), "{\"discoverable\":true}"), HttpStatus.NOT_IMPLEMENTED);
	}

	/**
	 * Tests that {@code PUT /api/me/guilds/{guildId}} requires
	 * {@code discoverable}.
	 */
	@Test
	void testUpdateGuildRequiresDiscoverable() throws Exception {
		assertError(json(put("/api/me/guilds/" + GUILD_ID), "{}"), HttpStatus.BAD_REQUEST);
	}

	/**
	 * Tests that {@code PUT /api/me/guilds/{guildId}} rejects an ID that isn't a
	 * snowflake.
	 *
	 * @param guildId the bad ID
	 */
	@ParameterizedTest
	@ValueSource(strings = { "123", "not-a-snowflake", "813847887657123840000" })
	void testUpdateGuildRejectsBadGuildId(String guildId) throws Exception {
		assertError(json(put("/api/me/guilds/" + guildId), "{\"discoverable\":true}"), HttpStatus.BAD_REQUEST);
	}

	/**
	 * Tests that {@code POST /api/auth/logout-everywhere} is a 501 stub.
	 */
	@Test
	void testLogoutEverywhereIsNotImplemented() throws Exception {
		assertError(post("/api/auth/logout-everywhere"), HttpStatus.NOT_IMPLEMENTED);
	}

	/**
	 * Adds a JSON body to a request.
	 *
	 * @param request the request builder
	 * @param body    the JSON body
	 * @return the request builder
	 */
	private static RequestBuilder json(
			org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	/**
	 * Performs a request and asserts the generic error envelope for a status.
	 *
	 * @param request the request
	 * @param status  the expected {@link HttpStatus}
	 * @return the {@link ResultActions}
	 */
	private ResultActions assertError(RequestBuilder request, HttpStatus status) throws Exception {
		return mockMvc.perform(request).andExpect(status().is(status.value()))
				.andExpect(header().exists(GlobalControllerExceptionHandler.CORRELATION_ID_HEADER))
				.andExpect(jsonPath("$.responseType").value("ERROR"))
				.andExpect(jsonPath("$.body.errorType").value(status.name()))
				.andExpect(jsonPath("$.body.errorMessage").value(status.getReasonPhrase()))
				.andExpect(jsonPath("$.body.correlationId").isNotEmpty());
	}
}
