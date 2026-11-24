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
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import app.confuzion.backend.TestcontainersConfiguration;
import app.confuzion.backend.exception.LimitExceededException;
import app.confuzion.backend.service.FriendService;

/**
 * Integration tests for friends and invite links (D-011) against Postgres, with
 * Spring Security applied: the invite lifecycle, the one 404 for every unusable
 * token, the 20-invite cap, concurrent accepts, and unfriending.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest(properties = "con-fuzion.rate-limiter.token.capacity=10000")
@Import(TestcontainersConfiguration.class)
class FriendControllerIntegrationTest {
	/**
	 * The configured app origin's invite prefix (the default in
	 * {@code application.properties}).
	 */
	private static final String INVITE_PREFIX = "http://localhost:8080/invite/";

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
	 * The {@link FriendService}, called directly for the concurrency tests.
	 */
	@Autowired
	private FriendService friendService;

	/**
	 * The {@link MockMvc}, with the security filter chain.
	 */
	private MockMvc mockMvc;

	/**
	 * The {@link SocialFixtures}.
	 */
	private SocialFixtures fixtures;

	/**
	 * Builds the {@link MockMvc} and fixtures.
	 */
	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
		fixtures = new SocialFixtures(jdbcTemplate);
	}

	/**
	 * Tests the happy path: the link comes from the configured origin whatever the
	 * request's host headers say, only the token's hash is stored, the preview
	 * names the inviter without their ID, accepting makes both friends, and the
	 * invite then works no more.
	 */
	@Test
	void inviteLifecycle() throws Exception {
		var inviter = fixtures.user("inviter", "Inviter");
		var friend = fixtures.user("friend", null);
		var late = fixtures.user("late", null);

		var created = mockMvc
				.perform(post("/api/friends/invites").with(login(inviter)).with(csrf()).header("Host", "evil.example")
						.header("X-Forwarded-Host", "evil.example"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.responseType").value("INVITE")).andReturn()
				.getResponse().getContentAsString();
		String url = JsonPath.read(created, "$.body.url");
		assertThat(url).startsWith(INVITE_PREFIX);
		var token = url.substring(INVITE_PREFIX.length());
		assertThat(token).matches("[A-Za-z0-9_-]{22}");
		assertThat(Instant.parse(JsonPath.read(created, "$.body.expiresAt")))
				.isBetween(Instant.now().plus(Duration.ofHours(23)), Instant.now().plus(Duration.ofHours(25)));
		assertThat(jdbcTemplate.queryForObject("SELECT token_hash FROM friend_invite WHERE inviter_id = ?",
				byte[].class, inviter)).isEqualTo(SocialFixtures.sha256(token));

		mockMvc.perform(get("/api/friends/invites/" + token).with(login(friend))).andExpect(status().isOk())
				.andExpect(jsonPath("$.responseType").value("INVITE_PREVIEW"))
				.andExpect(jsonPath("$.body.inviter.displayName").value("Inviter"))
				.andExpect(jsonPath("$.body.inviter.id").doesNotExist());
		mockMvc.perform(post("/api/friends/invites/" + token + "/accept").with(login(friend)).with(csrf()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.responseType").value("PERSON"))
				.andExpect(jsonPath("$.body.id").value(inviter.toString()))
				.andExpect(jsonPath("$.body.displayName").value("Inviter"));

		assertThat(friendIds(inviter)).containsExactly(friend.toString());
		assertThat(friendIds(friend)).containsExactly(inviter.toString());
		assertThat(jdbcTemplate.queryForObject("SELECT accepted_by FROM friend_invite WHERE inviter_id = ?", UUID.class,
				inviter)).isEqualTo(friend);
		assertNotFound(get("/api/friends/invites/" + token).with(login(late)));
		assertNotFound(post("/api/friends/invites/" + token + "/accept").with(login(late)).with(csrf()));
	}

	/**
	 * Tests that every unusable token is the same 404, for both preview and accept,
	 * and that an own or blocked invite isn't used up by trying.
	 */
	@Test
	void everyUnusableTokenIsTheSame404() throws Exception {
		var inviter = fixtures.user("inviter", null);
		var me = fixtures.user("me", null);
		var blocker = fixtures.user("blocker", null);
		var blocked = fixtures.user("blocked", null);
		fixtures.block(blocker, me);
		fixtures.block(me, blocked);
		var own = fixtures.invite(me, "1 day", false);
		var fromBlocker = fixtures.invite(blocker, "1 day", false);
		var fromBlocked = fixtures.invite(blocked, "1 day", false);
		var tokens = List.of("short", "!!!!!!!!!!!!!!!!!!!!!!", "a".repeat(23), "q3Jx0nV9cY2bT8pL1mR4wA",
				fixtures.invite(inviter, "-1 hour", false), fixtures.invite(inviter, "1 day", true), own, fromBlocker,
				fromBlocked);

		var bodies = new ArrayList<String>();
		for (var token : tokens) {
			bodies.add(assertNotFound(get("/api/friends/invites/" + token).with(login(me))));
			bodies.add(assertNotFound(post("/api/friends/invites/" + token + "/accept").with(login(me)).with(csrf())));
		}
		assertThat(bodies).allMatch(body -> body.equals(bodies.getFirst()));
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM friend_invite WHERE inviter_id IN (?, ?, ?) " + "AND used_at IS NULL", Long.class,
				me, blocker, blocked)).isEqualTo(3);
		assertThat(friendIds(me)).isEmpty();
	}

	/**
	 * Tests that accepting when the two are already friends succeeds, returns the
	 * friend and uses the invite up.
	 */
	@Test
	void acceptWhenAlreadyFriendsSucceeds() throws Exception {
		var inviter = fixtures.user("inviter", null);
		var friend = fixtures.user("friend", null);
		fixtures.friends(inviter, friend);
		var token = fixtures.invite(inviter, "1 day", false);

		mockMvc.perform(post("/api/friends/invites/" + token + "/accept").with(login(friend)).with(csrf()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.body.id").value(inviter.toString()));

		assertThat(friendIds(inviter)).containsExactly(friend.toString());
		assertNotFound(post("/api/friends/invites/" + token + "/accept").with(login(friend)).with(csrf()));
	}

	/**
	 * Tests the cap: used and expired invites don't count, the 21st active one is a
	 * 429 with errorType INVITE_LIMIT, and concurrent requests can't overshoot it.
	 */
	@Test
	void activeInvitesAreCappedAtTwenty() throws Exception {
		var me = fixtures.user("capped", null);
		fixtures.invite(me, "-1 hour", false);
		fixtures.invite(me, "1 day", true);

		var results = concurrently(30, i -> friendService.createInvite(me));

		assertThat(results.stream().filter(r -> r == null).count()).isEqualTo(FriendService.MAX_ACTIVE_INVITES);
		assertThat(results).filteredOn(r -> r != null).allMatch(LimitExceededException.class::isInstance);
		mockMvc.perform(post("/api/friends/invites").with(login(me)).with(csrf()))
				.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.responseType").value("ERROR"))
				.andExpect(jsonPath("$.body.errorType").value("INVITE_LIMIT"));
	}

	/**
	 * Tests that of many concurrent accepts of one invite exactly one wins, and
	 * that two people accepting each other's invites at once make one friendship.
	 */
	@Test
	void concurrentAcceptsMakeExactlyOneFriendship() throws Exception {
		var inviter = fixtures.user("popular", null);
		var token = fixtures.invite(inviter, "1 day", false);
		var acceptors = new ArrayList<UUID>();
		for (var i = 0; i < 8; i++)
			acceptors.add(fixtures.user("eager" + i, null));

		var results = concurrently(acceptors.size(), i -> friendService.acceptInvite(acceptors.get(i), token));

		assertThat(results.stream().filter(r -> r == null).count()).isEqualTo(1);
		assertThat(results).filteredOn(r -> r != null).allMatch(NoSuchElementException.class::isInstance);
		assertThat(friendIds(inviter)).hasSize(1);

		// Repeated, because the deadlock this guards against needs both claims to land
		// before either lock.
		for (var round = 0; round < 10; round++) {
			var a = fixtures.user("mutual_a", null);
			var b = fixtures.user("mutual_b", null);
			var fromA = fixtures.invite(a, "1 day", false);
			var fromB = fixtures.invite(b, "1 day", false);
			var mutual = concurrently(2, i -> friendService.acceptInvite(i == 0 ? a : b, i == 0 ? fromB : fromA));
			assertThat(mutual).containsOnlyNulls();
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM friendship WHERE user_a IN (?, ?)", Long.class,
					a, b)).isEqualTo(1);
		}
	}

	/**
	 * Tests that unfriending ends the friendship for both and removes each from the
	 * other's circles, leaving other members, and that it is a 204 whether or not
	 * there was a friendship or a user.
	 */
	@Test
	void unfriendRemovesEachFromTheOthersCircles() throws Exception {
		var me = fixtures.user("me", null);
		var friend = fixtures.user("friend", null);
		var other = fixtures.user("other", null);
		fixtures.friends(me, friend);
		fixtures.friends(me, other);
		var myCircle = fixtures.circle(me, friend, other);
		var theirCircle = fixtures.circle(friend, me);

		mockMvc.perform(delete("/api/friends/" + friend).with(login(me)).with(csrf()))
				.andExpect(status().isNoContent());

		assertThat(fixtures.areFriends(me, friend)).isFalse();
		assertThat(fixtures.areFriends(me, other)).isTrue();
		assertThat(fixtures.members(myCircle)).containsExactly(other);
		assertThat(fixtures.members(theirCircle)).isEmpty();
		mockMvc.perform(delete("/api/friends/" + friend).with(login(me)).with(csrf()))
				.andExpect(status().isNoContent());
		mockMvc.perform(delete("/api/friends/" + UUID.randomUUID()).with(login(me)).with(csrf()))
				.andExpect(status().isNoContent());
	}

	/**
	 * Tests that removing someone who isn't a friend leaves circles alone.
	 */
	@Test
	void unfriendingANonFriendChangesNothing() throws Exception {
		var me = fixtures.user("me", null);
		var stranger = fixtures.user("stranger", null);
		var myCircle = fixtures.circle(me, stranger);

		mockMvc.perform(delete("/api/friends/" + stranger).with(login(me)).with(csrf()))
				.andExpect(status().isNoContent());

		assertThat(fixtures.members(myCircle)).containsExactly(stranger);
	}

	/**
	 * Tests that the friends list is sorted by display name, ignoring case, and
	 * falls back to the username.
	 */
	@Test
	void friendsAreSortedByDisplayName() throws Exception {
		var me = fixtures.user("me", null);
		var zed = fixtures.user("aaa", "zed");
		var bob = fixtures.user("bob", null);
		var amy = fixtures.user("zzz", "Amy");
		fixtures.user("stranger", "Aaron");
		for (var friend : List.of(zed, bob, amy))
			fixtures.friends(me, friend);

		mockMvc.perform(get("/api/friends").with(login(me))).andExpect(status().isOk())
				.andExpect(jsonPath("$.responseType").value("PERSON_LIST"))
				.andExpect(jsonPath("$.body.people[*].displayName", contains("Amy", "bob", "zed")))
				.andExpect(jsonPath("$.body.people[0].avatarUrl")
						.value(startsWith("https://cdn.discordapp.com/embed/avatars/")));
	}

	/**
	 * Returns a user's friends' IDs.
	 *
	 * @param userId the user
	 * @return the IDs
	 */
	private List<String> friendIds(UUID userId) throws Exception {
		return JsonPath.read(
				mockMvc.perform(get("/api/friends").with(login(userId))).andReturn().getResponse().getContentAsString(),
				"$.body.people[*].id");
	}

	/**
	 * Performs a request, asserts the generic 404 envelope, and returns the body
	 * without its correlation ID.
	 *
	 * @param request the request
	 * @return the body with the correlation ID blanked
	 */
	private String assertNotFound(MockHttpServletRequestBuilder request) throws Exception {
		var body = mockMvc.perform(request).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.responseType").value("ERROR"))
				.andExpect(jsonPath("$.body.errorType").value("NOT_FOUND")).andReturn().getResponse()
				.getContentAsString();
		return body.replace(JsonPath.<String>read(body, "$.body.correlationId"), "");
	}

	/**
	 * Runs a task on many threads at once, each with its own index.
	 *
	 * @param threads how many
	 * @param task    the task
	 * @return each run's exception, or {@code null} when it succeeded
	 */
	private static List<Throwable> concurrently(int threads, IndexedTask task) throws Exception {
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(threads)) {
			var futures = new ArrayList<Future<Throwable>>();
			for (var t = 0; t < threads; t++) {
				var index = t;
				futures.add(executor.submit(() -> {
					start.await();
					try {
						task.run(index);
						return null;
					} catch (Exception e) {
						return e;
					}
				}));
			}
			start.countDown();
			var results = new ArrayList<Throwable>();
			for (var future : futures)
				results.add(future.get());
			return results;
		}
	}

	/**
	 * A task that gets its thread's index.
	 *
	 * @author mcawful
	 */
	@FunctionalInterface
	private interface IndexedTask {
		/**
		 * Runs the task.
		 *
		 * @param index the thread's index
		 * @return ignored
		 * @throws Exception when the task fails
		 */
		Object run(int index) throws Exception;
	}
}
