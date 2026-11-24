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

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.client.RestClient;

import app.confuzion.backend.service.DiscordOAuth2UserService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Spring Security: Discord sign-in, sessions, CSRF and security headers
 * ({@code docs/security.md}).
 * <ul>
 * <li>Deny by default. Public: health, the OAuth endpoints, and {@code GET} or
 * {@code HEAD} of anything outside {@code /api} except the API docs, which is
 * only ever the SPA (its shell, assets and deep links).</li>
 * <li>An unauthenticated {@code /api} request gets a 401 and a failed CSRF
 * check a 403, never a redirect. Both go through {@code /error}, so the body is
 * the standard error envelope (D-010).</li>
 * <li>CSRF: the {@code XSRF-TOKEN} cookie, echoed in the {@code X-XSRF-TOKEN}
 * header on every mutation.</li>
 * <li>Discord access tokens are used once, at login, and never stored: there is
 * no authorized-client store and the principal carries only our user ID.</li>
 * </ul>
 *
 * @author mcawful
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {
	/**
	 * The path the SPA's "Sign in with Discord" button links to, followed by the
	 * registration ID.
	 */
	public static final String AUTHORIZATION_BASE_URI = "/api/oauth2/authorization";

	/**
	 * The OAuth redirect (callback) path pattern.
	 */
	public static final String LOGIN_PROCESSING_URL = "/api/login/oauth2/code/*";

	/**
	 * The single-session logout path ({@code POST}, with CSRF). It answers 204.
	 */
	public static final String LOGOUT_URL = "/api/auth/logout";

	/**
	 * Where a failed sign-in lands. The SPA shows the sign-in page with an error.
	 */
	public static final String LOGIN_FAILURE_URL = "/?login=failed";

	/**
	 * The Content-Security-Policy.
	 */
	static final String CONTENT_SECURITY_POLICY = "default-src 'self'; img-src 'self' https://cdn.discordapp.com; "
			+ "connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'";

	/**
	 * The Permissions-Policy. The camera is for QR scanning.
	 */
	static final String PERMISSIONS_POLICY = "camera=(self), geolocation=(), microphone=()";

	/**
	 * One year, the HSTS max age.
	 */
	private static final long HSTS_MAX_AGE_SECONDS = Duration.ofDays(365).toSeconds();

	/**
	 * The connect and read timeout for Discord API calls at login.
	 */
	private static final Duration DISCORD_TIMEOUT = Duration.ofSeconds(5);

	/**
	 * Builds the security filter chain.
	 *
	 * @param http                         the {@link HttpSecurity}
	 * @param clientRegistrationRepository the {@link ClientRegistrationRepository}
	 * @param userService                  the {@link DiscordOAuth2UserService}
	 * @return the {@link SecurityFilterChain}
	 * @throws Exception when the configuration fails
	 */
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			ClientRegistrationRepository clientRegistrationRepository, DiscordOAuth2UserService userService)
			throws Exception {
		var authorizationRequestResolver = new DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository,
				AUTHORIZATION_BASE_URI);
		authorizationRequestResolver
				.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

		var csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		csrfTokenRepository.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Lax"));

		http.authorizeHttpRequests(authorize -> authorize
				// /error renders the envelope for 401s and 403s; "/" forwards to the SPA shell.
				.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
				// ActuatorAccessFilter keeps every actuator endpoint except health
				// loopback-only.
				.requestMatchers("/api/actuator/**").permitAll()
				.requestMatchers(AUTHORIZATION_BASE_URI + "/**", LOGIN_PROCESSING_URL).permitAll()
				.requestMatchers("/api/**").authenticated()
				// The API docs aren't part of the SPA, so they need a session too.
				.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").authenticated()
				// Everything else outside /api is the SPA. Controllers must map under /api, or
				// this rule makes a
				// GET of theirs public.
				.requestMatchers(HttpMethod.GET, "/**").permitAll().requestMatchers(HttpMethod.HEAD, "/**").permitAll()
				.anyRequest().authenticated())
				.oauth2Login(oauth2 -> oauth2.loginPage("/")
						.authorizationEndpoint(
								endpoint -> endpoint.authorizationRequestResolver(authorizationRequestResolver))
						.loginProcessingUrl(LOGIN_PROCESSING_URL)
						.userInfoEndpoint(endpoint -> endpoint.userService(userService))
						.authorizedClientRepository(new NoTokenStore()).successHandler(SecurityConfig::onLoginSuccess)
						.failureUrl(LOGIN_FAILURE_URL))
				.logout(logout -> logout.logoutUrl(LOGOUT_URL)
						.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
				.csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
				.addFilterAfter(loadCsrfToken(), CsrfFilter.class)
				.requestCache(cache -> cache.requestCache(new NullRequestCache()))
				.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
						(request, response, e) -> response.sendError(HttpStatus.UNAUTHORIZED.value())))
				.sessionManagement(sessions -> sessions.sessionFixation(fixation -> fixation.changeSessionId()))
				.headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
						// Sent on every response: browsers ignore it over plain HTTP, and behind the
						// ALB the
						// request's own scheme isn't a reliable signal.
						.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true)
								.maxAgeInSeconds(HSTS_MAX_AGE_SECONDS).requestMatcher(AnyRequestMatcher.INSTANCE))
						.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
						.permissionsPolicyHeader(permissions -> permissions.policy(PERMISSIONS_POLICY)));
		return http.build();
	}

	/**
	 * Creates the {@link RestClient} for Discord's API, with timeouts so a slow
	 * Discord can't hold a login open.
	 *
	 * @return the {@link RestClient}
	 */
	@Bean
	RestClient discordRestClient() {
		var requestFactory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(DISCORD_TIMEOUT).build());
		requestFactory.setReadTimeout(DISCORD_TIMEOUT);
		return RestClient.builder().requestFactory(requestFactory).build();
	}

	/**
	 * Sends a new user to onboarding and everyone else to the home page.
	 *
	 * @param request        the {@link HttpServletRequest}
	 * @param response       the {@link HttpServletResponse}
	 * @param authentication the new {@link Authentication}
	 * @throws IOException when the redirect fails
	 */
	static void onLoginSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
			throws IOException {
		var firstLogin = authentication.getPrincipal() instanceof OAuth2User user
				&& Boolean.TRUE.equals(user.getAttribute(DiscordOAuth2UserService.FIRST_LOGIN_ATTRIBUTE));
		response.sendRedirect(firstLogin ? "/onboarding" : "/");
	}

	/**
	 * Returns a filter that loads the CSRF token on every request, so the
	 * {@code XSRF-TOKEN} cookie is set before the SPA's first mutation (Spring
	 * otherwise loads it lazily).
	 *
	 * @return the {@link Filter}
	 */
	private static Filter loadCsrfToken() {
		return (request, response, chain) -> {
			if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token)
				token.getToken();
			chain.doFilter(request, response);
		};
	}

	/**
	 * An {@link OAuth2AuthorizedClientRepository} that stores nothing, so the
	 * Discord access and refresh tokens are dropped once the login's user lookup
	 * has used them. Spring's default would keep them in memory or in the session.
	 *
	 * @author mcawful
	 */
	static final class NoTokenStore implements OAuth2AuthorizedClientRepository {
		/**
		 * Always finds nothing.
		 *
		 * @param <T>                  the client type
		 * @param clientRegistrationId the registration ID
		 * @param principal            the principal
		 * @param request              the {@link HttpServletRequest}
		 * @return {@code null}
		 */
		@Override
		public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
				Authentication principal, HttpServletRequest request) {
			return null;
		}

		/**
		 * Discards the client and its tokens.
		 *
		 * @param authorizedClient the {@link OAuth2AuthorizedClient}
		 * @param principal        the principal
		 * @param request          the {@link HttpServletRequest}
		 * @param response         the {@link HttpServletResponse}
		 */
		@Override
		public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
				HttpServletRequest request, HttpServletResponse response) {
			// Intentionally empty: tokens are never stored.
		}

		/**
		 * Has nothing to remove.
		 *
		 * @param clientRegistrationId the registration ID
		 * @param principal            the principal
		 * @param request              the {@link HttpServletRequest}
		 * @param response             the {@link HttpServletResponse}
		 */
		@Override
		public void removeAuthorizedClient(String clientRegistrationId, Authentication principal,
				HttpServletRequest request, HttpServletResponse response) {
			// Intentionally empty: nothing is stored.
		}
	}
}
