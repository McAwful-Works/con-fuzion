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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.http.HttpHeaders;
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
import app.confuzion.backend.exception.RateLimitException;
import app.confuzion.backend.repository.EventRepository;
import app.confuzion.backend.repository.RsvpRepository;
import app.confuzion.backend.service.RsvpService;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The RSVP endpoints end to end, through the security filter chain: the
 * authorization matrix, the sign-up window, the after-start rules, removal,
 * blocks, capacity edits, the visibility an active RSVP grants, My schedule,
 * {@code /spots} caching and the join limit (D-013).
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest(properties = { "con-fuzion.rate-limiter.token.capacity=10000",
		"con-fuzion.rate-limiter.join.capacity=10000" })
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
				delete(base + target), get(base + "/spots"), get("/api/events?scope=attending")))
			assertThat(status(request.with(csrf()))).isEqualTo(401);
		for (var request : List.of(post(base + "/rsvp"), delete(base + "/rsvp"), delete(base + target)))
			assertThat(status(request.with(login(confirmed)))).isEqualTo(403);

		// Someone who can't see the event: 404 everywhere, nothing written.
		for (var request : List.of(post(base + "/rsvp"), delete(base + "/rsvp"), get(base + "/attendees"),
				delete(base + target), get(base + "/spots")))
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

		// /spots: anyone who can see the event, with their own status and position.
		var spots = content(get(base + "/spots").with(login(waitlisted)), 200);
		assertThat(JsonPath.<Map<String, Object>>read(spots, "$.body")).containsEntry("capacity", 2)
				.containsEntry("confirmed", 2).containsEntry("waitlisted", 1).containsEntry("spotsLeft", 0)
				.containsEntry("myStatus", "WAITLISTED").containsEntry("myPosition", 1);
		assertThat(JsonPath.<String>read(content(get(base + "/spots").with(login(viewer)), 200), "$.body.myStatus"))
				.isEqualTo("NONE");

		// My schedule is the caller's own, and seeing an event doesn't put it there.
		assertThat(attending(stranger)).isEmpty();
		assertThat(attending(viewer)).isEmpty();
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
		var spots = content(get(path(event) + "/spots").with(login(waiting)), 200);
		assertThat(JsonPath.<Integer>read(spots, "$.body.spotsLeft")).isEqualTo(2);
		assertThat(JsonPath.<Integer>read(spots, "$.body.waitlisted")).isEqualTo(1);
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
	 * Tests removal: the host's removal offers the spot to the next person in line,
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
		assertThat(JsonPath.<Map<String, Object>>read(content(get(path(event) + "/spots").with(login(removed)), 200),
				"$.body")).containsEntry("myStatus", "REMOVED").containsEntry("myPosition", null);
		assertThat(fixtures.rsvpStatus(event.id(), next)).isEqualTo("OFFERED");
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
	 * as {@code LEFT} and offers the spot to the next person, whichever of them
	 * blocks, and that after an unblock they may join again.
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
		assertThat(fixtures.rsvpStatus(event.id(), promoted)).isEqualTo("OFFERED");
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
	 * Tests {@code /spots} caching: a caller-specific {@code ETag} with
	 * {@code Cache-Control: private, no-cache}, a bodiless 304 for a matching
	 * {@code If-None-Match}, and a new tag once the caller's own status changes.
	 */
	@Test
	void testSpotsEtag() throws Exception {
		var event = event(2);
		var guest = guest(event);
		var first = response(get(path(event) + "/spots").with(login(guest)));
		var etag = first.getHeader(HttpHeaders.ETAG);
		assertThat(first.getStatus()).isEqualTo(200);
		assertThat(etag).matches("\"[A-Za-z0-9_-]{22}\"");
		assertThat(first.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache, private");

		var notModified = response(
				get(path(event) + "/spots").header(HttpHeaders.IF_NONE_MATCH, etag).with(login(guest)));
		assertThat(notModified.getStatus()).isEqualTo(304);
		assertThat(notModified.getContentAsByteArray()).isEmpty();
		assertThat(notModified.getHeader(HttpHeaders.ETAG)).isEqualTo(etag);
		assertThat(notModified.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache, private");

		join(guest, event, "CONFIRMED");
		var changed = response(get(path(event) + "/spots").header(HttpHeaders.IF_NONE_MATCH, etag).with(login(guest)));
		assertThat(changed.getStatus()).isEqualTo(200);
		assertThat(changed.getHeader(HttpHeaders.ETAG)).isNotEqualTo(etag);
		assertThat(JsonPath.<String>read(changed.getContentAsString(), "$.body.myStatus")).isEqualTo("CONFIRMED");
	}

	/**
	 * Tests the join limit: about one attempt per second per user, whatever the
	 * outcome of the first. Uses a service with the production limit, sharing this
	 * context's Valkey.
	 */
	@Test
	void testJoinIsRateLimited() {
		var limited = new RsvpService(webApplicationContext.getBean(EventRepository.class),
				webApplicationContext.getBean(RsvpRepository.class),
				webApplicationContext.getBean(TransactionTemplate.class), proxyManager(), 1);
		var event = event(5);
		var guest = guest(event);
		var second = event(5);
		fixtures.audience(second.id(), "USER", null, null, guest);
		limited.join(guest, event.publicId());
		// A repeat is answered from the checks, so it's never limited (rule 5).
		limited.join(guest, event.publicId());
		assertThatThrownBy(() -> limited.join(guest, second.publicId())).isInstanceOf(RateLimitException.class);
		assertThat(fixtures.rsvpStatus(second.id(), guest)).isNull();
		var other = guest(event);
		limited.join(other, event.publicId());
	}

	/**
	 * Returns the context's rate-limit {@link ProxyManager}.
	 *
	 * @return the {@link ProxyManager}
	 */
	@SuppressWarnings("unchecked")
	private ProxyManager<String> proxyManager() {
		return webApplicationContext.getBean(ProxyManager.class);
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
	 * Tests that moving a started event's start back into the future reopens it:
	 * the people left waiting after the start are offered the free spots at once.
	 */
	@Test
	void testMovingTheStartForwardFillsFreeSpots() throws Exception {
		var event = event(2);
		var leaver = guest(event);
		var waiting = guest(event);
		join(leaver, event, "CONFIRMED");
		join(waiting, event, "WAITLISTED");
		jdbcTemplate.update("UPDATE event SET starts_at = now() - interval '1 minute' WHERE id = ?", event.id());
		assertThat(status(delete(path(event) + "/rsvp").with(login(leaver)).with(csrf()))).isEqualTo(204);
		assertThat(fixtures.rsvpStatus(event.id(), waiting)).isEqualTo("WAITLISTED");

		assertThat(status(edit(event, 2, Instant.now().plus(1, ChronoUnit.DAYS)))).isEqualTo(200);
		assertThat(fixtures.rsvpStatus(event.id(), waiting)).isEqualTo("OFFERED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(2);
	}

	/**
	 * Tests My schedule (D-013 rule 12): only upcoming events where the caller is
	 * confirmed or waitlisted, in start order across pages, each with its
	 * {@code myStatus}. An event they left or were removed from, or that has ended,
	 * isn't listed.
	 */
	@Test
	void testMySchedule() throws Exception {
		var guest = fixtures.user("Guest");
		var later = fixtures.rsvpEvent(host, 2, "now() + interval '2 days'", "NULL");
		var sooner = fixtures.rsvpEvent(host, 2, "now() + interval '1 day'", "NULL");
		var left = fixtures.rsvpEvent(host, 5, "now() + interval '3 days'", "NULL");
		var removed = fixtures.rsvpEvent(host, 5, "now() + interval '4 days'", "NULL");
		var ended = fixtures.rsvpEvent(host, 5, "now() - interval '1 day'", "NULL");
		var other = fixtures.user("Other");
		for (var event : List.of(later, sooner, left, removed, ended))
			fixtures.audience(event.id(), "USER", null, null, guest);
		fixtures.audience(sooner.id(), "USER", null, null, other);
		join(other, sooner, "CONFIRMED");
		join(guest, sooner, "WAITLISTED");
		join(guest, later, "CONFIRMED");
		join(guest, left, "CONFIRMED");
		assertThat(status(delete(path(left) + "/rsvp").with(login(guest)).with(csrf()))).isEqualTo(204);
		join(guest, removed, "CONFIRMED");
		assertThat(status(delete(path(removed) + "/rsvps/" + guest).with(login(host)).with(csrf()))).isEqualTo(204);
		jdbcTemplate.update("INSERT INTO rsvp (event_id, user_id, status) VALUES (?, ?, 'CONFIRMED')", ended.id(),
				guest);

		var first = content(get("/api/events").param("scope", "attending").param("limit", "1").with(login(guest)), 200);
		assertThat(JsonPath.<List<String>>read(first, "$.body.events[*].id"))
				.containsExactly(sooner.publicId().toString());
		assertThat(JsonPath.<List<String>>read(first, "$.body.events[*].myStatus")).containsExactly("WAITLISTED");
		var second = content(get("/api/events").param("scope", "attending").param("limit", "1")
				.param("cursor", JsonPath.<String>read(first, "$.body.nextCursor")).with(login(guest)), 200);
		assertThat(JsonPath.<List<String>>read(second, "$.body.events[*].id"))
				.containsExactly(later.publicId().toString());
		assertThat(JsonPath.<List<String>>read(second, "$.body.events[*].myStatus")).containsExactly("CONFIRMED");
		assertThat(JsonPath.<Object>read(second, "$.body.nextCursor")).isNull();
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
		return edit(event, capacity, startsAt.toInstant());
	}

	/**
	 * Builds the host's edit of a seeded event with a new capacity and start.
	 *
	 * @param event    the event
	 * @param capacity the new capacity, or {@code null} for unlimited
	 * @param startsAt the new start
	 * @return the request
	 */
	private MockHttpServletRequestBuilder edit(SeededEvent event, Integer capacity, Instant startsAt) {
		var body = new HashMap<String, Object>();
		body.put("title", "Seeded");
		body.put("location", "Hall A");
		body.put("startsAt", startsAt.toString());
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
