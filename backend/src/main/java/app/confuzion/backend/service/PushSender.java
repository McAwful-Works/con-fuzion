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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.zerodeplibs.webpush.PushSubscription;
import com.zerodeplibs.webpush.VAPIDKeyPair;
import com.zerodeplibs.webpush.httpclient.StandardHttpClientRequestPreparer;

import app.confuzion.backend.config.VapidKeys;
import app.confuzion.backend.model.record.NotificationPayload;
import app.confuzion.backend.model.record.NotificationResponse.Type;
import app.confuzion.backend.repository.PushRepository;
import app.confuzion.backend.repository.PushRepository.Pending;
import app.confuzion.backend.repository.PushRepository.Subscription;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends {@code PENDING} notifications as Web Push (RFC 8030, 8291 and 8292) to
 * every subscription their recipient has (plan B8, D-014). Every task runs it:
 * a batch is claimed with {@code SKIP LOCKED} and marked {@code SENT} in one
 * statement, so two senders never push the same row, and no row lock is held
 * while the pushes go out. Sends run in parallel on virtual threads, each
 * bounded by a connect and a request timeout.
 * <p>
 * A row stays {@code SENT} when at least one browser took it, and ends
 * {@code FAILED} when none did, the user has push off or no subscription left,
 * or the row can't be read. A subscription the push service answers 404 or 410
 * for is deleted. A 429 pauses sends to that push service for its
 * {@code Retry-After}, and a row that only the pause held back goes back to
 * {@code PENDING}. A run walks the whole queue once, so rows held back at its
 * head never stall the rest. Delivery is at most once: a task that stops
 * mid-batch leaves those rows {@code SENT}, and the inbox still has them.
 * <p>
 * The sender POSTs only to an https endpoint on an allowed push service host,
 * checked again here before each send, and never follows a redirect, so a
 * stored endpoint can't point it anywhere else. Endpoints work like bearer
 * tokens, so logs name a subscription by its ID only, and never hold a key or a
 * payload.
 *
 * @author mcawful
 */
@Component
public class PushSender {
	/**
	 * The most notifications claimed at once.
	 */
	static final int BATCH_SIZE = 50;

	/**
	 * How long to wait for a connection to a push service.
	 */
	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

	/**
	 * How long to wait for a push service to answer.
	 */
	static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

	/**
	 * How long a push service keeps a message for a browser that's offline.
	 */
	static final Duration TTL = Duration.ofHours(6);

	/**
	 * The pause after a 429 without a usable {@code Retry-After}.
	 */
	static final Duration DEFAULT_RETRY_AFTER = Duration.ofMinutes(1);

	/**
	 * The longest pause a {@code Retry-After} can ask for.
	 */
	static final Duration MAX_RETRY_AFTER = Duration.ofHours(1);

	/**
	 * The push service hosts a subscription may use (D-014 rule 13), besides
	 * {@link #WNS_HOST}.
	 */
	private static final Set<String> HOSTS = Set.of("fcm.googleapis.com", "updates.push.services.mozilla.com",
			"web.push.apple.com");

	/**
	 * A Windows push service (WNS) host: one label under
	 * {@code notify.windows.com}.
	 */
	private static final Pattern WNS_HOST = Pattern.compile("[a-z0-9-]{1,63}\\.notify\\.windows\\.com");

	/**
	 * The {@link Logger}.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(PushSender.class);

	/**
	 * The {@link PushRepository}.
	 */
	private final PushRepository pushRepository;

	/**
	 * The {@link VapidKeys}.
	 */
	private final VapidKeys vapidKeys;

	/**
	 * The {@link JsonMapper}, to write push messages.
	 */
	private final JsonMapper jsonMapper;

	/**
	 * The VAPID JWT's subject: the app's origin.
	 */
	private final String subject;

	/**
	 * The {@link HttpClient} for push services.
	 */
	private final HttpClient http;

	/**
	 * The {@link Clock}.
	 */
	private final Clock clock;

	/**
	 * Until when each rate-limited push service host gets nothing from this task.
	 */
	private final Map<String, Instant> pausedUntil = new ConcurrentHashMap<>();

