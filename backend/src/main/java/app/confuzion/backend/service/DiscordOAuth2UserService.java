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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import app.confuzion.backend.model.record.DiscordGuild;
import app.confuzion.backend.model.record.DiscordUser;
import app.confuzion.backend.model.record.GuildResponse;

/**
 * Loads the signed-in user from Discord at login: reads {@code /users/@me} and
 * {@code /users/@me/guilds} with the access token, records the login through
 * {@link UserService}, and returns a principal that carries only our own user
 * ID. The access token is used for these two calls and then dropped; nothing
 * here stores it.
 * <p>
 * A {@code 429} gets one retry after Discord's {@code retry_after}, if that is
 * at most {@link #MAX_RETRY_WAIT}. When the user call still fails the login
 * fails; when the guild call fails the login goes ahead with the last known
 * servers.
 * <p>
 * A banned Discord ID, or a user whose suspension hasn't ended, is refused
 * before anything is recorded, with {@link #SIGN_IN_REFUSED}. Otherwise the
 * principal gets {@code ROLE_USER}, plus a {@code ROLE_} authority for each
 * role in {@code user_role}, but only while Discord reports MFA on (D-015).
 *
 * @author mcawful
 */
@Service
public class DiscordOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
	/**
	 * The principal attribute that holds the Con-Fuzion user ID, which is also the
	 * principal name (and so the session's principal index and the rate-limit key).
	 */
	public static final String USER_ID_ATTRIBUTE = "id";

	/**
	 * The principal attribute that says whether this login created the user.
	 */
	public static final String FIRST_LOGIN_ATTRIBUTE = "firstLogin";

	/**
	 * The OAuth2 error code of a sign-in refused for a ban or a suspension. The
	 * failure handler sends it to its own neutral page, which says neither which.
	 */
	public static final String SIGN_IN_REFUSED = "sign_in_refused";

	/**
	 * The longest {@code 429} wait we retry after. A longer one fails that call at
	 * once rather than hold the login.
	 */
	static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(5);

	/**
	 * The {@link Logger}.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(DiscordOAuth2UserService.class);

	/**
	 * A snowflake ID, as {@link GuildResponse#SNOWFLAKE_PATTERN} defines it.
	 */
	private static final Pattern SNOWFLAKE = Pattern.compile(GuildResponse.SNOWFLAKE_PATTERN);

	/**
	 * The query for the guild list. 200 is both the page size limit and the most
	 * servers a user can join, so one page always holds them all.
	 */
	private static final String GUILDS_QUERY = "/guilds?limit=200";

	/**
	 * The {@link RestClient} for Discord's API.
	 */
	private final RestClient restClient;

	/**
	 * The {@link UserService}.
	 */
	private final UserService userService;

	/**
	 * Creates the service.
	 *
	 * @param restClient  the {@link RestClient} for Discord's API
	 * @param userService the {@link UserService}
	 */
	public DiscordOAuth2UserService(@Qualifier("discordRestClient") RestClient restClient, UserService userService) {
		this.restClient = restClient;
		this.userService = userService;
	}

	/**
	 * Loads the Discord user and their servers, records the login, and returns the
	 * principal.
	 *
	 * @param userRequest the {@link OAuth2UserRequest} with the access token
	 * @return an {@link OAuth2User} named by the Con-Fuzion user ID
	 * @throws OAuth2AuthenticationException when Discord's user can't be read, the
	 *                                       sign-in is refused, or the login can't
	 *                                       be recorded
	 */
	@Override
	public OAuth2User loadUser(OAuth2UserRequest userRequest) {
		var token = userRequest.getAccessToken().getTokenValue();
		var userUri = userRequest.getClientRegistration().getProviderDetails().getUserInfoEndpoint().getUri();

		DiscordUser user;
		try {
			user = get(userUri, token, new ParameterizedTypeReference<DiscordUser>() {
			});
		} catch (RestClientException e) {
			LOGGER.warn("Discord user lookup failed: {}", e.getClass().getName());
			throw new OAuth2AuthenticationException(new OAuth2Error("discord_user_unavailable"), e);
		}
		if (user == null || !isSnowflake(user.id()) || user.username() == null)
			throw new OAuth2AuthenticationException(new OAuth2Error("discord_user_invalid"));

		UserService.LoginResult login;
		try {
			// The guild call (which may wait out a 429) comes first, so the refusal check
			// runs right before the login is recorded and the session saved: a ban or
			// suspension landing in between finds as little as possible to miss. A refused
			// sign-in records nothing and creates no account (D-015 rule 5).
			var guilds = fetchGuilds(userUri + GUILDS_QUERY, token);
			if (userService.signInRefusal(Long.parseLong(user.id())).isPresent())
				throw new OAuth2AuthenticationException(new OAuth2Error(SIGN_IN_REFUSED));
			login = userService.recordLogin(user, guilds);
		} catch (DataAccessException e) {
			// Fail the login cleanly (a redirect to the sign-in page) rather than a 500 on
			// the callback.
			LOGGER.error("Recording the login failed", e);
			throw new OAuth2AuthenticationException(new OAuth2Error("login_failed"), e);
		}

		// Granted roles are in effect only while Discord reports MFA on (D-015).
		List<GrantedAuthority> authorities = new ArrayList<>();
		authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
		if (Boolean.TRUE.equals(user.mfaEnabled()))
			login.roles().forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name())));

		return new DefaultOAuth2User(authorities,
				Map.of(USER_ID_ATTRIBUTE, login.userId().toString(), FIRST_LOGIN_ATTRIBUTE, login.firstLogin()),
				USER_ID_ATTRIBUTE);
	}

	/**
	 * Fetches the user's servers.
	 *
	 * @param uri   the guild list URI
	 * @param token the access token
	 * @return the servers with valid IDs and names, or {@code null} when Discord
	 *         didn't return a list
	 */
	private List<DiscordGuild> fetchGuilds(String uri, String token) {
		try {
			var guilds = get(uri, token, new ParameterizedTypeReference<List<DiscordGuild>>() {
			});
			if (guilds == null)
				return null;
			return guilds.stream().filter(guild -> guild != null && isSnowflake(guild.id()) && guild.name() != null)
					.toList();
		} catch (RestClientException e) {
			LOGGER.warn("Discord guild sync skipped, keeping the last known servers: {}", e.getClass().getName());
			return null;
		}
	}

	/**
	 * Returns whether a Discord ID is a snowflake that fits the {@code bigint}
	 * columns.
	 *
	 * @param id the ID, or {@code null}
	 * @return {@code true} for a usable ID
	 */
	private static boolean isSnowflake(String id) {
		if (id == null || !SNOWFLAKE.matcher(id).matches())
			return false;
		try {
			Long.parseLong(id);
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	/**
	 * GETs a Discord resource with the bearer token, retrying once after a
	 * {@code 429} whose wait is short enough.
	 *
	 * @param <T>   the body type
	 * @param uri   the URI
	 * @param token the access token
	 * @param type  the body type reference
	 * @return the body
	 * @throws RestClientException when the call fails, including a second
	 *                             {@code 429}
	 */
	private <T> T get(String uri, String token, ParameterizedTypeReference<T> type) {
		try {
			return restClient.get().uri(uri).headers(h -> h.setBearerAuth(token)).retrieve().body(type);
		} catch (HttpClientErrorException.TooManyRequests e) {
			var wait = retryAfter(e);
			if (wait == null || wait.compareTo(MAX_RETRY_WAIT) > 0)
				throw e;
			sleep(wait, e);
			return restClient.get().uri(uri).headers(h -> h.setBearerAuth(token)).retrieve().body(type);
		}
	}

	/**
	 * Returns how long Discord asked us to wait: the body's {@code retry_after}
	 * (fractional seconds), else the {@code Retry-After} header (whole seconds).
	 *
	 * @param e the {@code 429}
	 * @return the wait, or {@code null} when Discord gave none we can read
	 */
	static Duration retryAfter(HttpClientErrorException e) {
		try {
			var body = e.getResponseBodyAs(RateLimitBody.class);
			if (body != null && body.retryAfter() != null && body.retryAfter() >= 0)
				return Duration.ofMillis((long) Math.ceil(body.retryAfter() * 1000));
		} catch (RuntimeException ignored) {
			// Not JSON: fall back to the header.
		}
		var headers = e.getResponseHeaders();
		var header = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
		try {
			return header == null ? null : Duration.ofSeconds(Long.parseLong(header.strip()));
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	/**
	 * Waits before the retry.
	 *
	 * @param wait the wait
	 * @param e    the {@code 429}, rethrown if the wait is interrupted
	 */
	private static void sleep(Duration wait, HttpClientErrorException e) {
		try {
			Thread.sleep(wait);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw e;
		}
	}

	/**
	 * The part of Discord's {@code 429} body we read.
	 *
	 * @param retryAfter the seconds to wait
	 * @author mcawful
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record RateLimitBody(@JsonProperty("retry_after") Double retryAfter) {
	}
}
