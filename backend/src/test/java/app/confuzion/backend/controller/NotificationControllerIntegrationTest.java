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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import app.confuzion.backend.TestcontainersConfiguration;
import app.confuzion.backend.controller.EventFixtures.SeededEvent;
import app.confuzion.backend.model.record.NotificationPayload;
import app.confuzion.backend.model.record.NotificationResponse.Type;
import app.confuzion.backend.repository.NotificationRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration tests for the inbox API and its triggers against Postgres, with
 * Spring Security applied (D-014): who sees and marks what, who each trigger
 * notifies and with what, and what a block deletes.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest(properties = "con-fuzion.rate-limiter.token.capacity=10000")
@Import(TestcontainersConfiguration.class)
class NotificationControllerIntegrationTest {
	/**
	 * Writes request bodies.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

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
	 * The {@link NotificationRepository}, to seed inbox rows.
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
	 * Builds the {@link MockMvc} and fixtures.
	 */
	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
		fixtures = new EventFixtures(jdbcTemplate);
	}

	/**
	 * Tests that the inbox needs a session, is the caller's own newest first, pages
	 * with an opaque cursor, and rejects a cursor it didn't issue.
	 */
	@Test
	void testInboxIsPrivatePagedAndNewestFirst() throws Exception {
		var me = fixtures.user("Me");
		var other = fixtures.user("Other");
		var event = fixtures.event(me);
		var mine = List.of(seed(me, event), seed(me, event), seed(me, event));
		var theirs = seed(other, event);

		assertThat(status(get("/api/notifications"))).isEqualTo(401);
		var first = body(get("/api/notifications?limit=2").with(login(me)), 200);
		assertThat(JsonPath.<List<Number>>read(first, "$.body.items[*].id")).extracting(Number::longValue)
				.containsExactly(mine.get(2), mine.get(1));
		assertThat(JsonPath.<Integer>read(first, "$.body.unreadCount")).isEqualTo(3);
		assertThat(JsonPath.<List<String>>read(first, "$.body.items[*].eventId"))
				.containsOnly(event.publicId().toString());
		assertThat(JsonPath.<String>read(first, "$.body.items[0].payload.eventTitle")).isEqualTo("Seeded");
		var second = body(get("/api/notifications").param("limit", "2")
				.param("cursor", JsonPath.<String>read(first, "$.body.nextCursor")).with(login(me)), 200);
		assertThat(JsonPath.<List<Number>>read(second, "$.body.items[*].id")).extracting(Number::longValue)
				.containsExactly(mine.get(0));
		assertThat(JsonPath.<Object>read(second, "$.body.nextCursor")).isNull();

		var theirInbox = body(get("/api/notifications").with(login(other)), 200);
		assertThat(JsonPath.<List<Number>>read(theirInbox, "$.body.items[*].id")).extracting(Number::longValue)
				.containsExactly(theirs);
		assertThat(status(get("/api/notifications?cursor=bm90").with(login(me)))).isEqualTo(400);
		assertThat(status(get("/api/notifications?cursor=MA").with(login(me)))).isEqualTo(400);
	}

	/**
	 * Tests that marking read covers only the caller's own notifications up to the
	 * ID, that another user's ID and a missing CSRF token do nothing, and that an
	 * ID beyond every notification is still a 204.
	 */
	@Test
	void testMarkReadStopsAtTheIdAndTheOwner() throws Exception {
		var me = fixtures.user("Me");
		var other = fixtures.user("Other");
		var event = fixtures.event(me);
		seed(other, event);
		var mine = List.of(seed(me, event), seed(me, event), seed(me, event));

		assertThat(status(markRead(me, mine.get(1)).with(csrf()))).isEqualTo(204);

		assertThat(readFlags(me)).containsExactly(true, true, false);
		assertThat(unread(me)).isEqualTo(1);
		assertThat(readFlags(other)).containsExactly(false);
		assertThat(status(markRead(other, mine.get(2)).with(csrf()))).isEqualTo(204);
		assertThat(readFlags(me)).containsExactly(true, true, false);
		assertThat(readFlags(other)).containsExactly(true);
		assertThat(status(markRead(me, mine.get(2) + 1_000_000).with(csrf()))).isEqualTo(204);
		assertThat(unread(me)).isZero();
		assertThat(status(markRead(me, 1))).isEqualTo(403);
		assertThat(status(markRead(me, 0).with(csrf()))).isEqualTo(400);
	}

	/**
	 * Tests that the unread count stops at 100.
	 */
	@Test
	void testUnreadCountIsCapped() throws Exception {
		var me = fixtures.user("Me");
		var event = fixtures.event(me);
		for (var i = 0; i < 101; i++)
			seed(me, event);

		assertThat(unread(me)).isEqualTo(100);
	}

	/**
	 * Tests an edit of the start and location: every active RSVP but the host's
	 * gets one {@code EVENT_CHANGED} with no location and no name, a past-deadline
	 * offer, a left person and a removed one get nothing, and push is queued only
	 * for a user with push on and a subscription.
	 */
	@Test
	void testEditNotifiesActiveRsvpsButNotTheHost() throws Exception {
		var host = fixtures.user("Host");
		var event = fixtures.rsvpEvent(host, null, "now() + interval '1 day'", "NULL");
		var confirmed = fixtures.user("Confirmed");
		var waitlisted = fixtures.user("Waitlisted");
		var offered = fixtures.user("Offered");
		var lapsed = fixtures.user("Lapsed");
		var left = fixtures.user("Left");
		var removed = fixtures.user("Removed");
		rsvp(event, confirmed, "CONFIRMED", null);
		rsvp(event, waitlisted, "WAITLISTED", null);
		rsvp(event, offered, "OFFERED", "now() + interval '10 minutes'");
		rsvp(event, lapsed, "OFFERED", "now() - interval '1 minute'");
		rsvp(event, left, "LEFT", null);
		rsvp(event, removed, "REMOVED", null);
		pushOn(confirmed, true);
		pushOn(waitlisted, false);
		var newStart = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);

		assertThat(status(edit(host, event, "Seeded", "Secret Room 9", newStart, null))).isEqualTo(200);

		for (var user : List.of(confirmed, waitlisted, offered)) {
			var rows = rows(user);
			assertThat(rows).hasSize(1);
			assertThat(rows.getFirst().get("type")).isEqualTo("EVENT_CHANGED");
			assertThat(rows.getFirst().get("event_id")).isEqualTo(event.id());
			assertThat(rows.getFirst().get("payload").toString()).doesNotContain("Secret Room 9")
					.doesNotContain("Hall A");
		}
		assertThat(rows(confirmed).getFirst().get("push_status")).isEqualTo("PENDING");
		assertThat(rows(waitlisted).getFirst().get("push_status")).isEqualTo("NONE");
		assertThat(rows(offered).getFirst().get("push_status")).isEqualTo("NONE");
		for (var user : List.of(host, lapsed, left, removed))
			assertThat(rows(user)).isEmpty();
		var inbox = body(get("/api/notifications").with(login(confirmed)), 200);
		assertThat(JsonPath.<List<String>>read(inbox, "$.body.items[0].payload.changes")).containsExactly("START",
				"LOCATION");
		assertThat(JsonPath.<String>read(inbox, "$.body.items[0].payload.startsAt")).isEqualTo(newStart.toString());
		assertThat(JsonPath.<String>read(inbox, "$.body.items[0].payload.eventTitle")).isEqualTo("Seeded");
	}

	/**
	 * Tests that only a change of start, end or location notifies anyone: a new
	 * title sends nothing, and an end change is reported as {@code END} alone.
	 */
	@Test
	void testOtherEditsNotifyNobody() throws Exception {
		var host = fixtures.user("Host");
		var event = fixtures.rsvpEvent(host, 10, "now() + interval '1 day'", "NULL");
		var guest = fixtures.user("Guest");
		rsvp(event, guest, "CONFIRMED", null);
		var start = jdbcTemplate.queryForObject("SELECT starts_at FROM event WHERE id = ?", Timestamp.class, event.id())
				.toInstant();

		assertThat(status(edit(host, event, "New title", "Hall A", start, null))).isEqualTo(200);
		assertThat(rows(guest)).isEmpty();

		var end = start.plus(2, ChronoUnit.HOURS);
		assertThat(status(edit(host, event, "New title", "Hall A", start, end))).isEqualTo(200);
		var rows = rows(guest);
		assertThat(rows).hasSize(1);
		assertThat(rows.getFirst().get("payload").toString()).contains("END").doesNotContain("START")
				.doesNotContain("LOCATION");
		assertThat(status(edit(host, event, "Newer title", "Hall A", start, end))).isEqualTo(200);
		assertThat(rows(guest)).hasSize(1);
	}

	/**
	 * Tests that a rejected edit notifies nobody.
	 */
	@Test
	void testRejectedEditNotifiesNobody() throws Exception {
		var host = fixtures.user("Host");
		var event = fixtures.rsvpEvent(host, 5, "now() + interval '1 day'", "NULL");
		var guest = fixtures.user("Guest");
		var second = fixtures.user("Second");
		rsvp(event, guest, "CONFIRMED", null);
		rsvp(event, second, "CONFIRMED", null);
		jdbcTemplate.update("UPDATE event SET confirmed_count = 3 WHERE id = ?", event.id());

		var request = edit(host, event, "Seeded", "Moved", Instant.now().plus(2, ChronoUnit.DAYS), 2);

		assertThat(status(request)).isEqualTo(409);
		assertThat(rows(guest)).isEmpty();
	}

	/**
	 * Tests that a cancel notifies every active RSVP but the host's, once:
	 * cancelling again, and cancelling by someone else, send nothing.
	 */
	@Test
	void testCancelNotifiesOnce() throws Exception {
		var host = fixtures.user("Host");
		var event = fixtures.rsvpEvent(host, null, "now() + interval '1 day'", "NULL");
		var confirmed = fixtures.user("Confirmed");
		var waitlisted = fixtures.user("Waitlisted");
		var left = fixtures.user("Left");
		var stranger = fixtures.user("Stranger");
		rsvp(event, confirmed, "CONFIRMED", null);
		rsvp(event, waitlisted, "WAITLISTED", null);
		rsvp(event, left, "LEFT", null);

		assertThat(status(delete("/api/events/" + event.publicId()).with(login(stranger)).with(csrf()))).isEqualTo(404);
		assertThat(rows(confirmed)).isEmpty();
		assertThat(status(delete("/api/events/" + event.publicId()).with(login(host)).with(csrf()))).isEqualTo(204);
		assertThat(status(delete("/api/events/" + event.publicId()).with(login(host)).with(csrf()))).isEqualTo(204);

		for (var user : List.of(confirmed, waitlisted)) {
			assertThat(rows(user)).hasSize(1);
			assertThat(rows(user).getFirst().get("type")).isEqualTo("EVENT_CANCELLED");
			assertThat(rows(user).getFirst().get("payload").toString()).isEqualTo("{\"eventTitle\": \"Seeded\"}");
		}
		for (var user : List.of(host, left, stranger))
			assertThat(rows(user)).isEmpty();
	}

	/**
	 * Tests that accepting a friend invite notifies the inviter, with the new
	 * friend's ID and no name or event, and nobody else.
	 */
	@Test
	void testAcceptingAnInviteNotifiesTheInviter() throws Exception {
		var inviter = fixtures.user("Inviter");
		var friend = fixtures.user("Friend");
		var token = fixtures.social().invite(inviter, "1 day", false);

		assertThat(status(post("/api/friends/invites/" + token + "/accept").with(login(friend)).with(csrf())))
				.isEqualTo(200);

		var rows = rows(inviter);
		assertThat(rows).hasSize(1);
		assertThat(rows.getFirst().get("type")).isEqualTo("FRIEND_ACCEPTED");
		assertThat(rows.getFirst().get("event_id")).isNull();
		assertThat(rows(friend)).isEmpty();
		var inbox = body(get("/api/notifications").with(login(inviter)), 200);
		assertThat(JsonPath.<String>read(inbox, "$.body.items[0].payload.friendId")).isEqualTo(friend.toString());
		assertThat(JsonPath.<Object>read(inbox, "$.body.items[0].eventId")).isNull();
		assertThat(JsonPath.<Map<String, Object>>read(inbox, "$.body.items[0].payload")).containsOnlyKeys("friendId");
	}

	/**
	 * Tests that an invite that fails (used up, or between blocked people) notifies
	 * nobody.
	 */
	@Test
	void testAFailedAcceptNotifiesNobody() throws Exception {
		var inviter = fixtures.user("Inviter");
		var blocked = fixtures.user("Blocked");
		fixtures.block(inviter, blocked);
		var token = fixtures.social().invite(inviter, "1 day", false);
		var used = fixtures.social().invite(inviter, "1 day", true);

		assertThat(status(post("/api/friends/invites/" + token + "/accept").with(login(blocked)).with(csrf())))
				.isEqualTo(404);
		assertThat(status(post("/api/friends/invites/" + used + "/accept").with(login(blocked)).with(csrf())))
				.isEqualTo(404);

		assertThat(rows(inviter)).isEmpty();
	}

	/**
	 * Tests that a block deletes, for both people, the notifications about the
	 * other's events and their {@code FRIEND_ACCEPTED} for the other, and keeps
	 * everything about third parties.
	 */
	@Test
	void testBlockDeletesNotificationsBetweenThePair() throws Exception {
		var a = fixtures.user("A");
		var b = fixtures.user("B");
		var third = fixtures.user("Third");
		var aEvent = fixtures.event(a);
		var bEvent = fixtures.event(b);
		var thirdEvent = fixtures.event(third);
		seed(a, bEvent);
		seed(a, thirdEvent);
		seed(a, aEvent);
		friendAccepted(a, b);
		friendAccepted(a, third);
		seed(b, aEvent);
		seed(b, thirdEvent);
		friendAccepted(b, a);
		seed(third, aEvent);
		friendAccepted(third, a);

		assertThat(status(put("/api/blocks/" + b).with(login(a)).with(csrf()))).isEqualTo(204);

		assertThat(summary(a)).containsExactlyInAnyOrder("EVENT_CANCELLED:" + thirdEvent.id(),
				"EVENT_CANCELLED:" + aEvent.id(), "FRIEND_ACCEPTED:" + third);
		assertThat(summary(b)).containsExactly("EVENT_CANCELLED:" + thirdEvent.id());
		assertThat(summary(third)).containsExactlyInAnyOrder("EVENT_CANCELLED:" + aEvent.id(), "FRIEND_ACCEPTED:" + a);
	}

	/**
	 * Tests that the blocked person's notifications go too, whoever blocks, and
	 * that blocking again is fine.
	 */
	@Test
	void testBlockBothWaysIsIdempotent() throws Exception {
		var a = fixtures.user("A");
		var b = fixtures.user("B");
		var aEvent = fixtures.event(a);
		seed(b, aEvent);

		assertThat(status(put("/api/blocks/" + a).with(login(b)).with(csrf()))).isEqualTo(204);
		assertThat(rows(b)).isEmpty();
		assertThat(status(put("/api/blocks/" + b).with(login(a)).with(csrf()))).isEqualTo(204);
		assertThat(status(put("/api/blocks/" + b).with(login(a)).with(csrf()))).isEqualTo(204);
	}

	/**
	 * Seeds an {@code EVENT_CANCELLED} notification.
	 *
	 * @param userId the recipient
	 * @param event  the event
	 * @return the notification's ID
	 */
	private long seed(UUID userId, SeededEvent event) {
		return notificationRepository.insert(userId, Type.EVENT_CANCELLED, event.id(),
				new NotificationPayload("Seeded", null, null, null, null));
	}

	/**
	 * Seeds a {@code FRIEND_ACCEPTED} notification.
	 *
	 * @param userId   the recipient
	 * @param friendId the new friend
	 */
	private void friendAccepted(UUID userId, UUID friendId) {
		notificationRepository.insert(userId, Type.FRIEND_ACCEPTED, null,
				new NotificationPayload(null, null, null, null, friendId));
	}

	/**
	 * Adds an RSVP.
	 *
	 * @param event       the event
	 * @param userId      the user
	 * @param status      the RSVP status
	 * @param offerExpiry a SQL expression for the offer deadline, or {@code null}
	 */
	private void rsvp(SeededEvent event, UUID userId, String status, String offerExpiry) {
		jdbcTemplate.update("INSERT INTO rsvp (event_id, user_id, status, offer_expires_at) VALUES (?, ?, ?, "
				+ (offerExpiry == null ? "NULL" : offerExpiry) + ")", event.id(), userId, status);
	}

	/**
	 * Turns push on for a user, with or without a subscription.
	 *
	 * @param userId       the user
	 * @param subscription whether to add a subscription
	 */
	private void pushOn(UUID userId, boolean subscription) {
		jdbcTemplate.update("UPDATE app_user SET push_enabled = true WHERE id = ?", userId);
		if (subscription)
			jdbcTemplate.update(
					"INSERT INTO push_subscription (user_id, endpoint, p256dh, auth) VALUES (?, ?, 'k', 'a')", userId,
					"https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID());
	}

	/**
	 * Returns a user's notification rows, oldest first.
	 *
	 * @param userId the user
	 * @return the rows
	 */
	private List<Map<String, Object>> rows(UUID userId) {
		return jdbcTemplate.queryForList("SELECT type, event_id, payload::text AS payload, push_status "
				+ "FROM notification WHERE user_id = ? ORDER BY id", userId);
	}

	/**
	 * Describes each of a user's notifications as {@code type:eventId}, or
	 * {@code type:friendId}.
	 *
	 * @param userId the user
	 * @return the descriptions
	 */
	private List<String> summary(UUID userId) {
		return jdbcTemplate.query("SELECT type || ':' || COALESCE(event_id::text, payload ->> 'friendId') "
				+ "FROM notification WHERE user_id = ?", (rs, row) -> rs.getString(1), userId);
	}

	/**
	 * Returns whether each of a user's notifications is read, oldest first.
	 *
	 * @param userId the user
	 * @return the flags
	 */
	private List<Boolean> readFlags(UUID userId) {
		return jdbcTemplate.query("SELECT read_at IS NOT NULL FROM notification WHERE user_id = ? ORDER BY id",
				(rs, row) -> rs.getBoolean(1), userId);
	}

	/**
	 * Returns the unread count the inbox API reports.
	 *
	 * @param userId the user
	 * @return the count
	 */
	private int unread(UUID userId) throws Exception {
		return JsonPath.read(body(get("/api/notifications").with(login(userId)), 200), "$.body.unreadCount");
	}

	/**
	 * Builds a mark-read request.
	 *
	 * @param userId the caller
	 * @param upTo   the ID
	 * @return the request, signed in and without CSRF
	 */
	private MockHttpServletRequestBuilder markRead(UUID userId, long upTo) {
		return post("/api/notifications/read").contentType(MediaType.APPLICATION_JSON)
				.content("{\"upTo\":" + upTo + "}").with(login(userId));
	}

	/**
	 * Builds the host's edit of a seeded event.
	 *
	 * @param host     the host
	 * @param event    the event
	 * @param title    the title
	 * @param location the location
	 * @param startsAt the start
	 * @param extra    an {@link Instant} end, an {@link Integer} capacity, or
	 *                 {@code null}
	 * @return the request
	 */
	private MockHttpServletRequestBuilder edit(UUID host, SeededEvent event, String title, String location,
			Instant startsAt, Object extra) {
		var body = new HashMap<String, Object>();
		body.put("title", title);
		body.put("location", location);
		body.put("startsAt", startsAt.toString());
		if (extra instanceof Instant end)
			body.put("endsAt", end.toString());
		body.put("timeZone", "UTC");
		if (extra instanceof Integer capacity)
			body.put("capacity", capacity);
		body.put("locationVisibility", "CONFIRMED");
		body.put("audience", List.of(Map.of("type", "FRIENDS")));
		return patch("/api/events/" + event.publicId()).contentType(MediaType.APPLICATION_JSON)
				.content(JSON.writeValueAsString(body)).with(login(host)).with(csrf());
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
	private String body(MockHttpServletRequestBuilder request, int status) throws Exception {
		var response = mockMvc.perform(request).andReturn().getResponse();
		assertThat(response.getStatus()).isEqualTo(status);
		return response.getContentAsString();
	}
}
