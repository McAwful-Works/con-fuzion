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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import app.confuzion.backend.TestcontainersConfiguration;
import app.confuzion.backend.exception.LimitExceededException;
import app.confuzion.backend.model.record.EventRequest;
import app.confuzion.backend.model.record.EventResponse.AudienceType;
import app.confuzion.backend.model.record.EventResponse.LocationVisibility;
import app.confuzion.backend.service.EventService;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration tests for every {@code /api/events} endpoint, with Spring
 * Security and Postgres: the authorization matrix (anonymous, no CSRF, host,
 * audience member, stranger and blocked person), what a host may target, the
 * 20-a-day cap, location hiding, edits and cancelling, and feed ordering and
 * pagination.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest(properties = "con-fuzion.rate-limiter.token.capacity=10000")
@Import(TestcontainersConfiguration.class)
class EventControllerIntegrationTest {
	/**
	 * Serializes request bodies.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * The {@code FRIENDS} audience entry.
	 */
	private static final Map<String, String> FRIENDS = Map.of("type", "FRIENDS");

	/**
	 * The most events a host may create in any 24 hours.
	 */
	private static final int CAP = 20;

	/**
	 * The {@link WebApplicationContext}.
	 */
	@Autowired
	private WebApplicationContext webApplicationContext;

	/**
	 * The {@link JdbcTemplate}, to seed and inspect rows.
	 */
	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * The {@link EventService}, to race creates against the cap.
	 */
	@Autowired
	private EventService eventService;

	/**
	 * The {@link MockMvc}, with the security filter chain.
	 */
	private MockMvc mockMvc;

	/**
	 * The seeding helpers.
	 */
	private EventFixtures fixtures;

	/**
	 * The host.
	 */
	private UUID host;

	/**
	 * The host's friend, who the {@code FRIENDS} entry reaches.
	 */
	private UUID friend;

	/**
	 * Someone with no connection to the host.
	 */
	private UUID stranger;

