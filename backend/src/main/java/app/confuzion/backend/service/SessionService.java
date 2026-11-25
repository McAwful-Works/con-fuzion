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

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import app.confuzion.backend.model.record.AuditDetails;
import app.confuzion.backend.model.record.AuditEntryResponse.Action;
import app.confuzion.backend.model.record.AuditEntryResponse.TargetType;
import app.confuzion.backend.repository.AuditLogRepository;
import app.confuzion.backend.util.HttpUtil;

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
	 * The {@link PushService}, to stop pushes to signed-out devices.
	 */
	private final PushService pushService;

	/**
	 * The {@link AuditLogRepository}, to record moderation purges.
	 */
	private final AuditLogRepository auditLogRepository;

	/**
	 * Creates the service.
	 *
	 * @param sessionRepository  the indexed session repository
	 * @param pushService        the {@link PushService}
	 * @param auditLogRepository the {@link AuditLogRepository}
	 */
	public SessionService(FindByIndexNameSessionRepository<?> sessionRepository, PushService pushService,
			AuditLogRepository auditLogRepository) {
		this.sessionRepository = sessionRepository;
		this.pushService = pushService;
		this.auditLogRepository = auditLogRepository;
	}

	/**
	 * Ends every session of the signed-in user, on every device, and deletes all
	 * their push subscriptions so no signed-out device gets another push (D-014
	 * rule 18). Sessions go first, so a database error can't leave them alive. The
	 * current session is invalidated through the request, so it isn't saved again
	 * when this request ends.
	 *
	 * @param request        the {@link HttpServletRequest}
	 * @param response       the {@link HttpServletResponse}
	 * @param authentication the signed-in user's {@link Authentication}
	 */
	public void logoutEverywhere(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) {
		new SecurityContextLogoutHandler().logout(request, response, authentication);
		deleteSessions(authentication.getName());
		pushService.unsubscribeAll(HttpUtil.userId(authentication));
	}

	/**
	 * Ends every session of a user because a role change, suspension or ban
	 * requires it (D-015), and writes the {@code SESSIONS_PURGED} audit row with
	 * the count. It must run inside the transaction of the change that caused it,
	 * so the audit row commits or rolls back with that change. The sessions go at
	 * once, before the transaction commits: if the change then rolls back, the user
	 * only has to sign in again, whereas purging after the commit could leave a
	 * session with powers it no longer has. A request of the user's still in flight
	 * can't bring a session back (see {@code SessionConfig}).
	 *
	 * @param userId  the user's ID
	 * @param cause   the action behind the purge: {@code ROLES_CHANGED},
	 *                {@code USER_SUSPENDED} or {@code ACCOUNT_BANNED}
	 * @param actorId the moderator or admin who acted
	 * @return how many sessions were deleted
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public int purge(UUID userId, Action cause, UUID actorId) {
		var sessions = deleteSessions(userId.toString());
		auditLogRepository.insert(actorId, Action.SESSIONS_PURGED, TargetType.USER, userId.toString(),
				new AuditDetails(null, null, null, null, null, cause, sessions));
		return sessions;
	}

	/**
	 * Deletes every session indexed under a principal name.
	 *
	 * @param principalName the principal name, the user ID
	 * @return how many sessions were deleted
	 */
	private int deleteSessions(String principalName) {
		var ids = sessionRepository.findByPrincipalName(principalName).keySet();
		ids.forEach(sessionRepository::deleteById);
		return ids.size();
	}
}
