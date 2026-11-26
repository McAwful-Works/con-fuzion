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
import static app.confuzion.backend.controller.ReportControllerIntegrationTest.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import app.confuzion.backend.TestcontainersConfiguration;
import app.confuzion.backend.controller.EventFixtures.SeededEvent;
import app.confuzion.backend.model.record.EventRequest;
import app.confuzion.backend.model.record.EventResponse.AudienceType;
import app.confuzion.backend.model.record.EventResponse.LocationVisibility;
import app.confuzion.backend.repository.NotificationRepository;
import app.confuzion.backend.service.EventService;

/**
 * Integration tests for event moderation and the moderators' view of events
 * (D-015 rules 8, 10 and 11): a hidden event is a 404 for everyone but its host
 * and moderators, in the detail view, the feed, My schedule, RSVP and the spot
 * counts; hiding tells the host only; a hidden event holds back changes,
 * reminders and offers; and a moderator's cancel tells attendees as the host's
 * does, and the host the reason. Every action is audit-logged once. Also the
 * authorization matrix for every moderation route.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest(properties = { "con-fuzion.rate-limiter.token.capacity=10000",
		"con-fuzion.rate-limiter.join.capacity=10000" })
@Import(TestcontainersConfiguration.class)
class ModerationControllerIntegrationTest {
	/**
	 * The reason every test hides or cancels with.
	 */
	private static final String REASON = "The location isn't a public space.";

	/**
	 * The {@link WebApplicationContext}.
	 */
	@Autowired
	private WebApplicationContext webApplicationContext;

	/**
	 * The {@link JdbcTemplate}, to seed and read rows.
	 */
	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * The {@link EventService}, for a host's edit.
	 */
	@Autowired
	private EventService eventService;

	/**
	 * The {@link NotificationRepository}, for the reminder query.
	 */
	@Autowired
	private NotificationRepository notificationRepository;

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
	 * Tests that a hidden event vanishes for an attendee and an audience member
	 * everywhere they could see it, and for a moderator without MFA, while its host
	 * and moderators still see it. Moderators see its location but can't join it,
	 * and only the host sees the reason. Attendees keep their RSVPs and hear
	 * nothing; the host gets {@code EVENT_HIDDEN} with the reason. Hiding again and
	 * unhiding again change nothing; unhiding notifies nobody.
	 */
	@Test
	void hiddenEventIsVisibleOnlyToItsHostAndModerators() throws Exception {
		var host = fixtures.user("Host");
		var attendee = fixtures.user("Attendee");
		var viewer = fixtures.user("Viewer");
		var moderator = fixtures.user("Moderator");
		var event = fixtures.rsvpEvent(host, null, "now() + interval '1 day'", "NULL");
		fixtures.audience(event.id(), "USER", null, null, attendee);
		fixtures.audience(event.id(), "USER", null, null, viewer);
		confirm(event, attendee);
		var path = "/api/events/" + event.publicId();

		assertThat(status(get(path).with(login(viewer)))).isEqualTo(200);
		assertThat(status(get(path).with(as(moderator, "ROLE_MODERATOR")))).as("not under moderation").isEqualTo(404);
		assertThat(status(hide(event).with(as(moderator, "ROLE_MODERATOR")))).isEqualTo(204);

		for (var user : List.of(attendee, viewer)) {
			assertThat(status(get(path).with(login(user)))).isEqualTo(404);
			assertThat(status(get(path + "/spots").with(login(user)))).isEqualTo(404);
			assertThat(status(get(path + "/attendees").with(login(user)))).isEqualTo(404);
			for (var scope : List.of("feed", "attending"))
				assertThat(content(get("/api/events?scope=" + scope).with(login(user)), 200))
						.doesNotContain(event.publicId().toString());
		}
		assertThat(status(delete(path + "/rsvp").with(login(attendee)).with(csrf()))).isEqualTo(404);
		assertThat(status(post(path + "/rsvp").with(login(viewer)).with(csrf()))).isEqualTo(404);
		assertThat(status(get(path).with(as(moderator)))).as("moderator without MFA").isEqualTo(404);
		assertThat(status(get(path + "/spots").with(as(moderator)))).as("moderator without MFA").isEqualTo(404);
		assertThat(fixtures.rsvpStatus(event.id(), attendee)).isEqualTo("CONFIRMED");

		var asHost = content(get(path).with(login(host)), 200);
		assertThat(JsonPath.<String>read(asHost, "$.body.hiddenReason")).isEqualTo(REASON);
		assertThat(content(get("/api/events?scope=hosting").with(login(host)), 200))
				.contains(event.publicId().toString());
		assertThat(status(get(path + "/spots").with(login(host)))).isEqualTo(200);
		for (var role : List.of("ROLE_MODERATOR", "ROLE_ADMIN")) {
			var asModerator = content(get(path).with(as(moderator, role)), 200);
			assertThat(JsonPath.<String>read(asModerator, "$.body.location")).isEqualTo("Hall A");
			assertThat(JsonPath.<Object>read(asModerator, "$.body.hiddenReason")).isNull();
			assertThat(status(get(path + "/spots").with(as(moderator, role)))).isEqualTo(200);
			assertThat(status(get(path + "/attendees").with(as(moderator, role)))).isEqualTo(404);
		}
		assertThat(status(post(path + "/rsvp").with(as(moderator, "ROLE_MODERATOR")).with(csrf()))).isEqualTo(404);

		assertThat(notifications(host)).containsExactly(
				"EVENT_HIDDEN " + event.id() + " {\"reason\": \"" + REASON + "\", \"eventTitle\": \"Seeded\"}");
		assertThat(notifications(attendee)).isEmpty();
		assertThat(audit(event)).containsExactly("EVENT_HIDDEN " + moderator + " {\"reason\": \"" + REASON + "\"}");

		assertThat(status(hide(event).with(as(moderator, "ROLE_ADMIN")))).isEqualTo(204);
		assertThat(audit(event)).hasSize(1);
		assertThat(notifications(host)).hasSize(1);

		assertThat(status(unhide(event).with(as(moderator, "ROLE_MODERATOR")))).isEqualTo(204);
		assertThat(status(unhide(event).with(as(moderator, "ROLE_MODERATOR")))).isEqualTo(204);
		assertThat(status(get(path).with(login(attendee)))).isEqualTo(200);
		assertThat(JsonPath.<Object>read(content(get(path).with(login(host)), 200), "$.body.hiddenReason")).isNull();
		assertThat(status(get(path).with(as(moderator, "ROLE_MODERATOR")))).as("no longer moderated").isEqualTo(404);
		assertThat(audit(event)).containsExactly("EVENT_HIDDEN " + moderator + " {\"reason\": \"" + REASON + "\"}",
				"EVENT_UNHIDDEN " + moderator + " {}");
		assertThat(notifications(host)).hasSize(1);
		assertThat(notifications(attendee)).isEmpty();
	}

	/**
	 * Tests that a moderator sees an event they couldn't otherwise see, with its
	 * location, while someone else has reported it, in any status, and that a plain
	 * user still can't (D-015 rule 8). A moderator's own report opens nothing, so
	 * it can't get them past the host's block.
	 */
	@Test
	void moderatorSeesAReportedEvent() throws Exception {
		var host = fixtures.user("Host");
		var moderator = fixtures.user("Moderator");
		var event = fixtures.rsvpEvent(host, null, "now() + interval '1 day'", "NULL");
		var path = "/api/events/" + event.publicId();
		assertThat(status(get(path).with(as(moderator, "ROLE_MODERATOR")))).isEqualTo(404);

		jdbcTemplate.update(
				"INSERT INTO report (reporter_id, target_type, target_id, reason, status, handled_at) "
						+ "VALUES (?, 'EVENT', ?, 'SPAM', 'DISMISSED', now())",
				fixtures.user("Reporter"), event.publicId());

		var asModerator = content(get(path).with(as(moderator, "ROLE_MODERATOR")), 200);
		assertThat(JsonPath.<String>read(asModerator, "$.body.location")).isEqualTo("Hall A");
		assertThat(JsonPath.<String>read(asModerator, "$.body.myStatus")).isEqualTo("NONE");
		assertThat(status(get(path).with(as(moderator)))).isEqualTo(404);
		assertThat(status(get(path).with(login(fixtures.user("Stranger"))))).isEqualTo(404);

		var blocking = fixtures.rsvpEvent(host, null, "now() + interval '1 day'", "NULL");
		var blocked = fixtures.user("Blocked moderator");
		jdbcTemplate.update("INSERT INTO rsvp (event_id, user_id, status) VALUES (?, ?, 'REMOVED')", blocking.id(),
				blocked);
		fixtures.block(host, blocked);
		assertThat(status(post("/api/reports").with(as(blocked, "ROLE_MODERATOR")).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"targetType\":\"EVENT\",\"targetId\":\""
						+ blocking.publicId() + "\",\"reason\":\"OTHER\"}")))
				.isEqualTo(204);
		assertThat(status(get("/api/events/" + blocking.publicId()).with(as(blocked, "ROLE_MODERATOR"))))
				.isEqualTo(404);
		assertThat(status(get("/api/events/" + blocking.publicId()).with(as(moderator, "ROLE_MODERATOR"))))
				.as("another moderator").isEqualTo(200);
	}

	/**
	 * Tests that a hidden event holds back what attendees would hear (D-015 rule
	 * 10): an edit sends no {@code EVENT_CHANGED}, it gets no reminders, and a
	 * freed spot just frees up instead of going to the waitlist. Unhiding hands the
	 * free spots to the waitlist in order, so no one stays waitlisted while a spot
	 * is free (D-013).
	 */
	@Test
	void hiddenEventHoldsBackChangesRemindersAndOffers() throws Exception {
		var host = fixtures.user("Host");
		var attendee = fixtures.user("Attendee");
		var waitlisted = fixtures.user("Waitlisted");
		var event = fixtures.rsvpEvent(host, 2, "now() + interval '30 minutes'", "NULL");
		fixtures.audience(event.id(), "USER", null, null, attendee);
		confirm(event, attendee);
		jdbcTemplate.update("INSERT INTO rsvp (event_id, user_id, status) VALUES (?, ?, 'WAITLISTED')", event.id(),
				waitlisted);
		assertThat(notificationRepository.findEventsToRemind(Duration.ofHours(1))).contains(event.id());

		assertThat(status(hide(event).with(as(fixtures.user("Moderator"), "ROLE_MODERATOR")))).isEqualTo(204);

		assertThat(notificationRepository.findEventsToRemind(Duration.ofHours(1))).doesNotContain(event.id());
		assertThat(
				status(delete("/api/events/" + event.publicId() + "/rsvps/" + attendee).with(login(host)).with(csrf())))
				.isEqualTo(204);
		assertThat(fixtures.rsvpStatus(event.id(), waitlisted)).isEqualTo("WAITLISTED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(1);

		eventService.updateEvent(host, event.publicId(),
				new EventRequest("Seeded", null, "Hall B", null,
						Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS), null, "UTC", 3, null,
						null, LocationVisibility.CONFIRMED,
						List.of(new EventRequest.AudienceEntry(AudienceType.USER, null, null, attendee))));
		assertThat(fixtures.rsvpStatus(event.id(), waitlisted)).isEqualTo("WAITLISTED");
		assertThat(notifications(waitlisted)).isEmpty();
		assertThat(notifications(attendee)).isEmpty();

		assertThat(status(unhide(event).with(as(fixtures.user("Moderator"), "ROLE_MODERATOR")))).isEqualTo(204);
		assertThat(fixtures.rsvpStatus(event.id(), waitlisted)).isEqualTo("OFFERED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(2);
		assertThat(notifications(waitlisted)).singleElement().asString().startsWith("SPOT_OFFERED " + event.id());
	}

	/**
	 * Tests that a moderator's cancel of a hidden event tells everyone with an
	 * active RSVP as the host's would, with no reason, tells the host the reason,
	 * and is audit-logged once. Cancelling it again, cancelling an ended event, or
	 * cancelling one that doesn't exist changes nothing.
	 */
	@Test
	void moderatorCancelTellsAttendeesAndTheHostTheReason() throws Exception {
		var host = fixtures.user("Host");
		var attendee = fixtures.user("Attendee");
		var moderator = fixtures.user("Moderator");
		var event = fixtures.rsvpEvent(host, null, "now() + interval '1 day'", "NULL");
		confirm(event, attendee);
		jdbcTemplate.update("UPDATE event SET hidden_by_moderation = true, moderation_reason = 'Earlier' WHERE id = ?",
				event.id());

		assertThat(status(cancel(event).with(as(moderator, "ROLE_MODERATOR")))).isEqualTo(204);
		assertThat(status(cancel(event).with(as(moderator, "ROLE_ADMIN")))).isEqualTo(204);

		assertThat(jdbcTemplate.queryForObject("SELECT status FROM event WHERE id = ?", String.class, event.id()))
				.isEqualTo("CANCELLED");
		assertThat(notifications(attendee))
				.containsExactly("EVENT_CANCELLED " + event.id() + " {\"eventTitle\": \"Seeded\"}");
		assertThat(notifications(host)).containsExactly(
				"EVENT_CANCELLED " + event.id() + " {\"reason\": \"" + REASON + "\", \"eventTitle\": \"Seeded\"}");
		assertThat(audit(event)).containsExactly("EVENT_CANCELLED " + moderator + " {\"reason\": \"" + REASON + "\"}");

		var ended = fixtures.rsvpEvent(host, null, "now() - interval '1 day'", "NULL");
		assertThat(status(cancel(ended).with(as(moderator, "ROLE_MODERATOR")))).isEqualTo(204);
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM event WHERE id = ?", String.class, ended.id()))
				.isEqualTo("PUBLISHED");
		assertThat(audit(ended)).isEmpty();
		assertThat(status(cancel(new SeededEvent(0, UUID.randomUUID())).with(as(moderator, "ROLE_MODERATOR"))))
				.isEqualTo(404);
	}

	/**
	 * Tests the authorization matrix for every moderation route: signed out is a
	 * 401; a plain user, a moderator whose MFA is off (a stored role but no
	 * authority), the event's host and an attendee are 403s; a moderator and an
	 * admin reach the route. A missing event or report is a 404 for them.
	 */
	@Test
	void authorizationMatrix() throws Exception {
		var host = fixtures.user("Host");
		var attendee = fixtures.user("Attendee");
		var withoutMfa = fixtures.user("No MFA");
		jdbcTemplate.update("INSERT INTO user_role (user_id, role) VALUES (?, 'MODERATOR')", withoutMfa);
		var moderator = fixtures.user("Moderator");
		var admin = fixtures.user("Admin");
		for (var granted : List.of(Map.entry(moderator, "MODERATOR"), Map.entry(admin, "ADMIN"))) {
			jdbcTemplate.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", granted.getKey(),
					granted.getValue());
			jdbcTemplate.update("UPDATE app_user SET mfa_enabled = true WHERE id = ?", granted.getKey());
		}
		var event = fixtures.rsvpEvent(host, null, "now() + interval '1 day'", "NULL");
		confirm(event, attendee);
		var reportId = jdbcTemplate.queryForObject("INSERT INTO report (reporter_id, target_type, target_id, reason) "
				+ "VALUES (?, 'EVENT', ?, 'SPAM') RETURNING id", Long.class, attendee, event.publicId());
		var missingEvent = new SeededEvent(0, UUID.randomUUID());

		var denied = new LinkedHashMap<String, OAuth2LoginRequestPostProcessor>();
		denied.put("user", login(fixtures.user("User")));
		denied.put("moderator without MFA", as(withoutMfa));
		denied.put("host", login(host));
		denied.put("attendee", login(attendee));
		var routes = List.of(new Route("queue", () -> get("/api/admin/reports"), 200, 200),
				new Route("queue, ACTIONED", () -> get("/api/admin/reports?status=ACTIONED"), 200, 200),
				new Route("report", () -> get("/api/admin/reports/" + reportId), 200, 200),
				new Route("missing report", () -> get("/api/admin/reports/" + Long.MAX_VALUE), 404, 404),
				new Route("resolve", () -> resolve(reportId), 200, 409),
				new Route("resolve missing", () -> resolve(Long.MAX_VALUE), 404, 404),
				new Route("hide", () -> hide(event), 204, 204), new Route("unhide", () -> unhide(event), 204, 204),
				new Route("cancel", () -> cancel(event), 204, 204),
				new Route("hide missing", () -> hide(missingEvent), 404, 404),
				new Route("unhide missing", () -> unhide(missingEvent), 404, 404),
				new Route("cancel missing", () -> cancel(missingEvent), 404, 404));

		for (var route : routes) {
			var what = route.name();
			assertThat(status(route.request().get())).as(what).isEqualTo(401);
			for (var entry : denied.entrySet())
				assertThat(status(route.request().get().with(entry.getValue()))).as(what + " as " + entry.getKey())
						.isEqualTo(403);
			assertThat(status(route.request().get().with(as(moderator, "ROLE_MODERATOR")))).as(what + " as moderator")
					.isEqualTo(route.moderator());
			assertThat(status(route.request().get().with(as(admin, "ROLE_ADMIN")))).as(what + " as admin")
					.isEqualTo(route.admin());
		}
		assertThat(status(post("/api/admin/events/" + event.publicId() + "/unhide").with(as(admin, "ROLE_ADMIN"))))
				.as("no CSRF token").isEqualTo(403);
	}

	/**
	 * Gives a user a confirmed RSVP on an event, taking a spot.
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
	 * Returns a hide request, with CSRF.
	 *
	 * @param event the event
	 * @return the request
	 */
	private static MockHttpServletRequestBuilder hide(SeededEvent event) {
		return post("/api/admin/events/" + event.publicId() + "/hide").with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + REASON + "\"}");
	}

	/**
	 * Returns an unhide request, with CSRF.
	 *
	 * @param event the event
	 * @return the request
	 */
	private static MockHttpServletRequestBuilder unhide(SeededEvent event) {
		return post("/api/admin/events/" + event.publicId() + "/unhide").with(csrf());
	}

	/**
	 * Returns a moderator's cancel request, with CSRF.
	 *
	 * @param event the event
	 * @return the request
	 */
	private static MockHttpServletRequestBuilder cancel(SeededEvent event) {
		return post("/api/admin/events/" + event.publicId() + "/cancel").with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + REASON + "\"}");
	}

	/**
	 * Returns a resolve request, with CSRF.
	 *
	 * @param reportId the report's ID
	 * @return the request
	 */
	private static MockHttpServletRequestBuilder resolve(long reportId) {
		return post("/api/admin/reports/" + reportId + "/resolve").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"resolution\":\"ACTIONED\"}");
	}

	/**
	 * Returns a user's notifications, oldest first, as
	 * {@code "TYPE eventId payload"}.
	 *
	 * @param userId the user
	 * @return the notifications
	 */
	private List<String> notifications(UUID userId) {
		return jdbcTemplate.queryForList(
				"SELECT type || ' ' || coalesce(event_id::text, '-') || ' ' || payload::text FROM notification "
						+ "WHERE user_id = ? ORDER BY id",
				String.class, userId);
	}

	/**
	 * Returns an event's audit rows, oldest first, as
	 * {@code "ACTION actorId details"}.
	 *
	 * @param event the event
	 * @return the rows
	 */
	private List<String> audit(SeededEvent event) {
		return jdbcTemplate.queryForList(
				"SELECT action || ' ' || actor_id::text || ' ' || details::text FROM audit_log "
						+ "WHERE target_type = 'EVENT' AND target_id = ? ORDER BY id",
				String.class, event.publicId().toString());
	}

	/**
	 * Performs a request and returns its status.
	 *
	 * @param request the request
	 * @return the status
	 */
	private int status(MockHttpServletRequestBuilder request) throws Exception {
		return mockMvc.perform(request).andReturn().getResponse().getStatus();
	}

	/**
	 * Performs a request, checks its status and returns its body.
	 *
	 * @param request the request
	 * @param status  the expected status
	 * @return the body
	 */
	private String content(MockHttpServletRequestBuilder request, int status) throws Exception {
		var response = mockMvc.perform(request).andReturn().getResponse();
		assertThat(response.getStatus()).isEqualTo(status);
		return response.getContentAsString();
	}

	/**
	 * A moderation route and what a moderator and an admin get from it, in that
	 * order.
	 *
	 * @param name      what the route is, for failure messages
	 * @param request   builds a fresh request, with CSRF on writes
	 * @param moderator the moderator's status
	 * @param admin     the admin's status, which runs after the moderator's
	 * @author mcawful
	 */
	private record Route(String name, Supplier<MockHttpServletRequestBuilder> request, int moderator, int admin) {
	}
}
