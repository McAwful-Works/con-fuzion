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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
	 * The stub Discord.
	 */
	private static final DiscordStub DISCORD = new DiscordStub();

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
		var start = send("GET", SecurityConfig.AUTHORIZATION_BASE_URI + "/discord", null, null);
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