	/**
	 * Builds the {@link MockMvc} and seeds the host, a friend and a stranger.
	 */
	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
		fixtures = new EventFixtures(jdbcTemplate);
		host = fixtures.user("Host");
		friend = fixtures.user("Friend");
		stranger = fixtures.user("Stranger");
		fixtures.friends(host, friend);
	}

	/**
	 * Tests that every endpoint is a 401 for an anonymous caller.
	 */
	@Test
	void anonymousIsUnauthorized() throws Exception {
		var event = "/api/events/" + UUID.randomUUID();
		assertThat(status(get("/api/events"))).isEqualTo(401);
		assertThat(status(get(event))).isEqualTo(401);
		assertThat(status(json(post("/api/events"), body(Map.of())).with(csrf()))).isEqualTo(401);
		assertThat(status(json(patch(event), body(Map.of())).with(csrf()))).isEqualTo(401);
		assertThat(status(delete(event).with(csrf()))).isEqualTo(401);
	}

	/**
	 * Tests that every write is a 403 without a CSRF token, and changes nothing.
	 */
	@Test
	void writesNeedCsrf() throws Exception {
		var event = create(host, Map.of());
		assertThat(status(json(post("/api/events"), body(Map.of())).with(login(host)))).isEqualTo(403);
		assertThat(status(json(patch(path(event)), body(Map.of("title", "Changed"))).with(login(host)))).isEqualTo(403);
		assertThat(status(delete(path(event)).with(login(host)))).isEqualTo(403);
		assertThat(hostedCount(host)).isEqualTo(1);
		assertThat(read(get(path(event)), host, "$.body.title")).isEqualTo("Photo walk");
		assertThat(read(get(path(event)), host, "$.body.status")).isEqualTo("PUBLISHED");
	}

	/**
	 * Tests the authorization matrix: the host can read, edit and cancel; someone
	 * in the audience can only read; a stranger, a blocked friend and anyone asking
	 * for an unknown event get the same 404 for everything.
	 */
	@Test
	void authorizationMatrix() throws Exception {
		var blockedFriend = fixtures.user("Blocked");
		fixtures.friends(host, blockedFriend);
		fixtures.block(blockedFriend, host);
		var event = create(host, Map.of());
		var unknown = "/api/events/" + UUID.randomUUID();

		for (var outsider : List.of(stranger, blockedFriend)) {
			assertThat(status(get(path(event)).with(login(outsider)))).isEqualTo(404);
			assertThat(status(edit(path(event), outsider, Map.of("title", "Hijacked")))).isEqualTo(404);
			assertThat(status(delete(path(event)).with(login(outsider)).with(csrf()))).isEqualTo(404);
			assertThat(feedIds(outsider)).doesNotContain(event);
		}
		assertThat(status(get(path(event)).with(login(friend)))).isEqualTo(200);
		assertThat(status(edit(path(event), friend, Map.of("title", "Hijacked")))).isEqualTo(404);
		assertThat(status(delete(path(event)).with(login(friend)).with(csrf()))).isEqualTo(404);
		assertThat(feedIds(friend)).contains(event);
		for (var caller : List.of(friend, stranger, blockedFriend))
			assertThat(JsonPath.<List<String>>read(
					content(get("/api/events").param("scope", "hosting").with(login(caller)), 200),
					"$.body.events[*].id")).isEmpty();
		for (var caller : List.of(host, friend, stranger)) {
			assertThat(status(get(unknown).with(login(caller)))).isEqualTo(404);
			assertThat(status(edit(unknown, caller, Map.of()))).isEqualTo(404);
			assertThat(status(delete(unknown).with(login(caller)).with(csrf()))).isEqualTo(404);
		}
		assertThat(read(get(path(event)), host, "$.body.title")).isEqualTo("Photo walk");
		assertThat(read(get(path(event)), host, "$.body.status")).isEqualTo("PUBLISHED");

		assertThat(status(edit(path(event), host, Map.of("title", "Renamed")))).isEqualTo(200);
		assertThat(status(delete(path(event)).with(login(host)).with(csrf()))).isEqualTo(204);
	}

	/**
	 * Tests that a host can target every kind of usable entry, and gets the
	 * audience back with labels; a blank description is stored as null.
	 */
	@Test
	void createWithEveryUsableEntry() throws Exception {
		var guildId = fixtures.newGuildId();
		fixtures.guild(host, guildId, true);
		var serverMate = fixtures.user("Server Mate");
		fixtures.guild(serverMate, guildId, true);
		var circle = fixtures.circle(host, stranger);

		var response = content(json(post("/api/events"),
				body(Map.of("description", "  \n ", "audience", List.of(guildEntry(guildId), FRIENDS,
						circleEntry(circle.publicId()), userEntry(friend), userEntry(serverMate)))))
				.with(login(host)).with(csrf()), 201);

		assertThat(JsonPath.<String>read(response, "$.responseType")).isEqualTo("EVENT");
		assertThat(JsonPath.<Boolean>read(response, "$.body.isHost")).isTrue();
		assertThat(JsonPath.<Object>read(response, "$.body.description")).isNull();
		assertThat(JsonPath.<String>read(response, "$.body.host.displayName")).isEqualTo("Host");
		assertThat(JsonPath.<List<String>>read(response, "$.body.audience[*].type")).containsExactly("GUILD", "FRIENDS",
				"CIRCLE", "USER", "USER");
		assertThat(JsonPath.<List<String>>read(response, "$.body.audience[*].label")).containsExactly("Con", null,
				jdbcTemplate.queryForObject("SELECT name FROM circle WHERE id = ?", String.class, circle.id()),
				"Friend", "Server Mate");
		assertThat(JsonPath.<String>read(response, "$.body.audience[0].guildId")).isEqualTo(Long.toString(guildId));
		assertThat(JsonPath.<String>read(response, "$.body.audience[2].circleId"))
				.isEqualTo(circle.publicId().toString());
		assertThat(jdbcTemplate.queryForObject("SELECT description FROM event WHERE host_id = ?", String.class, host))
				.isNull();
		// Everyone the entries reach sees it.
		var event = JsonPath.<String>read(response, "$.body.id");
		for (var viewer : List.of(serverMate, friend, stranger))
			assertThat(feedIds(viewer)).contains(event);
	}

	/**
	 * Tests that a new event's host holds its first spot (D-013): a confirmed RSVP
	 * and a confirmed count of 1. The host's view says CONFIRMED, and anyone else's
	 * says NONE.
	 */
	@Test
	void createGivesTheHostTheFirstSpot() throws Exception {
		var response = content(json(post("/api/events"), body(Map.of("capacity", 2))).with(login(host)).with(csrf()),
				201);
		var event = JsonPath.<String>read(response, "$.body.id");

		assertThat(JsonPath.<String>read(response, "$.body.myStatus")).isEqualTo("CONFIRMED");
		assertThat(jdbcTemplate.queryForMap("SELECT e.confirmed_count, r.user_id, r.status FROM event e "
				+ "JOIN rsvp r ON r.event_id = e.id WHERE e.public_id = ?", UUID.fromString(event)))
				.containsEntry("confirmed_count", 1).containsEntry("user_id", host)
				.containsEntry("status", "CONFIRMED");
		assertThat(JsonPath.<String>read(content(get(path(event)).with(login(friend)), 200), "$.body.myStatus"))
				.isEqualTo("NONE");
	}

	/**
	 * Tests that every entry a host can't use is the same 404, with nothing saved.
	 */
	@Test
	void createRejectsUnusableEntries() throws Exception {
		var hiddenGuild = fixtures.newGuildId();
		fixtures.guild(host, hiddenGuild, false);
		var sharedGuild = fixtures.newGuildId();
		fixtures.guild(host, sharedGuild, true);
		var undiscoverableMate = fixtures.user("Undiscoverable");
		fixtures.guild(undiscoverableMate, sharedGuild, false);
		var blocker = fixtures.user("Blocker");
		fixtures.friends(host, blocker);
		fixtures.block(blocker, host);
		var blocked = fixtures.user("Blocked");
		fixtures.friends(host, blocked);
		fixtures.block(host, blocked);

		var unusable = List.of(guildEntry(hiddenGuild), guildEntry(fixtures.newGuildId()),
				circleEntry(fixtures.circle(stranger, friend).publicId()), circleEntry(UUID.randomUUID()),
				userEntry(stranger), userEntry(host), userEntry(blocker), userEntry(blocked),
				userEntry(undiscoverableMate), userEntry(UUID.randomUUID()));
		for (var entry : unusable)
			assertThat(status(json(post("/api/events"), body(Map.of("audience", List.of(FRIENDS, entry))))
					.with(login(host)).with(csrf()))).as(entry.toString()).isEqualTo(404);
		assertThat(hostedCount(host)).isZero();
	}

	/**
	 * Tests that a server ID too big for {@code bigint} is a 400.
	 */
	@Test
	void overflowingGuildIdIsBadRequest() throws Exception {
		assertThat(
				status(json(post("/api/events"), body(Map.of("audience", List.of(guildEntry("9223372036854775808")))))
						.with(login(host)).with(csrf())))
				.isEqualTo(400);
	}

	/**
	 * Tests that a {@code CONFIRMED} location is hidden from everyone but the host,
	 * that only the host gets the audience, and that a {@code VIEWERS} location is
	 * shown to everyone who can see the event.
	 */
	@Test
	void locationAndAudienceAreHostOnly() throws Exception {
		var confirmed = create(host, Map.of("locationVisibility", "CONFIRMED"));
		var viewers = create(host, Map.of("locationVisibility", "VIEWERS"));

		var asFriend = content(get(path(confirmed)).with(login(friend)), 200);
		assertThat(JsonPath.<Object>read(asFriend, "$.body.location")).isNull();
		assertThat(JsonPath.<Boolean>read(asFriend, "$.body.locationHidden")).isTrue();
		assertThat(JsonPath.<Object>read(asFriend, "$.body.audience")).isNull();
		assertThat(JsonPath.<Boolean>read(asFriend, "$.body.isHost")).isFalse();
		var asHost = content(get(path(confirmed)).with(login(host)), 200);
		assertThat(JsonPath.<String>read(asHost, "$.body.location")).isEqualTo("Hall B");
		assertThat(JsonPath.<Boolean>read(asHost, "$.body.locationHidden")).isFalse();
		assertThat(JsonPath.<List<String>>read(asHost, "$.body.audience[*].type")).containsExactly("FRIENDS");

		var feed = content(get("/api/events").with(login(friend)), 200);
		assertThat(JsonPath.<List<Object>>read(feed, "$.body.events[?(@.id == '" + confirmed + "')].location"))
				.containsExactly((Object) null);
		assertThat(JsonPath.<List<Object>>read(feed, "$.body.events[*].audience")).containsOnlyNulls();
		assertThat(read(get(path(viewers)), friend, "$.body.location")).isEqualTo("Hall B");
	}

	/**
	 * Tests the cap: 20 events in the last 24 hours, cancelled ones included, make
	 * the next create a 429 {@code EVENT_LIMIT}; older events don't count.
	 */
	@Test
	void capCountsTheLastDay() throws Exception {
		for (var i = 0; i < CAP; i++)
			fixtures.event(host);
		fixtures.cancel(fixtures.event(host).id());
		jdbcTemplate.update("UPDATE event SET created_at = now() - interval '25 hours' WHERE id IN "
				+ "(SELECT id FROM event WHERE host_id = ? ORDER BY id LIMIT 1)", host);

		var response = content(json(post("/api/events"), body(Map.of())).with(login(host)).with(csrf()), 429);
		assertThat(JsonPath.<String>read(response, "$.body.errorType")).isEqualTo("EVENT_LIMIT");
		assertThat(hostedCount(host)).isEqualTo(CAP + 1);

		jdbcTemplate.update("UPDATE event SET created_at = now() - interval '25 hours' WHERE id IN "
				+ "(SELECT id FROM event WHERE host_id = ? ORDER BY id LIMIT 2)", host);
		create(host, Map.of());
	}

	/**
	 * Tests that concurrent creates can't overshoot the cap.
	 */
	@Test
	void capHoldsUnderConcurrentCreates() throws Exception {
		for (var i = 0; i < CAP - 2; i++)
			fixtures.event(host);
		var request = new EventRequest("Race", null, "Hall B", null, Instant.now().plus(1, ChronoUnit.DAYS), null,
				"UTC", null, null, null, LocationVisibility.VIEWERS,
				List.of(new EventRequest.AudienceEntry(AudienceType.FRIENDS, null, null, null)));
		var tasks = new ArrayList<Callable<Boolean>>();
		for (var i = 0; i < 6; i++)
			tasks.add(() -> {
				try {
					eventService.createEvent(host, request);
					return true;
				} catch (LimitExceededException e) {
					return false;
				}
			});
		try (var executor = Executors.newFixedThreadPool(tasks.size())) {
			var created = 0;
			for (var future : executor.invokeAll(tasks))
				created += future.get() ? 1 : 0;
			assertThat(created).isEqualTo(2);
		}
		assertThat(hostedCount(host)).isEqualTo(CAP);
	}

	/**
	 * Tests that an edit replaces every field and the audience: removed entries go
	 * and new ones are added.
	 */
	@Test
	void editReplacesEverything() throws Exception {
		var guildId = fixtures.newGuildId();
		fixtures.guild(host, guildId, true);
		var event = create(host, Map.of("tag", "Panel", "audience", List.of(FRIENDS, userEntry(friend))));

		var response = content(edit(path(event), host, Map.of("title", "Renamed", "description", "Bring water",
				"location", "Hall C", "audience", List.of(userEntry(friend), guildEntry(guildId)))), 200);
		assertThat(JsonPath.<String>read(response, "$.body.title")).isEqualTo("Renamed");
		assertThat(JsonPath.<String>read(response, "$.body.description")).isEqualTo("Bring water");
		assertThat(JsonPath.<String>read(response, "$.body.location")).isEqualTo("Hall C");
		assertThat(JsonPath.<Object>read(response, "$.body.tag")).isNull();
		assertThat(JsonPath.<List<String>>read(response, "$.body.audience[*].type")).containsExactly("USER", "GUILD");
		assertThat(read(get(path(event)), host, "$.body.title")).isEqualTo("Renamed");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM event_audience a JOIN event e ON e.id = a.event_id WHERE e.public_id = ?",
				Integer.class, UUID.fromString(event))).isEqualTo(2);
	}

	/**
	 * Tests the offer window (D-014): 30 minutes when a create leaves it out, kept
	 * when an edit leaves it out (so a client that doesn't send it can't reset it),
	 * changed when an edit sends it, and 10 to 1,440 minutes.
	 */
	@Test
	void confirmWindowDefaultsAndSurvivesEditsThatLeaveItOut() throws Exception {
		var event = create(host, Map.of());
		assertThat(read(get(path(event)), host, "$.body.confirmWindowMinutes")).isEqualTo(30);

		assertThat(JsonPath.<Integer>read(content(edit(path(event), host, Map.of("confirmWindowMinutes", 45)), 200),
				"$.body.confirmWindowMinutes")).isEqualTo(45);
		assertThat(JsonPath.<Integer>read(content(edit(path(event), host, Map.of("title", "Renamed")), 200),
				"$.body.confirmWindowMinutes")).isEqualTo(45);
		var cleared = new HashMap<String, Object>();
		cleared.put("confirmWindowMinutes", null);
		assertThat(
				JsonPath.<Integer>read(content(edit(path(event), host, cleared), 200), "$.body.confirmWindowMinutes"))
				.isEqualTo(45);
		assertThat(read(get(path(event)), friend, "$.body.confirmWindowMinutes")).isEqualTo(45);

		var custom = create(host, Map.of("confirmWindowMinutes", 1440));
		assertThat(read(get(path(custom)), host, "$.body.confirmWindowMinutes")).isEqualTo(1440);
		for (var minutes : List.of(9, 1441)) {
			assertThat(status(json(post("/api/events"), body(Map.of("confirmWindowMinutes", minutes))).with(login(host))
					.with(csrf()))).isEqualTo(400);
			assertThat(status(edit(path(event), host, Map.of("confirmWindowMinutes", minutes)))).isEqualTo(400);
		}
		assertThat(read(get(path(event)), host, "$.body.confirmWindowMinutes")).isEqualTo(45);
	}

	/**
	 * Tests that entries already on the event survive an edit without a re-check,
	 * even once they would no longer be allowed, while a new unusable entry makes
	 * the whole edit a 404 with nothing saved.
	 */
	@Test
	void editKeepsExistingEntriesAndChecksNewOnes() throws Exception {
		var guildId = fixtures.newGuildId();
		fixtures.guild(host, guildId, true);
		var serverMate = fixtures.user("Server Mate");
		fixtures.guild(serverMate, guildId, true);
		var audience = List.of(guildEntry(guildId), userEntry(serverMate));
		var event = create(host, Map.of("audience", audience));
		jdbcTemplate.update("UPDATE user_guild SET discoverable = false WHERE user_id = ?", host);

		var response = content(edit(path(event), host, Map.of("title", "Still on", "audience", audience)), 200);
		assertThat(JsonPath.<List<Object>>read(response, "$.body.audience[*].label")).containsExactly(null,
				"Server Mate");

		var newcomer = fixtures.user("Newcomer");
		fixtures.guild(newcomer, guildId, true);
		assertThat(status(edit(path(event), host,
				Map.of("title", "Sneaky", "audience", List.of(guildEntry(guildId), userEntry(newcomer))))))
				.isEqualTo(404);
		assertThat(read(get(path(event)), host, "$.body.title")).isEqualTo("Still on");
		assertThat(read(get(path(event)), host, "$.body.audience.length()")).isEqualTo(2);
	}

	/**
	 * Tests cancelling: the event becomes host-only, can't be edited (409), and
	 * cancelling again is still a 204.
	 */
	@Test
	void cancelMakesEventHostOnly() throws Exception {
		var event = create(host, Map.of());

		assertThat(status(delete(path(event)).with(login(host)).with(csrf()))).isEqualTo(204);
		assertThat(status(delete(path(event)).with(login(host)).with(csrf()))).isEqualTo(204);
		assertThat(status(edit(path(event), host, Map.of()))).isEqualTo(409);
		assertThat(status(get(path(event)).with(login(friend)))).isEqualTo(404);
		assertThat(read(get(path(event)), host, "$.body.status")).isEqualTo("CANCELLED");
		assertThat(feedIds(friend)).doesNotContain(event);
	}

	/**
	 * Tests edits around the start: an event that has ended can't be edited (409);
	 * one that is under way may keep its start but can't move it into the past
	 * (400).
	 */
	@Test
	void editRespectsTheClock() throws Exception {
		var ended = fixtures.event(host, "now() - interval '5 hours'", "NULL");
		fixtures.audience(ended.id(), "FRIENDS", null, null, null);
		assertThat(status(edit(path(ended.publicId().toString()), host, Map.of()))).isEqualTo(409);

		var ongoing = fixtures.event(host, "now() - interval '1 hour'", "NULL");
		fixtures.audience(ongoing.id(), "FRIENDS", null, null, null);
		var startsAt = jdbcTemplate
				.queryForObject("SELECT starts_at FROM event WHERE id = ?", OffsetDateTime.class, ongoing.id())
				.toInstant();
		assertThat(status(edit(path(ongoing.publicId().toString()), host,
				Map.of("title", "Under way", "startsAt", startsAt.toString())))).isEqualTo(200);
		assertThat(status(edit(path(ongoing.publicId().toString()), host,
				Map.of("startsAt", startsAt.minus(1, ChronoUnit.MINUTES).toString())))).isEqualTo(400);
		assertThat(read(get(path(ongoing.publicId().toString())), host, "$.body.title")).isEqualTo("Under way");
	}

	/**
	 * Tests feed ordering and pagination: upcoming visible events only (an event
	 * under way included, ended and cancelled ones not), sorted by start then ID,
	 * split into pages that join up without gaps or repeats. The hosting list adds
	 * cancelled events, and a cursor only works for the scope that issued it.
	 */
	@Test
	void feedOrderAndPages() throws Exception {
		var tomorrowAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		var tie = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		var underWay = seeded("now() - interval '1 hour'", "NULL");
		var tomorrow = seeded("TIMESTAMPTZ '" + tomorrowAt + "'", "NULL");
		var tieA = seeded("TIMESTAMPTZ '" + tie + "'", "NULL");
		var tieB = seeded("TIMESTAMPTZ '" + tie + "'", "NULL");
		var later = seeded("now() + interval '3 days'", "now() + interval '4 days'");
		seeded("now() - interval '5 hours'", "NULL");
		seeded("now() - interval '2 days'", "now() - interval '1 day'");
		var cancelled = fixtures.event(host, "TIMESTAMPTZ '" + tomorrowAt.plusSeconds(60) + "'", "NULL");
		fixtures.audience(cancelled.id(), "FRIENDS", null, null, null);
		fixtures.cancel(cancelled.id());
		fixtures.event(stranger);

		var ties = new ArrayList<>(List.of(tieA, tieB));
		ties.sort(Comparator.naturalOrder());
		var expected = new ArrayList<>(List.of(underWay, tomorrow));
		expected.addAll(ties);
		expected.add(later);

		var pages = new ArrayList<List<String>>();
		var ids = new ArrayList<String>();
		String cursor = null;
		do {
			var request = get("/api/events").param("limit", "2").with(login(friend));
			if (cursor != null)
				request.param("cursor", cursor);
			var page = content(request, 200);
			pages.add(JsonPath.read(page, "$.body.events[*].id"));
			ids.addAll(pages.getLast());
			cursor = JsonPath.read(page, "$.body.nextCursor");
		} while (cursor != null);
		assertThat(ids).containsExactlyElementsOf(expected);
		assertThat(pages).extracting(List::size).containsExactly(2, 2, 1);

		var hosting = content(get("/api/events").param("scope", "hosting").with(login(host)), 200);
		// The cancelled event starts a minute after "tomorrow".
		var withCancelled = new ArrayList<>(expected);
		withCancelled.add(2, cancelled.publicId().toString());
		assertThat(JsonPath.<List<String>>read(hosting, "$.body.events[*].id"))
				.containsExactlyElementsOf(withCancelled);
		assertThat(JsonPath.<String>read(hosting, "$.body.events[2].status")).isEqualTo("CANCELLED");

		var feedCursor = JsonPath.<String>read(content(get("/api/events").param("limit", "1").with(login(friend)), 200),
				"$.body.nextCursor");
		assertThat(status(get("/api/events").param("scope", "hosting").param("cursor", feedCursor).with(login(host))))
				.isEqualTo(400);
		for (var bad : List.of("abc", "ZmVlZCxub3QtYS10aW1lLHgK", cursor("feed", "+100000-01-01T00:00:00Z")))
			assertThat(status(get("/api/events").param("cursor", bad).with(login(friend)))).as(bad).isEqualTo(400);
	}

	/**
	 * Seeds a published event by the host for their friends.
	 *
	 * @param startsAt the start, as a SQL expression
	 * @param endsAt   the end, as a SQL expression
	 * @return the event's public ID
	 */
	private String seeded(String startsAt, String endsAt) {
		var event = fixtures.event(host, startsAt, endsAt);
		fixtures.audience(event.id(), "FRIENDS", null, null, null);
		return event.publicId().toString();
	}

	/**
	 * Builds a cursor the way the service does.
	 *
	 * @param scope    the scope
	 * @param startsAt the start
	 * @return the cursor
	 */
	private static String cursor(String scope, String startsAt) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString((scope + "," + startsAt + "," + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Creates an event through the API and returns its ID.
	 *
	 * @param hostId  the host
	 * @param changes changes to the default body
	 * @return the event's public ID
	 */
	private String create(UUID hostId, Map<String, Object> changes) throws Exception {
		return JsonPath.read(content(json(post("/api/events"), body(changes)).with(login(hostId)).with(csrf()), 201),
				"$.body.id");
	}

	/**
	 * Builds an edit request.
	 *
	 * @param path    the event's path
	 * @param caller  the caller
	 * @param changes changes to the default body
	 * @return the request
	 */
	private static MockHttpServletRequestBuilder edit(String path, UUID caller, Map<String, Object> changes) {
		return json(patch(path), body(changes)).with(login(caller)).with(csrf());
	}

	/**
	 * Returns an event's path.
	 *
	 * @param eventId the event's public ID
	 * @return the path
	 */
	private static String path(String eventId) {
		return "/api/events/" + eventId;
	}

	/**
	 * Returns the IDs in a user's first feed page of 50.
	 *
	 * @param userId the user
	 * @return the event IDs
	 */
	private List<String> feedIds(UUID userId) throws Exception {
		return JsonPath.read(content(get("/api/events").param("limit", "50").with(login(userId)), 200),
				"$.body.events[*].id");
	}

	/**
	 * Counts the events a user hosts.
	 *
	 * @param userId the user
	 * @return the count
	 */
	private int hostedCount(UUID userId) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM event WHERE host_id = ?", Integer.class, userId);
	}

	/**
	 * Performs a request as a user and reads one JSON path from the response.
	 *
	 * @param request the request
	 * @param caller  the caller
	 * @param path    the JSON path
	 * @return the value
	 */
	private Object read(MockHttpServletRequestBuilder request, UUID caller, String path) throws Exception {
		return JsonPath.read(content(request.with(login(caller)), 200), path);
	}

	/**
	 * Performs a request and returns its status.
	 *
	 * @param request the request
	 * @return the status
	 */
	private int status(RequestBuilder request) throws Exception {
		return response(request).getStatus();
	}

	/**
	 * Performs a request, checks its status and returns its body.
	 *
	 * @param request the request
	 * @param status  the expected status
	 * @return the body
	 */
	private String content(RequestBuilder request, int status) throws Exception {
		var response = response(request);
		assertThat(response.getStatus()).isEqualTo(status);
		return response.getContentAsString();
	}

	/**
	 * Performs a request.
	 *
	 * @param request the request
	 * @return the response
	 */
	private MockHttpServletResponse response(RequestBuilder request) throws Exception {
		return mockMvc.perform(request).andReturn().getResponse();
	}

	/**
	 * Builds a valid event body, for the host's friends, with some fields changed.
	 *
	 * @param changes the fields to set
	 * @return the JSON body
	 */
	private static String body(Map<String, Object> changes) {
		var body = new LinkedHashMap<String, Object>();
		body.put("title", "Photo walk");
		body.put("location", "Hall B");
		body.put("startsAt", Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString());
		body.put("timeZone", "America/Chicago");
		body.put("locationVisibility", "VIEWERS");
		body.put("audience", List.of(FRIENDS));
		body.putAll(changes);
		return JSON.writeValueAsString(body);
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
	 * A server entry.
	 *
	 * @param guildId the server ID
	 * @return the entry
	 */
	private static Map<String, String> guildEntry(Object guildId) {
		return Map.of("type", "GUILD", "guildId", guildId.toString());
	}

	/**
	 * A circle entry.
	 *
	 * @param circleId the circle's public ID
	 * @return the entry
	 */
	private static Map<String, String> circleEntry(UUID circleId) {
		return Map.of("type", "CIRCLE", "circleId", circleId.toString());
	}

	/**
	 * A person entry.
	 *
	 * @param userId the user ID
	 * @return the entry
	 */
	private static Map<String, String> userEntry(UUID userId) {
		return Map.of("type", "USER", "userId", userId.toString());
	}
}
