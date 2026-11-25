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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import app.confuzion.backend.TestcontainersConfiguration;
import app.confuzion.backend.config.VapidKeys;
import app.confuzion.backend.model.record.NotificationPayload;
import app.confuzion.backend.model.record.NotificationResponse.Type;
import app.confuzion.backend.repository.NotificationRepository;
import app.confuzion.backend.repository.PushRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration tests for the {@link PushSender} against Postgres and a
 * {@link StubPushService}: what a browser receives decrypts with its own keys,
 * the VAPID JWT verifies, dead subscriptions are deleted, a 429 holds rows
 * back, and two senders never push a row twice (D-014). Other test classes
 * leave {@code PENDING} rows too, so every check looks only at this test's own
 * endpoints and rows.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PushSenderIntegrationTest {
	/**
	 * The VAPID subject the senders here use.
	 */
	private static final String ORIGIN = "https://con-fuzion.example";

	/**
	 * A source of Discord IDs.
	 */
	private static final AtomicLong DISCORD_IDS = new AtomicLong(System.nanoTime());

	/**
	 * Reads decrypted messages.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * The {@link PushRepository}.
	 */
	@Autowired
	private PushRepository pushRepository;

	/**
	 * The {@link NotificationRepository}, to queue notifications as the triggers
	 * do.
	 */
	@Autowired
	private NotificationRepository notificationRepository;

	/**
	 * The app's {@link JsonMapper}.
	 */
	@Autowired
	private JsonMapper jsonMapper;

	/**
	 * The {@link JdbcTemplate}.
	 */
	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * The {@link TransactionTemplate}, for a lock timeout that lasts one
	 * transaction.
	 */
	@Autowired
	private TransactionTemplate transactionTemplate;

	/**
	 * The throwaway VAPID key pair: public, then private.
	 */
	private String[] vapid;

	/**
	 * The stub push service.
	 */
	private StubPushService stub;

	/**
	 * Generates fresh VAPID keys and a fresh stub for each test.
	 */
	@BeforeEach
	void setUp() {
		vapid = TestBrowser.vapidKeys();
		stub = new StubPushService();
	}

	/**
	 * Tests that every subscription gets a message that decrypts with its own keys
	 * to only the type, the event's public ID and the lock-screen text, under 4 KB,
	 * with a VAPID JWT for the push service's origin that the server's key
	 * verifies. The row ends {@code SENT}.
	 *
	 * @throws Exception on failure
	 */
	@Test
	void testSendsDecryptableMessagesWithAVerifiedVapidJwt() throws Exception {
		var user = user(true);
		var chrome = new TestBrowser();
		var firefox = new TestBrowser();
		var chromeEndpoint = subscribe(user, "https://fcm.googleapis.com/fcm/send/", chrome);
		var firefoxEndpoint = subscribe(user, "https://updates.push.services.mozilla.com/wpush/v2/", firefox);
		var event = event(user);
		var id = notificationRepository.insert(user, Type.SPOT_CONFIRMED, event.id(),
				new NotificationPayload("Cosplay photo walk", null, null, null, null));

		sender().run();

		for (var target : List.of(Map.entry(chromeEndpoint, chrome), Map.entry(firefoxEndpoint, firefox))) {
			var received = stub.receivedBy(target.getKey());
			assertThat(received).hasSize(1);
			var request = received.getFirst();
			assertThat(request.body().length).isLessThan(4096);
			assertThat(request.headers().firstValue("Content-Encoding")).contains("aes128gcm");
			assertThat(request.headers().firstValue("TTL")).contains(String.valueOf(PushSender.TTL.toSeconds()));
			assertThat(request.timeout()).contains(PushSender.REQUEST_TIMEOUT);
			@SuppressWarnings("unchecked")
			Map<String, Object> message = JSON.readValue(target.getValue().decrypt(request.body()), Map.class);
			assertThat(message).containsExactlyInAnyOrderEntriesOf(Map.of("type", "SPOT_CONFIRMED", "eventId",
					event.publicId().toString(), "text", "You're confirmed for Cosplay photo walk."));

			var claims = StubPushService.verifyVapid(request, vapid[0]);
			var uri = request.uri();
			assertThat(claims.get("aud")).isEqualTo(uri.getScheme() + "://" + uri.getHost());
			assertThat(claims.get("sub")).isEqualTo(ORIGIN);
			var expiry = Instant.ofEpochSecond(((Number) claims.get("exp")).longValue());
			assertThat(expiry).isAfter(Instant.now()).isBefore(Instant.now().plus(Duration.ofHours(24)));
		}
		assertThat(pushStatus(id)).isEqualTo("SENT");
	}

	/**
	 * Tests that a subscription the push service answers 410 or 404 for is deleted,
	 * and a row no browser took ends {@code FAILED}, while a row that reached one
	 * of several browsers is {@code SENT}.
	 *
	 * @throws Exception on failure
	 */
	@Test
	void testGoneSubscriptionsAreDeleted() throws Exception {
		var lost = user(true);
		var gone = subscribe(lost, "https://fcm.googleapis.com/fcm/send/", new TestBrowser());
		var missing = subscribe(lost, "https://web.push.apple.com/", new TestBrowser());
		stub.answer(gone, 410, null);
		stub.answer(missing, 404, null);
		var lostRow = friendAccepted(lost);
		var mixed = user(true);
		var dead = subscribe(mixed, "https://fcm.googleapis.com/fcm/send/", new TestBrowser());
		var alive = subscribe(mixed, "https://wns2-par02p.notify.windows.com/w/?token=", new TestBrowser());
		stub.answer(dead, 410, null);
		var mixedRow = friendAccepted(mixed);

		sender().run();

		assertThat(stub.receivedBy(gone)).hasSize(1);
		assertThat(stub.receivedBy(missing)).hasSize(1);
		assertThat(endpoints(lost)).isEmpty();
		assertThat(pushStatus(lostRow)).isEqualTo("FAILED");
		assertThat(endpoints(mixed)).containsExactly(alive);
		assertThat(pushStatus(mixedRow)).isEqualTo("SENT");
	}

	/**
	 * Tests that a row whose user has turned push off, or has no subscription left,
	 * ends {@code FAILED} without a send, and so do a stored endpoint off the
	 * allowlist, which the sender never contacts, and a row whose payload can't be
	 * read, which doesn't hold up the rows behind it.
	 */
	@Test
	void testRowsThatCantBeSentFail() {
		var off = user(true);
		var offEndpoint = subscribe(off, "https://fcm.googleapis.com/fcm/send/", new TestBrowser());
		var offRow = friendAccepted(off);
		jdbcTemplate.update("UPDATE app_user SET push_enabled = false WHERE id = ?", off);
		var unsubscribed = user(true);
		subscribe(unsubscribed, "https://fcm.googleapis.com/fcm/send/", new TestBrowser());
		var unsubscribedRow = friendAccepted(unsubscribed);
		pushRepository.deleteAll(unsubscribed);
		var smuggled = user(true);
		var internal = "http://169.254.169.254/latest/meta-data/" + UUID.randomUUID();
		var browser = new TestBrowser();
		pushRepository.upsert(smuggled, internal, browser.p256dh(), browser.auth());
		var smuggledRow = friendAccepted(smuggled);
		var unreadable = user(true);
		subscribe(unreadable, "https://fcm.googleapis.com/fcm/send/", new TestBrowser());
		var unreadableRow = jdbcTemplate.queryForObject(
				"INSERT INTO notification (user_id, type, event_id, payload, push_status) "
						+ "VALUES (?, 'EVENT_CHANGED', ?, '{\"changes\": 5}', 'PENDING') RETURNING id",
				Long.class, unreadable, event(unreadable).id());
		var fine = user(true);
		var fineEndpoint = subscribe(fine, "https://fcm.googleapis.com/fcm/send/", new TestBrowser());
		var fineRow = friendAccepted(fine);

		sender().run();

		assertThat(stub.receivedBy(offEndpoint)).isEmpty();
		assertThat(stub.receivedBy(internal)).isEmpty();
		for (var row : List.of(offRow, unsubscribedRow, smuggledRow, unreadableRow))
			assertThat(pushStatus(row)).isEqualTo("FAILED");
		assertThat(stub.receivedBy(fineEndpoint)).hasSize(1);
		assertThat(pushStatus(fineRow)).isEqualTo("SENT");
		assertThat(endpoints(off)).containsExactly(offEndpoint);
	}

	/**
	 * Tests that a 429 holds the row back as {@code PENDING} and pauses that push
	 * service for its {@code Retry-After}, while other push services still get
	 * their messages, and the next run sends to it once the pause is over.
	 */
	@Test
	void testA429PausesThatPushServiceForItsRetryAfter() {
		var limited = user(true);
		var limitedEndpoint = subscribe(limited, "https://web.push.apple.com/", new TestBrowser());
		stub.answer(limitedEndpoint, 429, "120");
		var limitedRow = friendAccepted(limited);
		var other = user(true);
		var otherEndpoint = subscribe(other, "https://updates.push.services.mozilla.com/wpush/v2/", new TestBrowser());
		var otherRow = friendAccepted(other);
		var now = new AtomicLong(Instant.now().getEpochSecond());
		var sender = sender(new Clock() {
			@Override
			public Instant instant() {
				return Instant.ofEpochSecond(now.get());
			}

			@Override
			public java.time.ZoneId getZone() {
				return java.time.ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}
		});

		sender.run();
		assertThat(pushStatus(limitedRow)).isEqualTo("PENDING");
		assertThat(pushStatus(otherRow)).isEqualTo("SENT");
		assertThat(stub.receivedBy(otherEndpoint)).hasSize(1);

		now.addAndGet(60);
		sender.run();
		assertThat(stub.receivedBy(limitedEndpoint)).hasSize(1);
		assertThat(pushStatus(limitedRow)).isEqualTo("PENDING");

		stub.answer(limitedEndpoint, 201, null);
		now.addAndGet(61);
		sender.run();
		assertThat(stub.receivedBy(limitedEndpoint)).hasSize(2);
		assertThat(pushStatus(limitedRow)).isEqualTo("SENT");
	}

	/**
	 * Tests that more than a batch of rows held back by a paused push service don't
	 * stall the rows behind them: one run walks past them, and they wait as
	 * {@code PENDING} without another request.
	 */
	@Test
	void testRowsHeldBackDontStallTheQueue() {
		var limited = user(true);
		var limitedEndpoint = subscribe(limited, "https://web.push.apple.com/", new TestBrowser());
		stub.answer(limitedEndpoint, 429, "3600");
		var held = new ArrayList<Long>();
		for (var i = 0; i < PushSender.BATCH_SIZE + 5; i++)
			held.add(friendAccepted(limited));
		var other = user(true);
		var otherEndpoint = subscribe(other, "https://updates.push.services.mozilla.com/wpush/v2/", new TestBrowser());
		var otherRow = friendAccepted(other);
		var sender = sender();

		sender.run();
		assertThat(pushStatus(otherRow)).isEqualTo("SENT");
		assertThat(stub.receivedBy(otherEndpoint)).hasSize(1);
		for (var row : held)
			assertThat(pushStatus(row)).isEqualTo("PENDING");
		var requests = stub.receivedBy(limitedEndpoint).size();
		assertThat(requests).isPositive().isLessThanOrEqualTo(PushSender.BATCH_SIZE);

		sender.run();
		assertThat(stub.receivedBy(limitedEndpoint)).hasSize(requests);
	}

	/**
	 * Tests that a row being pushed isn't locked while the push service is slow, so
	 * marking it read and deleting it (as a block does) don't wait for the sender.
	 *
	 * @throws Exception on failure
	 */
	@Test
	void testNoRowLockIsHeldWhileSending() throws Exception {
		var user = user(true);
		var endpoint = subscribe(user, "https://fcm.googleapis.com/fcm/send/", new TestBrowser());
		var read = friendAccepted(user);
		var deleted = friendAccepted(user);
		stub.delay(Duration.ofSeconds(3));

		try (var executor = Executors.newSingleThreadExecutor()) {
			var running = executor.submit(sender()::run);
			var deadline = Instant.now().plusSeconds(10);
			while (stub.receivedBy(endpoint).size() < 2 && Instant.now().isBefore(deadline))
				Thread.sleep(10);
			assertThat(stub.receivedBy(endpoint)).hasSize(2);
			transactionTemplate.executeWithoutResult(status -> {
				jdbcTemplate.execute("SET LOCAL lock_timeout = '500ms'");
				assertThat(jdbcTemplate.update("UPDATE notification SET read_at = now() WHERE id = ?", read)).isOne();
				assertThat(jdbcTemplate.update("DELETE FROM notification WHERE id = ?", deleted)).isOne();
			});
			assertThat(running.isDone()).isFalse();
			running.get();
		}
		assertThat(pushStatus(read)).isEqualTo("SENT");
	}

	/**
	 * Tests that two senders running at once, with more than a batch queued and
	 * slow push services, push every row exactly once.
	 *
	 * @throws Exception on failure
	 */
	@Test
	void testTwoConcurrentSendersNeverSendARowTwice() throws Exception {
		var user = user(true);
		var browser = new TestBrowser();
		var endpoint = subscribe(user, "https://fcm.googleapis.com/fcm/send/", browser);
		var event = event(user);
		var rows = new ArrayList<Long>();
		var count = PushSender.BATCH_SIZE * 2 + 10;
		for (var i = 0; i < count; i++)
			rows.add(notificationRepository.insert(user, Type.EVENT_CANCELLED, event.id(),
					new NotificationPayload("Walk " + i, null, null, null, null)));
		stub.delay(Duration.ofMillis(20));
		var first = sender();
		var second = sender();

		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			for (var sender : List.of(first, second))
				executor.submit(() -> {
					start.await();
					sender.run();
					return null;
				});
			start.countDown();
		}

		var texts = new HashMap<String, Integer>();
		for (var received : stub.receivedBy(endpoint)) {
			@SuppressWarnings("unchecked")
			Map<String, Object> message = JSON.readValue(browser.decrypt(received.body()), Map.class);
			texts.merge((String) message.get("text"), 1, Integer::sum);
		}
		assertThat(texts).hasSize(count).allSatisfy((text, times) -> assertThat(times).isOne());
		for (var row : rows)
			assertThat(pushStatus(row)).isEqualTo("SENT");
	}

	/**
	 * Tests that with no VAPID keys push is off: nothing is sent and rows stay
	 * {@code PENDING}.
	 */
	@Test
	void testDoesNothingWithoutKeys() {
		var user = user(true);
		var endpoint = subscribe(user, "https://fcm.googleapis.com/fcm/send/", new TestBrowser());
		var row = friendAccepted(user);

		new PushSender(pushRepository, new VapidKeys("", ""), jsonMapper, ORIGIN, stub, Clock.systemUTC()).run();

		assertThat(stub.receivedBy(endpoint)).isEmpty();
		assertThat(pushStatus(row)).isEqualTo("PENDING");
	}

	/**
	 * Builds a sender with this test's keys and stub.
	 *
	 * @return the sender
	 */
	private PushSender sender() {
		return sender(Clock.systemUTC());
	}

	/**
	 * Builds a sender with this test's keys and stub, and a clock.
	 *
	 * @param clock the {@link Clock}
	 * @return the sender
	 */
	private PushSender sender(Clock clock) {
		return new PushSender(pushRepository, new VapidKeys(vapid[0], vapid[1]), jsonMapper, ORIGIN, stub, clock);
	}

	/**
	 * Adds a user.
	 *
	 * @param pushEnabled whether push is on
	 * @return the user's ID
	 */
	private UUID user(boolean pushEnabled) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO app_user (discord_id, username, push_enabled) VALUES (?, 'pushed', ?) RETURNING id",
				UUID.class, DISCORD_IDS.incrementAndGet(), pushEnabled);
	}

	/**
	 * Adds a published event tomorrow.
	 *
	 * @param host the host
	 * @return the event's internal and public IDs
	 */
	private Event event(UUID host) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO event (host_id, title, location_text, starts_at, time_zone, status) "
						+ "VALUES (?, 'Pushed walk', 'Hall A', now() + interval '1 day', 'UTC', 'PUBLISHED') "
						+ "RETURNING id, public_id",
				(rs, row) -> new Event(rs.getLong(1), rs.getObject(2, UUID.class)), host);
	}

	/**
	 * Subscribes a browser for a user on a push service.
	 *
	 * @param userId  the user
	 * @param prefix  the push service URL, before the browser's token
	 * @param browser the browser
	 * @return the endpoint
	 */
	private String subscribe(UUID userId, String prefix, TestBrowser browser) {
		var endpoint = prefix + UUID.randomUUID();
		pushRepository.upsert(userId, endpoint, browser.p256dh(), browser.auth());
		return endpoint;
	}

	/**
	 * Queues a {@code FRIEND_ACCEPTED} for a user.
	 *
	 * @param userId the user
	 * @return the notification's ID
	 */
	private long friendAccepted(UUID userId) {
		return notificationRepository.insert(userId, Type.FRIEND_ACCEPTED, null,
				new NotificationPayload(null, null, null, null, UUID.randomUUID()));
	}

	/**
	 * Returns a notification's push status.
	 *
	 * @param id the notification's ID
	 * @return the status
	 */
	private String pushStatus(long id) {
		return jdbcTemplate.queryForObject("SELECT push_status FROM notification WHERE id = ?", String.class, id);
	}

	/**
	 * Returns a user's subscription endpoints.
	 *
	 * @param userId the user
	 * @return the endpoints
	 */
	private List<String> endpoints(UUID userId) {
		return jdbcTemplate.queryForList("SELECT endpoint FROM push_subscription WHERE user_id = ?", String.class,
				userId);
	}

	/**
	 * A seeded event.
	 *
	 * @param id       the internal ID
	 * @param publicId the public ID
	 * @author mcawful
	 */
	private record Event(long id, UUID publicId) {
	}
}
