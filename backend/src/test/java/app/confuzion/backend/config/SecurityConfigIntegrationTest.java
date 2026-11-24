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
package app.confuzion.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.jayway.jsonpath.JsonPath;

import app.confuzion.backend.DiscordStub;
import app.confuzion.backend.TestcontainersConfiguration;

/**
 * Drives the real sign-in flow over HTTP against a {@link DiscordStub}: the
 * PKCE authorization redirect, the callback, the user upsert and guild sync,
 * session rotation, CSRF, logout, the 401/403 envelopes and the security
 * headers on both the API and the SPA.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
		"con-fuzion.admin.bootstrap-discord-ids=" + SecurityConfigIntegrationTest.ADMIN_ID,
		"con-fuzion.rate-limiter.token.capacity=10000" })
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityConfigIntegrationTest {
	/**
	 * A bootstrap admin's Discord ID.
	 */
	static final String ADMIN_ID = "100000000000000001";

	/**
	 * A valid event body, starting a week from now.
	 */
	private static final String EVENT_BODY = "{\"title\":\"Photo walk\",\"location\":\"Hall B\",\"startsAt\":\""
			+ Instant.now().plus(Duration.ofDays(7)) + "\",\"timeZone\":\"America/Chicago\","
			+ "\"locationVisibility\":\"VIEWERS\",\"audience\":[{\"type\":\"FRIENDS\"}]}";

	/**
	 * One valid request per social-graph (D-011) and event operation, with the
	 * status a signed-in user with no friends, circles, blocks or events gets, so
	 * the security chain is checked for every one of them. The circle, user, event
	 * and token are unknown. Event endpoints are still contract stubs (501).
	 */
	private static final List<Endpoint> ENDPOINTS = List.of(new Endpoint("POST", "/api/friends/invites", null, 201),
			new Endpoint("GET", "/api/friends/invites/q3Jx0nV9cY2bT8pL1mR4wA", null, 404),
			new Endpoint("POST", "/api/friends/invites/q3Jx0nV9cY2bT8pL1mR4wA/accept", null, 404),
			new Endpoint("GET", "/api/friends", null, 200),
			new Endpoint("DELETE", "/api/friends/3f2c9a4e-6d1b-4f7a-9c3e-2b8d5e1a7f60", null, 204),
			new Endpoint("GET", "/api/people?q=con", null, 200), new Endpoint("GET", "/api/circles", null, 200),
			new Endpoint("POST", "/api/circles", "{\"name\":\"Crew\"}", 201),
			new Endpoint("GET", "/api/circles/3f2c9a4e-6d1b-4f7a-9c3e-2b8d5e1a7f60", null, 404),
			new Endpoint("PATCH", "/api/circles/3f2c9a4e-6d1b-4f7a-9c3e-2b8d5e1a7f60", "{\"name\":\"Crew\"}", 404),
			new Endpoint("DELETE", "/api/circles/3f2c9a4e-6d1b-4f7a-9c3e-2b8d5e1a7f60", null, 404),
			new Endpoint("PUT",
					"/api/circles/3f2c9a4e-6d1b-4f7a-9c3e-2b8d5e1a7f60/members/5b1d0c7e-2a4f-4e6b-8d9c-1f3a5e7b9d20",
					null, 404),
			new Endpoint("DELETE",
					"/api/circles/3f2c9a4e-6d1b-4f7a-9c3e-2b8d5e1a7f60/members/5b1d0c7e-2a4f-4e6b-8d9c-1f3a5e7b9d20",
					null, 404),
			new Endpoint("GET", "/api/blocks", null, 200),
			new Endpoint("PUT", "/api/blocks/5b1d0c7e-2a4f-4e6b-8d9c-1f3a5e7b9d20", null, 204),
			new Endpoint("DELETE", "/api/blocks/5b1d0c7e-2a4f-4e6b-8d9c-1f3a5e7b9d20", null, 204),
			new Endpoint("POST", "/api/events", EVENT_BODY, 501),
			new Endpoint("GET", "/api/events?scope=hosting", null, 501),
			new Endpoint("GET", "/api/events/3f2c9a4e-6d1b-4f7a-9c3e-2b8d5e1a7f60", null, 501),
			new Endpoint("PATCH", "/api/events/3f2c9a4e-6d1b-4f7a-9c3e-2b8d5e1a7f60", EVENT_BODY, 501),
			new Endpoint("DELETE", "/api/events/3f2c9a4e-6d1b-4f7a-9c3e-2b8d5e1a7f60", null, 501));

	/**
	 * The stub Discord.
	 */
	private static final DiscordStub DISCORD = new DiscordStub();

	/**
	 * The Valkey key prefix of a session hash (the default namespace).
	 */
	private static final String SESSION_KEY_PREFIX = "spring:session:sessions:";

	/**
	 * The {@link HttpClient}. It never follows redirects.
	 */
	private final HttpClient http = HttpClient.newHttpClient();

	/**
	 * The app's port.
	 */
	@LocalServerPort
	private int port;

	/**
	 * The Valkey connection factory, to inspect stored sessions.
	 */
	@Autowired
	private RedisConnectionFactory redisConnectionFactory;

	/**
	 * The session repository, to stand in for an in-flight request.
	 */
	@Autowired
	private RedisIndexedSessionRepository sessionRepository;

	/**
	 * Points the Discord provider at the stub.
	 *
	 * @param registry the {@link DynamicPropertyRegistry}
	 */
	@DynamicPropertySource
	static void discord(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.client.provider.discord.token-uri", DISCORD::tokenUri);
		registry.add("spring.security.oauth2.client.provider.discord.user-info-uri", DISCORD::userInfoUri);
	}

	/**
	 * Stops the stub.
	 */
	@AfterAll
	void stopDiscord() {
		DISCORD.close();
	}

	/**
	 * Tests that an unauthenticated API call is a 401 in the error envelope, not a
	 * redirect, and doesn't create a session.
	 */
	@Test
	void unauthenticatedApiRequestIs401Envelope() throws Exception {
		var response = send("GET", "/api/me", null, null);

		assertError(response, 401, "UNAUTHORIZED");
		assertThat(response.headers().firstValue("Location")).isEmpty();
		assertThat(cookie(response, "SESSION")).isNull();
	}

	/**
	 * Tests the security headers on an API response, the SPA shell, a deep link and
	 * a hashed asset (which keeps its long-lived cache header), that the SPA
	 * answers HEAD, and that the API docs need a session.
	 */
	@Test
	void securityHeadersOnApiAndSpa() throws Exception {
		var spa = send("GET", "/", null, null);
		assertThat(spa.statusCode()).isEqualTo(200);
		assertThat(spa.body()).contains("test-spa-shell");

		var asset = send("GET", "/assets/app-abc123.js", null, null);
		assertThat(asset.statusCode()).isEqualTo(200);
		assertThat(asset.headers().firstValue("Cache-Control")).hasValue("max-age=31536000, public, immutable");
		assertThat(send("HEAD", "/onboarding", null, null).statusCode()).isEqualTo(200);
		assertThat(send("GET", "/v3/api-docs", null, null).statusCode()).isEqualTo(401);

		for (var response : List.of(send("GET", "/api/me", null, null), spa, send("GET", "/onboarding", null, null),
				asset)) {
			var headers = response.headers();
			assertThat(headers.firstValue("Content-Security-Policy")).hasValue(SecurityConfig.CONTENT_SECURITY_POLICY);
			assertThat(headers.firstValue("Strict-Transport-Security"))
					.hasValue("max-age=31536000 ; includeSubDomains");
			assertThat(headers.firstValue("X-Content-Type-Options")).hasValue("nosniff");
			assertThat(headers.firstValue("Referrer-Policy")).hasValue("strict-origin-when-cross-origin");
			assertThat(headers.firstValue("Permissions-Policy")).hasValue(SecurityConfig.PERMISSIONS_POLICY);
			assertThat(headers.firstValue("X-Frame-Options")).hasValue("DENY");
		}
	}

	/**
	 * Tests the full first and repeat sign-in: PKCE and scopes on the redirect, the
	 * code verifier on the token exchange, session rotation, the onboarding
	 * redirect, the stored profile and servers, and that no Discord token reaches
	 * Valkey.
	 */
	@Test
	void signInCreatesUserSyncsGuildsAndRotatesSession() throws Exception {
		DISCORD.reset("100000000000000010", "first_timer", false, "200000000000000001", "Beta Con",
				"200000000000000002", "Alpha Con");

		var login = login();
		var authorize = login.authorizeParams();
		assertThat(login.authorizeUri()).startsWith("https://discord.com/oauth2/authorize?");
		assertThat(authorize).containsEntry("scope", "identify guilds").containsEntry("response_type", "code")
				.containsEntry("code_challenge_method", "S256").containsKeys("state", "code_challenge")
				.containsEntry("redirect_uri", "http://127.0.0.1:" + port + "/api/login/oauth2/code/discord");
		assertThat(DISCORD.lastTokenRequest()).containsKey("code_verifier").containsEntry("code", "stub-code")
				.containsEntry("grant_type", "authorization_code");
		assertThat(login.location()).isEqualTo("/onboarding");
		assertThat(login.session()).isNotNull().isNotEqualTo(login.preLoginSession());

		var me = send("GET", "/api/me", login.session(), null);
		assertThat(me.statusCode()).isEqualTo(200);
		assertThat(JsonPath.<String>read(me.body(), "$.responseType")).isEqualTo("ME");
		assertThat(JsonPath.<String>read(me.body(), "$.body.username")).isEqualTo("first_timer");
		assertThat(JsonPath.<List<String>>read(me.body(), "$.body.roles")).containsExactly("USER");
		assertThat(JsonPath.<String>read(me.body(), "$.body.avatarUrl"))
				.matches("https://cdn\\.discordapp\\.com/embed/avatars/[0-5]\\.png");

		var guilds = send("GET", "/api/me/guilds", login.session(), null);
		assertThat(JsonPath.<List<String>>read(guilds.body(), "$.body.guilds[*].name")).containsExactly("Alpha Con",
				"Beta Con");
		assertThat(JsonPath.<List<Boolean>>read(guilds.body(), "$.body.guilds[*].discoverable")).containsOnly(false);

		var again = login();
		assertThat(again.location()).isEqualTo("/");
		assertThat(sessionStoreContainsAny(DISCORD.issuedTokens())).isFalse();
	}

	/**
	 * Tests that a {@code 429} on the guild list is retried once, and that past
	 * that the login still succeeds with the last known servers.
	 */
	@Test
	void guildRateLimitIsRetriedOnceThenLastKnownGuildsKept() throws Exception {
		DISCORD.reset("100000000000000020", "rate_limited", false, "200000000000000021", "Kept Con");
		login();

		DISCORD.setGuilds("200000000000000022", "New Con");
		DISCORD.queueGuildRateLimit(0.01);
		var retried = login();
		assertThat(DISCORD.guildCalls()).isEqualTo(3);
		assertThat(names(retried.session())).containsExactly("New Con");

		DISCORD.setGuilds("200000000000000023", "Never Seen Con");
		DISCORD.queueGuildRateLimit(0.01);
		DISCORD.queueGuildRateLimit(0.01);
		var kept = login();
		assertThat(kept.location()).isEqualTo("/");
		assertThat(names(kept.session())).containsExactly("New Con");

		DISCORD.queueGuildRateLimit(60);
		var tooLong = login();
		assertThat(DISCORD.guildCalls()).isEqualTo(6);
		assertThat(names(tooLong.session())).containsExactly("New Con");
	}

	/**
	 * Tests that a failed Discord user lookup fails the login with a redirect to
	 * the sign-in page.
	 */
	@Test
	void discordUserFailureFailsLogin() throws Exception {
		DISCORD.reset("100000000000000030", "unlucky", false);
		DISCORD.queueUserFailure();

		var login = login();

		assertThat(login.location()).isEqualTo("/welcome?login=failed");
		assertThat(send("GET", "/api/me", login.session(), null).statusCode()).isEqualTo(401);
	}

	/**
	 * Tests that a mutation needs the CSRF token, and works with it.
	 */
	@Test
	void mutationNeedsCsrfToken() throws Exception {
		DISCORD.reset("100000000000000040", "csrf_user", false, "200000000000000041", "Csrf Con");
		var session = login().session();
		var path = "/api/me/guilds/200000000000000041";
		var body = "{\"discoverable\":true}";

		assertError(send("PUT", path, session, body), 403, "FORBIDDEN");
		assertError(send("PUT", path, session, body, "X-XSRF-TOKEN", "forged"), 403, "FORBIDDEN");

		var ok = sendWithCsrf("PUT", path, session, body);
		assertThat(ok.statusCode()).isEqualTo(200);
		assertThat(JsonPath.<Boolean>read(ok.body(), "$.body.discoverable")).isTrue();
	}

	/**
	 * Tests that every social-graph and event endpoint is deny-by-default: a 401
	 * without a session, a 403 for a signed-in write without the CSRF token, and
	 * through to the endpoint with both. Error answers are the standard envelope; a
	 * 204 has no body.
	 */
	@Test
	void endpointsNeedSessionAndCsrf() throws Exception {
		DISCORD.reset("100000000000000080", "social_user", false);
		var session = login().session();
		// CSRF is checked before authorization, so anonymous writes send a token.
		var anonymousCsrf = cookie(send("GET", "/api/me", null, null), "XSRF-TOKEN");

		for (var endpoint : ENDPOINTS) {
			assertError(send(endpoint.method(), endpoint.path(), null, endpoint.body(), "Cookie",
					"XSRF-TOKEN=" + anonymousCsrf, "X-XSRF-TOKEN", anonymousCsrf), 401, "UNAUTHORIZED");
			if (!endpoint.method().equals("GET"))
				assertError(send(endpoint.method(), endpoint.path(), session, endpoint.body()), 403, "FORBIDDEN");
			var response = sendWithCsrf(endpoint.method(), endpoint.path(), session, endpoint.body());
			if (endpoint.status() >= 400)
				assertError(response, endpoint.status(), HttpStatus.valueOf(endpoint.status()).name());
			else
				assertThat(response.statusCode()).as(endpoint.method() + " " + endpoint.path())
						.isEqualTo(endpoint.status());
			if (endpoint.status() == 204)
				assertThat(response.body()).isEmpty();
		}
	}

	/**
	 * Tests that a user can't change someone else's server: it is a 404.
	 */
	@Test
	void otherUsersGuildIsNotFound() throws Exception {
		DISCORD.reset("100000000000000050", "owner", false, "200000000000000051", "Owner Con");
		login();
		DISCORD.reset("100000000000000052", "stranger", false, "200000000000000053", "Stranger Con");
		var stranger = login().session();

		assertError(sendWithCsrf("PUT", "/api/me/guilds/200000000000000051", stranger, "{\"discoverable\":true}"), 404,
				"NOT_FOUND");
		assertError(sendWithCsrf("PUT", "/api/me/guilds", stranger,
				"{\"guilds\":[{\"guildId\":\"200000000000000053\",\"discoverable\":true},"
						+ "{\"guildId\":\"200000000000000051\",\"discoverable\":true}]}"),
				404, "NOT_FOUND");
		assertThat(JsonPath.<List<Boolean>>read(send("GET", "/api/me/guilds", stranger, null).body(),
				"$.body.guilds[*].discoverable")).containsExactly(false);
	}

	/**
	 * Tests that logout ends the session with a bodiless 204 (never a redirect),
	 * needs the CSRF token, and is also a 204 without a session, as the contract
	 * documents.
	 */
	@Test
	void logoutEndsSession() throws Exception {
		DISCORD.reset("100000000000000060", "leaver", false);
		var session = login().session();

		assertError(send("POST", SecurityConfig.LOGOUT_URL, session, null), 403, "FORBIDDEN");
		assertThat(send("GET", "/api/me", session, null).statusCode()).isEqualTo(200);

		var logout = sendWithCsrf("POST", SecurityConfig.LOGOUT_URL, session, null);
		assertThat(logout.statusCode()).isEqualTo(204);
		assertThat(logout.body()).isEmpty();
		assertThat(logout.headers().firstValue("Location")).isEmpty();
		assertThat(send("GET", "/api/me", session, null).statusCode()).isEqualTo(401);

		var csrf = cookie(send("GET", "/api/me", null, null), "XSRF-TOKEN");
		var anonymous = http
				.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + SecurityConfig.LOGOUT_URL))
						.POST(BodyPublishers.noBody()).header("Cookie", "XSRF-TOKEN=" + csrf)
						.header("X-XSRF-TOKEN", csrf).build(), BodyHandlers.ofString());
		assertThat(anonymous.statusCode()).isEqualTo(204);
	}

	/**
	 * Tests that logging out everywhere ends every session of the user, and no one
	 * else's.
	 */
	@Test
	void logoutEverywhereEndsEverySessionOfTheUser() throws Exception {
		DISCORD.reset("100000000000000070", "everywhere", false);
		var phone = login().session();
		var laptop = login().session();
		DISCORD.reset("100000000000000071", "bystander", false);
		var bystander = login().session();

		assertThat(sendWithCsrf("POST", "/api/auth/logout-everywhere", phone, null).statusCode()).isEqualTo(204);

		assertThat(send("GET", "/api/me", phone, null).statusCode()).isEqualTo(401);
		assertThat(send("GET", "/api/me", laptop, null).statusCode()).isEqualTo(401);
		assertThat(send("GET", "/api/me", bystander, null).statusCode()).isEqualTo(200);
	}

	/**
	 * Tests that a session hash that lost its required fields is treated as no
	 * session: the request is an ordinary 401, the damaged hash and its expiry key
	 * are deleted, and the same client can sign in again (#44).
	 */
	@Test
	void damagedSessionIsTreatedAsMissing() throws Exception {
		var id = UUID.randomUUID().toString();
		var operations = sessionRepository.getSessionRedisOperations();
		operations.opsForHash().put(SESSION_KEY_PREFIX + id, "lastAccessedTime", Instant.now().toEpochMilli());
		operations.opsForValue().set(SESSION_KEY_PREFIX + "expires:" + id, "", Duration.ofMinutes(5));

		assertError(send("GET", "/api/me", cookieValue(id), null), 401, "UNAUTHORIZED");

		assertThat(operations.hasKey(SESSION_KEY_PREFIX + id)).isFalse();
		assertThat(operations.hasKey(SESSION_KEY_PREFIX + "expires:" + id)).isFalse();
		DISCORD.reset("100000000000000080", "damaged", false);
		var again = login(cookieValue(id));
		assertThat(again.location()).isEqualTo("/onboarding");
		assertThat(send("GET", "/api/me", again.session(), null).statusCode()).isEqualTo(200);
	}

	/**
	 * Tests the race behind #44, step by step. A request with the pre-login session
	 * loads it; meanwhile the sign-in callback rotates the session ID, renaming the
	 * hash away; then the request saves its last-accessed time, and {@code HSET}
	 * recreates the old key without {@code creationTime}. That old cookie must then
	 * get a 401, not a 500, and the new session is unaffected.
	 */
	@Test
	void signInRacingAnInFlightRequestLeavesNoBrokenSession() throws Exception {
		DISCORD.reset("100000000000000090", "racer", false);
		var start = send("GET", SecurityConfig.AUTHORIZATION_BASE_URI + "/discord", null, null);
		var preLogin = cookie(start, "SESSION");
		var state = query(URI.create(start.headers().firstValue("Location").orElseThrow()).getRawQuery()).get("state");
		var inFlight = sessionRepository.findById(sessionId(preLogin));

		var callback = send("GET", "/api/login/oauth2/code/discord?code=stub-code&state="
				+ java.net.URLEncoder.encode(state, StandardCharsets.UTF_8), preLogin, null);
		var session = cookie(callback, "SESSION");
		assertThat(session).isNotNull().isNotEqualTo(preLogin);
		inFlight.setLastAccessedTime(Instant.now());
		sessionRepository.save(inFlight);
		var operations = sessionRepository.getSessionRedisOperations();
		var preLoginKey = SESSION_KEY_PREFIX + sessionId(preLogin);
		assertThat(operations.opsForHash().keys(preLoginKey)).isNotEmpty().doesNotContain("creationTime");

		assertError(send("GET", "/api/me", preLogin, null), 401, "UNAUTHORIZED");
		assertThat(operations.hasKey(preLoginKey)).isFalse();
		assertThat(operations.hasKey(SESSION_KEY_PREFIX + "expires:" + sessionId(preLogin))).isFalse();
		assertThat(send("GET", "/api/me", session, null).statusCode()).isEqualTo(200);
	}

	/**
	 * Tests that a request still in flight during sign-out can't bring the session
	 * back. Sign-out marks the hash expired rather than deleting it, so the late
	 * save leaves an expired session: a 401, and the client can sign in again.
	 */
	@Test
	void signOutRacingAnInFlightRequestStaysSignedOut() throws Exception {
		DISCORD.reset("100000000000000095", "late_saver", false);
		var session = login().session();
		var inFlight = sessionRepository.findById(sessionId(session));

		assertThat(sendWithCsrf("POST", SecurityConfig.LOGOUT_URL, session, null).statusCode()).isEqualTo(204);
		inFlight.setLastAccessedTime(Instant.now());
		sessionRepository.save(inFlight);

		assertError(send("GET", "/api/me", session, null), 401, "UNAUTHORIZED");
		var again = login(session);
		assertThat(again.location()).isEqualTo("/");
		assertThat(send("GET", "/api/me", again.session(), null).statusCode()).isEqualTo(200);
	}

	/**
	 * Tests that logging out everywhere skips and deletes a damaged session that is
	 * still in the user's principal index, and still ends the healthy ones.
	 */
	@Test
	void logoutEverywhereSkipsDamagedSession() throws Exception {
		DISCORD.reset("100000000000000100", "half_broken", false);
		var phone = login().session();
		var laptop = sessionId(login().session());
		var operations = sessionRepository.getSessionRedisOperations();
		operations.opsForHash().delete(SESSION_KEY_PREFIX + laptop, "creationTime");

		assertThat(sendWithCsrf("POST", "/api/auth/logout-everywhere", phone, null).statusCode()).isEqualTo(204);

		assertThat(send("GET", "/api/me", phone, null).statusCode()).isEqualTo(401);
		assertThat(operations.hasKey(SESSION_KEY_PREFIX + laptop)).isFalse();
	}

	/**
	 * Tests that a bootstrap admin gets ADMIN only with Discord MFA on.
	 */
	@Test
	void bootstrapAdminNeedsMfa() throws Exception {
		DISCORD.reset(ADMIN_ID, "admin", false);
		assertThat(roles(login().session())).containsExactly("USER");

		DISCORD.reset(ADMIN_ID, "admin", true);
		assertThat(roles(login().session())).containsExactly("USER", "ADMIN");
	}

	/**
	 * Signs in through the stub and returns the result.
	 *
	 * @return the {@link Login}
	 */
	private Login login() throws Exception {
		return login(null);
	}

	/**
	 * Signs in through the stub from a client that already has a session cookie.
	 *
	 * @param existing the client's {@code SESSION} cookie value, or {@code null}
	 * @return the {@link Login}
	 */
	private Login login(String existing) throws Exception {
		var start = send("GET", SecurityConfig.AUTHORIZATION_BASE_URI + "/discord", existing, null);
		assertThat(start.statusCode()).isEqualTo(302);
		var authorizeUri = start.headers().firstValue("Location").orElseThrow();
		var params = query(URI.create(authorizeUri).getRawQuery());
		var preLogin = cookie(start, "SESSION");

		var callback = send("GET", "/api/login/oauth2/code/discord?code=stub-code&state="
				+ java.net.URLEncoder.encode(params.get("state"), StandardCharsets.UTF_8), preLogin, null);
		assertThat(callback.statusCode()).isEqualTo(302);
		var session = cookie(callback, "SESSION");
		return new Login(authorizeUri, params, preLogin, session == null ? preLogin : session,
				callback.headers().firstValue("Location").orElseThrow());
	}

	/**
	 * Returns the session ID a {@code SESSION} cookie value carries (it is Base64
	 * encoded).
	 *
	 * @param cookie the cookie value
	 * @return the session ID
	 */
	private static String sessionId(String cookie) {
		return new String(Base64.getDecoder().decode(cookie), StandardCharsets.UTF_8);
	}

	/**
	 * Returns the {@code SESSION} cookie value for a session ID.
	 *
	 * @param id the session ID
	 * @return the cookie value
	 */
	private static String cookieValue(String id) {
		return Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Returns the names of the session user's servers.
	 *
	 * @param session the session cookie value
	 * @return the names
	 */
	private List<String> names(String session) throws Exception {
		return JsonPath.read(send("GET", "/api/me/guilds", session, null).body(), "$.body.guilds[*].name");
	}

	/**
	 * Returns the session user's roles.
	 *
	 * @param session the session cookie value
	 * @return the roles
	 */
	private List<String> roles(String session) throws Exception {
		return JsonPath.read(send("GET", "/api/me", session, null).body(), "$.body.roles");
	}

	/**
	 * Returns whether any Valkey value contains one of the given strings.
	 *
	 * @param needles the strings
	 * @return {@code true} when one is found
	 */
	private boolean sessionStoreContainsAny(List<String> needles) {
		try (var connection = redisConnectionFactory.getConnection()) {
			var values = new java.util.ArrayList<byte[]>();
			for (var key : connection.keyCommands().keys("*".getBytes(StandardCharsets.UTF_8))) {
				var type = connection.keyCommands().type(key);
				if (type == DataType.HASH)
					values.addAll(connection.hashCommands().hGetAll(key).values());
				else if (type == DataType.STRING)
					values.add(connection.stringCommands().get(key));
				else if (type == DataType.SET)
					values.addAll(connection.setCommands().sMembers(key));
			}
			assertThat(values).isNotEmpty();
			return values.stream().map(value -> new String(value, StandardCharsets.ISO_8859_1))
					.anyMatch(value -> needles.stream().anyMatch(value::contains));
		}
	}

	/**
	 * Sends a request with the session's CSRF token, fetched from the cookie the
	 * app sets on a GET.
	 *
	 * @param method  the method
	 * @param path    the path
	 * @param session the session cookie value
	 * @param body    the JSON body, or {@code null}
	 * @return the response
	 */
	private HttpResponse<String> sendWithCsrf(String method, String path, String session, String body)
			throws Exception {
		var csrf = cookie(send("GET", "/api/me", session, null), "XSRF-TOKEN");
		assertThat(csrf).isNotBlank();
		return send(method, path, session + "; XSRF-TOKEN=" + csrf, body, "X-XSRF-TOKEN", csrf);
	}

	/**
	 * Sends a request.
	 *
	 * @param method  the method
	 * @param path    the path
	 * @param cookies the {@code SESSION} value, optionally followed by more
	 *                cookies, or {@code null}
	 * @param body    the JSON body, or {@code null}
	 * @param headers extra header name and value pairs
	 * @return the response
	 */
	private HttpResponse<String> send(String method, String path, String cookies, String body, String... headers)
			throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).method(method,
				body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
		if (body != null)
			request.header("Content-Type", "application/json");
		if (cookies != null)
			request.header("Cookie", "SESSION=" + cookies);
		if (headers.length > 0)
			request.headers(headers);
		return http.send(request.build(), BodyHandlers.ofString());
	}

	/**
	 * Returns a cookie the response sets.
	 *
	 * @param response the response
	 * @param name     the cookie name
	 * @return the value, or {@code null} when it isn't set
	 */
	private static String cookie(HttpResponse<String> response, String name) {
		return response.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith(name + "="))
				.map(c -> c.substring(name.length() + 1, c.indexOf(';') < 0 ? c.length() : c.indexOf(';')))
				.filter(value -> !value.isEmpty()).findFirst().orElse(null);
	}

	/**
	 * Parses a query string.
	 *
	 * @param query the raw query
	 * @return the decoded parameters
	 */
	private static Map<String, String> query(String query) {
		var params = new HashMap<String, String>();
		for (var pair : query.split("&")) {
			var parts = pair.split("=", 2);
			params.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
					parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
		}
		return params;
	}

	/**
	 * Asserts the standard error envelope.
	 *
	 * @param response  the response
	 * @param status    the expected status code
	 * @param errorType the expected error type
	 */
	private static void assertError(HttpResponse<String> response, int status, String errorType) {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(response.headers().firstValue("X-Correlation-Id")).isPresent();
		assertThat(JsonPath.<String>read(response.body(), "$.responseType")).isEqualTo("ERROR");
		assertThat(JsonPath.<String>read(response.body(), "$.body.errorType")).isEqualTo(errorType);
		assertThat(JsonPath.<String>read(response.body(), "$.body.correlationId"))
				.isEqualTo(response.headers().firstValue("X-Correlation-Id").orElseThrow());
	}

	/**
	 * One request to a social-graph or event endpoint.
	 *
	 * @param method the method
	 * @param path   the path, with any query
	 * @param body   the JSON body, or {@code null}
	 * @param status the status a signed-in caller with the CSRF token gets
	 * @author mcawful
	 */
	private record Endpoint(String method, String path, String body, int status) {
	}

	/**
	 * The result of a sign-in.
	 *
	 * @param authorizeUri    the Discord authorization URI the app redirected to
	 * @param authorizeParams its query parameters
	 * @param preLoginSession the session cookie before the callback
	 * @param session         the session cookie after the callback
	 * @param location        where the callback redirected
	 * @author mcawful
	 */
	private record Login(String authorizeUri, Map<String, String> authorizeParams, String preLoginSession,
			String session, String location) {
	}
}
