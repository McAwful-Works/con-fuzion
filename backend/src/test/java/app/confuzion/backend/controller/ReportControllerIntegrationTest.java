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
import static app.confuzion.backend.controller.ModerationControllerIntegrationTest.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import app.confuzion.backend.TestcontainersConfiguration;

/**
 * Integration tests for reporting and the moderation queue (D-015 rules 6 to 9
 * and 12): which targets can be reported, the duplicate rule and the daily cap,
 * the one-statement fan-out to moderators, that the reported user never sees a
 * report or who made it, and resolving.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest(properties = "con-fuzion.rate-limiter.token.capacity=10000")
@Import(TestcontainersConfiguration.class)
class ReportControllerIntegrationTest {
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
	 * Tests that a report reaches, in-app, every user with a stored role and MFA
	 * on, but not one with MFA off, the reporter, or the reported user even when
	 * they are a moderator; and that the reported moderator can't find it in the
	 * queue, open it or resolve it, while other moderators see who made it.
	 */
	@Test
	void reportReachesModeratorsButNeverTheReportedUser() throws Exception {
		var moderator = role("Moderator", "MODERATOR", true);
		var admin = role("Admin", "ADMIN", true);
		var withoutMfa = role("No MFA", "MODERATOR", false);
		var reportedModerator = role("Reported", "MODERATOR", true);
		var reporter = fixtures.user("Reporter");

		assertThat(status(report(reporter, "USER", reportedModerator, "HARASSMENT", "They followed me.")))
				.isEqualTo(204);

		var reportId = jdbcTemplate.queryForObject(
				"SELECT id FROM report WHERE reporter_id = ? AND target_id = ? AND status = 'OPEN'", Long.class,
				reporter, reportedModerator);
		for (var user : List.of(moderator, admin))
			assertThat(notifications(user))
					.containsExactly("REPORT_RECEIVED - {\"reportId\": " + reportId + ", \"targetType\": \"USER\"}");
		for (var user : List.of(withoutMfa, reportedModerator, reporter))
			assertThat(notifications(user)).isEmpty();

		var asReported = as(reportedModerator, "ROLE_MODERATOR");
		assertThat(pageIds(asReported, "OPEN")).doesNotContain(reportId);
		assertThat(pageIds(as(moderator, "ROLE_MODERATOR"), "OPEN")).contains(reportId);
		assertThat(status(get("/api/admin/reports/" + reportId).with(asReported))).isEqualTo(404);
		assertThat(status(resolve(reportId, "DISMISSED").with(asReported))).isEqualTo(404);

		var seen = content(get("/api/admin/reports/" + reportId).with(as(moderator, "ROLE_MODERATOR")), 200);
		assertThat(JsonPath.<String>read(seen, "$.body.reporterId")).isEqualTo(reporter.toString());
		assertThat(JsonPath.<String>read(seen, "$.body.targetLabel")).isEqualTo("Reported");
		assertThat(JsonPath.<String>read(seen, "$.body.note")).isEqualTo("They followed me.");
		assertThat(JsonPath.<String>read(seen, "$.body.status")).isEqualTo("OPEN");
	}

	/**
	 * Tests which targets can be reported (D-015 rule 6): another user, even across
	 * a block, and an event the reporter can see or once had an RSVP on, even after
	 * a hide. Reporting yourself or your own event is a 400; a missing user, a
	 * missing event and one the reporter can't see are 404s.
	 */
	@Test
	void reportTargets() throws Exception {
		var reporter = fixtures.user("Reporter");
		var host = fixtures.user("Host");
		var blocked = fixtures.user("Blocked");
		fixtures.block(blocked, reporter);
		var own = fixtures.event(reporter);
		var unseen = fixtures.event(host);
		var visible = fixtures.event(host);
		fixtures.audience(visible.id(), "USER", null, null, reporter);
		var attended = fixtures.event(host);
		jdbcTemplate.update("INSERT INTO rsvp (event_id, user_id, status) VALUES (?, ?, 'LEFT')", attended.id(),
				reporter);
		jdbcTemplate.update("UPDATE event SET hidden_by_moderation = true, moderation_reason = 'x' WHERE id = ?",
				attended.id());

		assertThat(status(report(reporter, "USER", reporter, "SPAM", null))).isEqualTo(400);
		assertThat(status(report(reporter, "EVENT", own.publicId(), "SPAM", null))).isEqualTo(400);
		assertThat(status(report(reporter, "USER", UUID.randomUUID(), "SPAM", null))).isEqualTo(404);
		assertThat(status(report(reporter, "EVENT", UUID.randomUUID(), "SPAM", null))).isEqualTo(404);
		assertThat(status(report(reporter, "EVENT", unseen.publicId(), "SPAM", null))).isEqualTo(404);
		assertThat(status(report(reporter, "EVENT", host, "SPAM", null))).as("a user ID as an event").isEqualTo(404);
		assertThat(status(report(reporter, "USER", blocked, "SPAM", null))).isEqualTo(204);
		assertThat(status(report(reporter, "EVENT", visible.publicId(), "UNSAFE", null))).isEqualTo(204);
		assertThat(status(report(reporter, "EVENT", attended.publicId(), "OTHER", " "))).isEqualTo(204);
		assertThat(jdbcTemplate.queryForList("SELECT target_type || ' ' || reason || ' ' || coalesce(note, '-') "
				+ "FROM report WHERE reporter_id = ? ORDER BY id", String.class, reporter))
				.containsExactly("USER SPAM -", "EVENT UNSAFE -", "EVENT OTHER -");
		assertThat(status(post("/api/reports").contentType(MediaType.APPLICATION_JSON)
				.content(body("USER", blocked, "SPAM", null)).with(login(reporter)))).as("no CSRF token")
				.isEqualTo(403);
	}

	/**
	 * Tests the duplicate rule and the daily cap (D-015 rule 6): a repeat on an
	 * open report is a 204 that writes nothing and doesn't count, even at the cap;
	 * the 11th new report in 24 hours is a 429 {@code REPORT_LIMIT}; and once the
	 * first is resolved, reporting that target again counts as new.
	 */
	@Test
	void duplicatesAndTheDailyCap() throws Exception {
		var reporter = fixtures.user("Reporter");
		var first = fixtures.user("First");
		var moderator = fixtures.user("Moderator");

		assertThat(status(report(reporter, "USER", first, "SPAM", "first note"))).isEqualTo(204);
		assertThat(status(report(reporter, "USER", first, "UNSAFE", "second note"))).isEqualTo(204);
		assertThat(jdbcTemplate.queryForList("SELECT reason || ' ' || note FROM report WHERE reporter_id = ?",
				String.class, reporter)).containsExactly("SPAM first note");
		for (var i = 0; i < 9; i++)
			assertThat(status(report(reporter, "USER", fixtures.user("Other"), "SPAM", null))).isEqualTo(204);

		var limited = mockMvc.perform(report(reporter, "USER", fixtures.user("Eleventh"), "SPAM", null)).andReturn()
				.getResponse();
		assertThat(limited.getStatus()).isEqualTo(429);
		assertThat(JsonPath.<String>read(limited.getContentAsString(), "$.body.errorType")).isEqualTo("REPORT_LIMIT");
		assertThat(status(report(reporter, "USER", first, "SPAM", null))).as("an open repeat at the cap")
				.isEqualTo(204);
		assertThat(count(reporter)).isEqualTo(10);

		var firstId = jdbcTemplate.queryForObject("SELECT id FROM report WHERE reporter_id = ? AND target_id = ?",
				Long.class, reporter, first);
		assertThat(status(resolve(firstId, "DISMISSED").with(as(moderator, "ROLE_MODERATOR")))).isEqualTo(200);
		assertThat(status(report(reporter, "USER", first, "SPAM", null))).isEqualTo(429);
		jdbcTemplate.update("UPDATE report SET created_at = now() - interval '25 hours' WHERE reporter_id = ?",
				reporter);
		assertThat(status(report(reporter, "USER", first, "SPAM", null))).isEqualTo(204);
		assertThat(count(reporter)).isEqualTo(11);
	}

	/**
	 * Tests the queue and resolving (D-015 rules 8 and 9): {@code OPEN} pages
	 * oldest first with a cursor, the others newest first; a cursor for another
	 * status is a 400. Resolving records the moderator and note, sends the reporter
	 * a {@code REPORT_REVIEWED} with no details, writes one audit row, and a second
	 * resolve is a 409 {@code REPORT_RESOLVED} that writes nothing.
	 */
	@Test
	void queueAndResolve() throws Exception {
		var reporter = fixtures.user("Reporter");
		var moderator = fixtures.user("Moderator");
		var asModerator = as(moderator, "ROLE_MODERATOR");
		var targets = List.of(fixtures.user("A"), fixtures.user("B"), fixtures.user("C"));
		for (var target : targets)
			assertThat(status(report(reporter, "USER", target, "SPAM", null))).isEqualTo(204);
		var ids = jdbcTemplate.queryForList("SELECT id FROM report WHERE reporter_id = ? ORDER BY id", Long.class,
				reporter);

		var open = pageIds(asModerator, "OPEN");
		assertThat(open).isSorted();
		assertThat(open).containsSubsequence(ids);
		var firstPage = content(get("/api/admin/reports?limit=1").with(asModerator), 200);
		var cursor = JsonPath.<String>read(firstPage, "$.body.nextCursor");
		var secondPage = content(get("/api/admin/reports?limit=1&cursor=" + cursor).with(asModerator), 200);
		assertThat(JsonPath.<Integer>read(secondPage, "$.body.items[0].id").longValue())
				.isGreaterThan(JsonPath.<Integer>read(firstPage, "$.body.items[0].id").longValue());
		assertThat(status(get("/api/admin/reports?status=ACTIONED&cursor=" + cursor).with(asModerator))).isEqualTo(400);

		var resolved = content(post("/api/admin/reports/" + ids.get(1) + "/resolve").with(csrf()).with(asModerator)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"resolution\":\"ACTIONED\",\"resolutionNote\":\"Suspended for a week\"}"), 200);
		assertThat(JsonPath.<String>read(resolved, "$.responseType")).isEqualTo("REPORT");
		assertThat(JsonPath.<String>read(resolved, "$.body.status")).isEqualTo("ACTIONED");
		assertThat(JsonPath.<String>read(resolved, "$.body.handledBy")).isEqualTo(moderator.toString());
		assertThat(JsonPath.<String>read(resolved, "$.body.resolutionNote")).isEqualTo("Suspended for a week");
		assertThat(JsonPath.<String>read(resolved, "$.body.handledAt")).isNotNull();
		assertThat(status(resolve(ids.get(2), "DISMISSED").with(asModerator))).isEqualTo(200);

		var conflict = mockMvc.perform(resolve(ids.get(1), "DISMISSED").with(as(fixtures.user("Admin"), "ROLE_ADMIN")))
				.andReturn().getResponse();
		assertThat(conflict.getStatus()).isEqualTo(409);
		assertThat(JsonPath.<String>read(conflict.getContentAsString(), "$.body.errorType"))
				.isEqualTo("REPORT_RESOLVED");

		assertThat(notifications(reporter)).containsExactly("REPORT_REVIEWED - {}", "REPORT_REVIEWED - {}");
		assertThat(
				jdbcTemplate.queryForList(
						"SELECT action || ' ' || actor_id::text || ' ' || details::text FROM audit_log "
								+ "WHERE target_type = 'REPORT' AND target_id = ?",
						String.class, ids.get(1).toString()))
				.containsExactly("REPORT_RESOLVED " + moderator
						+ " {\"note\": \"Suspended for a week\", \"resolution\": \"ACTIONED\"}");
		assertThat(pageIds(asModerator, "OPEN")).contains(ids.get(0)).doesNotContain(ids.get(1), ids.get(2));
		var done = new ArrayList<Long>(pageIds(asModerator, "ACTIONED"));
		done.addAll(pageIds(asModerator, "DISMISSED"));
		assertThat(pageIds(asModerator, "DISMISSED")).isSortedAccordingTo((a, b) -> Long.compare(b, a));
		assertThat(done).contains(ids.get(1), ids.get(2));
	}

	/**
	 * Gives a new user a stored role, with Discord MFA on or off at their last
	 * sign-in.
	 *
	 * @param name the display name
	 * @param role {@code MODERATOR} or {@code ADMIN}
	 * @param mfa  whether MFA was on
	 * @return the user ID
	 */
	private UUID role(String name, String role, boolean mfa) {
		var userId = fixtures.user(name);
		jdbcTemplate.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
		jdbcTemplate.update("UPDATE app_user SET mfa_enabled = ? WHERE id = ?", mfa, userId);
		return userId;
	}

	/**
	 * Returns a report request by a user, with CSRF.
	 *
	 * @param reporter   the reporter
	 * @param targetType {@code EVENT} or {@code USER}
	 * @param targetId   the target's ID
	 * @param reason     the reason
	 * @param note       the note, or {@code null}
	 * @return the request
	 */
	private static MockHttpServletRequestBuilder report(UUID reporter, String targetType, UUID targetId, String reason,
			String note) {
		return post("/api/reports").with(login(reporter)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(body(targetType, targetId, reason, note));
	}

	/**
	 * Returns a report's JSON body.
	 *
	 * @param targetType {@code EVENT} or {@code USER}
	 * @param targetId   the target's ID
	 * @param reason     the reason
	 * @param note       the note, or {@code null}
	 * @return the body
	 */
	private static String body(String targetType, UUID targetId, String reason, String note) {
		return "{\"targetType\":\"" + targetType + "\",\"targetId\":\"" + targetId + "\",\"reason\":\"" + reason + "\""
				+ (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
	}

	/**
	 * Returns a resolve request, with CSRF.
	 *
	 * @param reportId   the report's ID
	 * @param resolution {@code ACTIONED} or {@code DISMISSED}
	 * @return the request
	 */
	private static MockHttpServletRequestBuilder resolve(long reportId, String resolution) {
		return post("/api/admin/reports/" + reportId + "/resolve").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"resolution\":\"" + resolution + "\"}");
	}

	/**
	 * Returns the IDs in one status of the queue, following every page.
	 *
	 * @param moderator the moderator's principal
	 * @param status    the status
	 * @return the IDs, in the queue's order
	 */
	private List<Long> pageIds(RequestPostProcessor moderator, String status) throws Exception {
		var ids = new ArrayList<Long>();
		String cursor = null;
		do {
			var page = content(
					get("/api/admin/reports?limit=50&status=" + status + (cursor == null ? "" : "&cursor=" + cursor))
							.with(moderator),
					200);
			JsonPath.<List<Number>>read(page, "$.body.items[*].id").forEach(id -> ids.add(id.longValue()));
			cursor = JsonPath.read(page, "$.body.nextCursor");
		} while (cursor != null);
		return ids;
	}

	/**
	 * Counts a user's reports.
	 *
	 * @param reporter the reporter
	 * @return the count
	 */
	private int count(UUID reporter) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM report WHERE reporter_id = ?", Integer.class,
				reporter);
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
}
