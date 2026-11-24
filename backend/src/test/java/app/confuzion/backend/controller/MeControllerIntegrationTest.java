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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import app.confuzion.backend.TestcontainersConfiguration;
import app.confuzion.backend.service.DiscordOAuth2UserService;

/**
 * Authorization tests for every {@code /api/me} and {@code /api/auth} endpoint,
 * with Spring Security applied and a signed-in user from {@code oauth2Login()}:
 * anonymous callers get a 401, mutations need CSRF, and a user can only see and
 * change their own servers (anyone else's is a 404).
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest(properties = "con-fuzion.rate-limiter.token.capacity=10000")
@Import(TestcontainersConfiguration.class)
class MeControllerIntegrationTest {
	/**
	 * A server only the signed-in user is in.
	 */
	private static final String OWN_GUILD = "300000000000000001";

	/**
	 * A second server of the signed-in user.
	 */
	private static final String OWN_GUILD_2 = "300000000000000002";

	/**
	 * A server only the other user is in.
	 */
	private static final String OTHER_GUILD = "300000000000000003";

	/**
	 * The {@link WebApplicationContext}.
	 */
	@Autowired
	private WebApplicationContext webApplicationContext;

	/**
	 * The {@link JdbcTemplate}, to seed rows.
	 */
	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * The {@link MockMvc}, with the security filter chain.
	 */
	private MockMvc mockMvc;

	/**
	 * The signed-in user.
	 */
	private UUID me;

	/**
	 * Another user.
	 */
	private UUID other;

	/**
	 * Seeds two users with their own servers.
	 */
	@BeforeEach
	void seed() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
		me = user();
		other = user();
		guild(me, OWN_GUILD, "Own Con");
		guild(me, OWN_GUILD_2, "Another Own Con");
		guild(other, OTHER_GUILD, "Other Con");
	}

	/**
	 * Tests that every endpoint is a 401 for an anonymous caller.
	 */
	@Test
	void anonymousIsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/me/guilds")).andExpect(status().isUnauthorized());
		mockMvc.perform(json(patch("/api/me"), "{}").with(csrf())).andExpect(status().isUnauthorized());
		mockMvc.perform(json(put("/api/me/guilds/" + OWN_GUILD), "{\"discoverable\":true}").with(csrf()))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(json(put("/api/me/guilds"), bulk(OWN_GUILD)).with(csrf())).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/auth/logout-everywhere").with(csrf())).andExpect(status().isUnauthorized());
	}

	/**
	 * Tests that every mutation is a 403 without a CSRF token.
	 */
	@Test
	void mutationsNeedCsrf() throws Exception {
		mockMvc.perform(json(patch("/api/me"), "{}").with(login(me))).andExpect(status().isForbidden());
		mockMvc.perform(json(put("/api/me/guilds/" + OWN_GUILD), "{\"discoverable\":true}").with(login(me)))
				.andExpect(status().isForbidden());
		mockMvc.perform(json(put("/api/me/guilds"), bulk(OWN_GUILD)).with(login(me))).andExpect(status().isForbidden());
		mockMvc.perform(post("/api/auth/logout-everywhere").with(login(me))).andExpect(status().isForbidden());
		mockMvc.perform(post("/api/auth/logout").with(login(me))).andExpect(status().isForbidden());
		assertThat(discoverable(me, OWN_GUILD)).isFalse();
	}

	/**
	 * Tests the profile read and update.
	 */
	@Test
	void getAndUpdateMe() throws Exception {
		mockMvc.perform(get("/api/me").with(login(me))).andExpect(status().isOk())
				.andExpect(jsonPath("$.responseType").value("ME")).andExpect(jsonPath("$.body.id").value(me.toString()))
				.andExpect(jsonPath("$.body.timeZone").doesNotExist())
				.andExpect(jsonPath("$.body.roles[0]").value("USER"))
				.andExpect(jsonPath("$.body.email").doesNotExist());

		mockMvc.perform(json(patch("/api/me"), "{\"timeZone\":\"America/Chicago\"}").with(login(me)).with(csrf()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.body.timeZone").value("America/Chicago"))
				.andExpect(jsonPath("$.body.pushEnabled").value(false));
		mockMvc.perform(json(patch("/api/me"), "{\"pushEnabled\":true}").with(login(me)).with(csrf()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.body.timeZone").value("America/Chicago"))
				.andExpect(jsonPath("$.body.pushEnabled").value(true));
	}

	/**
	 * Tests that the admin authority shows up as a role.
	 */
	@Test
	void adminAuthorityIsAnAdminRole() throws Exception {
		mockMvc.perform(get("/api/me").with(login(me, "ROLE_USER", "ROLE_ADMIN")))
				.andExpect(jsonPath("$.body.roles[0]").value("USER"))
				.andExpect(jsonPath("$.body.roles[1]").value("ADMIN"));
	}

	/**
	 * Tests that a user sees only their own servers.
	 */
	@Test
	void guildListIsOwnOnly() throws Exception {
		mockMvc.perform(get("/api/me/guilds").with(login(me))).andExpect(status().isOk())
				.andExpect(jsonPath("$.responseType").value("GUILD_LIST"))
				.andExpect(jsonPath("$.body.guilds.length()").value(2))
				.andExpect(jsonPath("$.body.guilds[0].name").value("Another Own Con"))
				.andExpect(jsonPath("$.body.guilds[1].id").value(OWN_GUILD))
				.andExpect(jsonPath("$.body.guilds[1].iconUrl")
						.value("https://cdn.discordapp.com/icons/" + OWN_GUILD + "/hash.png"));
	}

	/**
	 * Tests the single-server update on an own server, and the 404 on someone
	 * else's or an unknown one.
	 */
	@Test
	void updateGuildIsOwnOnly() throws Exception {
		mockMvc.perform(
				json(put("/api/me/guilds/" + OWN_GUILD), "{\"discoverable\":true}").with(login(me)).with(csrf()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.responseType").value("GUILD"))
				.andExpect(jsonPath("$.body.discoverable").value(true));
		assertThat(discoverable(me, OWN_GUILD)).isTrue();

		mockMvc.perform(
				json(put("/api/me/guilds/" + OTHER_GUILD), "{\"discoverable\":true}").with(login(me)).with(csrf()))
				.andExpect(status().isNotFound());
		mockMvc.perform(
				json(put("/api/me/guilds/399999999999999999"), "{\"discoverable\":true}").with(login(me)).with(csrf()))
				.andExpect(status().isNotFound());
		assertThat(discoverable(other, OTHER_GUILD)).isFalse();
	}

	/**
	 * Tests that the bulk update changes every listed server at once.
	 */
	@Test
	void bulkUpdateChangesAll() throws Exception {
		mockMvc.perform(json(put("/api/me/guilds"), bulk(OWN_GUILD, OWN_GUILD_2)).with(login(me)).with(csrf()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.responseType").value("GUILD_LIST"))
				.andExpect(jsonPath("$.body.guilds.length()").value(2))
				.andExpect(jsonPath("$.body.guilds[0].discoverable").value(true))
				.andExpect(jsonPath("$.body.guilds[1].discoverable").value(true));
		assertThat(discoverable(me, OWN_GUILD)).isTrue();
		assertThat(discoverable(me, OWN_GUILD_2)).isTrue();
	}

	/**
	 * Tests that the bulk update is all or nothing: one foreign server makes it a
	 * 404 and changes nothing.
	 */
	@Test
	void bulkUpdateWithForeignGuildChangesNothing() throws Exception {
		mockMvc.perform(json(put("/api/me/guilds"), bulk(OWN_GUILD, OTHER_GUILD)).with(login(me)).with(csrf()))
				.andExpect(status().isNotFound());

		assertThat(discoverable(me, OWN_GUILD)).isFalse();
		assertThat(discoverable(other, OTHER_GUILD)).isFalse();
	}

	/**
	 * Tests that a server ID that matches the pattern but overflows {@code bigint}
	 * is a 400 on both updates.
	 *
	 * @param guildId the overflowing ID
	 */
	@ParameterizedTest
	@ValueSource(strings = { "9223372036854775808", "9999999999999999999" })
	void overflowingGuildIdIsBadRequest(String guildId) throws Exception {
		mockMvc.perform(json(put("/api/me/guilds/" + guildId), "{\"discoverable\":true}").with(login(me)).with(csrf()))
				.andExpect(status().isBadRequest());
		mockMvc.perform(json(put("/api/me/guilds"), bulk(OWN_GUILD, guildId)).with(login(me)).with(csrf()))
				.andExpect(status().isBadRequest());
		assertThat(discoverable(me, OWN_GUILD)).isFalse();
	}

	/**
	 * Returns a signed-in principal for a user, as the login would build it.
	 *
	 * @param userId      the user ID
	 * @param authorities the authorities, {@code ROLE_USER} when none are given
	 * @return the request post-processor
	 */
	private static OAuth2LoginRequestPostProcessor login(UUID userId, String... authorities) {
		var granted = Stream.of(authorities.length == 0 ? new String[] { "ROLE_USER" } : authorities)
				.map(SimpleGrantedAuthority::new).toList();
		return oauth2Login().oauth2User(
				new DefaultOAuth2User(granted, Map.of(DiscordOAuth2UserService.USER_ID_ATTRIBUTE, userId.toString()),
						DiscordOAuth2UserService.USER_ID_ATTRIBUTE));
	}

	/**
	 * Inserts a user with a random Discord ID.
	 *
	 * @return the user ID
	 */
	private UUID user() {
		var discordId = 400000000000000000L + (long) (Math.random() * 99999999999999999L);
		return jdbcTemplate.queryForObject(
				"INSERT INTO app_user (discord_id, username) VALUES (?, 'seeded') RETURNING id", UUID.class, discordId);
	}

	/**
	 * Inserts a server for a user, not discoverable.
	 *
	 * @param userId  the user ID
	 * @param guildId the server ID
	 * @param name    the server name
	 */
	private void guild(UUID userId, String guildId, String name) {
		jdbcTemplate.update(
				"INSERT INTO user_guild (user_id, guild_id, guild_name, icon_hash) VALUES (?, ?, ?, 'hash')", userId,
				Long.parseLong(guildId), name);
	}

	/**
	 * Returns whether a user is discoverable in a server.
	 *
	 * @param userId  the user ID
	 * @param guildId the server ID
	 * @return the flag
	 */
	private boolean discoverable(UUID userId, String guildId) {
		return jdbcTemplate.queryForObject("SELECT discoverable FROM user_guild WHERE user_id = ? AND guild_id = ?",
				Boolean.class, userId, Long.parseLong(guildId));
	}

	/**
	 * Builds a bulk body that makes every listed server discoverable.
	 *
	 * @param guildIds the server IDs
	 * @return the JSON body
	 */
	private static String bulk(String... guildIds) {
		var entries = new StringBuilder();
		for (var guildId : guildIds)
			entries.append(entries.isEmpty() ? "" : ",").append("{\"guildId\":\"").append(guildId)
					.append("\",\"discoverable\":true}");
		return "{\"guilds\":[" + entries + "]}";
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
}
