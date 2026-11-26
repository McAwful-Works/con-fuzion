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

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.confuzion.backend.exception.ConflictException;
import app.confuzion.backend.exception.ConflictException.Conflict;
import app.confuzion.backend.exception.EventNotEditableException;
import app.confuzion.backend.exception.InvalidInputException;
import app.confuzion.backend.exception.LimitExceededException;
import app.confuzion.backend.exception.LimitExceededException.Limit;
import app.confuzion.backend.model.entity.AppUser;
import app.confuzion.backend.model.entity.Event;
import app.confuzion.backend.model.entity.EventAudience;
import app.confuzion.backend.model.entity.EventAudience.Ref;
import app.confuzion.backend.model.entity.UserGuild;
import app.confuzion.backend.model.record.AuditDetails;
import app.confuzion.backend.model.record.AuditEntryResponse.Action;
import app.confuzion.backend.model.record.AuditEntryResponse.TargetType;
import app.confuzion.backend.model.record.EventPageResponse;
import app.confuzion.backend.model.record.EventRequest;
import app.confuzion.backend.model.record.EventResponse;
import app.confuzion.backend.model.record.EventResponse.AudienceEntry;
import app.confuzion.backend.model.record.EventResponse.AudienceType;
import app.confuzion.backend.model.record.EventResponse.LocationVisibility;
import app.confuzion.backend.model.record.EventResponse.Status;
import app.confuzion.backend.model.record.MyStatus;
import app.confuzion.backend.model.record.NotificationPayload;
import app.confuzion.backend.model.record.NotificationPayload.Change;
import app.confuzion.backend.model.record.NotificationResponse.Type;
import app.confuzion.backend.model.record.PersonResponse;
import app.confuzion.backend.repository.AppUserRepository;
import app.confuzion.backend.repository.AuditLogRepository;
import app.confuzion.backend.repository.EventAudienceRepository;
import app.confuzion.backend.repository.EventAudienceRepository.LabelledEntry;
import app.confuzion.backend.repository.EventRepository;
import app.confuzion.backend.repository.NotificationRepository;
import app.confuzion.backend.repository.RsvpRepository;
import app.confuzion.backend.repository.SocialRepository;
import app.confuzion.backend.repository.UserGuildRepository;

/**
 * Events, their audiences and the feed (D-012). Only the host may create, edit
 * or cancel an event, and every read for anyone else goes through the one
 * visibility rule, {@link EventRepository#VISIBLE}. An event the caller can't
 * see, or changes without being its host, is a 404
 * ({@link NoSuchElementException}), whether or not it exists.
 *
 * @author mcawful
 */
@Service
public class EventService {
	/**
	 * The most events a host may create in any 24 hours, cancelled ones included.
	 */
	static final int MAX_EVENTS_PER_DAY = 20;

	/**
	 * How long an event without an end counts as running.
	 */
	private static final Duration DEFAULT_LENGTH = Duration.ofHours(3);

	/**
	 * The scope that lists the caller's own events.
	 */
	private static final String HOSTING = "hosting";

	/**
	 * The scope that lists My schedule.
	 */
	private static final String ATTENDING = "attending";

	/**
	 * Separates a cursor's fields before encoding.
	 */
	private static final String CURSOR_SEPARATOR = ",";

	/**
	 * The {@link EventRepository}.
	 */
	private final EventRepository eventRepository;

	/**
	 * The {@link EventAudienceRepository}.
	 */
	private final EventAudienceRepository audienceRepository;

	/**
	 * The {@link AppUserRepository}, for hosts' profiles.
	 */
	private final AppUserRepository appUserRepository;

	/**
	 * The {@link UserGuildRepository}, for the servers a host may target.
	 */
	private final UserGuildRepository userGuildRepository;

	/**
	 * The {@link SocialRepository}, for the circles and people a host may target.
	 */
	private final SocialRepository socialRepository;

