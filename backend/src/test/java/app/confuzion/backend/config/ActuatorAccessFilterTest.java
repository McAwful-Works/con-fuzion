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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for the {@link ActuatorAccessFilter} class.
 *
 * @author mcawful
 */
class ActuatorAccessFilterTest {
	/**
	 * The {@link ActuatorAccessFilter} to test.
	 */
	private final ActuatorAccessFilter filter = new ActuatorAccessFilter();

	/**
	 * Tests which requests reach the rest of the chain and which get a 404.
	 *
	 * @param path       the request URI
	 * @param remoteAddr the client IP
	 * @param allowed    whether the request should pass
	 */
	@ParameterizedTest
	@CsvSource({ "/api/actuator/health, 203.0.113.5, true", "/api/actuator/health/liveness, 203.0.113.5, true",
			"/api/events, 203.0.113.5, true", "/api/actuator/metrics, 203.0.113.5, false",
			"/api/actuator/%6detrics, 203.0.113.5, false", "/api/actuator, 203.0.113.5, false",
			"/api/actuator/healthz, 203.0.113.5, false", "/api/actuator/metrics, 10.0.0.12, false",
			"/api/actuator/metrics, not-an-ip, false", "/api/actuator/metrics, 127.0.0.1, true",
			"/api/actuator/metrics, 0:0:0:0:0:0:0:1, true" })
	void testDoFilter(String path, String remoteAddr, boolean allowed) throws Exception {
		var request = new MockHttpServletRequest("GET", path);
		request.setRemoteAddr(remoteAddr);
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(chain.getRequest() != null).isEqualTo(allowed);
		assertThat(response.getStatus()).isEqualTo(allowed ? 200 : 404);
	}
}
