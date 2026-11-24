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

import static app.confuzion.backend.controller.ErrorEnvelopeAssert.json;
import static app.confuzion.backend.controller.SocialFixtures.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import app.confuzion.backend.TestcontainersConfiguration;

/**
 * Integration tests for circles against Postgres, with Spring Security applied:
 * CRUD, case-insensitive unique names, members, and the 404 for every circle
 * that isn't the caller's.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest(properties = "con-fuzion.rate-limiter.token.capacity=10000")
@Import(TestcontainersConfiguration.class)
class CircleControllerIntegrationTest {
	/**
	 * The {@link WebApplicationContext}.
	 */
	@Autowired
	private WebApplicationContext webApplicationContext;

	/**
	 * The {@link JdbcTemplate}.
	 */
	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * The {@link MockMvc}, with the security filter chain.
	 */
	private MockMvc mockMvc;

	/**
	 * The {@link SocialFixtures}.
	 */
	private SocialFixtures fixtures;

	/**
	 * Builds the {@link MockMvc} and fixtures.
	 */
	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
		fixtures = new SocialFixtures(jdbcTemplate);
	}

	/**
	 * Tests create, list, get, rename and delete, and that names are unique per
	 * owner ignoring case but free across owners.
	 */
	@Test
	void circleCrud() throws Exception {
		var me = fixtures.user("owner", null);
		var other = fixtures.user("other", null);
		var crew = create(me, "Crew");
		create(me, "art friends");
		mockMvc.perform(write(post("/api/circles"), me, "{\"name\":\"CREW\"}")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.body.errorType").value("CONFLICT"));
		create(other, "Crew");

		mockMvc.perform(get("/api/circles").with(login(me))).andExpect(status().isOk())
				.andExpect(jsonPath("$.responseType").value("CIRCLE_LIST"))
				.andExpect(jsonPath("$.body.circles[*].name", contains("art friends", "Crew")));
		mockMvc.perform(write(patch("/api/circles/" + crew), me, "{\"name\":\"Art Friends\"}"))
				.andExpect(status().isConflict());
		mockMvc.perform(write(patch("/api/circles/" + crew), me, "{\"name\":\"crew\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.responseType").value("CIRCLE")).andExpect(jsonPath("$.body.name").value("crew"));
		mockMvc.perform(get("/api/circles/" + crew).with(login(me))).andExpect(status().isOk())
				.andExpect(jsonPath("$.body.id").value(crew)).andExpect(jsonPath("$.body.name").value("crew"));

		mockMvc.perform(write(delete("/api/circles/" + crew), me, null)).andExpect(status().isNoContent());
		mockMvc.perform(get("/api/circles/" + crew).with(login(me))).andExpect(status().isNotFound());
		mockMvc.perform(write(delete("/api/circles/" + crew), me, null)).andExpect(status().isNotFound());
	}

	/**
	 * Tests adding and removing members: both are idempotent, members are listed by
	 * display name, and someone who is no longer pickable stays without a re-check
	 * until removed.
	 */
	@Test
	void membersAreAddedOnceAndKeptWithoutRecheck() throws Exception {
		var me = fixtures.user("owner", null);
		var guild = fixtures.guildId();
		fixtures.guild(me, guild, true);
		var sharer = fixtures.user("zed", null);
		fixtures.guild(sharer, guild, true);
		var friend = fixtures.user("amy", null);
		fixtures.friends(me, friend);
		var circle = create(me, "Crew");
		var members = "/api/circles/" + circle + "/members/";

		for (var member : List.of(sharer, friend, sharer))
			mockMvc.perform(write(put(members + member), me, null)).andExpect(status().isNoContent());
		mockMvc.perform(get("/api/circles").with(login(me)))
				.andExpect(jsonPath("$.body.circles[0].members[*].displayName", contains("amy", "zed")));

		jdbcTemplate.update("UPDATE user_guild SET discoverable = false WHERE user_id = ?", sharer);
		mockMvc.perform(write(put(members + sharer), me, null)).andExpect(status().isNoContent());
		mockMvc.perform(get("/api/circles/" + circle).with(login(me)))
				.andExpect(jsonPath("$.body.members[*].id", contains(friend.toString(), sharer.toString())));

		mockMvc.perform(write(delete(members + sharer), me, null)).andExpect(status().isNoContent());
		mockMvc.perform(write(delete(members + sharer), me, null)).andExpect(status().isNoContent());
		mockMvc.perform(write(put(members + sharer), me, null)).andExpect(status().isNotFound());
		assertThat(fixtures.members(UUID.fromString(circle))).containsExactly(friend);
	}

	/**
	 * Tests that every operation on someone else's circle, or an unknown one, is a
	 * 404 that changes nothing.
	 */
	@Test
	void otherUsersCircleIsNotFound() throws Exception {
		var owner = fixtures.user("owner", null);
		var stranger = fixtures.user("stranger", null);
		var member = fixtures.user("member", null);
		fixtures.friends(stranger, member);
		var circle = fixtures.circle(owner, member);

		for (var id : List.of(circle, UUID.randomUUID())) {
			var path = "/api/circles/" + id;
			mockMvc.perform(get(path).with(login(stranger))).andExpect(status().isNotFound());
			mockMvc.perform(write(patch(path), stranger, "{\"name\":\"Mine now\"}")).andExpect(status().isNotFound());
			mockMvc.perform(write(put(path + "/members/" + member), stranger, null)).andExpect(status().isNotFound());
			mockMvc.perform(write(delete(path + "/members/" + member), stranger, null))
					.andExpect(status().isNotFound());
			mockMvc.perform(write(delete(path), stranger, null)).andExpect(status().isNotFound());
		}
		mockMvc.perform(get("/api/circles").with(login(stranger))).andExpect(jsonPath("$.body.circles").isEmpty());
		mockMvc.perform(get("/api/circles/" + circle).with(login(owner)))
				.andExpect(jsonPath("$.body.name").value(startsWith("Circle ")))
				.andExpect(jsonPath("$.body.members[*].id", contains(member.toString())));
	}

	/**
	 * Creates a circle through the API.
	 *
	 * @param owner the owner
	 * @param name  the name
	 * @return the circle's public ID
	 */
	private String create(UUID owner, String name) throws Exception {
		var body = mockMvc.perform(write(post("/api/circles"), owner, "{\"name\":\"" + name + "\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.responseType").value("CIRCLE"))
				.andExpect(jsonPath("$.body.members").isEmpty()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.body.id");
	}

	/**
	 * Signs a write in, with the CSRF token and an optional JSON body.
	 *
	 * @param request the request builder
	 * @param user    the signed-in user
	 * @param body    the JSON body, or {@code null}
	 * @return the request builder
	 */
	private static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, UUID user, String body) {
		var signed = request.with(login(user)).with(csrf());
		return body == null ? signed : json(signed, body);
	}
}