	/**
	 * The {@link RsvpRepository}, for where the viewer stands and capacity edits.
	 */
	private final RsvpRepository rsvpRepository;

	/**
	 * The {@link NotificationRepository}.
	 */
	private final NotificationRepository notificationRepository;

	/**
	 * The {@link AuditLogRepository}, for moderators' actions.
	 */
	private final AuditLogRepository auditLogRepository;

	/**
	 * Creates the service.
	 *
	 * @param eventRepository        the {@link EventRepository}
	 * @param audienceRepository     the {@link EventAudienceRepository}
	 * @param appUserRepository      the {@link AppUserRepository}
	 * @param userGuildRepository    the {@link UserGuildRepository}
	 * @param socialRepository       the {@link SocialRepository}
	 * @param rsvpRepository         the {@link RsvpRepository}
	 * @param notificationRepository the {@link NotificationRepository}
	 * @param auditLogRepository     the {@link AuditLogRepository}
	 */
	public EventService(EventRepository eventRepository, EventAudienceRepository audienceRepository,
			AppUserRepository appUserRepository, UserGuildRepository userGuildRepository,
			SocialRepository socialRepository, RsvpRepository rsvpRepository,
			NotificationRepository notificationRepository, AuditLogRepository auditLogRepository) {
		this.eventRepository = eventRepository;
		this.audienceRepository = audienceRepository;
		this.appUserRepository = appUserRepository;
		this.userGuildRepository = userGuildRepository;
		this.socialRepository = socialRepository;
		this.rsvpRepository = rsvpRepository;
		this.notificationRepository = notificationRepository;
		this.auditLogRepository = auditLogRepository;
	}

	/**
	 * Creates and publishes an event hosted by the caller, who holds its first spot
	 * with a confirmed RSVP (D-013). The count of the host's recent events runs
	 * under a lock on their {@code app_user} row, so concurrent creates can't
	 * overshoot the cap.
	 *
	 * @param hostId  the signed-in user's ID
	 * @param request the validated {@link EventRequest}
	 * @return the new event as its host sees it
	 * @throws LimitExceededException when the host already created
	 *                                {@value #MAX_EVENTS_PER_DAY} events in the
	 *                                last 24 hours
	 * @throws NoSuchElementException when an audience entry is one the host can't
	 *                                use
	 */
	@Transactional
	public EventResponse createEvent(UUID hostId, EventRequest request) {
		eventRepository.lockHost(hostId);
		if (eventRepository.countCreatedInLastDay(hostId) >= MAX_EVENTS_PER_DAY)
			throw new LimitExceededException(Limit.EVENT_LIMIT);
		var refs = resolveAudience(hostId, request.audience(), Set.of());

		var event = new Event();
		event.setPublicId(UUID.randomUUID());
		event.setHostId(hostId);
		event.setStatus(Status.PUBLISHED);
		apply(event, request);
		eventRepository.save(event);
		eventRepository.addHostRsvp(event.getId());
		audienceRepository.saveAll(refs.stream().map(ref -> new EventAudience(event.getId(), ref)).toList());
		return toResponses(hostId, List.of(event), false).getFirst();
	}

	/**
	 * Returns an event the caller can see. A moderator also sees an event under
	 * moderation (hidden or reported), with its location, so they can review it
	 * (D-015 rule 8).
	 *
	 * @param viewer    the signed-in user's ID
	 * @param eventId   the event's public ID
	 * @param moderator whether the session holds MODERATOR or ADMIN in effect
	 * @return the event as the caller may see it
	 * @throws NoSuchElementException when it doesn't exist or the caller can't see
	 *                                it
	 */
	@Transactional(readOnly = true)
	public EventResponse getEvent(UUID viewer, UUID eventId, boolean moderator) {
		var event = eventRepository.findVisible(eventId, viewer, moderator).orElseThrow();
		return toResponses(viewer, List.of(event), moderator && eventRepository.isModerated(event.getId(), viewer))
				.getFirst();
	}

