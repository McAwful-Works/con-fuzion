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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import app.confuzion.backend.aspect.GlobalControllerExceptionHandler;
import app.confuzion.backend.model.record.GuildListResponse;
import app.confuzion.backend.model.record.GuildResponse;
import app.confuzion.backend.model.record.MeResponse;
import app.confuzion.backend.model.record.UpdateGuildsRequest;
import app.confuzion.backend.model.record.UpdateMeRequest;
import app.confuzion.backend.service.UserService;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit tests for {@link MeController}: valid requests reach the
 * {@link UserService} with the session's user ID, and request validation turns
 * a bad request into a 400 in the error envelope before the service is called.
 *
 * @author mcawful
 */
class MeControllerTest {
	/**
	 * A valid Discord server ID.
	 */
	private static final String GUILD_ID = "81384788765712384";

	/**
	 * A request value that must never reach the log.
	 */
	private static final String SECRET = "Secret/Rejected_Value";

	/**
	 * The signed-in user's ID.
	 */
	private static final UUID USER_ID = UUID.randomUUID();

	/**
	 * The signed-in user's {@link Authentication}.
	 */
	private static final Authentication AUTHENTICATION = UsernamePasswordAuthenticationToken
			.authenticated(USER_ID.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

	/**
	 * A profile the mocked service returns.
	 */
	private static final MeResponse ME = new MeResponse(USER_ID, "user", null,
			"https://cdn.discordapp.com/embed/avatars/0.png", null, false, List.of(MeResponse.Role.USER), false, false);

	/**
	 * A server list the mocked service returns.
	 */
	private static final GuildListResponse GUILDS = new GuildListResponse(
			List.of(new GuildResponse(GUILD_ID, "Con", null, true)));

	/**
	 * The mocked {@link UserService}.
	 */
	private final UserService userService = mock(UserService.class);

	/**
	 * The {@link MockMvc}, with the real exception handler and Bean Validation.
	 */
	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new MeController(userService))
			.setControllerAdvice(new GlobalControllerExceptionHandler()).build();

	/**
	 * Tests that {@code GET /api/me} returns the service's profile in the envelope.
	 */
	@Test
	void testGetMe() throws Exception {
		when(userService.getMe(eq(USER_ID), any())).thenReturn(ME);

		mockMvc.perform(get("/api/me").principal(AUTHENTICATION)).andExpect(status().isOk())
				.andExpect(jsonPath("$.responseType").value("ME"))
				.andExpect(jsonPath("$.body.id").value(USER_ID.toString()));
	}

