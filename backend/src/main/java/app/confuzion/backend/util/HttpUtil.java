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
package app.confuzion.backend.util;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;

import app.confuzion.backend.config.SecurityConfig;

import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;

/**
 * Utility class that contains methods for HTTP related operations.
 *
 * @author mcawful
 */
@UtilityClass
public class HttpUtil {
	/**
	 * Gets and returns the client IP address from the given
	 * {@link HttpServletRequest}.
	 * <p>
	 * This is {@code request.getRemoteAddr()}, which Tomcat's RemoteIpValve
	 * ({@code server.forward-headers-strategy=native}) resolves from
	 * {@code X-Forwarded-For} by trusting only private-range proxies. Never read
	 * {@code X-Forwarded-For} or similar headers directly: clients can set them.
	 *
	 * @param request the {@link HttpServletRequest}
	 * @return the client IP address as a string
	 */
	public String getRemoteAddr(HttpServletRequest request) {
		return request.getRemoteAddr();
	}

	/**
	 * Gets and returns the ID of the authenticated user of the given
	 * {@link HttpServletRequest}, if there is one.
	 *
	 * @param request the {@link HttpServletRequest}
	 * @return the principal name, or empty for an anonymous request
	 */
	public Optional<String> getUserId(HttpServletRequest request) {
		var principal = request.getUserPrincipal();
		return principal == null ? Optional.empty() : Optional.ofNullable(principal.getName());
	}

	/**
	 * Returns the signed-in user's ID, which is the session's principal name. It
	 * always comes from the session, never from the request.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @return the user ID
	 */
	public UUID userId(Authentication authentication) {
		return UUID.fromString(authentication.getName());
	}

	/**
	 * Returns whether the session has ADMIN in effect ({@code ROLE_ADMIN}).
	 *
	 * @param authentication the session's {@link Authentication}
	 * @return {@code true} for an admin
	 */
	public boolean isAdmin(Authentication authentication) {
		return authentication.getAuthorities().stream()
				.anyMatch(authority -> ("ROLE_" + SecurityConfig.ADMIN).equals(authority.getAuthority()));
	}
}
