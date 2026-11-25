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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import app.confuzion.backend.TestcontainersConfiguration;
import app.confuzion.backend.controller.EventFixtures.SeededEvent;
import app.confuzion.backend.repository.RsvpRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * The RSVP endpoints end to end, through the security filter chain: the
 * authorization matrix, the sign-up window, the after-start rules, removal,
 * blocks, capacity edits, the visibility an active RSVP grants and My schedule
 * (D-013).
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest(properties = "con-fuzion.rate-limiter.token.capacity=10000")
@Import(TestcontainersConfiguration.class)
class RsvpControllerIntegrationTest {
	/**
	 * Writes edit bodies.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

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
	 * The host.
	 */
	private UUID host;

	/**
	 * Builds the {@link MockMvc} and seeds the host.
	 */
	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
		fixtures = new EventFixtures(jdbcTemplate);
		host = fixtures.user("Host");
	}

	/**
	 * Tests the authorization matrix: who gets what from every RSVP endpoint and
	 * from My schedule. Signed out is a 401 and a write without CSRF a 403. Someone
	 * who can't see the event gets the same 404 everywhere. Only the host and
	 * confirmed attendees get the attendee list, only the host gets the waitlist,
	 * and only the host can remove people.
	 */
	@Test
	void testAuthorizationMatrix() throws Exception {
		var event = event(2);
		var confirmed = guest(event);
		var waitlisted = guest(event);
		var viewer = guest(event);
		var stranger = fixtures.user("Stranger");
		join(confirmed, event, "CONFIRMED");
		join(waitlisted, event, "WAITLISTED");
		var base = "/api/events/" + event.publicId();
		var target = "/rsvps/" + confirmed;

		// Signed out (with a CSRF token, which is checked first), and writes without
		// one.
		for (var request : List.of(post(base + "/rsvp"), delete(base + "/rsvp"), get(base + "/attendees"),
				delete(base + target), get("/api/events?scope=attending")))
			assertThat(status(request.with(csrf()))).isEqualTo(401);
		for (var request : List.of(post(base + "/rsvp"), delete(base + "/rsvp"), delete(base + target)))
			assertThat(status(request.with(login(confirmed)))).isEqualTo(403);

		// Someone who can't see the event: 404 everywhere, nothing written.
		for (var request : List.of(post(base + "/rsvp"), delete(base + "/rsvp"), get(base + "/attendees"),
				delete(base + target)))
			assertThat(status(request.with(login(stranger)).with(csrf()))).isEqualTo(404);
		assertThat(fixtures.rsvpStatus(event.id(), stranger)).isNull();
		assertThat(fixtures.rsvpStatus(event.id(), confirmed)).isEqualTo("CONFIRMED");

		// The attendee list: host and confirmed only, and the waitlist for the host.
		assertThat(status(get(base + "/attendees").with(login(viewer)))).isEqualTo(404);
		assertThat(status(get(base + "/attendees").with(login(waitlisted)))).isEqualTo(404);
		var asConfirmed = content(get(base + "/attendees").with(login(confirmed)), 200);
		assertThat(JsonPath.<List<String>>read(asConfirmed, "$.body.confirmed[*].id")).containsExactly(host.toString(),
				confirmed.toString());
		assertThat(JsonPath.<Object>read(asConfirmed, "$.body.waitlist")).isNull();
		var asHost = content(get(base + "/attendees").with(login(host)), 200);
		assertThat(JsonPath.<List<String>>read(asHost, "$.body.waitlist[*].id")).containsExactly(waitlisted.toString());

		// Removal: only the host. A confirmed attendee who can see the event gets a
		// 404.
		assertThat(status(delete(base + "/rsvps/" + waitlisted).with(login(confirmed)).with(csrf()))).isEqualTo(404);
		assertThat(fixtures.rsvpStatus(event.id(), waitlisted)).isEqualTo("WAITLISTED");

		// My schedule is the caller's own.
		assertThat(attending(stranger)).isEmpty();
		assertThat(attending(waitlisted)).containsExactly(event.publicId().toString());
	}

	/**
	 * Tests that joining twice returns the current status and writes nothing, and
	 * that the host joining gets {@code CONFIRMED}.
	 */
	@Test
	void testJoinIsIdempotent() throws Exception {
		var event = event(2);
		var first = guest(event);
		var second = guest(event);
		join(first, event, "CONFIRMED");
		join(first, event, "CONFIRMED");
		join(second, event, "WAITLISTED");
		var again = content(post(path(event) + "/rsvp").with(login(second)).with(csrf()), 200);
		assertThat(JsonPath.<Integer>read(again, "$.body.position")).isEqualTo(1);
		join(host, event, "CONFIRMED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(2);
		assertThat(
				jdbcTemplate.queryForObject("SELECT count(*) FROM rsvp WHERE event_id = ?", Integer.class, event.id()))
				.isEqualTo(3);
	}

	/**
	 * Tests the sign-up window: before {@code signupOpensAt} is
	 * {@code SIGNUP_NOT_OPEN}, from the start {@code SIGNUP_CLOSED}, and in between
	 * a join works. An active RSVP is still returned after the start.
	 */
	@Test
	void testSignupWindow() throws Exception {
		var notOpen = fixtures.rsvpEvent(host, 5, "now() + interval '1 day'", "now() + interval '1 hour'");
		var open = fixtures.rsvpEvent(host, 5, "now() + interval '1 day'", "now() - interval '1 hour'");
		var started = fixtures.rsvpEvent(host, 5, "now() - interval '1 minute'", "NULL");
		var guest = fixtures.user("Guest");
		for (var event : List.of(notOpen, open, started))
			fixtures.audience(event.id(), "USER", null, null, guest);

		assertConflict(post(path(notOpen) + "/rsvp").with(login(guest)).with(csrf()), "SIGNUP_NOT_OPEN");
		assertConflict(post(path(started) + "/rsvp").with(login(guest)).with(csrf()), "SIGNUP_CLOSED");
		join(guest, open, "CONFIRMED");
		assertThat(fixtures.rsvpStatus(notOpen.id(), guest)).isNull();
		assertThat(fixtures.rsvpStatus(started.id(), guest)).isNull();

		jdbcTemplate.update("UPDATE event SET starts_at = now() - interval '1 minute' WHERE id = ?", open.id());
		join(guest, open, "CONFIRMED");
	}

	/**
	 * Tests the after-start rules: leaving and removal still work, but nobody is
	 * promoted, so spots can be left while people wait. A capacity raise promotes
	 * nobody either. The same holds for a cancelled event.
	 */
	@Test
	void testNobodyIsPromotedAfterTheStart() throws Exception {
		var event = event(3);
		var first = guest(event);
		var second = guest(event);
		var waiting = guest(event);
		join(first, event, "CONFIRMED");
		join(second, event, "CONFIRMED");
		join(waiting, event, "WAITLISTED");
		jdbcTemplate.update("UPDATE event SET starts_at = now() - interval '1 minute' WHERE id = ?", event.id());

		assertThat(status(delete(path(event) + "/rsvp").with(login(first)).with(csrf()))).isEqualTo(204);
		assertThat(status(delete(path(event) + "/rsvps/" + second).with(login(host)).with(csrf()))).isEqualTo(204);
		assertThat(fixtures.rsvpStatus(event.id(), waiting)).isEqualTo("WAITLISTED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(1);
		assertThat(status(edit(event, 5))).isEqualTo(200);
		assertThat(fixtures.rsvpStatus(event.id(), waiting)).isEqualTo("WAITLISTED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(1);

		var cancelled = event(2);
		var holder = guest(cancelled);
		var next = guest(cancelled);
		join(holder, cancelled, "CONFIRMED");
		join(next, cancelled, "WAITLISTED");
		fixtures.cancel(cancelled.id());
		assertThat(status(delete(path(cancelled) + "/rsvp").with(login(holder)).with(csrf()))).isEqualTo(204);
		assertThat(fixtures.rsvpStatus(cancelled.id(), next)).isEqualTo("WAITLISTED");
		assertThat(fixtures.confirmedCount(cancelled.id())).isEqualTo(1);
	}

	/**
	 * Tests removal: the host's removal frees the spot for the next person in line,
	 * the removed person can't rejoin and reads {@code REMOVED} while the audience
	 * still reaches them, removing someone not signed up writes nothing, and the
	 * host can neither leave nor remove themself.
	 */
	@Test
	void testRemovalAndNoRejoin() throws Exception {
		var event = event(2);
		var removed = guest(event);
		var next = guest(event);
		var bystander = guest(event);
		join(removed, event, "CONFIRMED");
		join(next, event, "WAITLISTED");

		assertThat(status(delete(path(event) + "/rsvps/" + removed).with(login(host)).with(csrf()))).isEqualTo(204);
		assertThat(fixtures.rsvpStatus(event.id(), removed)).isEqualTo("REMOVED");
		assertThat(fixtures.rsvpStatus(event.id(), next)).isEqualTo("CONFIRMED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(2);

		assertConflict(post(path(event) + "/rsvp").with(login(removed)).with(csrf()), "RSVP_REMOVED");
		assertThat(JsonPath.<String>read(content(get(path(event)).with(login(removed)), 200), "$.body.myStatus"))
				.isEqualTo("REMOVED");
		assertThat(status(delete(path(event) + "/rsvps/" + removed).with(login(host)).with(csrf()))).isEqualTo(204);

		assertThat(status(delete(path(event) + "/rsvps/" + bystander).with(login(host)).with(csrf()))).isEqualTo(204);
		assertThat(fixtures.rsvpStatus(event.id(), bystander)).isNull();
		assertThat(status(delete(path(event) + "/rsvps/" + UUID.randomUUID()).with(login(host)).with(csrf())))
				.isEqualTo(204);

		assertConflict(delete(path(event) + "/rsvp").with(login(host)).with(csrf()), "HOST_CANNOT_LEAVE");
		assertConflict(delete(path(event) + "/rsvps/" + host).with(login(host)).with(csrf()), "HOST_CANNOT_LEAVE");

		// Someone who left may join again, at the back of the queue.
		var leaver = guest(event);
		join(leaver, event, "WAITLISTED");
		assertThat(status(delete(path(event) + "/rsvp").with(login(leaver)).with(csrf()))).isEqualTo(204);
		assertThat(status(delete(path(event) + "/rsvp").with(login(leaver)).with(csrf()))).isEqualTo(204);
		join(leaver, event, "WAITLISTED");
	}

	/**
	 * Tests that a block between the host and an attendee ends the attendee's RSVP
	 * as {@code LEFT} and promotes the next person, whichever of them blocks, and
	 * that after an unblock they may join again.
	 */
	@Test
	void testBlockEndsTheRsvp() throws Exception {
		var event = event(2);
		var blocked = guest(event);
		var promoted = guest(event);
		var next = guest(event);
		join(blocked, event, "CONFIRMED");
		join(promoted, event, "WAITLISTED");
		join(next, event, "WAITLISTED");

		assertThat(status(put("/api/blocks/" + blocked).with(login(host)).with(csrf()))).isEqualTo(204);
		assertThat(fixtures.rsvpStatus(event.id(), blocked)).isEqualTo("LEFT");
		assertThat(fixtures.rsvpStatus(event.id(), promoted)).isEqualTo("CONFIRMED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(2);
		assertThat(status(get(path(event)).with(login(blocked)))).isEqualTo(404);

		assertThat(status(put("/api/blocks/" + host).with(login(next)).with(csrf()))).isEqualTo(204);
		assertThat(fixtures.rsvpStatus(event.id(), next)).isEqualTo("LEFT");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(2);

		assertThat(status(delete("/api/blocks/" + blocked).with(login(host)).with(csrf()))).isEqualTo(204);
		fixtures.audience(event.id(), "USER", null, null, blocked);
		join(blocked, event, "WAITLISTED");
	}

	/**
	 * Tests the visibility an active RSVP grants (D-013 rule 10): confirmed and
	 * waitlisted people keep seeing the event after the audience stops reaching
	 * them and after it's cancelled, and it stays in their schedule. Someone who
	 * left is an ordinary viewer again. Only the host and confirmed people get a
	 * {@code CONFIRMED} location.
	 */
	@Test
	void testActiveRsvpGrantsVisibility() throws Exception {
		var event = event(2);
		var confirmed = guest(event);
		var waitlisted = guest(event);
		var leaver = guest(event);
		var viewer = guest(event);
		join(confirmed, event, "CONFIRMED");
		join(waitlisted, event, "WAITLISTED");
		join(leaver, event, "WAITLISTED");
		assertThat(status(delete(path(event) + "/rsvp").with(login(leaver)).with(csrf()))).isEqualTo(204);

		assertThat(location(confirmed, event)).isEqualTo("Hall A");
		assertThat(location(waitlisted, event)).isNull();
		assertThat(location(viewer, event)).isNull();
		assertThat(location(host, event)).isEqualTo("Hall A");

		jdbcTemplate.update("DELETE FROM event_audience WHERE event_id = ?", event.id());
		fixtures.cancel(event.id());
		for (var user : List.of(confirmed, waitlisted))
			assertThat(JsonPath.<String>read(content(get(path(event)).with(login(user)), 200), "$.body.status"))
					.isEqualTo("CANCELLED");
		for (var user : List.of(leaver, viewer))
			assertThat(status(get(path(event)).with(login(user)))).isEqualTo(404);
		assertThat(attending(confirmed)).containsExactly(event.publicId().toString());
		assertThat(attending(host)).containsExactly(event.publicId().toString());

		// Leaving a cancelled event takes it off the schedule, and out of sight.
		assertThat(status(delete(path(event) + "/rsvp").with(login(waitlisted)).with(csrf()))).isEqualTo(204);
		assertThat(attending(waitlisted)).isEmpty();
		assertThat(status(get(path(event)).with(login(waitlisted)))).isEqualTo(404);
	}

	/**
	 * Tests that the feed shows where the viewer stands and lists an event an
	 * active RSVP keeps visible, as the detail view does.
	 */
	@Test
	void testFeedCarriesMyStatus() throws Exception {
		var event = event(2);
		var guest = guest(event);
		join(guest, event, "CONFIRMED");
		jdbcTemplate.update("DELETE FROM event_audience WHERE event_id = ?", event.id());
		var feed = content(get("/api/events").param("limit", "50").with(login(guest)), 200);
		assertThat(JsonPath.<List<String>>read(feed, "$.body.events[?(@.id == '" + event.publicId() + "')].myStatus"))
				.containsExactly("CONFIRMED");
	}

	/**
	 * Tests capacity edits over HTTP: a cut below the confirmed count is a 409
	 * {@code CAPACITY_BELOW_CONFIRMED} that saves nothing, and making the event
	 * unlimited confirms everyone waiting, in queue order, at once.
	 */
	@Test
	void testCapacityEdits() throws Exception {
		var event = event(3);
		var first = guest(event);
		var second = guest(event);
		var third = guest(event);
		var fourth = guest(event);
		join(first, event, "CONFIRMED");
		join(second, event, "CONFIRMED");
		join(third, event, "WAITLISTED");
		join(fourth, event, "WAITLISTED");

		assertConflict(edit(event, 2), "CAPACITY_BELOW_CONFIRMED");
		assertThat(jdbcTemplate.queryForObject("SELECT capacity FROM event WHERE id = ?", Integer.class, event.id()))
				.isEqualTo(3);

		assertThat(status(edit(event, null))).isEqualTo(200);
		assertThat(fixtures.rsvpStatus(event.id(), third)).isEqualTo("CONFIRMED");
		assertThat(fixtures.rsvpStatus(event.id(), fourth)).isEqualTo("CONFIRMED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(5);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM rsvp WHERE event_id = ? AND status = 'WAITLISTED'",
				Integer.class, event.id())).isZero();
	}

	/**
	 * Builds the host's edit of a seeded event with a new capacity, keeping its
	 * start.
	 *
	 * @param event    the event
	 * @param capacity the new capacity, or {@code null} for unlimited
	 * @return the request
	 */
	private MockHttpServletRequestBuilder edit(SeededEvent event, Integer capacity) {
		var startsAt = jdbcTemplate.queryForObject("SELECT starts_at FROM event WHERE id = ?", Timestamp.class,
				event.id());
		var body = new HashMap<String, Object>();
		body.put("title", "Seeded");
		body.put("location", "Hall A");
		body.put("startsAt", startsAt.toInstant().toString());
		body.put("timeZone", "UTC");
		body.put("capacity", capacity);
		body.put("locationVisibility", "CONFIRMED");
		body.put("audience", List.of(Map.of("type", "FRIENDS")));
		return patch(path(event)).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body))
				.with(login(host)).with(csrf());
	}

	/**
	 * Returns the public IDs in a user's schedule.
	 *
	 * @param userId the user
	 * @return the event IDs
	 */
	private List<String> attending(UUID userId) throws Exception {
		return JsonPath.read(
				content(get("/api/events").param("scope", "attending").param("limit", "50").with(login(userId)), 200),
				"$.body.events[*].id");
	}

	/**
	 * Tests that the attendee list leaves out anyone in a block with the caller,
	 * either way, while the host still sees everyone. A block between two attendees
	 * leaves both their RSVPs alone.
	 */
	@Test
	void testAttendeesLeaveOutBlockedPeople() throws Exception {
		var event = event(5);
		var blocker = guest(event);
		var blocked = guest(event);
		join(blocker, event, "CONFIRMED");
		join(blocked, event, "CONFIRMED");
		assertThat(status(put("/api/blocks/" + blocked).with(login(blocker)).with(csrf()))).isEqualTo(204);

		assertThat(attendees(event, blocker)).containsExactly(host.toString(), blocker.toString());
		assertThat(attendees(event, blocked)).containsExactly(host.toString(), blocked.toString());
		assertThat(JsonPath.<List<String>>read(content(get(path(event) + "/attendees").with(login(host)), 200),
				"$.body.confirmed[*].id")).containsExactly(host.toString(), blocker.toString(), blocked.toString());
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(3);
	}

	/**
	 * Returns the confirmed people an attendee sees.
	 *
	 * @param event  the event
	 * @param userId the attendee
	 * @return the confirmed people's IDs
	 */
	private List<String> attendees(SeededEvent event, UUID userId) throws Exception {
		return JsonPath.read(content(get(path(event) + "/attendees").with(login(userId)), 200),
				"$.body.confirmed[*].id");
	}

	/**
	 * Tests the claim's own guards, for what can change between a join's checks and
	 * its claim: a block, or the host removing a double tap's twin. Either way
	 * nothing is written and no spot is taken.
	 */
	@Test
	void testClaimRefusesRemovedAndBlocked() {
		var event = event(5);
		var removed = guest(event);
		var blocked = guest(event);
		jdbcTemplate.update("INSERT INTO rsvp (event_id, user_id, status) VALUES (?, ?, 'REMOVED')", event.id(),
				removed);
		fixtures.block(blocked, host);
		var rsvpRepository = webApplicationContext.getBean(RsvpRepository.class);
		webApplicationContext.getBean(TransactionTemplate.class).executeWithoutResult(tx -> {
			rsvpRepository.lock(event.id());
			assertThat(rsvpRepository.insert(event.id(), host, removed, true)).isEmpty();
			assertThat(rsvpRepository.insert(event.id(), host, blocked, true)).isEmpty();
		});
		assertThat(fixtures.rsvpStatus(event.id(), removed)).isEqualTo("REMOVED");
		assertThat(fixtures.rsvpStatus(event.id(), blocked)).isNull();
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(1);
	}

	/**
	 * Seeds an event, starting tomorrow, with a {@code CONFIRMED}-only location.
	 *
	 * @param capacity the headcount
	 * @return the event
	 */
	private SeededEvent event(int capacity) {
		return fixtures.rsvpEvent(host, capacity, "now() + interval '1 day'", "NULL");
	}

	/**
	 * Seeds a guest the event's audience names.
	 *
	 * @param event the event
	 * @return the guest
	 */
	private UUID guest(SeededEvent event) {
		var guest = fixtures.user("Guest");
		fixtures.audience(event.id(), "USER", null, null, guest);
		return guest;
	}

	/**
	 * Joins an event and checks the status the join returns.
	 *
	 * @param userId the user
	 * @param event  the event
	 * @param status the expected status
	 */
	private void join(UUID userId, SeededEvent event, String status) throws Exception {
		var body = content(post(path(event) + "/rsvp").with(login(userId)).with(csrf()), 200);
		assertThat(JsonPath.<String>read(body, "$.body.status")).isEqualTo(status);
	}

	/**
	 * Returns the location a user sees.
	 *
	 * @param userId the user
	 * @param event  the event
	 * @return the location, or {@code null} when withheld
	 */
	private String location(UUID userId, SeededEvent event) throws Exception {
		return JsonPath.read(content(get(path(event)).with(login(userId)), 200), "$.body.location");
	}

	/**
	 * Checks that a request is a 409 with an errorType.
	 *
	 * @param request   the request
	 * @param errorType the expected errorType
	 */
	private void assertConflict(MockHttpServletRequestBuilder request, String errorType) throws Exception {
		assertThat(JsonPath.<String>read(content(request, 409), "$.body.errorType")).isEqualTo(errorType);
	}

	/**
	 * Returns an event's path.
	 *
	 * @param event the event
	 * @return {@code /api/events/{publicId}}
	 */
	private static String path(SeededEvent event) {
		return "/api/events/" + event.publicId();
	}

	/**
	 * Performs a request and returns its status.
	 *
	 * @param request the request
	 * @return the status
	 */
	private int status(MockHttpServletRequestBuilder request) throws Exception {
		return response(request).getStatus();
	}

	/**
	 * Performs a request, checks its status and returns its body.
	 *
	 * @param request the request
	 * @param status  the expected status
	 * @return the body
	 */
	private String content(MockHttpServletRequestBuilder request, int status) throws Exception {
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
	private MockHttpServletResponse response(MockHttpServletRequestBuilder request) throws Exception {
		return mockMvc.perform(request).andReturn().getResponse();
	}
}