	/**
	 * Returns a page of upcoming events: the caller's feed, the events they host,
	 * or My schedule.
	 *
	 * @param viewer the signed-in user's ID
	 * @param scope  {@code feed}, {@code hosting} or {@code attending}, already
	 *               validated
	 * @param cursor the previous page's cursor, or {@code null} for the first page
	 * @param limit  the most events to return, already validated
	 * @return the page
	 * @throws InvalidInputException when the cursor is malformed or was issued for
	 *                               the other scope
	 */
	@Transactional(readOnly = true)
	public EventPageResponse getEvents(UUID viewer, String scope, String cursor, int limit) {
		var after = cursor == null ? Cursor.FIRST : Cursor.decode(scope, cursor);
		var events = switch (scope) {
		case HOSTING -> eventRepository.findHosting(viewer, after.startsAt(), after.id(), limit + 1);
		case ATTENDING -> eventRepository.findAttending(viewer, after.startsAt(), after.id(), limit + 1);
		default -> eventRepository.findFeed(viewer, after.startsAt(), after.id(), limit + 1);
		};
		if (events.size() <= limit)
			return new EventPageResponse(toResponses(viewer, events, false), null);
		var page = events.subList(0, limit);
		var last = page.getLast();
		return new EventPageResponse(toResponses(viewer, page, false),
				new Cursor(last.getStartsAt(), last.getPublicId()).encode(scope));
	}

	/**
	 * Replaces every editable field of an event the caller hosts, its audience
	 * included. Entries already on the event are kept without a re-check; only new
	 * ones must be usable. The event row stays locked until the transaction ends,
	 * so the capacity check can't race a join (D-013). If the start, end or
	 * location changed, everyone with an active RSVP but the host gets one
	 * {@code EVENT_CHANGED} notification, in the same transaction (D-014), unless
	 * the event is hidden by moderators (D-015 rule 10). Once saved, waitlisted
	 * people fill any free spots in queue order, if the event hasn't started.
	 *
	 * @param hostId  the signed-in user's ID
	 * @param eventId the event's public ID
	 * @param request the validated {@link EventRequest}
	 * @return the updated event as its host sees it
	 * @throws NoSuchElementException    when the caller isn't the host, or a new
	 *                                   audience entry can't be used; nothing is
	 *                                   saved
	 * @throws EventNotEditableException when the event is cancelled or has ended
	 * @throws ConflictException         when the capacity is below the number
	 *                                   already confirmed; nothing is saved
	 * @throws InvalidInputException     when the request moves the start into the
	 *                                   past
	 */
	@Transactional
	public EventResponse updateEvent(UUID hostId, UUID eventId, EventRequest request) {
		var event = eventRepository.findByPublicIdAndHostId(eventId, hostId).orElseThrow();
		var now = Instant.now();
		if (event.getStatus() == Status.CANCELLED || !end(event).isAfter(now))
			throw new EventNotEditableException();
		if (!micros(request.startsAt()).equals(event.getStartsAt()) && !request.startsAt().isAfter(now))
			throw new InvalidInputException();
		if (request.capacity() != null && request.capacity() < event.getConfirmedCount())
			throw new ConflictException(Conflict.CAPACITY_BELOW_CONFIRMED);

		var current = audienceRepository.findByEventIdOrderById(event.getId());
		var currentRefs = current.stream().map(EventAudience::ref).collect(toSet());
		var refs = resolveAudience(hostId, request.audience(), currentRefs);
		audienceRepository.deleteAll(current.stream().filter(entry -> !refs.contains(entry.ref())).toList());
		audienceRepository.saveAll(refs.stream().filter(ref -> !currentRefs.contains(ref))
				.map(ref -> new EventAudience(event.getId(), ref)).toList());
		var changes = changes(event, request);
		apply(event, request);
		eventRepository.flush();
		if (!changes.isEmpty() && event.getModerationReason() == null)
			notificationRepository.insertForActiveRsvps(event.getId(), hostId, Type.EVENT_CHANGED,
					new NotificationPayload(event.getTitle(), null, event.getStartsAt(), changes, null, null, null,
							null));
		rsvpRepository.fillFreeSpots(event.getId(),
				event.getCapacity() == null ? Integer.MAX_VALUE : event.getCapacity() - event.getConfirmedCount());
		return toResponses(hostId, List.of(event), false).getFirst();
	}

