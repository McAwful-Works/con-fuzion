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

import static app.confuzion.backend.controller.ReportControllerIntegrationTest.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import app.confuzion.backend.service.RsvpService;

/**
 * Integration tests for user search, suspensions and bans (D-015) against
 * Postgres: what search returns and how it matches, who can suspend whom, and
 * what a ban does to the user's events and RSVPs. That sessions end at once is
 * tested over real sessions in {@code SecurityConfigIntegrationTest}, and bans
 * racing joins in {@code RsvpConcurrencyIntegrationTest}.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AdminUserControllerIntegrationTest {
	/**
	 * The fields {@code AdminUserResponse} has, exactly the owner's list.
	 */
	private static final List<String> FIELDS = List.of("id", "discordId", "username", "globalName", "createdAt",
			"lastLoginAt", "suspendedUntil", "ban", "reportCount");

	/**
	 * The {@link JdbcTemplate}.
	 */
	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * The {@link WebApplicationContext}.
	 */
	@Autowired
	private WebApplicationContext webApplicationContext;

	/**
	 * The {@link RsvpService}, for a banned user's join.
	 */
	@Autowired
	private RsvpService rsvpService;

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
	 * Tests the search: it matches an exact user ID or Discord ID, or part of a
	 * username or display name ignoring case, with {@code %} and {@code _} taken
	 * literally, by username. Each match has exactly the fields D-015 lists, never
	 * servers, friends, circles or RSVPs, with the reports naming them and any ban,
	 * and the searcher's own report count reads as 0.
	 */
	@Test
	void searchReturnsOnlyWhatModeratorsMaySee() throws Exception {
		var prefix = "s" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
		var moderator = fixtures.social().user(prefix + "_mod", null);
		grant(moderator, "MODERATOR");
		var target = fixtures.social().user(prefix + "_b", "Wild 100%!");
		var other = fixtures.social().user(prefix + "xa", "Tame 1000");
		var guildId = fixtures.newGuildId();
		fixtures.guild(target, guildId, true);
		fixtures.friends(target, other);
		fixtures.circle(target, other);
		var event = fixtures.rsvpEvent(other, 5, "now() + interval '1 day'", "NULL");
		jdbcTemplate.update("INSERT INTO rsvp (event_id, user_id, status) VALUES (?, ?, 'CONFIRMED')", event.id(),
				target);
		report(other, target);
		report(moderator, target);
		report(target, moderator);
		jdbcTemplate.update("INSERT INTO banned_account (discord_id, reason) VALUES (?, 'Spam')", discordId(target));
		var asModerator = as(moderator, "ROLE_MODERATOR");

		var byName = search(asModerator, prefix.toUpperCase());
		assertThat(JsonPath.<List<String>>read(byName, "$.body.items[*].username")).containsExactly(prefix + "_b",
				prefix + "_mod", prefix + "xa");
		var found = JsonPath.<Map<String, Object>>read(byName, "$.body.items[0]");
		assertThat(found).containsOnlyKeys(FIELDS);
		assertThat(found).containsEntry("id", target.toString())
				.containsEntry("discordId", Long.toString(discordId(target))).containsEntry("globalName", "Wild 100%!")
				.containsEntry("suspendedUntil", null).containsEntry("reportCount", 2);
		assertThat(JsonPath.<String>read(byName, "$.body.items[0].ban.reason")).isEqualTo("Spam");
		assertThat(JsonPath.<Integer>read(byName, "$.body.items[1].reportCount")).isZero();
		assertThat(JsonPath.<Object>read(byName, "$.body.items[2].ban")).isNull();

		// As wildcards, "100%" would match "Tame 1000" too, and "_" would match "x";
		// "!" is the escape character itself.
		assertThat(usernames(search(asModerator, "0%!"))).contains(prefix + "_b").doesNotContain(prefix + "xa");
		assertThat(usernames(search(asModerator, "100%"))).contains(prefix + "_b").doesNotContain(prefix + "xa");
		assertThat(usernames(search(asModerator, prefix + "_"))).containsExactly(prefix + "_b", prefix + "_mod");
		assertThat(usernames(search(asModerator, target.toString()))).containsExactly(prefix + "_b");
		assertThat(usernames(search(asModerator, Long.toString(discordId(other))))).containsExactly(prefix + "xa");
		assertThat(usernames(search(asModerator, "99999999999999999999"))).isEmpty();
	}

	/**
	 * Tests that a search returns at most 20 users (D-015 rule 13), the first by
	 * username.
	 */
	@Test
	void searchReturnsAtMostTwenty() throws Exception {
		var prefix = "c" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
		for (var i = 20; i >= 0; i--)
			fixtures.social().user(prefix + "_%02d".formatted(i), null);

		var found = usernames(search(as(user("MODERATOR"), "ROLE_MODERATOR"), prefix));
		assertThat(found).hasSize(20).first().isEqualTo(prefix + "_00");
		assertThat(found).last().isEqualTo(prefix + "_19");
	}

	/**
	 * Tests who can be suspended (D-015 rule 4): a moderator only users with no
	 * granted role, an admin moderators too, and nobody an admin or themself (409
	 * {@code TARGET_HAS_ROLE}). A suspension sets the end and is audit-logged with
	 * its session purge, while the user's RSVPs stay. The same end again writes
	 * nothing; a new one replaces it. Lifting it clears it, once.
	 */
	@Test
	void suspensionRules() throws Exception {
		var moderator = user("MODERATOR");
		var admin = user("ADMIN");
		var otherModerator = user("MODERATOR");
		var target = fixtures.user("Target");
		var hosted = fixtures.event(target);
		var event = fixtures.rsvpEvent(fixtures.user("Host"), 5, "now() + interval '1 day'", "NULL");
		jdbcTemplate.update("INSERT INTO rsvp (event_id, user_id, status) VALUES (?, ?, 'CONFIRMED')", event.id(),
				target);
		var asModerator = as(moderator, "ROLE_MODERATOR");
		var asAdmin = as(admin, "ROLE_ADMIN");
		var until = Instant.now().plus(Duration.ofDays(7)).truncatedTo(ChronoUnit.MICROS);

		assertThat(status(suspend(asModerator, target, until))).isEqualTo(204);
		assertThat(suspendedUntil(target)).isEqualTo(until);
		assertThat(fixtures.rsvpStatus(event.id(), target)).isEqualTo("CONFIRMED");
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM event WHERE id = ?", String.class, hosted.id()))
				.isEqualTo("PUBLISHED");
		assertThat(audit(target)).containsExactly(
				"USER_SUSPENDED " + moderator + " {\"until\": \"" + until + "\", \"reason\": \"Spam\"}",
				"SESSIONS_PURGED " + moderator + " {\"cause\": \"USER_SUSPENDED\", \"sessions\": 0}");
		assertThat(status(suspend(asModerator, target, until))).isEqualTo(204);
		assertThat(audit(target)).hasSize(2);
		var later = until.plus(Duration.ofDays(1));
		assertThat(status(suspend(asModerator, target, later))).isEqualTo(204);
		assertThat(suspendedUntil(target)).isEqualTo(later);
		assertThat(audit(target)).hasSize(4);

		assertThat(errorType(suspend(asModerator, otherModerator, until), 409)).isEqualTo("TARGET_HAS_ROLE");
		assertThat(errorType(suspend(asModerator, admin, until), 409)).isEqualTo("TARGET_HAS_ROLE");
		assertThat(errorType(suspend(asModerator, moderator, until), 409)).isEqualTo("TARGET_HAS_ROLE");
		assertThat(errorType(suspend(asAdmin, admin, until), 409)).isEqualTo("TARGET_HAS_ROLE");
		assertThat(status(suspend(asAdmin, otherModerator, until))).isEqualTo(204);
		assertThat(errorType(suspend(asAdmin, target, Instant.now().plus(Duration.ofDays(366))), 400))
				.isEqualTo("BAD_REQUEST");
		assertThat(errorType(suspend(asAdmin, UUID.randomUUID(), until), 404)).isEqualTo("NOT_FOUND");
		// A session that still says MODERATOR after the role was revoked.
		var revoked = fixtures.user("Revoked");
		assertThat(errorType(suspend(as(revoked, "ROLE_MODERATOR"), target, until), 403)).isEqualTo("FORBIDDEN");
		assertThat(suspendedUntil(otherModerator)).isEqualTo(until);
		assertThat(errorType(
				post("/api/admin/users/" + target + "/unsuspend").with(as(revoked, "ROLE_MODERATOR")).with(csrf()),
				403)).isEqualTo("FORBIDDEN");
		assertThat(suspendedUntil(target)).isEqualTo(later);
		assertThat(audit(admin)).isEmpty();

		assertThat(status(post("/api/admin/users/" + target + "/unsuspend").with(asModerator).with(csrf())))
				.isEqualTo(204);
		assertThat(suspendedUntil(target)).isNull();
		assertThat(audit(target)).hasSize(5).last().isEqualTo("USER_UNSUSPENDED " + moderator + " {}");
		assertThat(status(post("/api/admin/users/" + target + "/unsuspend").with(asModerator).with(csrf())))
				.isEqualTo(204);
		assertThat(audit(target)).hasSize(5);
	}

	/**
	 * Tests what a ban does (D-015): it cancels the user's upcoming hosted events,
	 * so attendees get {@code EVENT_CANCELLED}, leaving ended ones alone; it ends
	 * their RSVPs on other events as {@code LEFT}, offering each freed spot to the
	 * next person in line; and it stops them joining again. It is audit-logged with
	 * its session purge. Banning again writes nothing, and unbanning removes only
	 * the ban, once.
	 */
	@Test
	void banCancelsHostedEventsAndEndsRsvps() throws Exception {
		var admin = user("ADMIN");
		var asAdmin = as(admin, "ROLE_ADMIN");
		var banned = fixtures.user("Banned");
		var attendee = fixtures.user("Attendee");
		var hosted = fixtures.rsvpEvent(banned, 5, "now() + interval '1 day'", "NULL");
		join(hosted.id(), attendee, "CONFIRMED");
		var ended = fixtures.event(banned, "now() - interval '1 day'", "now() - interval '20 hours'");
		var host = fixtures.user("Host");
		var full = fixtures.rsvpEvent(host, 2, "now() + interval '1 day'", "NULL");
		join(full.id(), banned, "CONFIRMED");
		var waiting = fixtures.user("Waiting");
		join(full.id(), waiting, "WAITLISTED");
		var open = fixtures.rsvpEvent(host, 5, "now() + interval '1 day'", "NULL");
		fixtures.audience(open.id(), "USER", null, null, banned);
		var past = fixtures.event(host, "now() - interval '1 day'", "now() - interval '20 hours'");
		join(past.id(), banned, "CONFIRMED");
		var discordId = Long.toString(discordId(banned));

		assertThat(status(ban(asAdmin, discordId))).isEqualTo(204);

		assertThat(eventStatus(hosted.id())).isEqualTo("CANCELLED");
		assertThat(eventStatus(ended.id())).isEqualTo("PUBLISHED");
		assertThat(fixtures.rsvpStatus(hosted.id(), banned)).isEqualTo("CONFIRMED");
		assertThat(jdbcTemplate.queryForList("SELECT type FROM notification WHERE user_id = ? AND event_id = ?",
				String.class, attendee, hosted.id())).containsExactly("EVENT_CANCELLED");
		assertThat(fixtures.rsvpStatus(full.id(), banned)).isEqualTo("LEFT");
		assertThat(fixtures.rsvpStatus(full.id(), waiting)).isEqualTo("OFFERED");
		assertThat(fixtures.confirmedCount(full.id())).isEqualTo(2);
		assertThat(fixtures.rsvpStatus(past.id(), banned)).as("history").isEqualTo("CONFIRMED");
		assertThatThrownBy(() -> rsvpService.join(banned, open.publicId())).isInstanceOf(NoSuchElementException.class);
		assertThat(fixtures.rsvpStatus(open.id(), banned)).isNull();
		assertThat(audit(discordId)).containsExactly("ACCOUNT_BANNED " + admin + " {\"reason\": \"Spam\"}");
		assertThat(audit(banned.toString()))
				.containsExactly("SESSIONS_PURGED " + admin + " {\"cause\": \"ACCOUNT_BANNED\", \"sessions\": 0}");

		assertThat(status(ban(asAdmin, discordId))).isEqualTo(204);
		assertThat(audit(discordId)).hasSize(1);
		assertThat(status(delete("/api/admin/bans/" + discordId).with(asAdmin).with(csrf()))).isEqualTo(204);
		assertThat(bans(discordId)).isZero();
		assertThat(eventStatus(hosted.id())).isEqualTo("CANCELLED");
		assertThat(audit(discordId)).hasSize(2).last().isEqualTo("ACCOUNT_UNBANNED " + admin + " {}");
		assertThat(status(delete("/api/admin/bans/" + discordId).with(asAdmin).with(csrf()))).isEqualTo(204);
		assertThat(audit(discordId)).hasSize(2);
	}

	/**
	 * Tests the bans that change nothing about an account: a Discord ID with none
	 * is just banned (D-015 rule 4); an account holding ADMIN, the actor's own
	 * included, is a 409 {@code TARGET_HAS_ROLE}; an admin whose role was revoked
	 * is refused; and an ID past the range of a Discord ID is a 400. The refused
	 * ones write nothing.
	 */
	@Test
	void banWithoutAnAccountAndRefusedBans() throws Exception {
		var admin = user("ADMIN");
		var asAdmin = as(admin, "ROLE_ADMIN");
		var noAccount = Long.toString(ThreadLocalRandom.current().nextLong(1L << 60, Long.MAX_VALUE));

		assertThat(status(ban(asAdmin, noAccount))).isEqualTo(204);
		assertThat(bans(noAccount)).isOne();
		assertThat(audit(noAccount)).containsExactly("ACCOUNT_BANNED " + admin + " {\"reason\": \"Spam\"}");

		var otherAdmin = Long.toString(discordId(user("ADMIN")));
		for (var refused : List.of(otherAdmin, Long.toString(discordId(admin)))) {
			assertThat(errorType(ban(asAdmin, refused), 409)).isEqualTo("TARGET_HAS_ROLE");
			assertThat(bans(refused)).isZero();
			assertThat(audit(refused)).isEmpty();
		}
		var revoked = fixtures.user("Revoked");
		var plain = Long.toString(discordId(fixtures.user("Plain")));
		assertThat(errorType(ban(as(revoked, "ROLE_ADMIN"), plain), 403)).isEqualTo("FORBIDDEN");
		assertThat(errorType(delete("/api/admin/bans/" + noAccount).with(as(revoked, "ROLE_ADMIN")).with(csrf()), 403))
				.isEqualTo("FORBIDDEN");
		assertThat(bans(plain)).isZero();
		assertThat(bans(noAccount)).isOne();
		assertThat(errorType(ban(asAdmin, "9999999999999999999"), 400)).isEqualTo("BAD_REQUEST");
	}

	/**
	 * Creates a user with a stored role and MFA on.
	 *
	 * @param role {@code MODERATOR} or {@code ADMIN}
	 * @return the user ID
	 */
	private UUID user(String role) {
		var userId = fixtures.user(role);
		grant(userId, role);
		return userId;
	}

	/**
	 * Grants a stored role.
	 *
	 * @param userId the user ID
	 * @param role   {@code MODERATOR} or {@code ADMIN}
	 */
	private void grant(UUID userId, String role) {
		jdbcTemplate.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)", userId, role);
		jdbcTemplate.update("UPDATE app_user SET mfa_enabled = true WHERE id = ?", userId);
	}

	/**
	 * Files an open report on a user.
	 *
	 * @param reporter the reporter
	 * @param target   the reported user
	 */
	private void report(UUID reporter, UUID target) {
		jdbcTemplate.update(
				"INSERT INTO report (reporter_id, target_type, target_id, reason) VALUES (?, 'USER', ?, 'SPAM')",
				reporter, target);
	}

	/**
	 * Searches as a moderator.
	 *
	 * @param principal the moderator
	 * @param q         the search text
	 * @return the body
	 */
	private String search(OAuth2LoginRequestPostProcessor principal, String q) throws Exception {
		var response = mockMvc.perform(get("/api/admin/users").param("q", q).with(principal)).andReturn().getResponse();
		assertThat(response.getStatus()).isEqualTo(200);
		return response.getContentAsString();
	}

	/**
	 * Returns the usernames a search found.
	 *
	 * @param body the search's body
	 * @return the usernames, in order
	 */
	private static List<String> usernames(String body) {
		return JsonPath.read(body, "$.body.items[*].username");
	}

	/**
	 * Returns a suspend request, with CSRF.
	 *
	 * @param principal the moderator or admin
	 * @param userId    the user to suspend
	 * @param until     when it ends
	 * @return the request
	 */
	private static MockHttpServletRequestBuilder suspend(OAuth2LoginRequestPostProcessor principal, UUID userId,
			Instant until) {
		return post("/api/admin/users/" + userId + "/suspend").with(principal).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"until\":\"" + until + "\",\"reason\":\"Spam\"}");
	}

	/**
	 * Adds an RSVP, counting a held spot.
	 *
	 * @param eventId the event's internal ID
	 * @param userId  the user
	 * @param status  {@code CONFIRMED} or {@code WAITLISTED}
	 */
	private void join(long eventId, UUID userId, String status) {
		jdbcTemplate.update("INSERT INTO rsvp (event_id, user_id, status) VALUES (?, ?, ?)", eventId, userId, status);
		if (status.equals("CONFIRMED"))
			jdbcTemplate.update("UPDATE event SET confirmed_count = confirmed_count + 1 WHERE id = ?", eventId);
	}

	/**
	 * Returns a ban request, with CSRF.
	 *
	 * @param principal the admin
	 * @param discordId the Discord ID to ban
	 * @return the request
	 */
	private static MockHttpServletRequestBuilder ban(OAuth2LoginRequestPostProcessor principal, String discordId) {
		return post("/api/admin/bans").with(principal).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"discordId\":\"" + discordId + "\",\"reason\":\"Spam\"}");
	}

	/**
	 * Returns an event's status.
	 *
	 * @param eventId the event's internal ID
	 * @return the status
	 */
	private String eventStatus(long eventId) {
		return jdbcTemplate.queryForObject("SELECT status FROM event WHERE id = ?", String.class, eventId);
	}

	/**
	 * Counts the bans on a Discord ID.
	 *
	 * @param discordId the Discord ID
	 * @return 0 or 1
	 */
	private long bans(String discordId) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM banned_account WHERE discord_id = ?", Long.class,
				Long.parseLong(discordId));
	}

	/**
	 * Returns a user's Discord ID.
	 *
	 * @param userId the user ID
	 * @return the Discord ID
	 */
	private long discordId(UUID userId) {
		return jdbcTemplate.queryForObject("SELECT discord_id FROM app_user WHERE id = ?", Long.class, userId);
	}

	/**
	 * Returns when a user's suspension ends.
	 *
	 * @param userId the user ID
	 * @return the end, or {@code null}
	 */
	private Instant suspendedUntil(UUID userId) {
		var until = jdbcTemplate.queryForObject("SELECT suspended_until FROM app_user WHERE id = ?", Timestamp.class,
				userId);
		return until == null ? null : until.toInstant();
	}

	/**
	 * Returns the audit rows about a target, oldest first.
	 *
	 * @param targetId the target ID
	 * @return each row's action, actor and details
	 */
	private List<String> audit(Object targetId) {
		return jdbcTemplate.queryForList(
				"SELECT action || ' ' || actor_id || ' ' || details::text FROM audit_log WHERE target_id = ? ORDER BY id",
				String.class, targetId.toString());
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
	 * Performs a request, checks its status and returns its error type.
	 *
	 * @param request the request
	 * @param status  the expected status
	 * @return the error type
	 */
	private String errorType(MockHttpServletRequestBuilder request, int status) throws Exception {
		var response = mockMvc.perform(request).andReturn().getResponse();
		assertThat(response.getStatus()).isEqualTo(status);
		return JsonPath.read(response.getContentAsString(), "$.body.errorType");
	}
}
