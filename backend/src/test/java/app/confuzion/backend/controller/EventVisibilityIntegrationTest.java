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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import app.confuzion.backend.TestcontainersConfiguration;
import app.confuzion.backend.controller.EventFixtures.SeededEvent;

/**
 * The visibility truth table (D-012): every audience type, with the viewer and
 * (for servers) the host discoverable or not, then each visible case again with
 * a block in each direction and with the event cancelled, plus the host. Each
 * case checks the detail view and the feed, which must agree because both use
 * the one visibility predicate.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest(properties = "con-fuzion.rate-limiter.token.capacity=10000")
@Import(TestcontainersConfiguration.class)
class EventVisibilityIntegrationTest {
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
		 * Adds the case's relationships and audience entries.
		 *
		 * @param fixtures the seeding helpers
		 * @param host     the host
		 * @param viewer   the viewer
		 * @param event    the event
		 */
		void seed(EventFixtures fixtures, UUID host, UUID viewer, SeededEvent event);
	}

	/**
	 * Returns every audience case and whether the viewer should see the event when
	 * nothing else gets in the way.
	 *
	 * @return the name, the case and the expected visibility
	 */
	static List<Arguments> audiences() {
		return List.of(Arguments.of("no entries", (Audience) (f, h, v, e) -> {
		}, false), Arguments.of("USER names the viewer", (Audience) (f, h, v, e) -> {
			f.audience(e.id(), "USER", null, null, v);
		}, true), Arguments.of("USER names someone else", (Audience) (f, h, v, e) -> {
			f.audience(e.id(), "USER", null, null, f.user("Other"));
		}, false), Arguments.of("FRIENDS, viewer is a friend", (Audience) (f, h, v, e) -> {
			f.friends(h, v);
			f.audience(e.id(), "FRIENDS", null, null, null);
		}, true), Arguments.of("FRIENDS, viewer isn't a friend", (Audience) (f, h, v, e) -> {
			f.audience(e.id(), "FRIENDS", null, null, null);
		}, false), Arguments.of("friend, but no FRIENDS entry", (Audience) (f, h, v, e) -> {
			f.friends(h, v);
			f.audience(e.id(), "USER", null, null, f.user("Other"));
		}, false), Arguments.of("CIRCLE, viewer is a member", (Audience) (f, h, v, e) -> {
			f.audience(e.id(), "CIRCLE", null, f.circle(h, v).id(), null);
		}, true), Arguments.of("CIRCLE, viewer isn't a member", (Audience) (f, h, v, e) -> {
			f.audience(e.id(), "CIRCLE", null, f.circle(h, f.user("Other")).id(), null);
		}, false), Arguments.of("CIRCLE owned by someone else", (Audience) (f, h, v, e) -> {
			f.audience(e.id(), "CIRCLE", null, f.circle(f.user("Other"), v).id(), null);
		}, false), guild("both discoverable", true, true, true), guild("viewer only", true, false, false),
				guild("host only", false, true, false), guild("neither", false, false, false),
				Arguments.of("GUILD, viewer not in the server", (Audience) (f, h, v, e) -> {
					var guildId = f.newGuildId();
					f.guild(h, guildId, true);
					f.audience(e.id(), "GUILD", guildId, null, null);
				}, false), Arguments.of("GUILD, host not in the server", (Audience) (f, h, v, e) -> {
					var guildId = f.newGuildId();
					f.guild(v, guildId, true);
					f.audience(e.id(), "GUILD", guildId, null, null);
				}, false));
	}

	/**
	 * Returns a server case.
	 *
	 * @param name               the case name
	 * @param viewerDiscoverable whether the viewer is discoverable there
	 * @param hostDiscoverable   whether the host is discoverable there
	 * @param expected           whether the viewer should see the event
	 * @return the arguments
	 */
	private static Arguments guild(String name, boolean viewerDiscoverable, boolean hostDiscoverable,
			boolean expected) {
		return Arguments.of("GUILD, " + name, (Audience) (f, h, v, e) -> {
			var guildId = f.newGuildId();
			f.guild(v, guildId, viewerDiscoverable);
			f.guild(h, guildId, hostDiscoverable);
			f.audience(e.id(), "GUILD", guildId, null, null);
		}, expected);
	}

	/**
	 * Returns the full table: each audience case as it is, then with the host
	 * blocking the viewer, the viewer blocking the host, and the event cancelled.
	 * Only the first can be visible.
	 *
	 * @return the name, case, change and expected visibility
	 */
	static Stream<Arguments> truthTable() {
		var rows = new ArrayList<Arguments>();
		for (var audience : audiences()) {
			var args = audience.get();
			var name = (String) args[0];
			var expected = (boolean) args[2];
			rows.add(Arguments.of(name, args[1], Change.NONE, expected));
			rows.add(Arguments.of(name + " + host blocks viewer", args[1], Change.HOST_BLOCKS, false));
			rows.add(Arguments.of(name + " + viewer blocks host", args[1], Change.VIEWER_BLOCKS, false));
			rows.add(Arguments.of(name + " + cancelled", args[1], Change.CANCELLED, false));
		}
		return rows.stream();
	}

	/**
	 * What else happens to a case.
	 *
	 * @author mcawful
	 */
	enum Change {
		/**
		 * Nothing.
		 */
		NONE,

		/**
		 * The host blocks the viewer.
		 */
		HOST_BLOCKS,

		/**
		 * The viewer blocks the host.
		 */
		VIEWER_BLOCKS,

		/**
		 * The host cancels the event.
		 */
		CANCELLED
	}

	/**
	 * Tests one row of the truth table against the detail view and the feed.
	 *
	 * @param name     the case name
	 * @param audience the audience case
	 * @param change   what else happens
	 * @param expected whether the viewer should see the event
	 */
	@ParameterizedTest(name = "{0} -> {3}")
	@MethodSource("truthTable")
	void visibility(String name, Audience audience, Change change, boolean expected) throws Exception {
		var host = fixtures.user("Host");
		var viewer = fixtures.user("Viewer");
		var event = fixtures.event(host);
		audience.seed(fixtures, host, viewer, event);
		switch (change) {
		case HOST_BLOCKS -> fixtures.block(host, viewer);
		case VIEWER_BLOCKS -> fixtures.block(viewer, host);
		case CANCELLED -> fixtures.cancel(event.id());
		case NONE -> {
		}
		}

		var status = mockMvc.perform(get("/api/events/" + event.publicId()).with(login(viewer))).andReturn()
				.getResponse().getStatus();
		assertThat(status).as("detail").isEqualTo(expected ? 200 : 404);
		assertThat(feedIds(viewer)).as("feed").isEqualTo(expected ? List.of(event.publicId().toString()) : List.of());

		// The host always sees their own event, whatever the audience, blocks or
		// status.
		assertThat(mockMvc.perform(get("/api/events/" + event.publicId()).with(login(host))).andReturn().getResponse()
				.getStatus()).as("host detail").isEqualTo(200);
		assertThat(feedIds(host)).as("host feed")
				.isEqualTo(change == Change.CANCELLED ? List.of() : List.of(event.publicId().toString()));
	}

	/**
	 * Returns the RSVP rows of the truth table (D-013 rule 10): each RSVP status,
	 * on a published and a cancelled event, with no block and with a block each
	 * way. The event has no audience entries, so only the RSVP can make it visible.
	 *
	 * @return the status, whether the event is cancelled, the block, and the
	 *         expected visibility
	 */
	static Stream<Arguments> rsvpTruthTable() {
		var rows = new ArrayList<Arguments>();
		for (var status : List.of("CONFIRMED", "WAITLISTED", "LEFT", "REMOVED"))
			for (var cancelled : List.of(false, true))
				for (var change : List.of(Change.NONE, Change.HOST_BLOCKS, Change.VIEWER_BLOCKS))
					rows.add(Arguments.of(status, cancelled, change,
							(status.equals("CONFIRMED") || status.equals("WAITLISTED")) && change == Change.NONE));
		return rows.stream();
	}

	/**
	 * Tests one RSVP row: an active RSVP keeps the event visible, cancelled or not
	 * and whatever the audience, unless a block stands between the viewer and the
	 * host. A left or removed RSVP grants nothing. The detail view and the feed
	 * (which lists only published events) agree.
	 *
	 * @param status    the viewer's RSVP status
	 * @param cancelled whether the event is cancelled
	 * @param change    the block, if any
	 * @param expected  whether the viewer should see the event
	 */
	@ParameterizedTest(name = "RSVP {0}, cancelled {1}, {2} -> {3}")
	@MethodSource("rsvpTruthTable")
	void rsvpVisibility(String status, boolean cancelled, Change change, boolean expected) throws Exception {
		var host = fixtures.user("Host");
		var viewer = fixtures.user("Viewer");
		var event = fixtures.event(host);
		jdbcTemplate.update("INSERT INTO rsvp (event_id, user_id, status) VALUES (?, ?, ?)", event.id(), viewer,
				status);
		if (cancelled)
			fixtures.cancel(event.id());
		switch (change) {
		case HOST_BLOCKS -> fixtures.block(host, viewer);
		case VIEWER_BLOCKS -> fixtures.block(viewer, host);
		default -> {
		}
		}

		var detail = mockMvc.perform(get("/api/events/" + event.publicId()).with(login(viewer))).andReturn()
				.getResponse().getStatus();
		assertThat(detail).as("detail").isEqualTo(expected ? 200 : 404);
		assertThat(feedIds(viewer)).as("feed")
				.isEqualTo(expected && !cancelled ? List.of(event.publicId().toString()) : List.of());
	}

	/**
	 * Tests that the host sees their cancelled event in the hosting list, though no
	 * longer in their feed.
	 */
	@Test
	void hostSeesCancelledEventWhenHosting() throws Exception {
		var host = fixtures.user("Host");
		var event = fixtures.event(host);
		fixtures.cancel(event.id());

		var body = mockMvc.perform(get("/api/events").param("scope", "hosting").with(login(host))).andReturn()
				.getResponse().getContentAsString();
		assertThat(JsonPath.<List<String>>read(body, "$.body.events[*].id"))
				.containsExactly(event.publicId().toString());
		assertThat(JsonPath.<List<String>>read(body, "$.body.events[*].status")).containsExactly("CANCELLED");
	}

	/**
	 * Returns the IDs of every event in a user's feed.
	 *
	 * @param userId the user
	 * @return the event IDs, in feed order
	 */
	private List<String> feedIds(UUID userId) throws Exception {
		var body = mockMvc.perform(get("/api/events").param("limit", "50").with(login(userId))).andReturn()
				.getResponse().getContentAsString();
		return JsonPath.read(body, "$.body.events[*].id");
	}
}