	/**
	 * Cancels an event the caller hosts, and tells everyone with an active RSVP but
	 * the host, in the same transaction (D-014). Cancelling it again is a no-op
	 * that notifies nobody, and so is cancelling one that has already ended.
	 *
	 * @param hostId  the signed-in user's ID
	 * @param eventId the event's public ID
	 * @throws NoSuchElementException when the caller isn't the host
	 */
	@Transactional
	public void cancelEvent(UUID hostId, UUID eventId) {
		cancel(eventRepository.findByPublicIdAndHostId(eventId, hostId).orElseThrow());
	}

	/**
	 * Cancels an event as a moderator, through the host's cancel path, so everyone
	 * with an active RSVP but the host gets the usual {@code EVENT_CANCELLED} with
	 * no reason. The host gets {@code EVENT_CANCELLED} with the reason, and the
	 * cancel is audit-logged (D-015 rule 11). A cancelled or ended event is left as
	 * it is, and nobody is told.
	 *
	 * @param moderatorId the signed-in moderator's ID
	 * @param eventId     the event's public ID
	 * @param reason      the moderator's reason, already validated
	 * @throws NoSuchElementException when the event doesn't exist
	 */
	@Transactional
	public void cancelAsModerator(UUID moderatorId, UUID eventId, String reason) {
		var event = eventRepository.lockByPublicId(eventId).orElseThrow();
		if (event.getStatus() == Status.CANCELLED || !end(event).isAfter(Instant.now()))
			return;
		cancel(event);
		notificationRepository.insert(event.getHostId(), Type.EVENT_CANCELLED, event.getId(),
				new NotificationPayload(event.getTitle(), null, null, null, null, null, null, reason));
		auditLogRepository.insert(moderatorId, Action.EVENT_CANCELLED, TargetType.EVENT, eventId.toString(),
				new AuditDetails(reason, null, null, null, null, null, null));
	}

	/**
	 * Hides an event from everyone but its host and moderators (D-015 rule 10).
	 * Attendees keep their RSVPs and are told nothing; the host gets
	 * {@code EVENT_HIDDEN} with the reason. Audit-logged. Hiding a hidden event
	 * changes nothing.
	 *
	 * @param moderatorId the signed-in moderator's ID
	 * @param eventId     the event's public ID
	 * @param reason      the moderator's reason, already validated
	 * @throws NoSuchElementException when the event doesn't exist
	 */
	@Transactional
	public void hide(UUID moderatorId, UUID eventId, String reason) {
		var event = eventRepository.lockByPublicId(eventId).orElseThrow();
		if (event.getModerationReason() != null)
			return;
		eventRepository.setHidden(event.getId(), reason);
		notificationRepository.insert(event.getHostId(), Type.EVENT_HIDDEN, event.getId(),
				new NotificationPayload(event.getTitle(), null, null, null, null, null, null, reason));
		auditLogRepository.insert(moderatorId, Action.EVENT_HIDDEN, TargetType.EVENT, eventId.toString(),
				new AuditDetails(reason, null, null, null, null, null, null));
	}

