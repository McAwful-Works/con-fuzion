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
import java.net.InetAddress;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import app.confuzion.backend.util.HttpUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Keeps every actuator endpoint except health off the public internet until
 * Spring Security arrives (M1). Requests for any other {@code /api/actuator}
 * path get a 404 unless they come from loopback, such as a metrics sidecar in
 * the same task.
 *
 * @author mcawful
 */
@Component
public class ActuatorAccessFilter extends OncePerRequestFilter {
	/**
	 * The actuator base path.
	 */
	private static final String ACTUATOR_PATH = "/api/actuator";

	/**
	 * The public health endpoint path.
	 */
	private static final String HEALTH_PATH = ACTUATOR_PATH + "/health";

	/**
	 * Skips the filter for paths outside the actuator and for the health endpoint.
	 * Uses the decoded, normalized lookup path, so encoded variants of an actuator
	 * path are still filtered.
	 *
	 * @param request the {@link HttpServletRequest}
	 * @return {@code true} when the request doesn't need the loopback check
	 */
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		var path = UrlPathHelper.defaultInstance.getLookupPathForRequest(request);
		return !path.startsWith(ACTUATOR_PATH) || path.equals(HEALTH_PATH) || path.startsWith(HEALTH_PATH + "/");
	}

	/**
	 * Lets loopback requests through and answers everything else with a 404.
	 *
	 * @param request     the {@link HttpServletRequest}
	 * @param response    the {@link HttpServletResponse}
	 * @param filterChain the {@link FilterChain}
	 * @throws ServletException when the rest of the chain throws it
	 * @throws IOException      when the rest of the chain or the error response
	 *                          throws it
	 */
	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		if (isLoopback(HttpUtil.getRemoteAddr(request)))
			filterChain.doFilter(request, response);
		else
			response.sendError(HttpStatus.NOT_FOUND.value());
	}

	/**
	 * Returns whether the given IP literal is a loopback address. Never does a DNS
	 * lookup.
	 *
	 * @param remoteAddr the IP address string
	 * @return {@code true} for a loopback address, {@code false} for anything else
	 *         (including a value that isn't an IP literal)
	 */
	private static boolean isLoopback(String remoteAddr) {
		try {
			return remoteAddr != null && InetAddress.ofLiteral(remoteAddr).isLoopbackAddress();
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