	/**
	 * Creates the sender, with an {@link HttpClient} that never follows redirects
	 * and gives up connecting after {@link #CONNECT_TIMEOUT}.
	 *
	 * @param pushRepository the {@link PushRepository}
	 * @param vapidKeys      the {@link VapidKeys}
	 * @param jsonMapper     the {@link JsonMapper}
	 * @param origin         the app's public origin, the VAPID subject
	 */
	@Autowired
	public PushSender(PushRepository pushRepository, VapidKeys vapidKeys, JsonMapper jsonMapper,
			@Value("${con-fuzion.app.origin}") String origin) {
		this(pushRepository, vapidKeys, jsonMapper, origin, httpClient(), Clock.systemUTC());
	}

	/**
	 * Creates the sender with a given {@link HttpClient} and {@link Clock}, for
	 * tests.
	 *
	 * @param pushRepository the {@link PushRepository}
	 * @param vapidKeys      the {@link VapidKeys}
	 * @param jsonMapper     the {@link JsonMapper}
	 * @param origin         the app's public origin, the VAPID subject
	 * @param http           the {@link HttpClient}
	 * @param clock          the {@link Clock}
	 */
	PushSender(PushRepository pushRepository, VapidKeys vapidKeys, JsonMapper jsonMapper, String origin,
			HttpClient http, Clock clock) {
		this.pushRepository = pushRepository;
		this.vapidKeys = vapidKeys;
		this.jsonMapper = jsonMapper;
		this.subject = origin;
		this.http = http;
		this.clock = clock;
	}

	/**
	 * Builds the client for push services: it never follows a redirect, and gives
	 * up connecting after {@link #CONNECT_TIMEOUT}.
	 *
	 * @return the {@link HttpClient}
	 */
	static HttpClient httpClient() {
		return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(CONNECT_TIMEOUT)
				.build();
	}

	/**
	 * Sends what's pending, a batch at a time, walking the queue once from the
	 * oldest row. It runs five seconds after the last run ended, or as
	 * {@code con-fuzion.push.send-interval} (an ISO-8601 duration) says. With no
	 * VAPID keys, push is off and it does nothing.
	 */
	@Scheduled(fixedDelayString = "${con-fuzion.push.send-interval:PT5S}", initialDelayString = "${con-fuzion.push.send-interval:PT5S}")
	public void run() {
		var keyPair = vapidKeys.keyPair();
		if (keyPair.isEmpty())
			return;
		var afterId = 0L;
		List<Pending> rows;
		do {
			rows = pushRepository.claimPending(afterId, BATCH_SIZE);
			if (rows.isEmpty())
				return;
			sendBatch(keyPair.get(), rows);
			afterId = rows.getLast().id();
		} while (rows.size() == BATCH_SIZE);
	}

	/**
	 * Sends a claimed batch, then marks the rows that weren't sent.
	 *
	 * @param keyPair the VAPID key pair
	 * @param rows    the claimed rows, already {@code SENT}
	 */
	private void sendBatch(VAPIDKeyPair keyPair, List<Pending> rows) {
		var userIds = rows.stream().filter(Pending::pushEnabled).map(Pending::userId).collect(Collectors.toSet());
		var subscriptions = pushRepository.findSubscriptions(userIds).stream()
				.collect(Collectors.groupingBy(Subscription::userId));

		var sends = new ArrayList<List<Send>>();
		var failed = new ArrayList<Long>();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (var row : rows) {
				var message = message(row);
				var mine = row.pushEnabled() ? subscriptions.getOrDefault(row.userId(), List.of())
						: List.<Subscription>of();
				if (message == null || mine.isEmpty()) {
					failed.add(row.id());
					sends.add(List.of());
					continue;
				}
				sends.add(mine.stream().map(subscription -> new Send(subscription,
						executor.submit(() -> send(keyPair, subscription, message)))).toList());
			}
		}