	/**
	 * Makes a hidden event visible to its audience again and clears its reason.
	 * Nobody is told of the unhide. Spots freed while it was hidden then go to the
	 * waitlist in queue order, as after an edit, so a new join can't take them
	 * first (D-013); those people get the usual offer. Audit-logged. Unhiding a
	 * visible event changes nothing.
	 *
	 * @param moderatorId the signed-in moderator's ID
	 * @param eventId     the event's public ID
	 * @throws NoSuchElementException when the event doesn't exist
	 */
	@Transactional
	public void unhide(UUID moderatorId, UUID eventId) {
		var event = eventRepository.lockByPublicId(eventId).orElseThrow();
		if (event.getModerationReason() == null)
			return;
		eventRepository.setHidden(event.getId(), null);
		rsvpRepository.fillFreeSpots(event.getId(),
				event.getCapacity() == null ? Integer.MAX_VALUE : event.getCapacity() - event.getConfirmedCount());
		auditLogRepository.insert(moderatorId, Action.EVENT_UNHIDDEN, TargetType.EVENT, eventId.toString(), null);
	}

	/**
	 * Cancels an event that the caller has locked, and tells everyone with an
	 * active RSVP but the host, in the same transaction (D-014), hidden or not
	 * (D-015 rule 10). Cancelling it again is a no-op that notifies nobody, and so
	 * is cancelling one that has already ended.
	 *
	 * @param event the locked {@link Event}
	 */
	private void cancel(Event event) {
		if (event.getStatus() == Status.CANCELLED)
			return;
		eventRepository.cancel(event.getPublicId(), event.getHostId());
		if (end(event).isAfter(Instant.now()))
			notificationRepository.insertForActiveRsvps(event.getId(), event.getHostId(), Type.EVENT_CANCELLED,
					new NotificationPayload(event.getTitle(), null, null, null, null, null, null, null));
	}

	/**
	 * Returns what an edit changes that guests care about: the start, the end or
	 * the location. Nothing else notifies anyone. Times are compared at the
	 * database's precision.
	 *
	 * @param event   the event as stored
	 * @param request the edit
	 * @return the changes, in the order start, end, location; empty when none
	 */
	private static List<Change> changes(Event event, EventRequest request) {
		var changes = new ArrayList<Change>(3);
		if (!event.getStartsAt().equals(micros(request.startsAt())))
			changes.add(Change.START);
		if (!Objects.equals(event.getEndsAt(), micros(request.endsAt())))
			changes.add(Change.END);
		if (!Objects.equals(event.getLocationText(), request.location()))
			changes.add(Change.LOCATION);
		return changes;
	}

	/**
	 * Resolves a request's audience to internal references, and checks that every
	 * entry not already on the event is one the host may use: a server where they
	 * are discoverable, one of their own circles, {@code FRIENDS}, or a person they
	 * can pick (D-011, by the same rule as people search). Circles are resolved
	 * among the host's own, which also covers circle entries already on the event.
	 *
	 * @param hostId   the host's user ID
	 * @param entries  the requested entries, already validated
	 * @param existing the entries already on the event, which aren't re-checked
	 * @return the distinct references, in request order
	 * @throws NoSuchElementException when a new entry can't be used
	 * @throws InvalidInputException  when a server ID doesn't fit in a
	 *                                {@code bigint}
	 */
	private Set<Ref> resolveAudience(UUID hostId, List<EventRequest.AudienceEntry> entries, Set<Ref> existing) {
		var refs = new LinkedHashSet<Ref>();
		for (var entry : entries)
			refs.add(switch (entry.type()) {
			case GUILD -> new Ref(AudienceType.GUILD, UserService.parseSnowflake(entry.guildId()), null, null);
			case FRIENDS -> new Ref(AudienceType.FRIENDS, null, null, null);
			case CIRCLE -> new Ref(AudienceType.CIRCLE, null,
					socialRepository.findCircle(hostId, entry.circleId()).orElseThrow().id(), null);
			case USER -> new Ref(AudienceType.USER, null, null, entry.userId());
			});
		for (var ref : refs) {
			var usable = existing.contains(ref) || switch (ref.type()) {
			case GUILD -> userGuildRepository.findById(new UserGuild.Key(hostId, ref.guildId()))
					.filter(UserGuild::isDiscoverable).isPresent();
			case USER -> socialRepository.isPickable(hostId, ref.userId());
			case FRIENDS, CIRCLE -> true;
			};
			if (!usable)
				throw new NoSuchElementException();
		}
		return refs;
	}

