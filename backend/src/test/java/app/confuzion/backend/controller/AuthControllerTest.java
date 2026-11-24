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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import app.confuzion.backend.aspect.GlobalControllerExceptionHandler;
import app.confuzion.backend.service.SessionService;

/**
 * Unit tests for {@link AuthController}.
 *
 * @author mcawful
 */
class AuthControllerTest {
	/**
	 * The mocked {@link SessionService}.
	 */
	private final SessionService sessionService = mock(SessionService.class);

	/**
	 * The {@link MockMvc}, with the real exception handler.
	 */
	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(sessionService))
			.setControllerAdvice(new GlobalControllerExceptionHandler()).build();

	/**
	 * Tests that {@code POST /api/auth/logout-everywhere} ends the user's sessions
	 * and answers 204 with no body.
	 */
	@Test
	void testLogoutEverywhere() throws Exception {
		var authentication = UsernamePasswordAuthenticationToken.authenticated("user-id", null,
				List.of(new SimpleGrantedAuthority("ROLE_USER")));

		mockMvc.perform(post("/api/auth/logout-everywhere").principal(authentication)).andExpect(status().isNoContent())
				.andExpect(content().string(""));
		verify(sessionService).logoutEverywhere(any(), any(), eq(authentication));
	}
}
