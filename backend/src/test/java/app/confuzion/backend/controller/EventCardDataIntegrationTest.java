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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import app.confuzion.backend.StatementCounting;
import app.confuzion.backend.TestcontainersConfiguration;
import app.confuzion.backend.controller.EventFixtures.SeededEvent;

/**
 * The card data on {@code EventResponse} (D-017): the truth table for
 * {@code visibleBecause}, its exceptions (the host, an RSVP kept after the
 * audience stopped reaching the viewer, a moderator's review),
 * {@code spotsLeft}, and the statement count of a page, which must not grow
 * with the page.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest(properties = "con-fuzion.rate-limiter.token.capacity=10000")
@Import({ TestcontainersConfiguration.class, StatementCounting.class })
class EventCardDataIntegrationTest {
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
	 * Every server name the current test seeded, so a row can check that the ones
	 * it doesn't expect never appear.
	 */
	private final List<String> guildNames = new ArrayList<>();

	/**
	 * Builds the {@link MockMvc}.
	 */
	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
		fixtures = new EventFixtures(jdbcTemplate);
	}

	/**
	 * Sets up one audience case for a fresh host, viewer and event.
	 *
	 * @author mcawful
	 */
	@FunctionalInterface
	interface Audience {
		/**
		 * Adds the case's servers, relationships and audience entries.
		 *
		 * @param test   the test, for its seeding helpers
		 * @param host   the host
		 * @param viewer the viewer
		 * @param event  the event
		 */
		void seed(EventCardDataIntegrationTest test, UUID host, UUID viewer, SeededEvent event);
	}

	/**
	 * Returns every audience case and the viewer's expected reasons, or
	 * {@code null} when the event must stay invisible. A reason is written
	 * {@code GUILD:<name>}, {@code FRIEND} or {@code INVITED}.
	 *
	 * @return the name, the case and the expected reasons
	 */
	static List<Arguments> audiences() {
		return List.of(Arguments.of("no entries", (Audience) (t, h, v, e) -> {
		}, null), Arguments.of("USER names the viewer", (Audience) (t, h, v, e) -> {
			t.fixtures.audience(e.id(), "USER", null, null, v);
		}, List.of("INVITED")), Arguments.of("USER names someone else", (Audience) (t, h, v, e) -> {
			t.fixtures.audience(e.id(), "USER", null, null, t.fixtures.user("Other"));
		}, null), Arguments.of("FRIENDS, viewer is a friend", (Audience) (t, h, v, e) -> {
			t.fixtures.friends(h, v);
			t.fixtures.audience(e.id(), "FRIENDS", null, null, null);
		}, List.of("FRIEND")), Arguments.of("FRIENDS, viewer isn't a friend", (Audience) (t, h, v, e) -> {
			t.fixtures.audience(e.id(), "FRIENDS", null, null, null);
		}, null), Arguments.of("friend invited personally, no FRIENDS entry", (Audience) (t, h, v, e) -> {
			t.fixtures.friends(h, v);
			t.fixtures.audience(e.id(), "USER", null, null, v);
		}, List.of("INVITED")), Arguments.of("CIRCLE, viewer is a member", (Audience) (t, h, v, e) -> {
			t.fixtures.audience(e.id(), "CIRCLE", null, t.fixtures.circle(h, v).id(), null);
		}, List.of("INVITED")), Arguments.of("CIRCLE, viewer isn't a member", (Audience) (t, h, v, e) -> {
			t.fixtures.audience(e.id(), "CIRCLE", null, t.fixtures.circle(h, t.fixtures.user("Other")).id(), null);
		}, null), Arguments.of("two circles and a personal invite", (Audience) (t, h, v, e) -> {
			t.fixtures.audience(e.id(), "CIRCLE", null, t.fixtures.circle(h, v).id(), null);
			t.fixtures.audience(e.id(), "CIRCLE", null, t.fixtures.circle(h, v).id(), null);
			t.fixtures.audience(e.id(), "USER", null, null, v);
		}, List.of("INVITED")), guild("both discoverable", true, true, List.of("GUILD:Alpha")),
				guild("viewer not discoverable", false, true, null), guild("host not discoverable", true, false, null),
				guild("neither discoverable", false, false, null),
				Arguments.of("GUILD, host left the server", (Audience) (t, h, v, e) -> {
					var guildId = t.fixtures.newGuildId();
					t.guild(v, guildId, "Alpha", true);
					t.guild(h, guildId, "Alpha", true);
					t.fixtures.audience(e.id(), "GUILD", guildId, null, null);
					t.jdbcTemplate.update("DELETE FROM user_guild WHERE user_id = ? AND guild_id = ?", h, guildId);
				}, null), Arguments.of("GUILD, viewer not in the server", (Audience) (t, h, v, e) -> {
					var guildId = t.fixtures.newGuildId();
					t.guild(h, guildId, "Alpha", true);
					t.fixtures.audience(e.id(), "GUILD", guildId, null, null);
				}, null), Arguments.of("every arm at once", (Audience) (t, h, v, e) -> {
					t.sharedGuild(h, v, e, "Alpha", true, true);
					t.sharedGuild(h, v, e, "Gamma", true, false);
					var left = t.sharedGuild(h, v, e, "Delta", true, true);
					t.jdbcTemplate.update("DELETE FROM user_guild WHERE user_id = ? AND guild_id = ?", h, left);
					t.sharedGuild(h, v, e, "Epsilon", false, true);
					var notIn = t.fixtures.newGuildId();
					t.guild(h, notIn, "Zeta", true);
					t.fixtures.audience(e.id(), "GUILD", notIn, null, null);
					t.sharedGuild(h, v, e, "Beta", true, true);
					t.fixtures.friends(h, v);
					t.fixtures.audience(e.id(), "FRIENDS", null, null, null);
					t.fixtures.audience(e.id(), "CIRCLE", null, t.fixtures.circle(h, v).id(), null);
					t.fixtures.audience(e.id(), "CIRCLE", null, t.fixtures.circle(h, t.fixtures.user("Other")).id(),
							null);
					t.fixtures.audience(e.id(), "USER", null, null, v);
				}, List.of("GUILD:Alpha", "GUILD:Beta", "FRIEND", "INVITED")));
	}

	/**
	 * Returns a case with one server that both the viewer and the host are in.
	 *
	 * @param name               the case name
	 * @param viewerDiscoverable whether the viewer is discoverable there
	 * @param hostDiscoverable   whether the host is discoverable there
	 * @param expected           the expected reasons, or {@code null} for invisible
	 * @return the arguments
	 */
	private static Arguments guild(String name, boolean viewerDiscoverable, boolean hostDiscoverable,
			List<String> expected) {
		return Arguments.of("GUILD, " + name,
				(Audience) (t, h, v, e) -> t.sharedGuild(h, v, e, "Alpha", viewerDiscoverable, hostDiscoverable),
				expected);
	}

	/**
	 * Returns the full table: each audience case as it is, then with the host
	 * blocking the viewer and the viewer blocking the host, which must hide it.
	 *
	 * @return the name, case, block and expected reasons
	 */
	static Stream<Arguments> truthTable() {
		var rows = new ArrayList<Arguments>();
		for (var audience : audiences()) {
			var args = audience.get();
			rows.add(Arguments.of(args[0], args[1], Block.NONE, args[2]));
			rows.add(Arguments.of(args[0] + " + host blocks viewer", args[1], Block.HOST_BLOCKS, null));
			rows.add(Arguments.of(args[0] + " + viewer blocks host", args[1], Block.VIEWER_BLOCKS, null));
		}
		return rows.stream();
	}

	/**
	 * Which block, if any, stands between the viewer and the host.
	 *
	 * @author mcawful
	 */
	enum Block {
		/**
		 * None.
		 */
		NONE,

		/**
		 * The host blocks the viewer.
		 */
		HOST_BLOCKS,

		/**
		 * The viewer blocks the host.
		 */
		VIEWER_BLOCKS
	}

	/**
	 * Tests one row of the truth table: the viewer's reasons in the detail view and
	 * the feed, or a 404 and no feed entry. Whatever the row, the viewer's
	 * responses never name a server outside their reasons or carry any of the
	 * host's circle names or IDs, and the host gets {@code null}.
	 *
	 * @param name     the case name
	 * @param audience the audience case
	 * @param block    the block, if any
	 * @param expected the expected reasons, or {@code null} for invisible
	 */
	@ParameterizedTest(name = "{0} -> {3}")
	@MethodSource("truthTable")
	void visibleBecause(String name, Audience audience, Block block, List<String> expected) throws Exception {
		var host = fixtures.user("Host");
		var viewer = fixtures.user("Viewer");
		var event = fixtures.event(host);
		audience.seed(this, host, viewer, event);
		switch (block) {
		case HOST_BLOCKS -> fixtures.block(host, viewer);
		case VIEWER_BLOCKS -> fixtures.block(viewer, host);
		case NONE -> {
		}
		}

		var detail = mockMvc.perform(get("/api/events/" + event.publicId()).with(login(viewer))).andReturn()
				.getResponse();
		var feed = feed(viewer, "feed");
		if (expected == null) {
			assertThat(detail.getStatus()).as("detail").isEqualTo(404);
			assertThat(JsonPath.<List<String>>read(feed, "$.body.events[*].id")).as("feed").isEmpty();
		} else {
			assertThat(detail.getStatus()).as("detail").isEqualTo(200);
			assertThat(reasons(JsonPath.read(detail.getContentAsString(), "$.body.visibleBecause"))).as("detail")
					.isEqualTo(expected);
			assertThat(reasons(JsonPath.read(feed, "$.body.events[0].visibleBecause"))).as("feed").isEqualTo(expected);
		}
		for (var body : List.of(detail.getContentAsString(), feed))
			assertNoLeaks(body, host, expected == null ? List.of() : expected);

		var hostDetail = mockMvc.perform(get("/api/events/" + event.publicId()).with(login(host))).andReturn()
				.getResponse().getContentAsString();
		assertThat(JsonPath.<Object>read(hostDetail, "$.body.visibleBecause")).as("host").isNull();
	}

	/**
	 * Tests that an RSVP kept after the audience stopped reaching the viewer
	 * (removed from the circle, or no longer discoverable in the server) keeps the
	 * event visible with an empty list, in the detail view, the feed and My
	 * schedule, never falling back to the audience (D-013 rule 10, D-017).
	 */
	@Test
	void rsvpAfterTheAudienceStoppedReachingTheViewerGivesAnEmptyList() throws Exception {
		var host = fixtures.user("Host");
		var viewer = fixtures.user("Viewer");
		var event = fixtures.event(host);
		var circle = fixtures.circle(host, viewer);
		fixtures.audience(event.id(), "CIRCLE", null, circle.id(), null);
		var guildId = sharedGuild(host, viewer, event, "Alpha", true, true);
		confirm(event, viewer);
		assertThat(detailReasons(viewer, event)).containsExactly("GUILD:Alpha", "INVITED");

		jdbcTemplate.update("DELETE FROM circle_member WHERE circle_id = ?", circle.id());
		jdbcTemplate.update("UPDATE user_guild SET discoverable = false WHERE user_id = ? AND guild_id = ?", viewer,
				guildId);

		assertThat(detailReasons(viewer, event)).isEmpty();
		for (var scope : List.of("feed", "attending")) {
			var body = feed(viewer, scope);
			assertThat(JsonPath.<List<String>>read(body, "$.body.events[*].id")).as(scope)
					.containsExactly(event.publicId().toString());
			assertThat(reasons(JsonPath.read(body, "$.body.events[0].visibleBecause"))).as(scope).isEmpty();
			assertNoLeaks(body, host, List.of());
		}
	}

	/**
	 * Tests that an attendee of a cancelled event, whose audience still names them,
	 * gets an empty list: a cancelled event is visible only through the RSVP.
	 */
	@Test
	void rsvpOnACancelledEventGivesAnEmptyList() throws Exception {
		var host = fixtures.user("Host");
		var viewer = fixtures.user("Viewer");
		var event = fixtures.event(host);
		fixtures.audience(event.id(), "USER", null, null, viewer);
		confirm(event, viewer);
		fixtures.cancel(event.id());

		assertThat(detailReasons(viewer, event)).isEmpty();
	}

	/**
	 * Tests that a moderator reviewing a hidden event gets an empty list, even when
	 * its audience reaches them (D-015 rule 10, D-017).
	 */
	@Test
	void moderatorReviewingAHiddenEventGetsAnEmptyList() throws Exception {
		var host = fixtures.user("Host");
		var moderator = fixtures.user("Moderator");
		jdbcTemplate.update("INSERT INTO user_role (user_id, role) VALUES (?, 'MODERATOR')", moderator);
		jdbcTemplate.update("UPDATE app_user SET mfa_enabled = true WHERE id = ?", moderator);
		var event = fixtures.event(host);
		fixtures.friends(host, moderator);
		fixtures.audience(event.id(), "FRIENDS", null, null, null);
		jdbcTemplate.update("UPDATE event SET hidden_by_moderation = true, moderation_reason = 'Spam' WHERE id = ?",
				event.id());

		var body = mockMvc
				.perform(get("/api/events/" + event.publicId())
						.with(ReportControllerIntegrationTest.as(moderator, "ROLE_MODERATOR")))
				.andReturn().getResponse().getContentAsString();
		assertThat(reasons(JsonPath.read(body, "$.body.visibleBecause"))).isEmpty();
	}

	/**
	 * Tests that the host gets {@code null} in every list, and the full audience
	 * instead.
	 */
	@Test
	void hostGetsNullInEveryList() throws Exception {
		var host = fixtures.user("Host");
		var event = fixtures.event(host);
		sharedGuild(host, fixtures.user("Viewer"), event, "Alpha", true, true);
		jdbcTemplate.update("INSERT INTO rsvp (event_id, user_id, status) VALUES (?, ?, 'CONFIRMED')", event.id(),
				host);

		for (var scope : List.of("feed", "hosting", "attending")) {
			var body = feed(host, scope);
			assertThat(JsonPath.<List<Object>>read(body, "$.body.events[*].visibleBecause")).as(scope)
					.containsExactly((Object) null);
			assertThat(JsonPath.<List<String>>read(body, "$.body.events[0].audience[*].label")).as(scope)
					.containsExactly("Alpha");
		}
	}

	/**
	 * Tests {@code spotsLeft} for a full, a partly full and an unlimited event, as
	 * the host and a viewer see them.
	 */
	@Test
	void spotsLeft() throws Exception {
		var host = fixtures.user("Host");
		var viewer = fixtures.user("Viewer");
		var full = fixtures.rsvpEvent(host, 2, "now() + interval '1 day'", "NULL");
		var partly = fixtures.rsvpEvent(host, 5, "now() + interval '2 days'", "NULL");
		var unlimited = fixtures.rsvpEvent(host, null, "now() + interval '3 days'", "NULL");
		for (var event : List.of(full, partly, unlimited)) {
			fixtures.audience(event.id(), "USER", null, null, viewer);
			confirm(event, fixtures.user("Guest"));
		}

		for (var userId : List.of(host, viewer)) {
			var body = feed(userId, "feed");
			assertThat(JsonPath.<List<Integer>>read(body, "$.body.events[*].spotsLeft")).containsExactly(0, 3, null);
		}
		var detail = mockMvc.perform(get("/api/events/" + full.publicId()).with(login(viewer))).andReturn()
				.getResponse().getContentAsString();
		assertThat(JsonPath.<Integer>read(detail, "$.body.spotsLeft")).isZero();
	}

	/**
	 * Tests that the host's create and edit responses count the spots the RSVP
	 * writes took: the host's own spot on create, and the waitlist's promotions
	 * when an edit raises the capacity.
	 */
	@Test
	void spotsLeftAfterCreateAndEdit() throws Exception {
		var host = fixtures.user("Host");
		var startsAt = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		var created = mockMvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON)
				.content(eventBody(startsAt, 2)).with(login(host)).with(csrf())).andReturn().getResponse();
		assertThat(created.getStatus()).isEqualTo(201);
		assertThat(JsonPath.<Integer>read(created.getContentAsString(), "$.body.spotsLeft")).isEqualTo(1);

		String eventId = JsonPath.read(created.getContentAsString(), "$.body.id");
		var event = new SeededEvent(
				jdbcTemplate.queryForObject("SELECT id FROM event WHERE public_id = ?::uuid", Long.class, eventId),
				UUID.fromString(eventId));
		confirm(event, fixtures.user("Guest"));
		for (var i = 0; i < 3; i++)
			jdbcTemplate.update("INSERT INTO rsvp (event_id, user_id, status) VALUES (?, ?, 'WAITLISTED')", event.id(),
					fixtures.user("Waiting"));

		var edited = mockMvc.perform(patch("/api/events/" + eventId).contentType(MediaType.APPLICATION_JSON)
				.content(eventBody(startsAt, 4)).with(login(host)).with(csrf())).andReturn().getResponse();
		assertThat(edited.getStatus()).isEqualTo(200);
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(4);
		assertThat(JsonPath.<Integer>read(edited.getContentAsString(), "$.body.spotsLeft")).isZero();
	}

	/**
	 * Tests that a page takes as many statements with eight events as with one,
	 * each from a different host and reaching the viewer through every arm, so the
	 * card data isn't loaded per event.
	 */
	@Test
	void statementCountStaysFlatAsThePageGrows() throws Exception {
		var viewer = fixtures.user("Viewer");
		for (var i = 0; i < 8; i++) {
			var host = fixtures.user("Host");
			var event = fixtures.event(host, "now() + interval '" + (i + 1) + " hours'", "NULL");
			sharedGuild(host, viewer, event, "Server " + i, true, true);
			fixtures.friends(host, viewer);
			fixtures.audience(event.id(), "FRIENDS", null, null, null);
			fixtures.audience(event.id(), "CIRCLE", null, fixtures.circle(host, viewer).id(), null);
			confirm(event, viewer);
		}

		for (var scope : List.of("feed", "attending")) {
			statements(viewer, scope, 1);
			var one = statements(viewer, scope, 1);
			var eight = statements(viewer, scope, 8);
			assertThat(eight).as(scope).isEqualTo(one);
		}
		var body = feed(viewer, "feed");
		assertThat(JsonPath.<List<String>>read(body, "$.body.events[*].visibleBecause[0].guildName")).hasSize(8);
	}

	/**
	 * Requests a page and returns how many statements it took.
	 *
	 * @param viewer the viewer
	 * @param scope  the scope
	 * @param limit  the page size
	 * @return the statement count
	 */
	private int statements(UUID viewer, String scope, int limit) throws Exception {
		StatementCounting.STATEMENTS.set(0);
		var body = mockMvc.perform(
				get("/api/events").param("scope", scope).param("limit", Integer.toString(limit)).with(login(viewer)))
				.andReturn().getResponse().getContentAsString();
		var count = StatementCounting.STATEMENTS.get();
		assertThat(JsonPath.<List<String>>read(body, "$.body.events[*].id")).hasSize(limit);
		return count;
	}

	/**
	 * Builds a create or edit body for the host's friends.
	 *
	 * @param startsAt the start
	 * @param capacity the headcount
	 * @return the JSON body
	 */
	private static String eventBody(Instant startsAt, int capacity) {
		return """
				{"title": "Photo walk", "location": "Hall B", "startsAt": "%s", "timeZone": "UTC",
				"locationVisibility": "VIEWERS", "capacity": %d, "audience": [{"type": "FRIENDS"}]}
				""".formatted(startsAt, capacity);
	}

	/**
	 * Puts the viewer and the host in a new server and adds it to the event's
	 * audience.
	 *
	 * @param host               the host
	 * @param viewer             the viewer
	 * @param event              the event
	 * @param name               the server's name
	 * @param viewerDiscoverable whether the viewer is discoverable there
	 * @param hostDiscoverable   whether the host is discoverable there
	 * @return the server ID
	 */
	long sharedGuild(UUID host, UUID viewer, SeededEvent event, String name, boolean viewerDiscoverable,
			boolean hostDiscoverable) {
		var guildId = fixtures.newGuildId();
		guild(viewer, guildId, name, viewerDiscoverable);
		guild(host, guildId, name, hostDiscoverable);
		fixtures.audience(event.id(), "GUILD", guildId, null, null);
		return guildId;
	}

	/**
	 * Puts a user in a server with a name.
	 *
	 * @param userId       the user
	 * @param guildId      the server ID
	 * @param name         the server's name
	 * @param discoverable whether they are discoverable there
	 */
	void guild(UUID userId, long guildId, String name, boolean discoverable) {
		guildNames.add(name);
		jdbcTemplate.update("INSERT INTO user_guild (user_id, guild_id, guild_name, discoverable) VALUES (?, ?, ?, ?)",
				userId, guildId, name, discoverable);
	}

	/**
	 * Gives a user a confirmed RSVP that takes a spot.
	 *
	 * @param event  the event
	 * @param userId the user
	 */
	private void confirm(SeededEvent event, UUID userId) {
		jdbcTemplate.update("INSERT INTO rsvp (event_id, user_id, status) VALUES (?, ?, 'CONFIRMED')", event.id(),
				userId);
		jdbcTemplate.update("UPDATE event SET confirmed_count = confirmed_count + 1 WHERE id = ?", event.id());
	}

	/**
	 * Checks that a viewer's response names no server outside their reasons and
	 * carries none of the host's circle names or IDs.
	 *
	 * @param body     the response body
	 * @param host     the host
	 * @param expected the viewer's reasons
	 */
	private void assertNoLeaks(String body, UUID host, List<String> expected) {
		for (var name : guildNames)
			if (!expected.contains("GUILD:" + name))
				assertThat(body).as("server name").doesNotContain("\"" + name + "\"");
		for (var circle : jdbcTemplate.queryForList("SELECT name, public_id::text AS id FROM circle WHERE owner_id = ?",
				host))
			assertThat(body).as("circle").doesNotContain((String) circle.get("name"), (String) circle.get("id"));
	}

	/**
	 * Returns a viewer's reasons from the detail view.
	 *
	 * @param viewer the viewer
	 * @param event  the event
	 * @return the reasons
	 */
	private List<String> detailReasons(UUID viewer, SeededEvent event) throws Exception {
		var body = mockMvc.perform(get("/api/events/" + event.publicId()).with(login(viewer))).andReturn().getResponse()
				.getContentAsString();
		return reasons(JsonPath.read(body, "$.body.visibleBecause"));
	}

	/**
	 * Returns the first page of a list.
	 *
	 * @param userId the caller
	 * @param scope  the scope
	 * @return the response body
	 */
	private String feed(UUID userId, String scope) throws Exception {
		return mockMvc.perform(get("/api/events").param("scope", scope).param("limit", "50").with(login(userId)))
				.andReturn().getResponse().getContentAsString();
	}

	/**
	 * Writes reasons as {@code GUILD:<name>}, {@code FRIEND} or {@code INVITED},
	 * checking that only a server reason has a name.
	 *
	 * @param reasons the {@code visibleBecause} JSON
	 * @return the reasons, in order
	 */
	private static List<String> reasons(List<Map<String, Object>> reasons) {
		assertThat(reasons).isNotNull();
		return reasons.stream().map(reason -> {
			var type = (String) reason.get("type");
			assertThat(reason).containsOnlyKeys("type", "guildName");
			if (!type.equals("GUILD"))
				assertThat(reason.get("guildName")).as(type).isNull();
			return type.equals("GUILD") ? type + ":" + reason.get("guildName") : type;
		}).toList();
	}
}
