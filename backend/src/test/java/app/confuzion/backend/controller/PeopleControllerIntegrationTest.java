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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import app.confuzion.backend.service.FriendService;

/**
 * Integration tests for the pickable set (D-011) against Postgres: people
 * search and adding a circle member agree with one truth table, and search
 * matches the start of a username or display name, ignoring case, literally.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest(properties = "con-fuzion.rate-limiter.token.capacity=10000")
@Import(TestcontainersConfiguration.class)
class PeopleControllerIntegrationTest {
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
	 * Tests the truth table: friends, plus people sharing a server where both are
	 * discoverable; never the viewer, never a block either way. Search and adding a
	 * circle member must give the same answer for every row.
	 */
	@Test
	void pickableTruthTable() throws Exception {
		var me = fixtures.user("tt_me", null);
		var bothDiscoverable = fixtures.guildId();
		var onlyMeDiscoverable = fixtures.guildId();
		var onlyThemDiscoverable = fixtures.guildId();
		fixtures.guild(me, bothDiscoverable, true);
		fixtures.guild(me, onlyMeDiscoverable, true);
		fixtures.guild(me, onlyThemDiscoverable, false);

		var expected = new LinkedHashMap<UUID, Boolean>();
		var friend = expect(expected, "tt_friend", true);
		fixtures.friends(me, friend);
		var sharer = expect(expected, "tt_sharer", true);
		fixtures.guild(sharer, bothDiscoverable, true);
		var friendAndSharer = expect(expected, "tt_friend_and_sharer", true);
		fixtures.friends(me, friendAndSharer);
		fixtures.guild(friendAndSharer, bothDiscoverable, true);
		var theyHidden = expect(expected, "tt_they_hidden", false);
		fixtures.guild(theyHidden, onlyMeDiscoverable, false);
		var iHidden = expect(expected, "tt_i_hidden", false);
		fixtures.guild(iHidden, onlyThemDiscoverable, true);
		var otherServer = expect(expected, "tt_other_server", false);
		fixtures.guild(otherServer, fixtures.guildId(), true);
		expect(expected, "tt_stranger", false);
		var iBlocked = expect(expected, "tt_i_blocked", false);
		fixtures.guild(iBlocked, bothDiscoverable, true);
		fixtures.block(me, iBlocked);
		var blockedMe = expect(expected, "tt_blocked_me", false);
		fixtures.guild(blockedMe, bothDiscoverable, true);
		fixtures.block(blockedMe, me);
		var blockedFriend = expect(expected, "tt_blocked_friend", false);
		fixtures.friends(me, blockedFriend);
		fixtures.block(blockedFriend, me);
		expected.put(me, false);

		var found = search(me, "tt_");
		var circle = fixtures.circle(me);
		for (var row : expected.entrySet()) {
			assertThat(found.contains(row.getKey().toString())).as(row.getKey().toString()).isEqualTo(row.getValue());
			mockMvc.perform(put("/api/circles/" + circle + "/members/" + row.getKey()).with(login(me)).with(csrf()))
					.andExpect(status().is(row.getValue() ? 204 : 404));
		}
		assertThat(found).doesNotHaveDuplicates();
		assertThat(fixtures.members(circle)).containsExactlyInAnyOrder(friend, sharer, friendAndSharer);
		mockMvc.perform(put("/api/circles/" + circle + "/members/" + UUID.randomUUID()).with(login(me)).with(csrf()))
				.andExpect(status().isNotFound());
	}

	/**
	 * Tests that search matches the start of the username or the display name,
	 * ignoring case, treats {@code _} and {@code %} literally, and sorts by display
	 * name.
	 */
	@Test
	void searchMatchesPrefixesLiterallyIgnoringCase() throws Exception {
		var me = fixtures.user("searcher", null);
		var guild = fixtures.guildId();
		fixtures.guild(me, guild, true);
		var byUsername = fixtures.user("con_goer", "Zara");
		var byDisplayName = fixtures.user("zzz", "Con_Fan");
		var wildcardOnly = fixtures.user("conxgoer", null);
		var middle = fixtures.user("the_con_guy", null);
		var percent = fixtures.user("10%off", null);
		var digits = fixtures.user("100off", null);
		for (var user : List.of(byUsername, byDisplayName, wildcardOnly, middle, percent, digits))
			fixtures.guild(user, guild, true);

		assertThat(search(me, "CON_")).containsExactly(byDisplayName.toString(), byUsername.toString());
		assertThat(search(me, "con")).containsExactly(byDisplayName.toString(), wildcardOnly.toString(),
				byUsername.toString());
		assertThat(search(me, "10%")).containsExactly(percent.toString());
		assertThat(search(me, "1%")).isEmpty();
	}

	/**
	 * Tests that search returns at most 20 people.
	 */
	@Test
	void searchReturnsAtMostTwenty() throws Exception {
		var me = fixtures.user("crowd_me", null);
		var guild = fixtures.guildId();
		fixtures.guild(me, guild, true);
		for (var i = 0; i < FriendService.MAX_SEARCH_RESULTS + 5; i++)
			fixtures.guild(fixtures.user("crowd" + i, null), guild, true);

		mockMvc.perform(get("/api/people").param("q", "crowd").with(login(me))).andExpect(status().isOk())
				.andExpect(jsonPath("$.responseType").value("PERSON_LIST"))
				.andExpect(jsonPath("$.body.people.length()").value(FriendService.MAX_SEARCH_RESULTS));
	}

	/**
	 * Creates a user and records whether the viewer should be able to pick them.
	 *
	 * @param expected the truth table
	 * @param username the username
	 * @param pickable whether they are pickable
	 * @return the user ID
	 */
	private UUID expect(Map<UUID, Boolean> expected, String username, boolean pickable) {
		var user = fixtures.user(username, null);
		expected.put(user, pickable);
		return user;
	}

	/**
	 * Searches as a user.
	 *
	 * @param me the user
	 * @param q  the search text
	 * @return the IDs found, in order
	 */
	private List<String> search(UUID me, String q) throws Exception {
		return JsonPath.read(mockMvc.perform(get("/api/people").param("q", q).with(login(me)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.body.people[*].id");
	}
}
