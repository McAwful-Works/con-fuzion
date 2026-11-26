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
import app.confuzion.backend.model.record.MyStatus;
import app.confuzion.backend.repository.AdminUserRepository;
import app.confuzion.backend.repository.AuditLogRepository;
import app.confuzion.backend.repository.RsvpRepository;
import app.confuzion.backend.repository.UserRoleRepository;

/**
 * User search, suspensions and bans (D-015). {@code SecurityConfig} lets only
 * MODERATOR or ADMIN in effect reach search and suspensions, and only ADMIN
 * reach bans. Every write locks the target's {@code app_user} row and then
 * re-checks the actor's stored roles, as a role change does, since a revoke may
 * have committed while the request waited.
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
	 * The {@link RsvpRepository}, to end a banned user's RSVPs.
	 */
	private final RsvpRepository rsvpRepository;

	/**
	 * The {@link EventService}, to cancel a banned user's events the usual way.
	 */
	private final EventService eventService;

	/**
	 * The {@link AuditLogRepository}.
	 */
	private final AuditLogRepository auditLogRepository;

	/**
	 * The {@link SessionService}, to end a suspended or banned user's sessions.
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
	 * @param rsvpRepository      the {@link RsvpRepository}
	 * @param eventService        the {@link EventService}
	 * @param auditLogRepository  the {@link AuditLogRepository}
	 * @param sessionService      the {@link SessionService}
	 * @param pushService         the {@link PushService}
	 */
	public AdminUserService(AdminUserRepository adminUserRepository, UserRoleRepository userRoleRepository,
			RsvpRepository rsvpRepository, EventService eventService, AuditLogRepository auditLogRepository,
			SessionService sessionService, PushService pushService) {
		this.adminUserRepository = adminUserRepository;
		this.userRoleRepository = userRoleRepository;
		this.rsvpRepository = rsvpRepository;
		this.eventService = eventService;
		this.auditLogRepository = auditLogRepository;
		this.sessionService = sessionService;
		this.pushService = pushService;
	}

	/**
	 * Finds users by an exact user ID or Discord ID, or by part of a username or
	 * display name, ignoring case (D-015 rule 14).
	 *
	 * @param viewer the moderator searching
	 * @param q      the search text, 2 to 100 characters
	 * @return at most {@link #SEARCH_LIMIT} matches, by username
	 */
	@Transactional(readOnly = true)
	public AdminUserListResponse findUsers(UUID viewer, String q) {
		var text = q.strip();
		UUID id = null;
		try {
			id = UUID.fromString(text);
		} catch (IllegalArgumentException e) {
			// Not a user ID: match it as a name or a Discord ID only.
		}
		Long discordId = null;
		if (DIGITS.matcher(text).matches())
			try {
				discordId = Long.parseLong(text);
			} catch (NumberFormatException e) {
				// Past the range of a Discord ID.
			}
		return new AdminUserListResponse(adminUserRepository.search(text, id, discordId, viewer, SEARCH_LIMIT));
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
		signOut(userId, Action.USER_SUSPENDED, actorId);
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

	/**
	 * Bans a Discord ID (D-015 rule 4), whether or not it has an account. If it has
	 * one, in this transaction and in D-013's lock order (the user's row, then
	 * their events in ID order): their upcoming hosted events are cancelled through
	 * {@link EventService#cancelEvent}, so attendees get the usual
	 * {@code EVENT_CANCELLED}; their active RSVPs on other events end as
	 * {@code LEFT}, each freed spot going to the next person in line; and their
	 * sessions and push subscriptions are deleted. The account, and the host's own
	 * RSVPs on their events, are kept. Banning a banned ID changes nothing and
	 * writes nothing.
	 * <p>
	 * A join of theirs racing the ban either commits first, and the ban ends it, or
	 * waits for the ban and writes nothing ({@link RsvpRepository#lockJoiner}).
	 * Someone else joining one of their events waits on the event lock and then
	 * finds it cancelled.
	 *
	 * @param actorId   the admin
	 * @param discordId the Discord user ID
	 * @param reason    why, for the audit log and other admins
	 * @throws NumberFormatException when the ID is past the range of a Discord ID
	 * @throws AccessDeniedException when the actor no longer holds ADMIN
	 * @throws ConflictException     with {@link Conflict#TARGET_HAS_ROLE} when the
	 *                               account holds ADMIN (the actor themself
	 *                               included)
	 */
	@Transactional
	public void ban(UUID actorId, String discordId, String reason) {
		var id = Long.parseLong(discordId);
		if (!adminUserRepository.insertBan(id, reason, actorId))
			return;
		var userId = adminUserRepository.lockByDiscordId(id);
		if (!userRoleRepository.lockRoles(actorId).contains(GrantedRole.ADMIN))
			throw new AccessDeniedException("The actor no longer holds ADMIN");
		userId.ifPresent(this::cleanUp);
		auditLogRepository.insert(actorId, Action.ACCOUNT_BANNED, TargetType.DISCORD_ACCOUNT, discordId,
				new AuditDetails(reason, null, null, null, null, null, null));
		userId.ifPresent(user -> signOut(user, Action.ACCOUNT_BANNED, actorId));
	}

	/**
	 * Lifts a ban. Nothing the ban did is undone. An ID that isn't banned changes
	 * nothing and writes nothing.
	 *
	 * @param actorId   the admin
	 * @param discordId the Discord user ID
	 * @throws NumberFormatException when the ID is past the range of a Discord ID
	 * @throws AccessDeniedException when the actor no longer holds ADMIN
	 */
	@Transactional
	public void unban(UUID actorId, String discordId) {
		var id = Long.parseLong(discordId);
		if (!userRoleRepository.lockRoles(actorId).contains(GrantedRole.ADMIN))
			throw new AccessDeniedException("The actor no longer holds ADMIN");
		if (adminUserRepository.deleteBan(id))
			auditLogRepository.insert(actorId, Action.ACCOUNT_UNBANNED, TargetType.DISCORD_ACCOUNT, discordId, null);
	}

	/**
	 * Cancels a banned user's upcoming hosted events and ends their active RSVPs on
	 * other events as {@code LEFT}, under the event row locks. The caller holds the
	 * user's row lock.
	 *
	 * @param userId the banned user's ID
	 * @throws ConflictException with {@link Conflict#TARGET_HAS_ROLE} when the user
	 *                           holds ADMIN
	 */
	private void cleanUp(UUID userId) {
		if (userRoleRepository.findRoles(userId).contains(GrantedRole.ADMIN))
			throw new ConflictException(Conflict.TARGET_HAS_ROLE);
		for (var event : rsvpRepository.lockForBan(userId)) {
			if (event.hosted())
				eventService.cancelEvent(userId, event.publicId());
			else if (rsvpRepository.end(event.id(), userId, "LEFT")
					.filter(held -> held == MyStatus.CONFIRMED || held == MyStatus.OFFERED).isPresent())
				rsvpRepository.freeSpot(event.id());
		}
	}

	/**
	 * Ends a user's sessions, audit-logging the purge, and deletes their push
	 * subscriptions, as "log out everywhere" does (D-014 rule 18).
	 *
	 * @param userId  the user's ID
	 * @param cause   {@code USER_SUSPENDED} or {@code ACCOUNT_BANNED}
	 * @param actorId the moderator or admin
	 */
	private void signOut(UUID userId, Action cause, UUID actorId) {
		sessionService.purge(userId, cause, actorId);
		pushService.unsubscribeAll(userId);
	}
}
