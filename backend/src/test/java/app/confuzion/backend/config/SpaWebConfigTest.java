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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Tests for {@link SpaWebConfig} against the sample SPA in
 * {@code src/test/resources/static}: the fallback, the cache headers, and that
 * {@code /api} is never answered with the SPA shell.
 *
 * @author mcawful
 */
@SpringJUnitWebConfig(SpaWebConfigTest.TestConfig.class)
class SpaWebConfigTest {
	/**
	 * A bare MVC context with only the {@link SpaWebConfig} in it.
	 *
	 * @author mcawful
	 */
	@Configuration
	@EnableWebMvc
	@Import(SpaWebConfig.class)
	static class TestConfig {
	}

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
	 * Tests that the root and client-side routes serve the shell, revalidated.
	 *
	 * @param path the request path
	 */
	@ParameterizedTest
	@ValueSource(strings = { "/index.html", "/onboarding", "/events/123/edit", "/events/" })
	void routesServeIndexWithNoCache(String path) throws Exception {
		mockMvc.perform(get(path)).andExpect(status().isOk())
				.andExpect(content().string(containsString("test-spa-shell")))
				.andExpect(header().string("Cache-Control", "no-cache"));
	}

	/**
	 * Tests that the root is sent to the shell.
	 */
	@Test
	void rootForwardsToIndex() throws Exception {
		mockMvc.perform(get("/")).andExpect(forwardedUrl("/index.html"));
	}

	/**
	 * Tests that the service worker is served as itself, revalidated.
	 */
	@Test
	void serviceWorkerIsNotCached() throws Exception {
		mockMvc.perform(get("/sw.js")).andExpect(status().isOk()).andExpect(content().string(containsString("\"sw\"")))
				.andExpect(header().string("Cache-Control", "no-cache"));
	}

	/**
	 * Tests that hashed assets are cached for a year and marked immutable.
	 */
	@Test
	void hashedAssetsAreImmutable() throws Exception {
		mockMvc.perform(get("/assets/app-abc123.js")).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
	}

	/**
	 * Tests that a missing file is a 404 and never the HTML shell.
	 *
	 * @param path the request path
	 */
	@ParameterizedTest
	@ValueSource(strings = { "/assets/missing-abc.js", "/missing.js", "/assets/nope/x.css", "/application.properties",
			"/logback.xml", "/db/migration/x.sql", "/static/index.html", "/%2e%2e/application.properties" })
	void missingFilesAreNotFound(String path) throws Exception {
		mockMvc.perform(get(path)).andExpect(status().isNotFound());
	}

	/**
	 * Tests that {@code /api} paths are never answered with the SPA shell, so they
	 * reach the generic JSON 404 handler.
	 *
	 * @param path the request path
	 */
	@ParameterizedTest
	@ValueSource(strings = { "/api", "/api/", "/api/x", "/api/events/42", "/%61pi/x" })
	void apiPathsAreNeverShadowed(String path) throws Exception {
		mockMvc.perform(get(path)).andExpect(status().isNotFound()).andExpect(content().string(""));
	}

	/**
	 * Tests that a non-GET request to a client route isn't served the shell.
	 */
	@Test
	void postToRouteIsRejected() throws Exception {
		mockMvc.perform(post("/onboarding")).andExpect(status().isMethodNotAllowed());
	}
}