	/**
	 * Copies a request's editable fields onto an event. A blank description is
	 * stored as {@code null}, a missing offer window keeps the current one (D-014),
	 * and times are cut to the database's microseconds, so the response echoes what
	 * was stored.
	 *
	 * @param event   the {@link Event}
	 * @param request the validated {@link EventRequest}
	 */
	private static void apply(Event event, EventRequest request) {
		var description = request.description();
		event.setTitle(request.title());
		event.setDescription(description == null || description.isBlank() ? null : description);
		event.setLocationText(request.location());
		event.setTag(request.tag());
		event.setStartsAt(micros(request.startsAt()));
		event.setEndsAt(micros(request.endsAt()));
		event.setTimeZone(request.timeZone());
		event.setCapacity(request.capacity());
		if (request.confirmWindowMinutes() != null)
			event.setConfirmWindowMinutes(request.confirmWindowMinutes());
		event.setSignupOpensAt(micros(request.signupOpensAt()));
		event.setLocationVisibility(request.locationVisibility());
		event.setUpdatedAt(Instant.now());
	}

	/**
	 * Cuts a time to microseconds, the database's precision.
	 *
	 * @param time the time, or {@code null}
	 * @return the cut time, or {@code null}
	 */
	private static Instant micros(Instant time) {
		return time == null ? null : time.truncatedTo(ChronoUnit.MICROS);
	}

	/**
	 * Returns when an event counts as over: its end, or its start plus
	 * {@link #DEFAULT_LENGTH} when it has none.
	 *
	 * @param event the {@link Event}
	 * @return the end
	 */
	private static Instant end(Event event) {
		return event.getEndsAt() != null ? event.getEndsAt() : event.getStartsAt().plus(DEFAULT_LENGTH);
	}

	/**
	 * Maps events to what the viewer may see, loading hosts, where the viewer
	 * stands and (for the viewer's own events) audiences in one query each.
	 *
	 * @param viewer    the viewer's user ID
	 * @param events    the events, which the viewer may see
	 * @param reviewing whether a moderator is reviewing the one event under
	 *                  moderation, and so sees its location
	 * @return the responses, in the same order
	 */
	private List<EventResponse> toResponses(UUID viewer, List<Event> events, boolean reviewing) {
		var hosts = appUserRepository.findAllById(events.stream().map(Event::getHostId).collect(toSet())).stream()
				.collect(toMap(AppUser::getId, Function.identity()));
		var ownIds = events.stream().filter(event -> event.getHostId().equals(viewer)).map(Event::getId).toList();
		var audiences = ownIds.isEmpty() ? Map.<Long, List<AudienceEntry>>of()
				: audienceRepository.findLabelled(ownIds).stream().collect(
						groupingBy(LabelledEntry::getEventId, mapping(EventService::toAudienceEntry, toList())));
		var statuses = rsvpRepository.findStatuses(viewer, events.stream().map(Event::getId).toList());
		return events.stream().map(event -> toResponse(viewer, event, hosts.get(event.getHostId()),
				statuses.getOrDefault(event.getId(), MyStatus.NONE), audiences, reviewing)).toList();
	}