		var heldBack = new ArrayList<Long>();
		var gone = new ArrayList<Long>();
		for (var i = 0; i < rows.size(); i++) {
			if (sends.get(i).isEmpty())
				continue;
			var outcomes = new ArrayList<Outcome>();
			for (var send : sends.get(i)) {
				outcomes.add(send.outcome());
				if (send.outcome() == Outcome.GONE)
					gone.add(send.subscription().id());
			}
			if (outcomes.contains(Outcome.ACCEPTED))
				continue;
			(outcomes.contains(Outcome.DEFERRED) ? heldBack : failed).add(rows.get(i).id());
		}
		pushRepository.markPushStatus(failed, "FAILED");
		pushRepository.markPushStatus(heldBack, "PENDING");
		pushRepository.deleteByIds(gone);
		LOGGER.info("Push batch: {} sent, {} failed, {} held back, {} dead subscriptions deleted",
				rows.size() - failed.size() - heldBack.size(), failed.size(), heldBack.size(), gone.size());
	}

	/**
	 * Sends one notification to one subscription.
	 *
	 * @param keyPair      the VAPID key pair
	 * @param subscription the subscription
	 * @param message      the push message
	 * @return how it went
	 */
	private Outcome send(VAPIDKeyPair keyPair, Subscription subscription, byte[] message) {
		var host = allowedHost(subscription.endpoint());
		if (host == null) {
			LOGGER.warn("Push subscription {} isn't on an allowed push service, so nothing was sent",
					subscription.id());
			return Outcome.FAILED;
		}
		var paused = pausedUntil.get(host);
		if (paused != null && clock.instant().isBefore(paused))
			return Outcome.DEFERRED;

		HttpRequest request;
		try {
			var keys = new PushSubscription.Keys();
			keys.setP256dh(subscription.p256dh());
			keys.setAuth(subscription.auth());
			var target = new PushSubscription();
			target.setEndpoint(subscription.endpoint());
			target.setKeys(keys);
			request = StandardHttpClientRequestPreparer.getBuilder().pushSubscription(target).vapidJWTSubject(subject)
					.vapidJWTExpiresAfter(12, TimeUnit.HOURS).ttl(TTL.toSeconds(), TimeUnit.SECONDS)
					.pushMessage(message).build(keyPair).toRequestBuilder().timeout(REQUEST_TIMEOUT).build();
		} catch (RuntimeException e) {
			// The exception's message can quote the keys, so only its type is logged.
			LOGGER.warn("Push subscription {} couldn't be encrypted for ({})", subscription.id(),
					e.getClass().getSimpleName());
			return Outcome.FAILED;
		}

		try {
			var response = http.send(request, BodyHandlers.discarding());
			var status = response.statusCode();
			if (status >= 200 && status < 300)
				return Outcome.ACCEPTED;
			if (status == 404 || status == 410)
				return Outcome.GONE;
			if (status == 429) {
				var now = clock.instant();
				var until = now.plus(retryAfter(response.headers().firstValue("Retry-After").orElse(null), now));
				pausedUntil.merge(host, until, (a, b) -> a.isAfter(b) ? a : b);
				LOGGER.warn("Push service {} is rate limiting: paused until {}", host, until);
				return Outcome.DEFERRED;
			}
			LOGGER.warn("Push to subscription {} got HTTP {}", subscription.id(), status);
			return Outcome.FAILED;
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Push to subscription {} failed ({})", subscription.id(), e.getClass().getSimpleName());
			return Outcome.FAILED;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Outcome.FAILED;
		}
	}

	/**
	 * Writes a notification's push message: only its type, its event's public ID
	 * and the lock-screen text (D-014 rule 8), well under the 4 KB push limit.
	 *
	 * @param row the notification
	 * @return the UTF-8 JSON, or {@code null} when the row can't be read or written
	 */
	private byte[] message(Pending row) {
		if (row.type() == null) {
			LOGGER.warn("Notification {} can't be read, so it wasn't pushed", row.id());
			return null;
		}
		try {
			return jsonMapper
					.writeValueAsBytes(new Message(row.type(), row.eventId(), text(row.type(), row.payload())));
		} catch (RuntimeException e) {
			// Only the type: a message could quote the payload.
			LOGGER.warn("Notification {} couldn't be written as a push ({})", row.id(), e.getClass().getSimpleName());
			return null;
		}
	}

	/**
	 * Returns the lock-screen text for a notification: the event's title and what
	 * happened, never a location or anyone's name (D-014 rule 8).
	 *
	 * @param type    what happened
	 * @param payload what the notification says
	 * @return the text
	 */
	static String text(Type type, NotificationPayload payload) {
		var title = payload.eventTitle();
		return switch (type) {
		case SPOT_OFFERED -> "A spot opened up for you at " + title + ". Accept it before the offer runs out.";
		case SPOT_CONFIRMED -> "You're confirmed for " + title + ".";
		case EVENT_CHANGED -> payload.changes() == null || payload.changes().isEmpty() ? title + " was updated."
				: title + " has a new " + changes(payload.changes()) + ".";
		case EVENT_CANCELLED -> title + " was cancelled.";
		case EVENT_REMINDER -> title + " starts in about an hour.";
		case FRIEND_ACCEPTED -> "Someone accepted your friend invite.";
		};
	}

	/**
	 * Lists what changed, as in "start time, end time and location".
	 *
	 * @param changes the changes, at least one
	 * @return the list
	 */
	private static String changes(List<NotificationPayload.Change> changes) {
		var words = changes.stream().map(change -> switch (change) {
		case START -> "start time";
		case END -> "end time";
		case LOCATION -> "location";
		}).toList();
		if (words.size() == 1)
			return words.getFirst();
		return String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.getLast();
	}

	/**
	 * Returns an endpoint's host when it's an https URL on an allowed push service
	 * (D-014 rule 13), with no user info and no port. This is the second check
	 * after the request pattern, in case that's ever loosened.
	 *
	 * @param endpoint the endpoint
	 * @return the host, or {@code null} when it isn't allowed
	 */
	static String allowedHost(String endpoint) {
		URI uri;
		try {
			uri = new URI(endpoint);
		} catch (URISyntaxException e) {
			return null;
		}
		var host = uri.getHost();
		if (!"https".equals(uri.getScheme()) || host == null || uri.getRawUserInfo() != null || uri.getPort() != -1)
			return null;
		host = host.toLowerCase(Locale.ROOT);
		return HOSTS.contains(host) || WNS_HOST.matcher(host).matches() ? host : null;
	}

	/**
	 * Reads a 429's {@code Retry-After}: seconds, or an HTTP date. A missing or
	 * unreadable one means {@link #DEFAULT_RETRY_AFTER}, and the result is kept
	 * between one second and {@link #MAX_RETRY_AFTER}.
	 *
	 * @param value the header, or {@code null}
	 * @param now   the current time, for a date
	 * @return how long to pause
	 */
	static Duration retryAfter(String value, Instant now) {
		var pause = DEFAULT_RETRY_AFTER;
		if (value != null) {
			try {
				pause = Duration.ofSeconds(Long.parseLong(value.strip()));
			} catch (NumberFormatException notSeconds) {
				try {
					pause = Duration.between(now,
							ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
				} catch (DateTimeParseException notADate) {
					// Keep the default.
				}
			}
		}
		if (pause.compareTo(Duration.ofSeconds(1)) < 0)
			return Duration.ofSeconds(1);
		return pause.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : pause;
	}

	/**
	 * How one send went.
	 *
	 * @author mcawful
	 */
	enum Outcome {
		/**
		 * The push service took it.
		 */
		ACCEPTED,

		/**
		 * The push service is rate limiting, so it wasn't sent and can be tried again.
		 */
		DEFERRED,

		/**
		 * The subscription is gone (404 or 410), so it's deleted.
		 */
		GONE,

		/**
		 * It wasn't sent, and won't be tried again.
		 */
		FAILED
	}

	/**
	 * A send in progress.
	 *
	 * @param subscription the subscription it goes to
	 * @param future       its outcome, once done
	 * @author mcawful
	 */
	private record Send(Subscription subscription, Future<Outcome> future) {
		/**
		 * Returns the outcome of a finished send. A send that threw counts as failed.
		 *
		 * @return the outcome
		 */
		Outcome outcome() {
			return future.state() == Future.State.SUCCESS ? future.resultNow() : Outcome.FAILED;
		}
	}

	/**
	 * The push message a browser receives.
	 *
	 * @param type    what happened
	 * @param eventId the event's public ID, left out for {@code FRIEND_ACCEPTED}
	 * @param text    the lock-screen text
	 * @author mcawful
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Message(Type type, UUID eventId, String text) {
	}
}
