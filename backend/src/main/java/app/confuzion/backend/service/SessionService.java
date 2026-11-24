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
package app.confuzion.backend.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Ends sessions. Sessions live in Valkey and are indexed by principal name (the
 * Con-Fuzion user ID), so all of a user's sessions can be found from any app
 * instance.
 *
 * @author mcawful
 */
@Service
public class SessionService {
	/**
	 * The indexed session repository.
	 */
	private final FindByIndexNameSessionRepository<?> sessionRepository;

	/**
	 * Creates the service.
	 *
	 * @param sessionRepository the indexed session repository
	 */
	public SessionService(FindByIndexNameSessionRepository<?> sessionRepository) {
		this.sessionRepository = sessionRepository;
	}

	/**
	 * Ends every session of the signed-in user, on every device. The current
	 * session is invalidated through the request, so it isn't saved again when this
	 * request ends.
	 *
	 * @param request        the {@link HttpServletRequest}
	 * @param response       the {@link HttpServletResponse}
	 * @param authentication the signed-in user's {@link Authentication}
	 */
	public void logoutEverywhere(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) {
		new SecurityContextLogoutHandler().logout(request, response, authentication);
		sessionRepository.findByPrincipalName(authentication.getName()).keySet().forEach(sessionRepository::deleteById);
	}
}
