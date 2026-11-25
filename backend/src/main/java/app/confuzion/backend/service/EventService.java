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
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.confuzion.backend.exception.EventNotEditableException;
import app.confuzion.backend.exception.InvalidInputException;
import app.confuzion.backend.exception.LimitExceededException;
import app.confuzion.backend.exception.LimitExceededException.Limit;
import app.confuzion.backend.model.entity.AppUser;
import app.confuzion.backend.model.entity.Event;
import app.confuzion.backend.model.entity.EventAudience;
import app.confuzion.backend.model.entity.EventAudience.Ref;
import app.confuzion.backend.model.entity.UserGuild;
import app.confuzion.backend.model.record.EventPageResponse;
import app.confuzion.backend.model.record.EventRequest;
import app.confuzion.backend.model.record.EventResponse;
import app.confuzion.backend.model.record.EventResponse.AudienceEntry;
import app.confuzion.backend.model.record.EventResponse.AudienceType;
import app.confuzion.backend.model.record.EventResponse.LocationVisibility;
import app.confuzion.backend.model.record.EventResponse.Status;
import app.confuzion.backend.model.record.MyStatus;
import app.confuzion.backend.model.record.PersonResponse;
import app.confuzion.backend.repository.AppUserRepository;
import app.confuzion.backend.repository.EventAudienceRepository;
import app.confuzion.backend.repository.EventAudienceRepository.LabelledEntry;
import app.confuzion.backend.repository.EventRepository;
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
	 * Creates the service.
	 *
	 * @param eventRepository     the {@link EventRepository}
	 * @param audienceRepository  the {@link EventAudienceRepository}
	 * @param appUserRepository   the {@link AppUserRepository}
	 * @param userGuildRepository the {@link UserGuildRepository}
	 * @param socialRepository    the {@link SocialRepository}
	 */
	public EventService(EventRepository eventRepository, EventAudienceRepository audienceRepository,
			AppUserRepository appUserRepository, UserGuildRepository userGuildRepository,
			SocialRepository socialRepository) {
		this.eventRepository = eventRepository;
		this.audienceRepository = audienceRepository;
		this.appUserRepository = appUserRepository;
		this.userGuildRepository = userGuildRepository;
		this.socialRepository = socialRepository;
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
		return toResponses(hostId, List.of(event)).getFirst();
	}

	/**
	 * Returns an event the caller can see.
	 *
	 * @param viewer  the signed-in user's ID
	 * @param eventId the event's public ID
	 * @return the event as the caller may see it
	 * @throws NoSuchElementException when it doesn't exist or the caller can't see
	 *                                it
	 */
	@Transactional(readOnly = true)
	public EventResponse getEvent(UUID viewer, UUID eventId) {
		return toResponses(viewer, List.of(eventRepository.findVisible(eventId, viewer).orElseThrow())).getFirst();
	}

	/**
	 * Returns a page of upcoming events: the caller's feed, or the events they
	 * host.
	 *
	 * @param viewer the signed-in user's ID
	 * @param scope  {@code feed} or {@code hosting}, already validated
	 * @param cursor the previous page's cursor, or {@code null} for the first page
	 * @param limit  the most events to return, already validated
	 * @return the page
	 * @throws InvalidInputException when the cursor is malformed or was issued for
	 *                               the other scope
	 */
	@Transactional(readOnly = true)
	public EventPageResponse getEvents(UUID viewer, String scope, String cursor, int limit) {
		var after = cursor == null ? Cursor.FIRST : Cursor.decode(scope, cursor);
		var events = HOSTING.equals(scope)
				? eventRepository.findHosting(viewer, after.startsAt(), after.id(), limit + 1)
				: eventRepository.findFeed(viewer, after.startsAt(), after.id(), limit + 1);
		if (events.size() <= limit)
			return new EventPageResponse(toResponses(viewer, events), null);
		var page = events.subList(0, limit);
		var last = page.getLast();
		return new EventPageResponse(toResponses(viewer, page),
				new Cursor(last.getStartsAt(), last.getPublicId()).encode(scope));
	}

	/**
	 * Replaces every editable field of an event the caller hosts, its audience
	 * included. Entries already on the event are kept without a re-check; only new
	 * ones must be usable. The event row stays locked until the transaction ends.
	 *
	 * @param hostId  the signed-in user's ID
	 * @param eventId the event's public ID
	 * @param request the validated {@link EventRequest}
	 * @return the updated event as its host sees it
	 * @throws NoSuchElementException    when the caller isn't the host, or a new
	 *                                   audience entry can't be used; nothing is
	 *                                   saved
	 * @throws EventNotEditableException when the event is cancelled or has ended
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

		var current = audienceRepository.findByEventIdOrderById(event.getId());
		var currentRefs = current.stream().map(EventAudience::ref).collect(toSet());
		var refs = resolveAudience(hostId, request.audience(), currentRefs);
		audienceRepository.deleteAll(current.stream().filter(entry -> !refs.contains(entry.ref())).toList());
		audienceRepository.saveAll(refs.stream().filter(ref -> !currentRefs.contains(ref))
				.map(ref -> new EventAudience(event.getId(), ref)).toList());
		apply(event, request);
		eventRepository.flush();
		return toResponses(hostId, List.of(event)).getFirst();
	}

	/**
	 * Cancels an event the caller hosts. Cancelling it again is a no-op.
	 *
	 * @param hostId  the signed-in user's ID
	 * @param eventId the event's public ID
	 * @throws NoSuchElementException when the caller isn't the host
	 */
	@Transactional
	public void cancelEvent(UUID hostId, UUID eventId) {
		if (eventRepository.cancel(eventId, hostId) == 0)
			throw new NoSuchElementException();
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
	 * stored as {@code null}, and times are cut to the database's microseconds, so
	 * the response echoes what was stored.
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
	 * Maps events to what the viewer may see, loading hosts and (for the viewer's
	 * own events) audiences in one query each.
	 *
	 * @param viewer the viewer's user ID
	 * @param events the events, which the viewer may see
	 * @return the responses, in the same order
	 */
	private List<EventResponse> toResponses(UUID viewer, List<Event> events) {
		var hosts = appUserRepository.findAllById(events.stream().map(Event::getHostId).collect(toSet())).stream()
				.collect(toMap(AppUser::getId, Function.identity()));
		var ownIds = events.stream().filter(event -> event.getHostId().equals(viewer)).map(Event::getId).toList();
		var audiences = ownIds.isEmpty() ? Map.<Long, List<AudienceEntry>>of()
				: audienceRepository.findLabelled(ownIds).stream().collect(
						groupingBy(LabelledEntry::getEventId, mapping(EventService::toAudienceEntry, toList())));
		return events.stream().map(event -> toResponse(viewer, event, hosts.get(event.getHostId()), audiences))
				.toList();
	}

	/**
	 * Maps one event to what the viewer may see. Only the host gets the audience,
	 * and a {@link LocationVisibility#CONFIRMED} location is withheld from everyone
	 * else (until M3 adds confirmed attendees).
	 *
	 * @param viewer    the viewer's user ID
	 * @param event     the {@link Event}
	 * @param host      the host
	 * @param audiences the audiences of the viewer's own events, by event ID
	 * @return the {@link EventResponse}
	 */
	private static EventResponse toResponse(UUID viewer, Event event, AppUser host,
			Map<Long, List<AudienceEntry>> audiences) {
		var isHost = event.getHostId().equals(viewer);
		var locationHidden = !isHost && event.getLocationVisibility() == LocationVisibility.CONFIRMED;
		return new EventResponse(event.getPublicId(),
				new PersonResponse(host.getId(),
						host.getGlobalName() != null ? host.getGlobalName() : host.getUsername(),
						UserService.avatarUrl(host.getDiscordId(), host.getAvatarHash())),
				event.getTitle(), event.getDescription(), locationHidden ? null : event.getLocationText(),
				locationHidden, event.getLocationVisibility(), event.getTag(), event.getStartsAt(), event.getEndsAt(),
				event.getTimeZone(), event.getSignupOpensAt(), event.getCapacity(), event.getStatus(), isHost,
				// Until joining is built (#54), the host's RSVP is the only one there is.
				isHost ? MyStatus.CONFIRMED : MyStatus.NONE,
				isHost ? audiences.getOrDefault(event.getId(), List.of()) : null);
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
