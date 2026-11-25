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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import app.confuzion.backend.TestcontainersConfiguration;
import app.confuzion.backend.service.TestBrowser;

/**
 * Integration tests for push subscriptions and the key against Postgres (D-014
 * rules 13, 14 and 18): the cap replaces the oldest, an endpoint moves to
 * whoever subscribed with it last, a caller only ever removes their own, and
 * "log out everywhere" removes them all. This context has a throwaway VAPID key
 * pair, generated at run time.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest(properties = "con-fuzion.rate-limiter.token.capacity=10000")
@Import(TestcontainersConfiguration.class)
class PushControllerIntegrationTest {
	/**
	 * The throwaway VAPID key pair: public, then private.
	 */
	private static final String[] VAPID = TestBrowser.vapidKeys();

	/**
	 * A browser's public key: an uncompressed point's length of base64url. The
	 * sender isn't run here, so it needn't be on the curve.
	 */
	private static final String P256DH = "B" + "A".repeat(86);

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
	 * The {@link MockMvc}.
	 */
	private MockMvc mockMvc;

	/**
	 * The fixtures.
	 */
	private EventFixtures fixtures;

	/**
	 * Gives this context the throwaway VAPID keys.
	 *
	 * @param registry the {@link DynamicPropertyRegistry}
	 */
	@DynamicPropertySource
	static void vapid(DynamicPropertyRegistry registry) {
		registry.add("con-fuzion.push.vapid.public-key", () -> VAPID[0]);
		registry.add("con-fuzion.push.vapid.private-key", () -> VAPID[1]);
	}

	/**
	 * Sets up {@link MockMvc} with Spring Security.
	 */
	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
		fixtures = new EventFixtures(jdbcTemplate);
	}

	/**
	 * Tests that the key is the configured public key, for a signed-in caller only.
	 *
	 * @throws Exception on failure
	 */
	@Test
	void testKeyIsTheConfiguredPublicKey() throws Exception {
		mockMvc.perform(get("/api/push/key")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/push/key").with(login(fixtures.user("Keyed")))).andExpect(status().isOk())
				.andExpect(jsonPath("$.responseType").value("PUSH_KEY"))
				.andExpect(jsonPath("$.body.publicKey").value(VAPID[0]));
	}

	/**
	 * Tests that an 11th subscription replaces the oldest, and subscribing again
	 * with a kept endpoint makes it the newest with the new keys.
	 *
	 * @throws Exception on failure
	 */
	@Test
	void testAnEleventhSubscriptionReplacesTheOldest() throws Exception {
		var me = fixtures.user("Many");
		var endpoints = new ArrayList<String>();
		for (var i = 0; i < 10; i++) {
			endpoints.add(endpoint());
			subscribe(me, endpoints.getLast(), "a".repeat(22));
		}
		subscribe(me, endpoints.getFirst(), "b".repeat(22));
		var eleventh = endpoint();
		subscribe(me, eleventh, "a".repeat(22));

		var kept = new ArrayList<>(endpoints.subList(2, 10));
		kept.add(endpoints.getFirst());
		kept.add(eleventh);
		assertThat(subscriptions(me)).extracting(row -> row.get("endpoint")).containsExactlyElementsOf(kept);
		assertThat(subscriptions(me)).filteredOn(row -> row.get("endpoint").equals(endpoints.getFirst()))
				.extracting(row -> row.get("auth")).containsExactly("b".repeat(22));
	}

	/**
	 * Tests that subscribing with an endpoint another user has moves it to the
	 * caller with the caller's keys, so the browser's previous user gets nothing
	 * more on it.
	 *
	 * @throws Exception on failure
	 */
	@Test
	void testAnEndpointBelongsToWhoeverSubscribedLast() throws Exception {
		var before = fixtures.user("Before");
		var after = fixtures.user("After");
		var shared = endpoint();
		subscribe(before, shared, "a".repeat(22));
		subscribe(after, shared, "c".repeat(22));

		assertThat(subscriptions(before)).isEmpty();
		assertThat(subscriptions(after)).extracting(row -> row.get("auth")).containsExactly("c".repeat(22));
	}

	/**
	 * Tests that unsubscribing removes only the caller's own subscription, and is a
	 * 204 for one the caller doesn't have, another user's included. Writes need the
	 * CSRF token.
	 *
	 * @throws Exception on failure
	 */
	@Test
	void testUnsubscribeRemovesOnlyTheCallersOwn() throws Exception {
		var me = fixtures.user("Mine");
		var other = fixtures.user("Theirs");
		var mine = endpoint();
		var theirs = endpoint();
		subscribe(me, mine, "a".repeat(22));
		subscribe(other, theirs, "a".repeat(22));

		mockMvc.perform(delete("/api/push/subscriptions").with(login(me)).contentType(MediaType.APPLICATION_JSON)
				.content(endpointBody(mine))).andExpect(status().isForbidden());
		for (var endpoint : List.of(theirs, mine, mine))
			mockMvc.perform(delete("/api/push/subscriptions").with(login(me)).with(csrf())
					.contentType(MediaType.APPLICATION_JSON).content(endpointBody(endpoint)))
					.andExpect(status().isNoContent());

		assertThat(subscriptions(me)).isEmpty();
		assertThat(subscriptions(other)).extracting(row -> row.get("endpoint")).containsExactly(theirs);
	}

	/**
	 * Tests that "log out everywhere" deletes every subscription the user has, and
	 * nobody else's (D-014 rule 18).
	 *
	 * @throws Exception on failure
	 */
	@Test
	void testLogoutEverywhereDeletesTheUsersSubscriptions() throws Exception {
		var me = fixtures.user("Leaving");
		var other = fixtures.user("Staying");
		subscribe(me, endpoint(), "a".repeat(22));
		subscribe(me, endpoint(), "a".repeat(22));
		subscribe(other, endpoint(), "a".repeat(22));

		mockMvc.perform(post("/api/auth/logout-everywhere").with(login(me)).with(csrf()))
				.andExpect(status().isNoContent());

		assertThat(subscriptions(me)).isEmpty();
		assertThat(subscriptions(other)).hasSize(1);
	}

	/**
	 * Subscribes a user through the API.
	 *
	 * @param userId   the user
	 * @param endpoint the endpoint
	 * @param auth     the authentication secret
	 * @throws Exception on failure
	 */
	private void subscribe(UUID userId, String endpoint, String auth) throws Exception {
		mockMvc.perform(post("/api/push/subscriptions").with(login(userId)).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"" + endpoint
						+ "\",\"keys\":{\"p256dh\":\"" + P256DH + "\",\"auth\":\"" + auth + "\"}}"))
				.andExpect(status().isNoContent());
	}

	/**
	 * Returns a user's subscriptions, oldest first.
	 *
	 * @param userId the user
	 * @return the rows
	 */
	private List<Map<String, Object>> subscriptions(UUID userId) {
		return jdbcTemplate.queryForList("SELECT endpoint, auth FROM push_subscription WHERE user_id = ? ORDER BY id",
				userId);
	}

	/**
	 * Returns a new FCM endpoint.
	 *
	 * @return the endpoint
	 */
	private static String endpoint() {
		return "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID();
	}

	/**
	 * Returns an unsubscribe body.
	 *
	 * @param endpoint the endpoint
	 * @return the JSON
	 */
	private static String endpointBody(String endpoint) {
		return "{\"endpoint\":\"" + endpoint + "\"}";
	}
}
