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

import static app.confuzion.backend.controller.EventFixtures.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import app.confuzion.backend.controller.EventFixtures.SeededEvent;

/**
 * The Discover feed's search and filters (D-017): each one alone and combined,
 * paged; that they never reach past visibility, a block or a hidden location;
 * that {@code guild} can't probe servers and follows {@code visibleBecause};
 * and that the other scopes refuse them.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest(properties = "con-fuzion.rate-limiter.token.capacity=10000")
@Import(TestcontainersConfiguration.class)
class EventFeedFiltersIntegrationTest {
	/**
	 * Every title {@link #seedFeed} seeds, in feed order.
	 */
	private static final List<String> ALL = List.of("Board night", "Board swap", "Board brunch", "Cosplay shoot",
			"Dice", "Karaoke", "Panel", "Map talk", "My board");

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
	 * The seeding helpers.
	 */
	private EventFixtures fixtures;

	/**
	 * How many events the current test seeded, so each starts an hour after the
	 * last and the feed's order is the seeding order.
	 */
	private int seeded;

	/**
	 * Builds the {@link MockMvc}.
	 */
	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
		fixtures = new EventFixtures(jdbcTemplate);
		seeded = 0;
	}

	/**
	 * Returns each filter alone and in combination, with the titles it must keep
	 * from {@link #seedFeed}, in feed order. {@code GUILD} stands for the shared
	 * server's ID.
	 *
	 * @return the query and the expected titles
	 */
	static Stream<Arguments> filters() {
		return Stream.of(Arguments.of("", ALL), Arguments.of("friends=false&hasSpots=false", ALL),
				Arguments.of("q=BOARD",
						List.of("Board night", "Board swap", "Board brunch", "Cosplay shoot", "Dice", "My board")),
				Arguments.of("guild=GUILD",
						List.of("Board swap", "Board brunch", "Cosplay shoot", "Panel", "My board")),
				Arguments.of("friends=true", List.of("Board night", "Board swap", "Karaoke")),
				Arguments.of("hasSpots=true",
						List.of("Board night", "Board brunch", "Cosplay shoot", "Dice", "Karaoke", "Map talk",
								"My board")),
				Arguments.of("q=board&guild=GUILD", List.of("Board swap", "Board brunch", "Cosplay shoot", "My board")),
				Arguments.of("q=board&friends=true", List.of("Board night", "Board swap")),
				Arguments.of("q=board&hasSpots=true",
						List.of("Board night", "Board brunch", "Cosplay shoot", "Dice", "My board")),
				Arguments.of("guild=GUILD&hasSpots=true", List.of("Board brunch", "Cosplay shoot", "My board")),
				Arguments.of("friends=true&hasSpots=true", List.of("Board night", "Karaoke")),
				Arguments.of("q=board&guild=GUILD&hasSpots=true", List.of("Board brunch", "Cosplay shoot", "My board")),
				Arguments.of("q=board&guild=GUILD&friends=true&hasSpots=true", List.of()));
	}

	/**
	 * Tests one filter or combination: following the cursor across exactly two
	 * pages, sending the same filters each time, returns the matching events in
	 * feed order and nothing else.
	 *
	 * @param query    the query string
	 * @param expected the titles it keeps
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("filters")
	void filtersNarrowTheFeedAndPage(String query, List<String> expected) throws Exception {
		var viewer = fixtures.user("Viewer");
		var guildId = seedFeed(viewer);
		var limit = Math.max(1, expected.size() - 1);

		var pages = pages(viewer, query.replace("GUILD", Long.toString(guildId)), limit);

		assertThat(pages.stream().flatMap(List::stream).toList()).isEqualTo(expected);
		assertThat(pages).hasSize(expected.size() < 2 ? 1 : 2);
	}

	/**
	 * Seeds a viewer's feed: events from a friend, from someone they share a server
	 * with, from someone who invited them, and their own, each reaching them in a
	 * different way and with a different number of spots left. The viewer's own
	 * event is for the shared server, so {@code guild} keeps it.
	 *
	 * @param viewer the viewer
	 * @return the server the viewer shares with the hosts
	 */
	private long seedFeed(UUID viewer) {
		var friend = fixtures.user("Friend");
		var member = fixtures.user("Member");
		var inviter = fixtures.user("Inviter");
		fixtures.friends(friend, viewer);
		var guildId = fixtures.newGuildId();
		for (var user : List.of(viewer, friend, member))
			fixtures.guild(user, guildId, true);

		var boardNight = event(friend, "Board night", null, null, "Hall A", null, 0);
		fixtures.audience(boardNight.id(), "FRIENDS", null, null, null);
		var boardSwap = event(friend, "Board swap", null, null, "Hall A", 2, 2);
		fixtures.audience(boardSwap.id(), "FRIENDS", null, null, null);
		fixtures.audience(boardSwap.id(), "GUILD", guildId, null, null);
		var boardBrunch = event(member, "Board brunch", null, null, "Hall A", 5, 1);
		fixtures.audience(boardBrunch.id(), "GUILD", guildId, null, null);
		var cosplay = event(member, "Cosplay shoot", "Board the bus at 9", null, "Hall A", null, 1);
		fixtures.audience(cosplay.id(), "GUILD", guildId, null, null);
		var dice = event(inviter, "Dice", null, "Boardgame", "Hall A", null, 0);
		fixtures.audience(dice.id(), "USER", null, null, viewer);
		var karaoke = event(friend, "Karaoke", null, null, "Hall A", 3, 1);
		fixtures.audience(karaoke.id(), "FRIENDS", null, null, null);
		var panel = event(member, "Panel", null, null, "Hall A", 2, 2);
		fixtures.audience(panel.id(), "GUILD", guildId, null, null);
		var mapTalk = event(inviter, "Map talk", null, null, "Board room", null, 0);
		fixtures.audience(mapTalk.id(), "USER", null, null, viewer);
		var own = event(viewer, "My board", null, null, "Hall A", null, 1);
		fixtures.audience(own.id(), "GUILD", guildId, null, null);
		return guildId;
	}

	/**
	 * Tests that the search never matches text that is only in the location,
	 * whether the viewer may see the location or not, nor an event the viewer can't
	 * see, nor one whose host they blocked.
	 */
	@Test
	void searchNeverMatchesTheLocationOrAnInvisibleEvent() throws Exception {
		var viewer = fixtures.user("Viewer");
		var host = fixtures.user("Host");
		var withheld = event(host, "Meet", null, null, "Zebra lair", null, 0, "CONFIRMED");
		fixtures.audience(withheld.id(), "USER", null, null, viewer);
		var shown = event(host, "Gather", null, null, "Zebra lair", null, 0);
		fixtures.audience(shown.id(), "USER", null, null, viewer);
		event(fixtures.user("Stranger"), "Zebra crossing", "Zebra", "Zebra", "Hall A", null, 0);
		var blocked = fixtures.user("Blocked");
		var blockedEvent = event(blocked, "Zebra parade", null, null, "Hall A", null, 0);
		fixtures.audience(blockedEvent.id(), "USER", null, null, viewer);
		fixtures.block(viewer, blocked);

		assertThat(titles(viewer, "q=zebra")).isEmpty();
		assertThat(titles(viewer, "q=lair")).isEmpty();
		assertThat(titles(viewer, "")).containsExactly("Meet", "Gather");
	}

	/**
	 * Tests that the viewer's own events match {@code guild=X} when their audience
	 * names X, and {@code friends=true} when it has a {@code FRIENDS} entry, but
	 * not another server, and not when the audience is only a circle or a person.
	 */
	@Test
	void ownEventsMatchTheirOwnAudience() throws Exception {
		var viewer = fixtures.user("Viewer");
		var x = fixtures.newGuildId();
		var y = fixtures.newGuildId();
		fixtures.guild(viewer, x, true);
		fixtures.guild(viewer, y, true);
		var forServer = event(viewer, "For X", null, null, "Hall A", null, 1);
		fixtures.audience(forServer.id(), "GUILD", x, null, null);
		var forFriends = event(viewer, "For friends", null, null, "Hall A", null, 1);
		fixtures.audience(forFriends.id(), "FRIENDS", null, null, null);
		var forCircle = event(viewer, "For a circle", null, null, "Hall A", null, 1);
		fixtures.audience(forCircle.id(), "CIRCLE", null, fixtures.circle(viewer, fixtures.user("Member")).id(), null);
		var forPerson = event(viewer, "For a person", null, null, "Hall A", null, 1);
		fixtures.audience(forPerson.id(), "USER", null, null, fixtures.user("Guest"));

		assertThat(titles(viewer, "guild=" + x)).containsExactly("For X");
		assertThat(titles(viewer, "guild=" + y)).isEmpty();
		assertThat(titles(viewer, "friends=true")).containsExactly("For friends");
		assertThat(titles(viewer, "")).containsExactly("For X", "For friends", "For a circle", "For a person");
	}

	/**
	 * Tests that {@code %}, {@code _}, the escape character {@code !} and a
	 * backslash in the search match only themselves.
	 */
	@Test
	void wildcardsMatchLiterally() throws Exception {
		var viewer = fixtures.user("Viewer");
		var host = fixtures.user("Host");
		for (var title : List.of("100% fun", "100 percent", "snake_case", "snakeXcase", "Wow! cards", "Wowie",
				"C:\\dir", "C:dir")) {
			var event = event(host, title, null, null, "Hall A", null, 0);
			fixtures.audience(event.id(), "USER", null, null, viewer);
		}

		assertThat(titles(viewer, "q=0%")).containsExactly("100% fun");
		assertThat(titles(viewer, "q=%%")).isEmpty();
		assertThat(titles(viewer, "q=e_c")).containsExactly("snake_case");
		assertThat(titles(viewer, "q=__")).isEmpty();
		assertThat(titles(viewer, "q=w!")).containsExactly("Wow! cards");
		assertThat(titles(viewer, "q=:\\")).containsExactly("C:\\dir");
	}

	/**
	 * Returns the ways a {@code guild} can fail to be a server where the viewer is
	 * discoverable.
	 *
	 * @return the case names
	 */
	static Stream<String> unusableGuilds() {
		return Stream.of("unknown", "not a member", "not discoverable", "not a snowflake", "too large");
	}

	/**
	 * Tests that a server the viewer isn't discoverable in is a 400 with the
	 * generic body however it fails, so the filter can't tell a server that exists
	 * from one that doesn't, or one the viewer isn't in from one they hide in.
	 *
	 * @param name the case name
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("unusableGuilds")
	void unusableGuildIsABadRequest(String name) throws Exception {
		var viewer = fixtures.user("Viewer");
		var host = fixtures.user("Host");
		var guildId = fixtures.newGuildId();
		fixtures.guild(host, guildId, true);
		var event = event(host, "Meet", null, null, "Hall A", null, 0);
		fixtures.audience(event.id(), "GUILD", guildId, null, null);
		fixtures.audience(event.id(), "USER", null, null, viewer);
		var guild = switch (name) {
		case "unknown" -> Long.toString(fixtures.newGuildId());
		case "not a member" -> Long.toString(guildId);
		case "not discoverable" -> {
			fixtures.guild(viewer, guildId, false);
			yield Long.toString(guildId);
		}
		case "not a snowflake" -> "12345";
		default -> "9999999999999999999";
		};

		var response = mockMvc.perform(get("/api/events").param("guild", guild).with(login(viewer))).andReturn()
				.getResponse();

		assertThat(response.getStatus()).isEqualTo(400);
		var body = response.getContentAsString();
		assertThat(JsonPath.<Map<String, Object>>read(body, "$.body")).containsOnlyKeys("errorType", "errorMessage",
				"correlationId");
		assertThat(JsonPath.<String>read(body, "$.body.errorType")).isEqualTo("BAD_REQUEST");
		assertThat(body).doesNotContain("\"Con\"", "Meet");
		if (guild.length() >= 17)
			assertThat(body).doesNotContain(guild);
	}

	/**
	 * Tests that an event stops matching {@code guild=X} once its host turns
	 * discoverability off in X, or leaves X, while the viewer still sees it through
	 * another server and a personal invite.
	 *
	 * @param leave {@code true} to leave the server, {@code false} to stop being
	 *              discoverable there
	 */
	@ParameterizedTest(name = "leave = {0}")
	@ValueSource(booleans = { false, true })
	void guildFollowsTheHostsMembershipNow(boolean leave) throws Exception {
		var viewer = fixtures.user("Viewer");
		var host = fixtures.user("Host");
		var x = fixtures.newGuildId();
		var other = fixtures.newGuildId();
		for (var guildId : List.of(x, other))
			for (var user : List.of(viewer, host))
				fixtures.guild(user, guildId, true);
		var event = event(host, "Meet", null, null, "Hall A", null, 0);
		fixtures.audience(event.id(), "GUILD", x, null, null);
		fixtures.audience(event.id(), "GUILD", other, null, null);
		fixtures.audience(event.id(), "USER", null, null, viewer);
		assertThat(titles(viewer, "guild=" + x)).containsExactly("Meet");

		if (leave)
			jdbcTemplate.update("DELETE FROM user_guild WHERE user_id = ? AND guild_id = ?", host, x);
		else
			jdbcTemplate.update("UPDATE user_guild SET discoverable = false WHERE user_id = ? AND guild_id = ?", host,
					x);

		assertThat(titles(viewer, "guild=" + x)).isEmpty();
		assertThat(titles(viewer, "guild=" + other)).containsExactly("Meet");
		assertThat(titles(viewer, "")).containsExactly("Meet");
	}

	/**
	 * Returns every filter, alone and all together, with each block direction.
	 *
	 * @return the query and who blocks whom
	 */
	static Stream<Arguments> blockedFilters() {
		var rows = new ArrayList<Arguments>();
		for (var query : List.of("", "q=board", "guild=GUILD", "friends=true", "hasSpots=true",
				"q=board&guild=GUILD&friends=true&hasSpots=true"))
			for (var hostBlocks : List.of(true, false))
				rows.add(Arguments.of(query, hostBlocks));
		return rows.stream();
	}

	/**
	 * Tests that a block either way hides an event under every filter, even one
	 * that reaches the viewer in every way and matches every filter.
	 *
	 * @param query      the query string
	 * @param hostBlocks {@code true} when the host blocks the viewer, {@code false}
	 *                   for the other way round
	 */
	@ParameterizedTest(name = "{0}, host blocks = {1}")
	@MethodSource("blockedFilters")
	void blocksHideEventsUnderEveryFilter(String query, boolean hostBlocks) throws Exception {
		var viewer = fixtures.user("Viewer");
		var host = fixtures.user("Host");
		fixtures.friends(host, viewer);
		var guildId = fixtures.newGuildId();
		fixtures.guild(viewer, guildId, true);
		fixtures.guild(host, guildId, true);
		var event = event(host, "Board games", null, null, "Hall A", null, 0);
		fixtures.audience(event.id(), "FRIENDS", null, null, null);
		fixtures.audience(event.id(), "GUILD", guildId, null, null);
		fixtures.audience(event.id(), "USER", null, null, viewer);
		var withGuild = query.replace("GUILD", Long.toString(guildId));
		assertThat(titles(viewer, withGuild)).containsExactly("Board games");

		if (hostBlocks)
			fixtures.block(host, viewer);
		else
			fixtures.block(viewer, host);

		assertThat(titles(viewer, withGuild)).isEmpty();
	}

	/**
	 * Tests that each parameter is a 400 with {@code hosting} and
	 * {@code attending}, even as {@code false}.
	 *
	 * @param query the query string, without the scope
	 */
	@ParameterizedTest
	@ValueSource(strings = { "q=board", "guild=GUILD", "friends=true", "friends=false", "hasSpots=true",
			"hasSpots=false" })
	void otherScopesRejectTheFilters(String query) throws Exception {
		var viewer = fixtures.user("Viewer");
		var guildId = fixtures.newGuildId();
		fixtures.guild(viewer, guildId, true);
		for (var scope : List.of("hosting", "attending")) {
			var status = mockMvc.perform(list(viewer, scope, query.replace("GUILD", Long.toString(guildId))))
					.andReturn().getResponse().getStatus();
			assertThat(status).as(scope).isEqualTo(400);
		}
	}

	/**
	 * Inserts a published event starting an hour after the previous one, with its
	 * location visible to viewers.
	 *
	 * @param hostId      the host
	 * @param title       the title
	 * @param description the description, or {@code null}
	 * @param tag         the tag, or {@code null}
	 * @param location    the location
	 * @param capacity    the headcount, or {@code null} for unlimited
	 * @param confirmed   how many spots are taken
	 * @return the event's IDs
	 */
	private SeededEvent event(UUID hostId, String title, String description, String tag, String location,
			Integer capacity, int confirmed) {
		return event(hostId, title, description, tag, location, capacity, confirmed, "VIEWERS");
	}

	/**
	 * Inserts a published event starting an hour after the previous one.
	 *
	 * @param hostId             the host
	 * @param title              the title
	 * @param description        the description, or {@code null}
	 * @param tag                the tag, or {@code null}
	 * @param location           the location
	 * @param capacity           the headcount, or {@code null} for unlimited
	 * @param confirmed          how many spots are taken
	 * @param locationVisibility {@code VIEWERS} or {@code CONFIRMED}
	 * @return the event's IDs
	 */
	private SeededEvent event(UUID hostId, String title, String description, String tag, String location,
			Integer capacity, int confirmed, String locationVisibility) {
		seeded++;
		return jdbcTemplate.queryForObject("""
				INSERT INTO event (host_id, title, description, tag, location_text, starts_at, time_zone, capacity,
					confirmed_count, location_visibility)
				VALUES (?, ?, ?, ?, ?, now() + make_interval(hours => ?), 'UTC', ?, ?, ?) RETURNING id, public_id
				""", (rs, row) -> new SeededEvent(rs.getLong(1), rs.getObject(2, UUID.class)), hostId, title,
				description, tag, location, seeded, capacity, confirmed, locationVisibility);
	}

	/**
	 * Returns the titles in a viewer's feed, which must fit on one page of 50.
	 *
	 * @param viewer the viewer
	 * @param query  the query string, which may be empty
	 * @return the titles, in feed order
	 */
	private List<String> titles(UUID viewer, String query) throws Exception {
		var pages = pages(viewer, query, 50);
		assertThat(pages).hasSize(1);
		return pages.getFirst();
	}

	/**
	 * Follows the cursor through a viewer's feed, sending the same query with each
	 * page, and returns each page's titles.
	 *
	 * @param viewer the viewer
	 * @param query  the query string, which may be empty
	 * @param limit  the page size
	 * @return the titles, page by page
	 */
	private List<List<String>> pages(UUID viewer, String query, int limit) throws Exception {
		var pages = new ArrayList<List<String>>();
		String cursor = null;
		do {
			var request = list(viewer, "feed", query).param("limit", Integer.toString(limit));
			if (cursor != null)
				request.param("cursor", cursor);
			var response = mockMvc.perform(request).andReturn().getResponse();
			assertThat(response.getStatus()).as(query).isEqualTo(200);
			var body = response.getContentAsString();
			pages.add(JsonPath.read(body, "$.body.events[*].title"));
			cursor = JsonPath.read(body, "$.body.nextCursor");
		} while (cursor != null && pages.size() < 10);
		return pages;
	}

	/**
	 * Builds a list request from a query string whose values aren't URL-encoded, so
	 * that {@code %} reaches the server as it is.
	 *
	 * @param userId the caller
	 * @param scope  the scope
	 * @param query  {@code name=value} pairs joined by {@code &}, which may be
	 *               empty
	 * @return the request
	 */
	private static MockHttpServletRequestBuilder list(UUID userId, String scope, String query) {
		var request = get("/api/events").param("scope", scope).with(login(userId));
		for (var pair : query.split("&"))
			if (!pair.isEmpty()) {
				var nameValue = pair.split("=", 2);
				request.param(nameValue[0], nameValue[1]);
			}
		return request;
	}
}
