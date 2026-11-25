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
import static org.assertj.core.api.Assertions.within;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import app.confuzion.backend.TestcontainersConfiguration;
import app.confuzion.backend.controller.EventFixtures.SeededEvent;
import app.confuzion.backend.service.OfferExpiryJob;
import tools.jackson.databind.json.JsonMapper;

/**
 * Offers with a deadline, end to end (D-014): a freed or added spot is offered
 * to the next person in line, or confirms them near the start, with the
 * notification in the same transaction; accepting, declining, the deadline
 * (rule 15) and the expiry job; cancelled events (rule 16), unlimited
 * capacities (rule 17); and the authorization matrix for accept.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest(properties = { "con-fuzion.rate-limiter.token.capacity=10000",
		"con-fuzion.rate-limiter.join.capacity=10000" })
@Import(TestcontainersConfiguration.class)
class RsvpOffersIntegrationTest {
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
	 * The {@link JdbcTemplate}, to seed and inspect rows.
	 */
	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * The {@link OfferExpiryJob}, run by hand rather than waiting for its schedule.
	 */
	@Autowired
	private OfferExpiryJob offerExpiryJob;

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
	 * Tests the authorization matrix for accept: signed out is a 401, without CSRF
	 * a 403, someone who can't see the event a 404, and the host or anyone already
	 * confirmed a 200 that changes nothing. Waitlisted, never joined and removed
	 * are {@code NO_OFFER}. Only the person offered gets the spot, and only once.
	 */
	@Test
	void testAcceptAuthorizationMatrix() throws Exception {
		var event = event(3);
		var confirmed = guest(event);
		var leaver = guest(event);
		var offered = guest(event);
		var waitlisted = guest(event);
		var removed = guest(event);
		var viewer = guest(event);
		var stranger = fixtures.user("Stranger");
		join(confirmed, event, "CONFIRMED");
		join(leaver, event, "CONFIRMED");
		join(offered, event, "WAITLISTED");
		join(waitlisted, event, "WAITLISTED");
		join(removed, event, "WAITLISTED");
		assertThat(status(delete(path(event) + "/rsvps/" + removed).with(login(host)).with(csrf()))).isEqualTo(204);
		leave(leaver, event);
		assertThat(fixtures.rsvpStatus(event.id(), offered)).isEqualTo("OFFERED");
		// The offer holds its spot: a join can't take it.
		join(guest(event), event, "WAITLISTED");
		var accept = path(event) + "/rsvp/accept";

		assertThat(status(post(accept).with(csrf()))).isEqualTo(401);
		assertThat(status(post(accept).with(login(offered)))).isEqualTo(403);
		assertThat(status(post(accept).with(login(stranger)).with(csrf()))).isEqualTo(404);
		for (var userId : List.of(waitlisted, removed, viewer))
			assertConflict(post(accept).with(login(userId)).with(csrf()), "NO_OFFER");
		assertThat(fixtures.rsvpStatus(event.id(), offered)).isEqualTo("OFFERED");

		for (var userId : List.of(host, confirmed, offered, offered))
			assertThat(JsonPath.<Map<String, Object>>read(content(post(accept).with(login(userId)).with(csrf()), 200),
					"$.body")).containsEntry("status", "CONFIRMED").containsEntry("position", null)
					.containsEntry("offerExpiresAt", null);
		assertThat(fixtures.rsvpStatus(event.id(), offered)).isEqualTo("CONFIRMED");
		assertThat(fixtures.rsvpStatus(event.id(), waitlisted)).isEqualTo("WAITLISTED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(3);
		// Accepting sends nothing (D-014 rule 4): the offer's notification is the only
		// one.
		assertThat(notifications(offered)).containsExactly("SPOT_OFFERED");
	}

	/**
	 * Tests an offer from a freed spot: the next person in line holds it until
	 * {@code now + window}, the count stays, they get {@code SPOT_OFFERED} with only
	 * the title and the deadline, and every read shows the offer to them alone
	 * ({@code /spots}, a repeat join, the feed, My schedule). They don't see the
	 * {@code CONFIRMED} location or the attendee list until they accept; the host
	 * sees them in {@code offered}. Declining is leaving, and offers the spot to
	 * the next person.
	 */
	@Test
	void testFreedSpotIsOffered() throws Exception {
		var event = event(2);
		var leaver = guest(event);
		var offered = guest(event);
		var next = guest(event);
		join(leaver, event, "CONFIRMED");
		join(offered, event, "WAITLISTED");
		join(next, event, "WAITLISTED");

		var before = Instant.now();
		leave(leaver, event);

		assertThat(fixtures.rsvpStatus(event.id(), offered)).isEqualTo("OFFERED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(2);
		var deadline = offerExpiresAt(event, offered);
		assertThat(deadline).isCloseTo(before.plus(30, ChronoUnit.MINUTES), within(10, ChronoUnit.SECONDS));
		var spots = JsonPath.<Map<String, Object>>read(content(get(path(event) + "/spots").with(login(offered)), 200),
				"$.body");
		assertThat(spots).containsEntry("myStatus", "OFFERED").containsEntry("myPosition", null)
				.containsEntry("confirmed", 2).containsEntry("spotsLeft", 0);
		assertThat(Instant.parse((String) spots.get("offerExpiresAt"))).isEqualTo(deadline);
		assertThat(JsonPath.<Map<String, Object>>read(content(get(path(event) + "/spots").with(login(next)), 200),
				"$.body")).containsEntry("myStatus", "WAITLISTED").containsEntry("myPosition", 1)
				.containsEntry("offerExpiresAt", null).containsEntry("waitlisted", 1);
		var again = JsonPath.<Map<String, Object>>read(
				content(post(path(event) + "/rsvp").with(login(offered)).with(csrf()), 200), "$.body");
		assertThat(again).containsEntry("status", "OFFERED").containsEntry("position", null);
		assertThat(Instant.parse((String) again.get("offerExpiresAt"))).isEqualTo(deadline);

		assertThat(notifications(offered)).containsExactly("SPOT_OFFERED");
		assertThat(jdbcTemplate.queryForList("SELECT jsonb_object_keys(payload) FROM notification WHERE user_id = ?",
				String.class, offered)).containsExactlyInAnyOrder("eventTitle", "offerExpiresAt");
		assertThat(jdbcTemplate.queryForObject("SELECT payload->>'eventTitle' FROM notification WHERE user_id = ?",
				String.class, offered)).isEqualTo("Seeded");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT CAST(payload->>'offerExpiresAt' AS timestamptz) FROM notification WHERE user_id = ?",
				Timestamp.class, offered).toInstant()).isEqualTo(deadline);
		assertThat(notifications(next)).isEmpty();

		var view = JsonPath.<Map<String, Object>>read(content(get(path(event)).with(login(offered)), 200), "$.body");
		assertThat(view).containsEntry("myStatus", "OFFERED").containsEntry("location", null);
		assertThat(status(get(path(event) + "/attendees").with(login(offered)))).isEqualTo(404);
		assertThat(JsonPath.<List<String>>read(
				content(get("/api/events").param("scope", "attending").with(login(offered)), 200),
				"$.body.events[*].myStatus")).containsExactly("OFFERED");
		var asHost = content(get(path(event) + "/attendees").with(login(host)), 200);
		assertThat(JsonPath.<List<String>>read(asHost, "$.body.offered[*].id")).containsExactly(offered.toString());
		assertThat(JsonPath.<List<String>>read(asHost, "$.body.waitlist[*].id")).containsExactly(next.toString());

		// Declining is leaving: LEFT, and the next person is offered the spot.
		leave(offered, event);
		assertThat(fixtures.rsvpStatus(event.id(), offered)).isEqualTo("LEFT");
		assertThat(fixtures.rsvpStatus(event.id(), next)).isEqualTo("OFFERED");
		assertThat(notifications(next)).containsExactly("SPOT_OFFERED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(2);
		assertConflict(post(path(event) + "/rsvp/accept").with(login(offered)).with(csrf()), "NO_OFFER");

		// Removing an offered person ends it as REMOVED, sends them nothing, and frees
		// the spot: nobody is waiting, so the count goes down.
		assertThat(status(delete(path(event) + "/rsvps/" + next).with(login(host)).with(csrf()))).isEqualTo(204);
		assertThat(fixtures.rsvpStatus(event.id(), next)).isEqualTo("REMOVED");
		assertThat(notifications(next)).containsExactly("SPOT_OFFERED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(1);
	}

	/**
	 * Tests that a spot freed when the event starts within the window confirms the
	 * next person at once, with {@code SPOT_CONFIRMED} and only the title.
	 */
	@Test
	void testNearTheStartConfirmsAtOnce() throws Exception {
		var event = fixtures.rsvpEvent(host, 2, "now() + interval '20 minutes'", "NULL");
		var leaver = guest(event);
		var next = guest(event);
		join(leaver, event, "CONFIRMED");
		join(next, event, "WAITLISTED");

		leave(leaver, event);

		assertThat(fixtures.rsvpStatus(event.id(), next)).isEqualTo("CONFIRMED");
		assertThat(offerExpiresAt(event, next)).isNull();
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(2);
		assertThat(notifications(next)).containsExactly("SPOT_CONFIRMED");
		assertThat(jdbcTemplate.queryForList("SELECT jsonb_object_keys(payload) FROM notification WHERE user_id = ?",
				String.class, next)).containsExactly("eventTitle");
	}

	/**
	 * Tests an offer past its deadline (D-014 rule 15). Before the job runs, reads
	 * already show {@code NONE}, the host's list leaves it out, and accepting is
	 * {@code OFFER_EXPIRED}. The job ends it as {@code EXPIRED} and offers the spot
	 * to the next person; accepting is still {@code OFFER_EXPIRED}, and joining
	 * again goes to the back of the queue. A join before the job runs ends the
	 * offer itself, under the lock, and joins at the back.
	 */
	@Test
	void testDeadline() throws Exception {
		var event = event(2);
		var leaver = guest(event);
		var late = guest(event);
		var second = guest(event);
		var third = guest(event);
		join(leaver, event, "CONFIRMED");
		join(late, event, "WAITLISTED");
		join(second, event, "WAITLISTED");
		join(third, event, "WAITLISTED");
		leave(leaver, event);
		pastDeadline(event, late);

		assertThat(JsonPath.<Map<String, Object>>read(content(get(path(event) + "/spots").with(login(late)), 200),
				"$.body")).containsEntry("myStatus", "NONE").containsEntry("offerExpiresAt", null);
		assertThat(JsonPath.<String>read(content(get(path(event)).with(login(late)), 200), "$.body.myStatus"))
				.isEqualTo("NONE");
		assertThat(JsonPath.<List<String>>read(content(get(path(event) + "/attendees").with(login(host)), 200),
				"$.body.offered")).isEmpty();
		assertConflict(post(path(event) + "/rsvp/accept").with(login(late)).with(csrf()), "OFFER_EXPIRED");
		assertThat(fixtures.rsvpStatus(event.id(), late)).isEqualTo("OFFERED");

		offerExpiryJob.run();

		assertThat(fixtures.rsvpStatus(event.id(), late)).isEqualTo("EXPIRED");
		assertThat(offerExpiresAt(event, late)).isNull();
		assertThat(fixtures.rsvpStatus(event.id(), second)).isEqualTo("OFFERED");
		assertThat(notifications(second)).containsExactly("SPOT_OFFERED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(2);
		assertConflict(post(path(event) + "/rsvp/accept").with(login(late)).with(csrf()), "OFFER_EXPIRED");
		offerExpiryJob.run();
		assertThat(fixtures.rsvpStatus(event.id(), second)).isEqualTo("OFFERED");

		// A join before the job: second's offer ends, third is offered the spot, and
		// second goes to the back, behind the late joiner.
		pastDeadline(event, second);
		var body = content(post(path(event) + "/rsvp").with(login(second)).with(csrf()), 200);
		assertThat(JsonPath.<Map<String, Object>>read(body, "$.body")).containsEntry("status", "WAITLISTED")
				.containsEntry("position", 1);
		assertThat(fixtures.rsvpStatus(event.id(), third)).isEqualTo("OFFERED");
		join(late, event, "WAITLISTED");
		assertThat(JsonPath.<Integer>read(content(get(path(event) + "/spots").with(login(late)), 200),
				"$.body.myPosition")).isEqualTo(2);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM rsvp WHERE event_id = ? AND user_id = ? AND status = 'EXPIRED'", Integer.class,
				event.id(), second)).isEqualTo(1);
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(2);
	}

	/**
	 * Tests cancelled events (D-014 rule 16): an open offer stays until its
	 * deadline and keeps the event visible, accepting is {@code NO_OFFER}, and at
	 * the deadline the job ends it without offering the spot again.
	 */
	@Test
	void testCancelledEventKeepsOffersUntilTheDeadline() throws Exception {
		var event = event(2);
		var leaver = guest(event);
		var offered = guest(event);
		var next = guest(event);
		join(leaver, event, "CONFIRMED");
		join(offered, event, "WAITLISTED");
		join(next, event, "WAITLISTED");
		leave(leaver, event);
		fixtures.cancel(event.id());

		assertThat(JsonPath.<String>read(content(get(path(event)).with(login(offered)), 200), "$.body.myStatus"))
				.isEqualTo("OFFERED");
		assertConflict(post(path(event) + "/rsvp/accept").with(login(offered)).with(csrf()), "NO_OFFER");
		offerExpiryJob.run();
		assertThat(fixtures.rsvpStatus(event.id(), offered)).isEqualTo("OFFERED");

		pastDeadline(event, offered);
		offerExpiryJob.run();
		assertThat(fixtures.rsvpStatus(event.id(), offered)).isEqualTo("EXPIRED");
		assertThat(fixtures.rsvpStatus(event.id(), next)).isEqualTo("WAITLISTED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(1);
		assertThat(status(get(path(event)).with(login(offered)))).isEqualTo(404);
	}

	/**
	 * Tests capacity raises: each new spot is offered to the next person in line
	 * (several offers open at once), and making the capacity unlimited confirms
	 * every open offer and everyone waitlisted, each with {@code SPOT_CONFIRMED}
	 * (D-014 rule 17). An offer past its deadline isn't confirmed.
	 */
	@Test
	void testCapacityRaises() throws Exception {
		var event = event(2);
		var guests = List.of(guest(event), guest(event), guest(event), guest(event), guest(event));
		join(guests.get(0), event, "CONFIRMED");
		for (var guest : guests.subList(1, 5))
			join(guest, event, "WAITLISTED");

		assertThat(status(edit(event, 4))).isEqualTo(200);
		assertThat(guests.stream().map(guest -> fixtures.rsvpStatus(event.id(), guest)))
				.containsExactly("CONFIRMED", "OFFERED", "OFFERED", "WAITLISTED", "WAITLISTED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(4);
		// The spots held by offers count: a cut below them is refused.
		assertConflict(edit(event, 3), "CAPACITY_BELOW_CONFIRMED");

		pastDeadline(event, guests.get(2));
		assertThat(status(edit(event, null))).isEqualTo(200);
		assertThat(guests.stream().map(guest -> fixtures.rsvpStatus(event.id(), guest)))
				.containsExactly("CONFIRMED", "CONFIRMED", "OFFERED", "CONFIRMED", "CONFIRMED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(6);
		assertThat(notifications(guests.get(1))).containsExactly("SPOT_OFFERED", "SPOT_CONFIRMED");
		assertThat(notifications(guests.get(3))).containsExactly("SPOT_CONFIRMED");

		// The job ends the late offer and, nobody waiting, frees its spot.
		offerExpiryJob.run();
		assertThat(fixtures.rsvpStatus(event.id(), guests.get(2))).isEqualTo("EXPIRED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(5);
	}

	/**
	 * Tests that a block between the host and an offered person ends the offer as
	 * {@code LEFT} and offers the spot to the next person (D-014 rule 5).
	 */
	@Test
	void testBlockEndsAnOffer() throws Exception {
		var event = event(2);
		var leaver = guest(event);
		var offered = guest(event);
		var next = guest(event);
		join(leaver, event, "CONFIRMED");
		join(offered, event, "WAITLISTED");
		join(next, event, "WAITLISTED");
		leave(leaver, event);

		assertThat(status(put("/api/blocks/" + offered).with(login(host)).with(csrf()))).isEqualTo(204);

		assertThat(fixtures.rsvpStatus(event.id(), offered)).isEqualTo("LEFT");
		assertThat(fixtures.rsvpStatus(event.id(), next)).isEqualTo("OFFERED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(2);
	}

	/**
	 * Seeds an event, starting tomorrow, with a {@code CONFIRMED}-only location and
	 * the default 30-minute window.
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
	 * Leaves an event, or declines an offer.
	 *
	 * @param userId the user
	 * @param event  the event
	 */
	private void leave(UUID userId, SeededEvent event) throws Exception {
		assertThat(status(delete(path(event) + "/rsvp").with(login(userId)).with(csrf()))).isEqualTo(204);
	}

	/**
	 * Moves a user's open offer past its deadline, without running the job.
	 *
	 * @param event  the event
	 * @param userId the offered user
	 */
	private void pastDeadline(SeededEvent event, UUID userId) {
		assertThat(jdbcTemplate.update("UPDATE rsvp SET offer_expires_at = now() - interval '1 second' "
				+ "WHERE event_id = ? AND user_id = ? AND status = 'OFFERED'", event.id(), userId)).isEqualTo(1);
	}

	/**
	 * Returns the deadline on a user's latest RSVP.
	 *
	 * @param event  the event
	 * @param userId the user
	 * @return the deadline, or {@code null}
	 */
	private Instant offerExpiresAt(SeededEvent event, UUID userId) {
		var deadline = jdbcTemplate.queryForObject(
				"SELECT offer_expires_at FROM rsvp WHERE event_id = ? AND user_id = ? ORDER BY id DESC LIMIT 1",
				Timestamp.class, event.id(), userId);
		return deadline == null ? null : deadline.toInstant();
	}

	/**
	 * Returns the types of a user's notifications, oldest first.
	 *
	 * @param userId the user
	 * @return the types
	 */
	private List<String> notifications(UUID userId) {
		return jdbcTemplate.queryForList("SELECT type FROM notification WHERE user_id = ? ORDER BY id", String.class,
				userId);
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
