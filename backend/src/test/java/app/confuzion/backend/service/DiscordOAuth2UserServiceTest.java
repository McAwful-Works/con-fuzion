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
package app.confuzion.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import app.confuzion.backend.model.record.AuditEntryResponse.Action;
import app.confuzion.backend.model.record.DiscordGuild;
import app.confuzion.backend.model.record.DiscordUser;
import app.confuzion.backend.model.record.GrantedRole;

/**
 * Unit tests for {@link DiscordOAuth2UserService}, with Discord stubbed by
 * {@link MockRestServiceServer}.
 *
 * @author mcawful
 */
class DiscordOAuth2UserServiceTest {
	/**
	 * The stubbed user info URI.
	 */
	private static final String USER_URI = "https://discord.test/api/v10/users/@me";

	/**
	 * The stubbed guild list URI.
	 */
	private static final String GUILDS_URI = USER_URI + "/guilds?limit=200";

	/**
	 * A role holder's Discord ID.
	 */
	private static final long ADMIN_ID = 700000000000000001L;

	/**
	 * A user ID.
	 */
	private static final UUID USER_ID = UUID.randomUUID();

	/**
	 * The {@link MockRestServiceServer}.
	 */
	private MockRestServiceServer discord;

	/**
	 * The mocked {@link UserService}.
	 */
	private UserService userService;

	/**
	 * The service under test.
	 */
	private DiscordOAuth2UserService service;

	/**
	 * Builds the service on a stubbed {@link RestClient}.
	 */
	@BeforeEach
	void setUp() {
		var builder = RestClient.builder();
		discord = MockRestServiceServer.bindTo(builder).build();
		userService = mock(UserService.class);
		when(userService.recordLogin(any(), any())).thenReturn(new UserService.LoginResult(USER_ID, true, List.of()));
		when(userService.signInRefusal(anyLong())).thenReturn(Optional.empty());
		service = new DiscordOAuth2UserService(builder.build(), userService);
	}

	/**
	 * Tests a login: the principal is named by our user ID, carries the first login
	 * flag, and has only {@code ROLE_USER}; the bearer token goes to both calls.
	 */
	@Test
	void loadsUserAndGuilds() {
		expect(USER_URI).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token"))
				.andRespond(json(user("700000000000000002", true)));
		expect(GUILDS_URI).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token"))
				.andRespond(json("[{\"id\":\"800000000000000001\",\"name\":\"Con\",\"icon\":null,\"owner\":true},"
						+ "{\"id\":\"bad\",\"name\":\"Skipped\"},{\"id\":\"800000000000000002\"},null]"));

		var principal = service.loadUser(request());

		assertThat(principal.getName()).isEqualTo(USER_ID.toString());
		assertThat((Boolean) principal.getAttribute(DiscordOAuth2UserService.FIRST_LOGIN_ATTRIBUTE)).isTrue();
		assertThat(principal.getAttributes()).containsOnlyKeys(DiscordOAuth2UserService.USER_ID_ATTRIBUTE,
				DiscordOAuth2UserService.FIRST_LOGIN_ATTRIBUTE);
		assertThat(authorities(principal.getAuthorities())).containsExactly("ROLE_USER");
		verify(userService).recordLogin(new DiscordUser("700000000000000002", "user", null, null, true),
				List.of(new DiscordGuild("800000000000000001", "Con", null)));
		discord.verify();
	}

	/**
	 * Tests that the granted roles from {@code user_role} become authorities with
	 * MFA on, and not with MFA off or missing.
	 */
	@Test
	void grantedRolesNeedMfa() {
		when(userService.recordLogin(any(), any())).thenReturn(
				new UserService.LoginResult(USER_ID, false, List.of(GrantedRole.MODERATOR, GrantedRole.ADMIN)));
		expect(USER_URI).andRespond(json(user(Long.toString(ADMIN_ID), true)));
		expect(GUILDS_URI).andRespond(json("[]"));
		assertThat(authorities(service.loadUser(request()).getAuthorities())).containsExactlyInAnyOrder("ROLE_USER",
				"ROLE_MODERATOR", "ROLE_ADMIN");

		discord.reset();
		expect(USER_URI).andRespond(json(user(Long.toString(ADMIN_ID), false)));
		expect(GUILDS_URI).andRespond(json("[]"));
		assertThat(authorities(service.loadUser(request()).getAuthorities())).containsExactly("ROLE_USER");

		discord.reset();
		expect(USER_URI).andRespond(json("{\"id\":\"" + ADMIN_ID + "\",\"username\":\"admin\"}"));
		expect(GUILDS_URI).andRespond(json("[]"));
		assertThat(authorities(service.loadUser(request()).getAuthorities())).containsExactly("ROLE_USER");
	}

