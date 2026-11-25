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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.confuzion.backend.exception.ConflictException;
import app.confuzion.backend.exception.ConflictException.Conflict;
import app.confuzion.backend.exception.InvalidInputException;
import app.confuzion.backend.model.record.AuditDetails;
import app.confuzion.backend.model.record.AuditEntryResponse.Action;
import app.confuzion.backend.model.record.AuditEntryResponse.TargetType;
import app.confuzion.backend.model.record.AuditPageResponse;
import app.confuzion.backend.model.record.GrantedRole;
import app.confuzion.backend.model.record.RolesResponse;
import app.confuzion.backend.repository.AppUserRepository;
import app.confuzion.backend.repository.AuditLogRepository;
import app.confuzion.backend.repository.UserRoleRepository;

/**
 * Role grants and the audit log (D-015). Every caller holds ADMIN in effect,
 * which {@code SecurityConfig} checks before the request gets here.
 *
 * @author mcawful
 */
@Service
public class AdminService {
	/**
	 * The {@link AppUserRepository}.
	 */
	private final AppUserRepository appUserRepository;

	/**
	 * The {@link UserRoleRepository}.
	 */
	private final UserRoleRepository userRoleRepository;

	/**
	 * The {@link AuditLogRepository}.
	 */
	private final AuditLogRepository auditLogRepository;

	/**
	 * The {@link SessionService}, to end a user's sessions when their roles change.
	 */
	private final SessionService sessionService;

	/**
	 * Creates the service.
	 *
	 * @param appUserRepository  the {@link AppUserRepository}
	 * @param userRoleRepository the {@link UserRoleRepository}
	 * @param auditLogRepository the {@link AuditLogRepository}
	 * @param sessionService     the {@link SessionService}
	 */
	public AdminService(AppUserRepository appUserRepository, UserRoleRepository userRoleRepository,
			AuditLogRepository auditLogRepository, SessionService sessionService) {
		this.appUserRepository = appUserRepository;
		this.userRoleRepository = userRoleRepository;
		this.auditLogRepository = auditLogRepository;
		this.sessionService = sessionService;
	}

	/**
	 * Returns a user's granted roles, whether or not MFA puts them in effect.
	 *
	 * @param userId the user's ID
	 * @return the {@link RolesResponse}
	 * @throws NoSuchElementException when there is no such user
	 */
	@Transactional(readOnly = true)
	public RolesResponse getRoles(UUID userId) {
		var user = appUserRepository.findById(userId).orElseThrow();
		return new RolesResponse(userRoleRepository.findRoles(userId), user.isMfaEnabled());
	}

	/**
	 * Sets a user's granted roles to exactly the given set (D-015 rule 3). The same
	 * set again changes nothing and writes nothing. Otherwise the change is
	 * audit-logged and the user's sessions are deleted, so the new roles apply at
	 * their next sign-in.
	 * <p>
	 * The user's row and then every ADMIN row are locked first (see
	 * {@link UserRoleRepository}), so the last-ADMIN check sees the outcome of any
	 * concurrent change and two admins removing each other can't leave none.
	 *
	 * @param actorId the admin making the change
	 * @param userId  the user's ID
	 * @param roles   the new set, possibly empty
	 * @return the {@link RolesResponse} with the new set
	 * @throws NoSuchElementException when there is no such user
	 * @throws AccessDeniedException  when the actor no longer holds ADMIN
	 * @throws ConflictException      with {@link Conflict#LAST_ADMIN} when nobody
	 *                                would hold ADMIN any more
	 */
	@Transactional
	public RolesResponse setRoles(UUID actorId, UUID userId, Set<GrantedRole> roles) {
		var mfaEnabled = userRoleRepository.lockUser(userId).orElseThrow();
		var admins = userRoleRepository.lockAdmins();
		// The session's ADMIN was checked before this request waited for the lock; a
		// concurrent change may have removed it since.
		if (!admins.contains(actorId))
			throw new AccessDeniedException("The actor no longer holds ADMIN");
		var current = userRoleRepository.findRoles(userId);
		var wanted = roles.isEmpty() ? List.<GrantedRole>of() : List.copyOf(EnumSet.copyOf(roles));
		if (current.equals(wanted))
			return new RolesResponse(current, mfaEnabled);
		if (!wanted.contains(GrantedRole.ADMIN) && admins.equals(List.of(userId)))
			throw new ConflictException(Conflict.LAST_ADMIN);

		for (var role : current)
			if (!wanted.contains(role))
				userRoleRepository.revoke(userId, role);
		for (var role : wanted)
			if (!current.contains(role))
				userRoleRepository.grant(userId, role, actorId);
		auditLogRepository.insert(actorId, Action.ROLES_CHANGED, TargetType.USER, userId.toString(),
				new AuditDetails(null, null, null, null, wanted, null, null));
		sessionService.purge(userId, Action.ROLES_CHANGED, actorId);
		return new RolesResponse(wanted, mfaEnabled);
	}

	/**
	 * Returns a page of the audit log, newest first.
	 *
	 * @param cursor the previous page's cursor, or {@code null} for the first page
	 * @param limit  the most rows to return
	 * @return the {@link AuditPageResponse}
	 * @throws InvalidInputException when the cursor isn't one this method issued
	 */
	@Transactional(readOnly = true)
	public AuditPageResponse getAudit(String cursor, int limit) {
		var items = auditLogRepository.findPage(cursor == null ? null : decode(cursor), limit + 1);
		if (items.size() <= limit)
			return new AuditPageResponse(items, null);
		var page = items.subList(0, limit);
		return new AuditPageResponse(page, encode(page.getLast().id()));
	}

	/**
	 * Encodes an audit row ID as an opaque cursor.
	 *
	 * @param id the last row's ID on the page
	 * @return the cursor
	 */
	private static String encode(long id) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(Long.toString(id).getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Decodes a cursor.
	 *
	 * @param cursor the opaque cursor, already matching the base64url alphabet
	 * @return the audit row ID it names
	 * @throws InvalidInputException when it isn't a cursor from {@link #encode}
	 */
	private static long decode(String cursor) {
		long id;
		try {
			id = Long.parseLong(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
		} catch (IllegalArgumentException e) {
			throw new InvalidInputException(e);
		}
		if (id < 1)
			throw new InvalidInputException();
		return id;
	}
}
