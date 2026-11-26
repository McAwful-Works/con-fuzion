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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.confuzion.backend.exception.ConflictException;
import app.confuzion.backend.exception.ConflictException.Conflict;
import app.confuzion.backend.exception.InvalidInputException;
import app.confuzion.backend.model.record.AdminUserListResponse;
import app.confuzion.backend.model.record.AuditDetails;
import app.confuzion.backend.model.record.AuditEntryResponse.Action;
import app.confuzion.backend.model.record.AuditEntryResponse.TargetType;
import app.confuzion.backend.model.record.GrantedRole;
import app.confuzion.backend.repository.AdminUserRepository;
import app.confuzion.backend.repository.AuditLogRepository;
import app.confuzion.backend.repository.UserRoleRepository;

/**
 * User search and suspensions (D-015). {@code SecurityConfig} lets only
 * MODERATOR or ADMIN in effect reach them. Every write locks the target's
 * {@code app_user} row and then re-checks the actor's stored roles, as a role
 * change does, since a revoke may have committed while the request waited.
 *
 * @author mcawful
 */
@Service
public class AdminUserService {
	/**
	 * The most users a search returns (D-015 rule 13).
	 */
	static final int SEARCH_LIMIT = 20;

	/**
	 * The longest a suspension may run (D-015 rule 4).
	 */
	static final Duration MAX_SUSPENSION = Duration.ofDays(365);

	/**
	 * Search text that may be a Discord ID: digits only, at most 19 of them.
	 */
	private static final Pattern DIGITS = Pattern.compile("[0-9]{1,19}");

	/**
	 * The {@link AdminUserRepository}.
	 */
	private final AdminUserRepository adminUserRepository;

	/**
	 * The {@link UserRoleRepository}.
	 */
	private final UserRoleRepository userRoleRepository;

	/**
	 * The {@link AuditLogRepository}.
	 */
	private final AuditLogRepository auditLogRepository;

	/**
	 * The {@link SessionService}, to end a suspended user's sessions.
	 */
	private final SessionService sessionService;

	/**
	 * The {@link PushService}, so a signed-out device gets no more pushes.
	 */
	private final PushService pushService;

	/**
	 * Creates the service.
	 *
	 * @param adminUserRepository the {@link AdminUserRepository}
	 * @param userRoleRepository  the {@link UserRoleRepository}
	 * @param auditLogRepository  the {@link AuditLogRepository}
	 * @param sessionService      the {@link SessionService}
	 * @param pushService         the {@link PushService}
	 */
	public AdminUserService(AdminUserRepository adminUserRepository, UserRoleRepository userRoleRepository,
			AuditLogRepository auditLogRepository, SessionService sessionService, PushService pushService) {
		this.adminUserRepository = adminUserRepository;
		this.userRoleRepository = userRoleRepository;
		this.auditLogRepository = auditLogRepository;
		this.sessionService = sessionService;
		this.pushService = pushService;
	}

	/**
	 * Finds users by an exact user ID or Discord ID, or by part of a username or
	 * display name, ignoring case (D-015 rule 14). The text is used as sent, so the
	 * 2-character minimum it was validated against holds.
	 *
	 * @param viewer the moderator searching
	 * @param q      the search text, 2 to 100 characters
	 * @return at most {@link #SEARCH_LIMIT} matches, by username
	 */
	@Transactional(readOnly = true)
	public AdminUserListResponse findUsers(UUID viewer, String q) {
		UUID id = null;
		try {
			id = UUID.fromString(q);
		} catch (IllegalArgumentException e) {
			// Not a user ID: match it as a name or a Discord ID only.
		}
		Long discordId = null;
		if (DIGITS.matcher(q).matches())
			try {
				discordId = Long.parseLong(q);
			} catch (NumberFormatException e) {
				// Past the range of a Discord ID.
			}
		return new AdminUserListResponse(adminUserRepository.search(q, id, discordId, viewer, SEARCH_LIMIT));
	}

	/**
	 * Suspends a user until a time (D-015 rule 4): they can't sign in, and their
	 * sessions and push subscriptions are deleted at once. Their events and RSVPs
	 * are left alone. Suspending again replaces the end; the same end again changes
	 * nothing and writes nothing.
	 *
	 * @param actorId      the moderator or admin
	 * @param actorIsAdmin whether the session has ADMIN in effect
	 * @param userId       the user to suspend
	 * @param until        when the suspension ends
	 * @param reason       why, for the audit log
	 * @throws InvalidInputException  when the end isn't in the future or is more
	 *                                than {@link #MAX_SUSPENSION} away
	 * @throws NoSuchElementException when there is no such user
	 * @throws AccessDeniedException  when the actor no longer holds a role
	 * @throws ConflictException      with {@link Conflict#TARGET_HAS_ROLE} when the
	 *                                user holds ADMIN, or MODERATOR and the actor
	 *                                isn't an admin (the actor themself included)
	 */
	@Transactional
	public void suspend(UUID actorId, boolean actorIsAdmin, UUID userId, Instant until, String reason) {
		var now = Instant.now();
		if (!until.isAfter(now) || until.isAfter(now.plus(MAX_SUSPENSION)))
			throw new InvalidInputException();
		var end = until.truncatedTo(ChronoUnit.MICROS);
		var suspension = adminUserRepository.lockForSuspension(userId).orElseThrow();
		var actorRoles = userRoleRepository.lockRoles(actorId);
		if (actorRoles.isEmpty())
			throw new AccessDeniedException("The actor no longer holds a role");
		var targetRoles = userRoleRepository.findRoles(userId);
		if (targetRoles.contains(GrantedRole.ADMIN) || (targetRoles.contains(GrantedRole.MODERATOR)
				&& !(actorIsAdmin && actorRoles.contains(GrantedRole.ADMIN))))
			throw new ConflictException(Conflict.TARGET_HAS_ROLE);
		if (end.equals(suspension.until()))
			return;

		adminUserRepository.setSuspendedUntil(userId, end);
		auditLogRepository.insert(actorId, Action.USER_SUSPENDED, TargetType.USER, userId.toString(),
				new AuditDetails(reason, end, null, null, null, null, null));
		sessionService.purge(userId, Action.USER_SUSPENDED, actorId);
		// Signed out, so no more pushes, as "log out everywhere" does (D-014 rule 18).
		pushService.unsubscribeAll(userId);
	}

	/**
	 * Lifts a user's suspension. A user who isn't suspended, or whose suspension
	 * has ended, changes nothing and writes nothing.
	 *
	 * @param actorId the moderator or admin
	 * @param userId  the user
	 * @throws NoSuchElementException when there is no such user
	 * @throws AccessDeniedException  when the actor no longer holds a role
	 */
	@Transactional
	public void unsuspend(UUID actorId, UUID userId) {
		var suspension = adminUserRepository.lockForSuspension(userId).orElseThrow();
		if (userRoleRepository.lockRoles(actorId).isEmpty())
			throw new AccessDeniedException("The actor no longer holds a role");
		if (!suspension.isActive(Instant.now()))
			return;
		adminUserRepository.setSuspendedUntil(userId, null);
		auditLogRepository.insert(actorId, Action.USER_UNSUSPENDED, TargetType.USER, userId.toString(), null);
	}
}