	/**
	 * Tests that a banned or suspended user is refused with the neutral error code
	 * and their login isn't recorded. The guilds are fetched first, so the check
	 * runs right before the login would be recorded.
	 */
	@Test
	void refusedSignInRecordsNothing() {
		when(userService.signInRefusal(ADMIN_ID)).thenReturn(Optional.of(Action.ACCOUNT_BANNED));
		expect(USER_URI).andRespond(json(user(Long.toString(ADMIN_ID), true)));
		expect(GUILDS_URI).andRespond(json("[]"));

		assertThatThrownBy(() -> service.loadUser(request())).isInstanceOfSatisfying(
				OAuth2AuthenticationException.class,
				e -> assertThat(e.getError().getErrorCode()).isEqualTo(DiscordOAuth2UserService.SIGN_IN_REFUSED));
		verify(userService, never()).recordLogin(any(), any());
		discord.verify();
	}

	/**
	 * Tests that a {@code 429} on the guild list is retried once after
	 * {@code retry_after}.
	 */
	@Test
	void guildRateLimitIsRetriedOnce() {
		expect(USER_URI).andRespond(json(user("700000000000000003", false)));
		expect(GUILDS_URI).andRespond(rateLimited("0.05", "1"));
		expect(GUILDS_URI).andRespond(json("[{\"id\":\"800000000000000003\",\"name\":\"Late Con\"}]"));

		var start = Instant.now();
		service.loadUser(request());

		assertThat(Duration.between(start, Instant.now())).isGreaterThanOrEqualTo(Duration.ofMillis(50));
		verify(userService).recordLogin(any(), eq(List.of(new DiscordGuild("800000000000000003", "Late Con", null))));
		discord.verify();
	}

	/**
	 * Tests that a second {@code 429}, a wait over the cap, or another failure on
	 * the guild list keeps the last known servers.
	 */
	@Test
	void guildFailuresKeepLastKnown() {
		expect(USER_URI).andRespond(json(user("700000000000000004", false)));
		expect(GUILDS_URI).andRespond(rateLimited("0.01", "1"));
		expect(GUILDS_URI).andRespond(rateLimited("0.01", "1"));
		service.loadUser(request());

		discord.reset();
		expect(USER_URI).andRespond(json(user("700000000000000004", false)));
		expect(GUILDS_URI).andRespond(rateLimited("30", "30"));
		service.loadUser(request());

		discord.reset();
		expect(USER_URI).andRespond(json(user("700000000000000004", false)));
		expect(GUILDS_URI).andRespond(withServerError());
		service.loadUser(request());

		verify(userService, times(3)).recordLogin(any(), isNull());
		discord.verify();
	}

	/**
	 * Tests that a {@code 429} on the user lookup is retried once, and that a
	 * failed or invalid lookup fails the login without recording it.
	 */
	@Test
	void userFailuresFailLogin() {
		expect(USER_URI).andRespond(rateLimited("0.01", "1"));
		expect(USER_URI).andRespond(json(user("700000000000000005", false)));
		expect(GUILDS_URI).andRespond(json("[]"));
		service.loadUser(request());
		discord.verify();

		discord.reset();
		expect(USER_URI).andRespond(withServerError());
		assertThatThrownBy(() -> service.loadUser(request())).isInstanceOf(OAuth2AuthenticationException.class);

		discord.reset();
		expect(USER_URI).andRespond(json("{\"id\":\"9999999999999999999\",\"username\":\"overflow\"}"));
		assertThatThrownBy(() -> service.loadUser(request())).isInstanceOf(OAuth2AuthenticationException.class);

		discord.reset();
		expect(USER_URI).andRespond(json("{\"id\":\"700000000000000006\"}"));
		assertThatThrownBy(() -> service.loadUser(request())).isInstanceOf(OAuth2AuthenticationException.class);

		discord.reset();
		expect(USER_URI).andRespond(json(user("700000000000000007", false)));
		expect(GUILDS_URI).andRespond(json("[]"));
		when(userService.recordLogin(argThat(u -> u != null && "700000000000000007".equals(u.id())), any()))
				.thenThrow(new DataAccessResourceFailureException("database down"));
		assertThatThrownBy(() -> service.loadUser(request())).isInstanceOf(OAuth2AuthenticationException.class);

		var captor = ArgumentCaptor.forClass(DiscordUser.class);
		verify(userService, times(2)).recordLogin(captor.capture(), any());
		assertThat(captor.getAllValues()).extracting(DiscordUser::id).containsExactly("700000000000000005",
				"700000000000000007");
	}

