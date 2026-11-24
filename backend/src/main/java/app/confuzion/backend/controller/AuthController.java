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

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.confuzion.backend.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The {@link RestController} for session endpoints of our own. Sign-in
 * ({@code /api/oauth2/authorization/discord}) and single-session logout
 * ({@code POST /api/auth/logout}) come from Spring Security; see
 * {@code SecurityConfig}.
 *
 * @author mcawful
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "auth", description = "Sessions")
public class AuthController {
	/**
	 * The {@link SessionService}.
	 */
	private final SessionService sessionService;

	/**
	 * Creates the controller.
	 *
	 * @param sessionService the {@link SessionService}
	 */
	public AuthController(SessionService sessionService) {
		this.sessionService = sessionService;
	}

	/**
	 * Ends every session of the signed-in user, on every device, including this
	 * one.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param request        the {@link HttpServletRequest}
	 * @param response       the {@link HttpServletResponse}
	 */
	@PostMapping("/logout-everywhere")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Sign the user out on every device, including this one")
	public void logoutEverywhere(@Parameter(hidden = true) Authentication authentication, HttpServletRequest request,
			HttpServletResponse response) {
		sessionService.logoutEverywhere(request, response, authentication);
	}
}
