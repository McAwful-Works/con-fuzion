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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import app.confuzion.backend.TestcontainersConfiguration;
import app.confuzion.backend.aspect.GlobalControllerExceptionHandler;
import app.confuzion.backend.service.DiscordOAuth2UserService;

/**
 * Tests maintenance mode (D-015 rule 16) through the real security chain: every
 * {@code /api} route but health is a 503 in the error envelope, signed in or
 * not, before CSRF, sign-in or authorization can answer, and the SPA is still
 * served.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest(properties = "con-fuzion.maintenance=true")
@Import(TestcontainersConfiguration.class)
class MaintenanceFilterIntegrationTest {
	/**
	 * The {@link WebApplicationContext}.
	 */
	@Autowired
	private WebApplicationContext webApplicationContext;

	/**
	 * The {@link MockMvc}, with the security chain.
	 */
	private MockMvc mockMvc;

	/**
	 * Builds the {@link MockMvc}.
	 */
	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
	}

	/**
	 * Tests that signed-out requests get the 503, including a write without CSRF,
	 * the start of a sign-in and its callback, which would otherwise be a 401, a
	 * 403 or a redirect.
	 *
	 * @param path the path
	 */
	@ParameterizedTest
	@ValueSource(strings = { "/api/me", "/api", "/api/unknown", "/api/admin/audit", "/api/oauth2/authorization/discord",
			"/api/login/oauth2/code/discord?code=x&state=y", "/api/actuator/metrics", "/api/actuator/healthz" })
	void signedOutRequestsAreUnavailable(String path) throws Exception {
		assertUnavailable(mockMvc.perform(get(path)));
		assertUnavailable(mockMvc.perform(request(HttpMethod.POST, path)));
	}

	/**
	 * Tests that a signed-in user, an admin included, gets the same 503, for reads
	 * and for writes with or without CSRF.
	 */
	@Test
	void signedInRequestsAreUnavailable() throws Exception {
		var userId = UUID.randomUUID().toString();
		var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"));
		var login = oauth2Login().oauth2User(
				new DefaultOAuth2User(authorities, Map.of(DiscordOAuth2UserService.USER_ID_ATTRIBUTE, userId),
						DiscordOAuth2UserService.USER_ID_ATTRIBUTE));

		assertUnavailable(mockMvc.perform(get("/api/me").with(login)));
		assertUnavailable(mockMvc.perform(get("/api/admin/audit").with(login)));
		assertUnavailable(mockMvc.perform(post("/api/auth/logout").with(login)));
		assertUnavailable(mockMvc.perform(post("/api/events").with(login).with(csrf())));
	}

	/**
	 * Tests that health still answers, so the load balancer keeps the task, and
	 * that pages outside {@code /api} aren't touched, so the SPA can show its "back
	 * soon" page.
	 */
	@Test
	void healthAndTheSpaStayUp() throws Exception {
		mockMvc.perform(get("/api/actuator/health")).andExpect(status().isOk());
		var spa = mockMvc.perform(get("/")).andReturn().getResponse().getStatus();
		assertThat(spa).isNotEqualTo(503);
		var page = mockMvc.perform(get("/events/3f2c9a4e-6d1b-4f7a-9c3e-2b8d5e1a7f60")).andReturn().getResponse()
				.getStatus();
		assertThat(page).isNotEqualTo(503);
	}

	/**
	 * Asserts the maintenance response: a 503 in the error envelope with a matching
	 * correlation ID, never cached, with the security headers.
	 *
	 * @param result the {@link ResultActions}
	 */
	private static void assertUnavailable(ResultActions result) throws Exception {
		result.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.responseType").value("ERROR"))
				.andExpect(jsonPath("$.body.errorType").value("SERVICE_UNAVAILABLE"))
				.andExpect(jsonPath("$.body.errorMessage").value("Service Unavailable"))
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"));
		var response = result.andReturn().getResponse();
		var correlationId = response.getHeader(GlobalControllerExceptionHandler.CORRELATION_ID_HEADER);
		assertThat(correlationId).isNotBlank();
		assertThat(response.getContentAsString()).contains("\"correlationId\":\"" + correlationId + "\"");
	}
}
