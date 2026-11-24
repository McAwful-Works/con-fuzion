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
package app.confuzion.backend.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import app.confuzion.backend.TestcontainersConfiguration;

/**
 * Integration tests for the {@link RateLimiterAspect} against a real Valkey:
 * buckets are per user, falling back to per IP.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RateLimiterAspectIntegrationTest {
	/**
	 * More requests than the default capacity plus any refills during a test.
	 */
	private static final int MAX_ATTEMPTS = 50;

	/**
	 * The {@link WebApplicationContext}.
	 */
	@Autowired
	private WebApplicationContext webApplicationContext;

	/**
	 * The {@link MockMvc}.
	 */
	private MockMvc mockMvc;

	/**
	 * Builds the {@link MockMvc}.
	 */
	@BeforeEach
	void setup() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
	}

	/**
	 * Tests that the same user does get throttled.
	 */
	@Test
	void sameUserIsThrottled() throws Exception {
		var user = uniqueName();

		assertThat(status(user, "198.51.100.20")).isEqualTo(200);
		assertThat(exhaust(user, "198.51.100.20")).isTrue();
	}

	/**
	 * Tests that two different users don't throttle each other, even from the same
	 * IP.
	 */
	@Test
	void differentUsersDoNotThrottleEachOther() throws Exception {
		assertThat(exhaust(uniqueName(), "198.51.100.30")).isTrue();

		assertThat(status(uniqueName(), "198.51.100.30")).isEqualTo(200);
	}

	/**
	 * Tests that anonymous callers get a bucket per IP, separate from users.
	 */
	@Test
	void anonymousCallersAreLimitedPerIp() throws Exception {
		assertThat(exhaust(null, "198.51.100.40")).isTrue();

		assertThat(status(null, "198.51.100.41")).isEqualTo(200);
		assertThat(status(uniqueName(), "198.51.100.40")).isEqualTo(200);
	}

	/**
	 * Sends requests until one is throttled.
	 *
	 * @param user       the principal name, or {@code null} for anonymous
	 * @param remoteAddr the client IP
	 * @return whether a request was throttled within {@link #MAX_ATTEMPTS}
	 */
	private boolean exhaust(String user, String remoteAddr) throws Exception {
		for (var i = 0; i < MAX_ATTEMPTS; i++)
			if (status(user, remoteAddr) == 429)
				return true;
		return false;
	}

	/**
	 * Calls the probe endpoint and returns the status.
	 *
	 * @param user       the principal name, or {@code null} for anonymous
	 * @param remoteAddr the client IP
	 * @return the HTTP status
	 */
	private int status(String user, String remoteAddr) throws Exception {
		var request = get("/api/test-probe/ping").with(r -> {
			r.setRemoteAddr(remoteAddr);
			return r;
		});
		if (user != null)
			request.principal(() -> user);
		return mockMvc.perform(request).andReturn().getResponse().getStatus();
	}

	/**
	 * Returns a user name no other test uses.
	 *
	 * @return a unique name
	 */
	private static String uniqueName() {
		return "user-" + UUID.randomUUID();
	}
}