	/**
	 * Tests that a valid {@code PATCH /api/me} reaches the service.
	 */
	@Test
	void testUpdateMe() throws Exception {
		var request = new UpdateMeRequest("America/Chicago", true);
		when(userService.updateMe(eq(USER_ID), any(), eq(request))).thenReturn(ME);

		mockMvc.perform(json(patch("/api/me"), "{\"timeZone\":\"America/Chicago\",\"pushEnabled\":true}")
				.principal(AUTHENTICATION)).andExpect(status().isOk())
				.andExpect(jsonPath("$.responseType").value("ME"));
		verify(userService).updateMe(eq(USER_ID), any(), eq(request));
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
	 * Tests that {@code GET /api/me/guilds} returns the service's list.
	 */
	@Test
	void testGetGuilds() throws Exception {
		when(userService.getGuilds(USER_ID)).thenReturn(GUILDS);

		mockMvc.perform(get("/api/me/guilds").principal(AUTHENTICATION)).andExpect(status().isOk())
				.andExpect(jsonPath("$.responseType").value("GUILD_LIST"))
				.andExpect(jsonPath("$.body.guilds[0].id").value(GUILD_ID));
	}

	/**
	 * Tests that a valid {@code PUT /api/me/guilds/{guildId}} reaches the service.
	 */
	@Test
	void testUpdateGuild() throws Exception {
		when(userService.updateGuild(USER_ID, GUILD_ID, true)).thenReturn(GUILDS.guilds().getFirst());

		mockMvc.perform(json(put("/api/me/guilds/" + GUILD_ID), "{\"discoverable\":true}").principal(AUTHENTICATION))
				.andExpect(status().isOk()).andExpect(jsonPath("$.responseType").value("GUILD"))
				.andExpect(jsonPath("$.body.discoverable").value(true));
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
	@ValueSource(strings = { "123", "not-a-snowflake", "18446744073709551615", "813847887657123840000" })
	void testUpdateGuildRejectsBadGuildId(String guildId) throws Exception {
		assertError(json(put("/api/me/guilds/" + guildId), "{\"discoverable\":true}"), HttpStatus.BAD_REQUEST);
	}

	/**
	 * Tests that a valid bulk {@code PUT /api/me/guilds} reaches the service, at
	 * both ends of the size limit.
	 */
	@Test
	void testUpdateGuilds() throws Exception {
		when(userService.updateGuilds(eq(USER_ID), any())).thenReturn(GUILDS);

		for (var count : List.of(1, UpdateGuildsRequest.MAX_GUILDS))
			mockMvc.perform(json(put("/api/me/guilds"), bulk(count)).principal(AUTHENTICATION))
					.andExpect(status().isOk()).andExpect(jsonPath("$.responseType").value("GUILD_LIST"));
		verify(userService, times(2)).updateGuilds(eq(USER_ID), any());
	}

	/**
	 * Tests that bulk {@code PUT /api/me/guilds} rejects a body that is empty, too
	 * long, repeats a server, or has a bad entry.
	 *
	 * @param body the bad JSON body
	 */
	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"guilds\":null}", "{\"guilds\":[]}", "{\"guilds\":[null]}",
			"{\"guilds\":[{\"guildId\":\"81384788765712384\"}]}", "{\"guilds\":[{\"discoverable\":true}]}",
			"{\"guilds\":[{\"guildId\":\"123\",\"discoverable\":true}]}",
			"{\"guilds\":[{\"guildId\":\"81384788765712384\",\"discoverable\":true},"
					+ "{\"guildId\":\"81384788765712384\",\"discoverable\":false}]}" })
	void testUpdateGuildsRejectsBadBody(String body) throws Exception {
		assertError(json(put("/api/me/guilds"), body), HttpStatus.BAD_REQUEST);
	}

	/**
	 * Tests that bulk {@code PUT /api/me/guilds} rejects more servers than a
	 * Discord account can be in.
	 */
	@Test
	void testUpdateGuildsRejectsTooManyEntries() throws Exception {
		assertError(json(put("/api/me/guilds"), bulk(UpdateGuildsRequest.MAX_GUILDS + 1)), HttpStatus.BAD_REQUEST);
	}

	/**
	 * Tests that a rejected request value never reaches the log, because client
	 * errors log the exception type only.
	 */
	@Test
	void testRejectedValueIsNotLogged() throws Exception {
		var appender = new ListAppender<ILoggingEvent>();
		appender.start();
		var logger = (Logger) LoggerFactory.getLogger(GlobalControllerExceptionHandler.class);
		logger.addAppender(appender);
		try {
			assertError(json(patch("/api/me"), "{\"timeZone\":\"" + SECRET + "\"}"), HttpStatus.BAD_REQUEST);
			assertError(json(patch("/api/me"), "{\"pushEnabled\":\"" + SECRET + "\"}"), HttpStatus.BAD_REQUEST);
		} finally {
			logger.detachAppender(appender);
		}

		assertThat(appender.list).hasSize(2).allSatisfy(
				event -> assertThat(event.getFormattedMessage()).contains("400 from").doesNotContain(SECRET));
	}

	/**
	 * Builds a bulk update body for distinct, valid server IDs.
	 *
	 * @param count the number of entries
	 * @return the JSON body
	 */
	private static String bulk(int count) {
		return IntStream.range(0, count)
				.mapToObj(i -> "{\"guildId\":\"" + (81384788765712384L + i) + "\",\"discoverable\":true}")
				.collect(Collectors.joining(",", "{\"guilds\":[", "]}"));
	}

	/**
	 * Adds a JSON body to a request.
	 *
	 * @param request the request builder
	 * @param body    the JSON body
	 * @return the request builder
	 */
	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
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
		var result = mockMvc.perform(request).andExpect(status().is(status.value()))
				.andExpect(header().exists(GlobalControllerExceptionHandler.CORRELATION_ID_HEADER))
				.andExpect(jsonPath("$.responseType").value("ERROR"))
				.andExpect(jsonPath("$.body.errorType").value(status.name()))
				.andExpect(jsonPath("$.body.errorMessage").value(status.getReasonPhrase()))
				.andExpect(jsonPath("$.body.correlationId").isNotEmpty());
		verifyNoInteractions(userService);
		return result;
	}
}
