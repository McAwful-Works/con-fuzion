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
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import app.confuzion.backend.aspect.GlobalControllerExceptionHandler;
import app.confuzion.backend.model.record.ErrorResponseBodyRecord;
import app.confuzion.backend.model.record.ResponseRecord;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maintenance mode (D-015 rule 16): answers every {@code /api} request except
 * health with a 503 in the D-010 envelope ({@code SERVICE_UNAVAILABLE}), signed
 * in or not. The SPA outside {@code /api} is still served, so it can show its
 * "back soon" page. {@code SecurityConfig} adds it to the security chain only
 * while {@code con-fuzion.maintenance} is true.
 * <p>
 * It writes the body itself, rather than through an error dispatch, so a
 * request never reaches a controller, the session store or the database, and it
 * logs nothing per request.
 *
 * @author mcawful
 */
final class MaintenanceFilter extends OncePerRequestFilter {
	/**
	 * The health endpoint path, which load balancers still need.
	 */
	private static final String HEALTH_PATH = "/api/actuator/health";

	/**
	 * The {@link JsonMapper}, to write the envelope as controllers do.
	 */
	private final JsonMapper jsonMapper;

	/**
	 * Creates the filter.
	 *
	 * @param jsonMapper the {@link JsonMapper}
	 */
	MaintenanceFilter(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	/**
	 * Skips paths outside {@code /api}, and health. Uses the decoded, normalized
	 * lookup path, as {@link ActuatorAccessFilter} does.
	 *
	 * @param request the {@link HttpServletRequest}
	 * @return {@code true} when the request isn't answered with a 503
	 */
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		var path = UrlPathHelper.defaultInstance.getLookupPathForRequest(request);
		var api = path.equals("/api") || path.startsWith("/api/");
		return !api || path.equals(HEALTH_PATH) || path.startsWith(HEALTH_PATH + "/");
	}

	/**
	 * Answers with the 503 envelope and doesn't continue the chain.
	 *
	 * @param request     the {@link HttpServletRequest}
	 * @param response    the {@link HttpServletResponse}
	 * @param filterChain the {@link FilterChain}, not called
	 * @throws IOException when the response can't be written
	 */
	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws IOException {
		var correlationId = UUID.randomUUID().toString();
		response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8);
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.setHeader(GlobalControllerExceptionHandler.CORRELATION_ID_HEADER, correlationId);
		response.getWriter().write(jsonMapper.writeValueAsString(
				new ResponseRecord<>(ErrorResponseBodyRecord.of(HttpStatus.SERVICE_UNAVAILABLE, correlationId))));
	}
}