	/**
	 * Maps one event to what the viewer may see. Only the host gets the audience,
	 * and a {@link LocationVisibility#CONFIRMED} location is withheld from everyone
	 * but the host, confirmed attendees (D-013) and a moderator reviewing it (D-015
	 * rule 8).
	 *
	 * @param viewer    the viewer's user ID
	 * @param event     the {@link Event}
	 * @param host      the host
	 * @param myStatus  where the viewer stands
	 * @param audiences the audiences of the viewer's own events, by event ID
	 * @param reviewing whether a moderator is reviewing it
	 * @return the {@link EventResponse}
	 */
	private static EventResponse toResponse(UUID viewer, Event event, AppUser host, MyStatus myStatus,
			Map<Long, List<AudienceEntry>> audiences, boolean reviewing) {
		var isHost = event.getHostId().equals(viewer);
		var locationHidden = !isHost && !reviewing && myStatus != MyStatus.CONFIRMED
				&& event.getLocationVisibility() == LocationVisibility.CONFIRMED;
		return new EventResponse(event.getPublicId(),
				new PersonResponse(host.getId(),
						host.getGlobalName() != null ? host.getGlobalName() : host.getUsername(),
						UserService.avatarUrl(host.getDiscordId(), host.getAvatarHash())),
				event.getTitle(), event.getDescription(), locationHidden ? null : event.getLocationText(),
				locationHidden, event.getLocationVisibility(), event.getTag(), event.getStartsAt(), event.getEndsAt(),
				event.getTimeZone(), event.getSignupOpensAt(), event.getCapacity(), event.getConfirmWindowMinutes(),
				event.getStatus(), isHost, myStatus, isHost ? audiences.getOrDefault(event.getId(), List.of()) : null,
				isHost ? event.getModerationReason() : null);
	}

	/**
	 * Maps a labelled entry to the response's audience entry.
	 *
	 * @param entry the {@link LabelledEntry}
	 * @return the {@link AudienceEntry}
	 */
	private static AudienceEntry toAudienceEntry(LabelledEntry entry) {
		return new AudienceEntry(AudienceType.valueOf(entry.getType()),
				entry.getGuildId() == null ? null : entry.getGuildId().toString(), entry.getCircleId(),
				entry.getUserId(), entry.getLabel());
	}

	/**
	 * A position in {@code (startsAt, public ID)} order. Clients get it as an
	 * opaque base64url string that names the scope it was issued for.
	 *
	 * @param startsAt the last event's start
	 * @param id       the last event's public ID
	 * @author mcawful
	 */
	private record Cursor(Instant startsAt, UUID id) {
		/**
		 * Before every event.
		 */
		static final Cursor FIRST = new Cursor(Instant.EPOCH, new UUID(0, 0));

		/**
		 * Encodes the cursor for a scope.
		 *
		 * @param scope the scope
		 * @return the opaque cursor
		 */
		String encode(String scope) {
			var raw = String.join(CURSOR_SEPARATOR, scope, startsAt.toString(), id.toString());
			return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
		}

		/**
		 * Decodes a cursor that must have been issued for a scope. Its start must be
		 * within two years of now, as every issued cursor's is, which also keeps it
		 * inside the database's range.
		 *
		 * @param scope  the scope
		 * @param cursor the opaque cursor, already matching the base64url alphabet
		 * @return the cursor
		 * @throws InvalidInputException when it is malformed, out of range or for
		 *                               another scope
		 */
		static Cursor decode(String scope, String cursor) {
			Cursor decoded;
			try {
				var parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
						.split(CURSOR_SEPARATOR, -1);
				if (parts.length != 3 || !parts[0].equals(scope))
					throw new InvalidInputException();
				decoded = new Cursor(Instant.parse(parts[1]), UUID.fromString(parts[2]));
			} catch (IllegalArgumentException | DateTimeException e) {
				throw new InvalidInputException(e);
			}
			var horizon = EventRequest.MAX_HORIZON.multipliedBy(2);
			var now = Instant.now();
			if (decoded.startsAt().isBefore(now.minus(horizon)) || decoded.startsAt().isAfter(now.plus(horizon)))
				throw new InvalidInputException();
			return decoded;
		}
	}
}
