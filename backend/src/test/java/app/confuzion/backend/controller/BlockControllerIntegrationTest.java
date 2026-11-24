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

import static app.confuzion.backend.controller.SocialFixtures.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import app.confuzion.backend.TestcontainersConfiguration;

/**
 * Integration tests for blocks against Postgres, with Spring Security applied:
 * the full mutual cutoff (D-011), the block list, and unblocking restoring
 * nothing.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest(properties = "con-fuzion.rate-limiter.token.capacity=10000")
@Import(TestcontainersConfiguration.class)
class BlockControllerIntegrationTest {
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
	 * Tests the cutoff: blocking ends the friendship, each one's USER audience
	 * entries on the other's events, and both people's circle memberships of each
	 * other (other members stay), and from then on, both ways, neither can find,
	 * pick, or use an invite of the other. Only the blocker sees the block.
	 */
	@Test
	void blockIsAFullMutualCutoff() throws Exception {
		var me = fixtures.user("cut_me", null);
		var them = fixtures.user("cut_them", null);
		var bystander = fixtures.user("cut_bystander", null);
		var guild = fixtures.guildId();
		for (var user : List.of(me, them, bystander))
			fixtures.guild(user, guild, true);
		fixtures.friends(me, them);
		var myCircle = fixtures.circle(me, them, bystander);
		var theirCircle = fixtures.circle(them, me, bystander);
		var myInvite = fixtures.invite(me, "1 day", false);
		var theirInvite = fixtures.invite(them, "1 day", false);
		var myEvent = fixtures.event(me, them, bystander);
		var theirEvent = fixtures.event(them, me);

		mockMvc.perform(put("/api/blocks/" + them).with(login(me)).with(csrf())).andExpect(status().isNoContent());

		assertThat(fixtures.areFriends(me, them)).isFalse();
		assertThat(fixtures.members(myCircle)).containsExactly(bystander);
		assertThat(fixtures.members(theirCircle)).containsExactly(bystander);
		assertThat(fixtures.audience(myEvent)).containsExactlyInAnyOrder("FRIENDS", "USER:" + bystander);
		assertThat(fixtures.audience(theirEvent)).containsExactly("FRIENDS");
		for (var pair : List.of(List.of(me, them, theirInvite, theirCircle), List.of(them, me, myInvite, myCircle))) {
			var viewer = (UUID) pair.get(0);
			var other = (UUID) pair.get(1);
			assertThat(search(viewer, "cut_")).containsExactly(bystander.toString());
			mockMvc.perform(get("/api/friends/invites/" + pair.get(2)).with(login(viewer)))
					.andExpect(status().isNotFound());
			mockMvc.perform(post("/api/friends/invites/" + pair.get(2) + "/accept").with(login(viewer)).with(csrf()))
					.andExpect(status().isNotFound());
			var ownCircle = viewer.equals(me) ? myCircle : theirCircle;
			mockMvc.perform(put("/api/circles/" + ownCircle + "/members/" + other).with(login(viewer)).with(csrf()))
					.andExpect(status().isNotFound());
		}
		assertThat(blocked(me)).containsExactly(them.toString());
		assertThat(blocked(them)).isEmpty();
	}

	/**
	 * Tests that blocking is idempotent, that an unknown user is a 204 that stores
	 * nothing, and that blocking oneself is a 400.
	 */
	@Test
	void blockEdgeCases() throws Exception {
		var me = fixtures.user("me", null);
		var them = fixtures.user("them", null);

		for (var i = 0; i < 2; i++)
			mockMvc.perform(put("/api/blocks/" + them).with(login(me)).with(csrf())).andExpect(status().isNoContent());
		mockMvc.perform(put("/api/blocks/" + UUID.randomUUID()).with(login(me)).with(csrf()))
				.andExpect(status().isNoContent());
		mockMvc.perform(put("/api/blocks/" + me).with(login(me)).with(csrf())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.body.errorType").value("BAD_REQUEST"));

		assertThat(blocked(me)).containsExactly(them.toString());
	}

	/**
	 * Tests that unblocking removes only the caller's own block and restores
	 * nothing, and is a 204 when there was no block.
	 */
	@Test
	void unblockRestoresNothing() throws Exception {
		var me = fixtures.user("me", null);
		var them = fixtures.user("them", null);
		fixtures.friends(me, them);
		var circle = fixtures.circle(me, them);
		mockMvc.perform(put("/api/blocks/" + them).with(login(me)).with(csrf())).andExpect(status().isNoContent());
		fixtures.block(them, me);

		mockMvc.perform(delete("/api/blocks/" + them).with(login(me)).with(csrf())).andExpect(status().isNoContent());
		mockMvc.perform(delete("/api/blocks/" + them).with(login(me)).with(csrf())).andExpect(status().isNoContent());

		assertThat(blocked(me)).isEmpty();
		assertThat(blocked(them)).containsExactly(me.toString());
		assertThat(fixtures.areFriends(me, them)).isFalse();
		assertThat(fixtures.members(circle)).isEmpty();
	}

	/**
	 * Returns the IDs a user has blocked.
	 *
	 * @param me the user
	 * @return the IDs
	 */
	private List<String> blocked(UUID me) throws Exception {
		return JsonPath.read(mockMvc.perform(get("/api/blocks").with(login(me))).andExpect(status().isOk())
				.andExpect(jsonPath("$.responseType").value("PERSON_LIST")).andReturn().getResponse()
				.getContentAsString(), "$.body.people[*].id");
	}

	/**
	 * Searches as a user.
	 *
	 * @param me the user
	 * @param q  the search text
	 * @return the IDs found
	 */
	private List<String> search(UUID me, String q) throws Exception {
		return JsonPath.read(mockMvc.perform(get("/api/people").param("q", q).with(login(me)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.body.people[*].id");
	}
}
