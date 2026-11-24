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

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpSession;

/**
 * Test-only controller that integration tests call to exercise the runtime
 * foundation (rate limits, sessions, error bodies) before any real endpoint
 * exists. It lives in the controller package so both aspects apply to it, and
 * it exists only on the test classpath, and only under the {@value #PROFILE}
 * profile, so other test contexts (such as a future endpoint matrix) never see
 * it. {@code IntegrationProbeSecurity} lets anonymous callers reach it.
 *
 * @author mcawful
 */
@RestController
@Profile(IntegrationProbeController.PROFILE)
@RequestMapping("/api/test-probe")
public class IntegrationProbeController {
	/**
	 * The profile that enables this controller.
	 */
	public static final String PROFILE = "integration-probe";

	/**
	 * A detail that must never reach a client.
	 */
	public static final String INTERNAL_DETAIL = "internal detail: hikari pool secret";

	/**
	 * The {@link JdbcTemplate}.
	 */
	private final JdbcTemplate jdbcTemplate;

	/**
	 * Creates the controller.
	 *
	 * @param jdbcTemplate the {@link JdbcTemplate}
	 */
	public IntegrationProbeController(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Returns a constant, for rate-limit tests.
	 *
	 * @return {@code pong}
	 */
	@GetMapping("/ping")
	public String ping() {
		return "pong";
	}

	/**
	 * Increments and returns a counter stored in the HTTP session.
	 *
	 * @param session the {@link HttpSession}
	 * @return the new counter value
	 */
	@GetMapping("/session")
	public int session(HttpSession session) {
		var count = session.getAttribute("count") instanceof Integer previous ? previous + 1 : 1;
		session.setAttribute("count", count);
		return count;
	}

	/**
	 * Forces a Postgres unique-constraint violation.
	 *
	 * @return never returns normally
	 */
	@GetMapping("/conflict")
	public String conflict() {
		jdbcTemplate.execute("CREATE TEMP TABLE IF NOT EXISTS probe_secret_table (id int CONSTRAINT "
				+ "probe_secret_pkey PRIMARY KEY); INSERT INTO probe_secret_table VALUES (1), (1)");
		return "unreachable";
	}

	/**
	 * Throws an unexpected exception carrying an internal detail.
	 *
	 * @return never returns normally
	 */
	@GetMapping("/boom")
	public String boom() {
		throw new IllegalStateException(INTERNAL_DETAIL);
	}
}