	/**
	 * Tests how the wait is read: the body's fractional seconds first, then the
	 * header's whole seconds, else no retry.
	 */
	@Test
	void retryAfterParsing() {
		assertThat(DiscordOAuth2UserService.retryAfter(tooMany(body(1.234), "2"))).isEqualTo(Duration.ofMillis(1234));
		assertThat(DiscordOAuth2UserService.retryAfter(tooMany(null, "2"))).isEqualTo(Duration.ofSeconds(2));
		assertThat(DiscordOAuth2UserService.retryAfter(tooMany(body(null), " 3 "))).isEqualTo(Duration.ofSeconds(3));
		assertThat(DiscordOAuth2UserService.retryAfter(tooMany(body(-1.0), "4"))).isEqualTo(Duration.ofSeconds(4));
		assertThat(DiscordOAuth2UserService.retryAfter(tooMany(null, "soon"))).isNull();
		assertThat(DiscordOAuth2UserService.retryAfter(tooMany(null, null))).isNull();
	}

	/**
	 * Expects one GET of a URI.
	 *
	 * @param uri the URI
	 * @return the {@link ResponseActions}
	 */
	private ResponseActions expect(String uri) {
		return discord.expect(once(), requestTo(uri));
	}

	/**
	 * Builds a JSON success.
	 *
	 * @param body the body
	 * @return the response creator
	 */
	private static ResponseCreator json(String body) {
		return withSuccess(body, MediaType.APPLICATION_JSON);
	}

	/**
	 * Builds a Discord {@code 429}.
	 *
	 * @param retryAfter the body's {@code retry_after}
	 * @param header     the {@code Retry-After} header
	 * @return the response creator
	 */
	private static ResponseCreator rateLimited(String retryAfter, String header) {
		var headers = new HttpHeaders();
		headers.set(HttpHeaders.RETRY_AFTER, header);
		return withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON).headers(headers).body(
				"{\"message\":\"You are being rate limited.\",\"retry_after\":" + retryAfter + ",\"global\":false}");
	}

	/**
	 * Builds a {@code 429} exception whose body converts to the given value.
	 *
	 * @param body   the converted body, or {@code null} for a body that isn't JSON
	 * @param header the {@code Retry-After} header, or {@code null}
	 * @return the exception
	 */
	private static HttpClientErrorException tooMany(DiscordOAuth2UserService.RateLimitBody body, String header) {
		var headers = new HttpHeaders();
		if (header != null)
			headers.set(HttpHeaders.RETRY_AFTER, header);
		var e = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", headers, new byte[0],
				null);
		e.setBodyConvertFunction(type -> {
			if (body == null)
				throw new IllegalStateException("not JSON");
			return body;
		});
		return e;
	}

	/**
	 * Builds a {@code 429} body.
	 *
	 * @param retryAfter the {@code retry_after} value
	 * @return the body
	 */
	private static DiscordOAuth2UserService.RateLimitBody body(Double retryAfter) {
		return new DiscordOAuth2UserService.RateLimitBody(retryAfter);
	}

	/**
	 * Builds a {@code /users/@me} body.
	 *
	 * @param id  the Discord ID
	 * @param mfa the MFA flag
	 * @return the JSON
	 */
	private static String user(String id, boolean mfa) {
		return "{\"id\":\"" + id + "\",\"username\":\"user\",\"global_name\":null,\"avatar\":null,"
				+ "\"discriminator\":\"0\",\"mfa_enabled\":" + mfa + ",\"verified\":true}";
	}

	/**
	 * Builds the user request the login filter would pass.
	 *
	 * @return the {@link OAuth2UserRequest}
	 */
	private static OAuth2UserRequest request() {
		var registration = ClientRegistration.withRegistrationId("discord").clientId("id").clientSecret("secret")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("{baseUrl}/api/login/oauth2/code/{registrationId}")
				.authorizationUri("https://discord.test/oauth2/authorize").tokenUri("https://discord.test/token")
				.userInfoUri(USER_URI).scope("identify", "guilds").build();
		return new OAuth2UserRequest(registration, new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "token",
				Instant.now(), Instant.now().plusSeconds(60)));
	}

	/**
	 * Returns authority names.
	 *
	 * @param authorities the authorities
	 * @return the names
	 */
	private static List<String> authorities(Collection<? extends GrantedAuthority> authorities) {
		return authorities.stream().map(GrantedAuthority::getAuthority).toList();
	}
}
