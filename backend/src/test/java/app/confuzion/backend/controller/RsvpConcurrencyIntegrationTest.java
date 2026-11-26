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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;

import app.confuzion.backend.TestcontainersConfiguration;
import app.confuzion.backend.controller.EventFixtures.SeededEvent;
import app.confuzion.backend.exception.ConflictException;
import app.confuzion.backend.exception.ConflictException.Conflict;
import app.confuzion.backend.model.record.EventRequest;
import app.confuzion.backend.model.record.EventResponse.AudienceType;
import app.confuzion.backend.model.record.EventResponse.LocationVisibility;
import app.confuzion.backend.model.record.RsvpResponse;
import app.confuzion.backend.repository.RsvpRepository;
import app.confuzion.backend.service.AdminUserService;
import app.confuzion.backend.service.BlockService;
import app.confuzion.backend.service.EventService;
import app.confuzion.backend.service.OfferExpiryJob;
import app.confuzion.backend.service.RsvpService;

/**
 * The RSVP concurrency suite (plan B4, D-013): parallel joins never overbook
 * and are served in insert order, freed and added spots go to the waitlist in
 * queue order, and joins interleaved with leaves and capacity raises never
 * leave anyone waitlisted while a spot is free, or deadlock.
 *
 * @author mcawful
 */
@Tag("integration")
@SpringBootTest(properties = { "con-fuzion.rate-limiter.token.capacity=10000",
		"con-fuzion.rate-limiter.join.capacity=10000" })
@Import(TestcontainersConfiguration.class)
class RsvpConcurrencyIntegrationTest {
	/**
	 * How long a parallel batch may take before the test counts it as hung.
	 */
	private static final long TIMEOUT_SECONDS = 120;

	/**
	 * The {@link JdbcTemplate}, to seed and inspect rows.
	 */
	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * The {@link RsvpService}.
	 */
	@Autowired
	private RsvpService rsvpService;

	/**
	 * The {@link EventService}, for capacity edits.
	 */
	@Autowired
	private EventService eventService;

	/**
	 * The {@link OfferExpiryJob}, run by hand rather than waiting for its schedule.
	 */
	@Autowired
	private OfferExpiryJob offerExpiryJob;

	/**
	 * The {@link BlockService}, for blocks racing joins.
	 */
	@Autowired
	private BlockService blockService;

	/**
	 * The {@link AdminUserService}, for a ban racing joins.
	 */
	@Autowired
	private AdminUserService adminUserService;

	/**
	 * The {@link RsvpRepository}, to hold a join's locks open.
	 */
	@Autowired
	private RsvpRepository rsvpRepository;

	/**
	 * The {@link TransactionTemplate}, to hold a join's or a ban's locks open.
	 */
	@Autowired
	private TransactionTemplate transactionTemplate;

	/**
	 * The seeding helpers.
	 */
	private EventFixtures fixtures;

	/**
	 * The host.
	 */
	private UUID host;

	/**
	 * The server every guest shares with the host, so they all see the event.
	 */
	private long guildId;

	/**
	 * Seeds the host and the server.
	 */
	@BeforeEach
	void setUp() {
		fixtures = new EventFixtures(jdbcTemplate);
		host = fixtures.user("Host");
		guildId = fixtures.newGuildId();
		fixtures.guild(host, guildId, true);
	}

	/**
	 * Tests the headline case. Capacity 11 with the host holding one spot: 200
	 * parallel joins confirm exactly 10 guests and waitlist 190 in insert order.
	 * Three confirmed guests leave and the first three waitlisted are offered the
	 * spots in order (D-014). One offer expires and the next in line is offered its
	 * spot. Raising the capacity by 2 offers the next two, and cutting it below the
	 * held spots is a 409 that saves nothing.
	 */
	@Test
	void testFirstComeFirstServed() throws Exception {
		var event = event(11);
		var guests = guests(200);

		var results = parallel(guests.stream()
				.<Callable<RsvpResponse>>map(guest -> () -> rsvpService.join(guest, event.publicId())).toList());

		assertThat(results).filteredOn(r -> r.status() == RsvpResponse.Status.CONFIRMED).hasSize(10)
				.allMatch(r -> r.position() == null);
		assertThat(results).filteredOn(r -> r.status() == RsvpResponse.Status.WAITLISTED)
				.extracting(RsvpResponse::position).containsExactlyInAnyOrderElementsOf(range(1, 190));
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(11);
		// Insert order: the 10 lowest RSVP IDs hold the spots, and the rest queue in
		// ID order with the positions their joins were told.
		var queue = queue(event);
		assertThat(queue).hasSize(200);
		assertThat(queue.subList(0, 10)).allMatch(r -> r.status().equals("CONFIRMED"));
		assertThat(queue.subList(10, 200)).allMatch(r -> r.status().equals("WAITLISTED"));
		for (var i = 10; i < 200; i++)
			assertThat(results.get(guests.indexOf(queue.get(i).userId())).position()).isEqualTo(i - 9);

		for (var i = 0; i < 3; i++) {
			rsvpService.leave(queue.get(i).userId(), event.publicId());
			assertThat(statuses(event, queue.subList(10, 14)))
					.containsExactlyElementsOf(nCopiesThen(i + 1, "OFFERED", 3 - i, "WAITLISTED"));
			assertThat(fixtures.confirmedCount(event.id())).isEqualTo(11);
		}

		jdbcTemplate.update("UPDATE rsvp SET offer_expires_at = now() - interval '1 second' WHERE event_id = ? "
				+ "AND user_id = ?", event.id(), queue.get(10).userId());
		offerExpiryJob.run();
		assertThat(statuses(event, queue.subList(10, 15))).containsExactly("EXPIRED", "OFFERED", "OFFERED", "OFFERED",
				"WAITLISTED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(11);

		eventService.updateEvent(host, event.publicId(), request(13));
		assertThat(statuses(event, queue.subList(14, 17))).containsExactly("OFFERED", "OFFERED", "WAITLISTED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(13);
		assertThat(rsvpService.getSpots(queue.get(16).userId(), event.publicId(), false).myPosition()).isEqualTo(1);

		assertThatThrownBy(() -> eventService.updateEvent(host, event.publicId(), request(12))).isInstanceOfSatisfying(
				ConflictException.class, e -> assertThat(e.getConflict()).isEqualTo(Conflict.CAPACITY_BELOW_CONFIRMED));
		assertThat(jdbcTemplate.queryForObject("SELECT capacity FROM event WHERE id = ?", Integer.class, event.id()))
				.isEqualTo(13);
	}

	/**
	 * Tests joins interleaved with leaves and capacity raises, the raises starting
	 * once 20 joins are done so they land among the rest: every operation succeeds
	 * (no deadlock, no lock timeout), the count matches the confirmed rows and
	 * never exceeds the capacity, and nobody is left waitlisted while a spot is
	 * free.
	 */
	@Test
	void testInterleavedWritesNeverStrandTheWaitlist() throws Exception {
		var event = event(5);
		var guests = guests(120);
		var joined = new CountDownLatch(20);
		var tasks = new ArrayList<Callable<Object>>();
		for (var i = 0; i < guests.size(); i++) {
			var guest = guests.get(i);
			var leaves = i % 4 == 0;
			tasks.add(() -> {
				rsvpService.join(guest, event.publicId());
				joined.countDown();
				if (leaves)
					rsvpService.leave(guest, event.publicId());
				return guest;
			});
		}
		Collections.shuffle(tasks);
		// Submitted first, so it holds a thread from the start.
		tasks.addFirst(() -> {
			joined.await();
			for (var capacity : List.of(8, 11, 14)) {
				eventService.updateEvent(host, event.publicId(), request(capacity));
				Thread.sleep(5);
			}
			return host;
		});

		parallel(tasks);

		var held = count(event, "CONFIRMED") + count(event, "OFFERED");
		var waitlisted = count(event, "WAITLISTED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(held);
		assertThat(held).isEqualTo(14);
		assertThat(waitlisted).isEqualTo(120 - 30 + 1 - 14);
		assertThat(count(event, "LEFT")).isEqualTo(30);
	}

	/**
	 * Tests accepts racing the expiry job across their deadline: 30 offers that run
	 * out in half a second, each accepted at a random moment in the second around
	 * it while two tasks run the job. Nothing deadlocks, each accept is
	 * {@code CONFIRMED} or {@code OFFER_EXPIRED} and its row agrees, the count
	 * matches the held spots and never exceeds the capacity, and every expired
	 * offer's spot went to the next person in line.
	 */
	@Test
	void testAcceptsRaceTheExpiryJob() throws Exception {
		var event = event(41);
		var guests = guests(100);
		for (var guest : guests)
			rsvpService.join(guest, event.publicId());
		var queue = queue(event);
		for (var row : queue.subList(0, 30))
			rsvpService.leave(row.userId(), event.publicId());
		var offered = queue.subList(40, 70).stream().map(Row::userId).toList();
		assertThat(statuses(event, queue.subList(40, 71)))
				.containsExactlyElementsOf(nCopiesThen(30, "OFFERED", 1, "WAITLISTED"));
		jdbcTemplate.update("UPDATE rsvp SET offer_expires_at = now() + interval '500 milliseconds' "
				+ "WHERE event_id = ? AND status = 'OFFERED'", event.id());

		var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500);
		var tasks = new ArrayList<Callable<String>>();
		for (var userId : offered)
			tasks.add(() -> {
				Thread.sleep(ThreadLocalRandom.current().nextLong(1_000));
				try {
					return rsvpService.accept(userId, event.publicId()).status().name();
				} catch (ConflictException e) {
					return e.getConflict().name();
				}
			});
		for (var i = 0; i < 2; i++)
			tasks.add(() -> {
				while (System.nanoTime() < deadline)
					offerExpiryJob.run();
				return "JOB";
			});

		var results = parallel(tasks);
		offerExpiryJob.run();

		for (var i = 0; i < offered.size(); i++) {
			var status = fixtures.rsvpStatus(event.id(), offered.get(i));
			assertThat(results.get(i)).isIn("CONFIRMED", "OFFER_EXPIRED");
			assertThat(status).isEqualTo(results.get(i).equals("CONFIRMED") ? "CONFIRMED" : "EXPIRED");
		}
		var held = count(event, "CONFIRMED") + count(event, "OFFERED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(held).isEqualTo(41);
		// Each expired offer's spot was offered to the next person in line, in order.
		var expired = (int) results.stream().filter("OFFER_EXPIRED"::equals).count();
		assertThat(statuses(event, queue.subList(70, 100)))
				.containsExactlyElementsOf(nCopiesThen(expired, "OFFERED", 30 - expired, "WAITLISTED"));
	}

	/**
	 * Tests blocks racing joins and leaves (D-013 rule 11): the host blocks some
	 * guests and some guests block the host while everyone joins and some leave.
	 * Nothing deadlocks, nobody in a block with the host keeps an active RSVP, the
	 * count matches the confirmed rows, and the spots stay filled.
	 */
	@Test
	void testBlocksRacingJoinsAndLeaves() throws Exception {
		var event = event(5);
		var guests = guests(80);
		var blocked = new HashSet<UUID>();
		var tasks = new ArrayList<Callable<Object>>();
		for (var i = 0; i < guests.size(); i++) {
			var guest = guests.get(i);
			var leaves = i % 4 == 1;
			if (i % 8 == 0 || i % 8 == 4) {
				blocked.add(guest);
				var hostBlocks = i % 8 == 0;
				tasks.add(() -> {
					if (hostBlocks)
						blockService.block(host, guest);
					else
						blockService.block(guest, host);
					return guest;
				});
			}
			tasks.add(() -> {
				try {
					rsvpService.join(guest, event.publicId());
					if (leaves)
						rsvpService.leave(guest, event.publicId());
				} catch (NoSuchElementException e) {
					// Only a guest whose block landed first can't see the event.
					assertThat(blocked).contains(guest);
				}
				return guest;
			});
		}
		Collections.shuffle(tasks);

		parallel(tasks);

		var active = jdbcTemplate.queryForList(
				"SELECT user_id FROM rsvp WHERE event_id = ? AND status IN ('CONFIRMED', 'WAITLISTED', 'OFFERED')",
				UUID.class, event.id());
		assertThat(active).doesNotContainAnyElementsOf(blocked).hasSize(80 - 20 - blocked.size() + 1);
		var held = count(event, "CONFIRMED") + count(event, "OFFERED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(held);
		assertThat(held).isEqualTo(5);
	}

	/**
	 * Tests a ban racing joins and leaves (D-015): the banned user keeps joining
	 * and leaving one event while others join and leave it and an event the banned
	 * user hosts, and the ban lands once 20 joins are done. Nothing deadlocks; the
	 * banned user ends with no active RSVP on either event, their own host RSVP
	 * aside; their event is cancelled; and on each event the count matches the held
	 * spots, never exceeds the capacity, and nobody is left waitlisted while a spot
	 * is free.
	 */
	@Test
	void testBanRacingJoinsAndLeaves() throws Exception {
		var event = event(5);
		var banned = guests(1).getFirst();
		var hosted = fixtures.rsvpEvent(banned, 5, "now() + interval '1 day'", "NULL");
		fixtures.audience(hosted.id(), "GUILD", guildId, null, null);
		var admin = admin();
		var guests = guests(60);
		var joined = new CountDownLatch(20);
		var tasks = new ArrayList<Callable<Object>>();
		for (var i = 0; i < guests.size(); i++) {
			var guest = guests.get(i);
			var leaves = i % 4 == 0;
			tasks.add(() -> {
				rsvpService.join(guest, event.publicId());
				joined.countDown();
				try {
					rsvpService.join(guest, hosted.publicId());
					if (leaves)
						rsvpService.leave(guest, hosted.publicId());
				} catch (NoSuchElementException e) {
					// The ban cancelled the event first.
				}
				if (leaves)
					rsvpService.leave(guest, event.publicId());
				return guest;
			});
		}
		for (var i = 0; i < 10; i++)
			tasks.add(() -> {
				try {
					rsvpService.join(banned, event.publicId());
					rsvpService.leave(banned, event.publicId());
					rsvpService.join(banned, event.publicId());
				} catch (NoSuchElementException e) {
					// The ban landed first.
				}
				return banned;
			});
		Collections.shuffle(tasks);
		// Submitted first, so it holds a thread from the start.
		tasks.addFirst(() -> {
			joined.await();
			adminUserService.ban(admin, discordId(banned), "Spam");
			return admin;
		});

		parallel(tasks);

		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) FROM rsvp WHERE user_id = ? AND event_id = ?
					AND status IN ('CONFIRMED', 'WAITLISTED', 'OFFERED')""", Integer.class, banned, event.id()))
				.isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM event WHERE id = ?", String.class, hosted.id()))
				.isEqualTo("CANCELLED");
		assertThat(fixtures.rsvpStatus(hosted.id(), banned)).isEqualTo("CONFIRMED");
		for (var seeded : List.of(event, hosted)) {
			var held = count(seeded, "CONFIRMED") + count(seeded, "OFFERED");
			assertThat(fixtures.confirmedCount(seeded.id())).as("count").isEqualTo(held);
			assertThat(held).isLessThanOrEqualTo(5);
		}
		var held = count(event, "CONFIRMED") + count(event, "OFFERED");
		assertThat(count(event, "WAITLISTED") == 0 || held == 5).as("no free spot while waitlisted").isTrue();
		assertThat(held).isEqualTo(5);
	}

	/**
	 * Tests a join of the banned user that has written its RSVP but not committed
	 * when the ban starts: the ban waits for it (the join holds a share of the
	 * user's row) and then ends that RSVP as {@code LEFT}, freeing the spot.
	 */
	@Test
	void testBanWaitsForAJoinInFlightAndEndsIt() throws Exception {
		var event = event(2);
		var banned = guests(1).getFirst();
		var admin = admin();
		var inserted = new CountDownLatch(1);
		var release = new CountDownLatch(1);

		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var join = executor.submit(() -> transactionTemplate.executeWithoutResult(tx -> {
				rsvpRepository.lockJoiner(banned);
				var locked = rsvpRepository.lock(event.id());
				assertThat(rsvpRepository.insert(event.id(), locked.hostId(), banned, locked.hasFreeSpot()))
						.isPresent();
				inserted.countDown();
				await(release);
			}));
			await(inserted);
			var ban = executor.submit(() -> adminUserService.ban(admin, discordId(banned), "Spam"));
			awaitLockWaiters(1);
			assertThat(ban.isDone()).isFalse();
			release.countDown();
			join.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			ban.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		assertThat(fixtures.rsvpStatus(event.id(), banned)).isEqualTo("LEFT");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(1);
	}

	/**
	 * Tests a join of the banned user that starts while the ban holds the user's
	 * row and hasn't committed: the join waits for the ban, then writes nothing and
	 * is a not-found.
	 */
	@Test
	void testJoinWaitingForABanWritesNothing() throws Exception {
		var event = event(5);
		var banned = guests(1).getFirst();
		var locked = new CountDownLatch(1);
		var release = new CountDownLatch(1);

		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			// What a ban holds before it commits: the ban row and the user's row lock.
			var ban = executor.submit(() -> transactionTemplate.executeWithoutResult(tx -> {
				jdbcTemplate.update("INSERT INTO banned_account (discord_id, reason) VALUES (?, 'Spam')",
						Long.parseLong(discordId(banned)));
				jdbcTemplate.queryForList("SELECT id FROM app_user WHERE id = ? FOR NO KEY UPDATE", banned);
				locked.countDown();
				await(release);
			}));
			await(locked);
			var join = executor.submit(() -> rsvpService.join(banned, event.publicId()));
			awaitLockWaiters(1);
			assertThat(join.isDone()).isFalse();
			release.countDown();
			ban.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			assertThatThrownBy(() -> join.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
					.hasCauseInstanceOf(NoSuchElementException.class);
		}
		assertThat(fixtures.rsvpStatus(event.id(), banned)).isNull();
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(1);
	}

	/**
	 * Tests an event create that starts while a ban or a suspension holds the
	 * host's row and hasn't committed: the create waits on the same lock, then is
	 * refused with a plain 403 and creates nothing.
	 */
	@Test
	void testCreateWaitingForABanOrSuspensionCreatesNothing() throws Exception {
		for (var barring : List.of(
				"INSERT INTO banned_account (discord_id, reason) "
						+ "SELECT discord_id, 'Spam' FROM app_user WHERE id = ?",
				"UPDATE app_user SET suspended_until = now() + interval '1 day' WHERE id = ?")) {
			var barred = guests(1).getFirst();
			var locked = new CountDownLatch(1);
			var release = new CountDownLatch(1);

			try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
				var bar = executor.submit(() -> transactionTemplate.executeWithoutResult(tx -> {
					jdbcTemplate.queryForList("SELECT id FROM app_user WHERE id = ? FOR NO KEY UPDATE", barred);
					jdbcTemplate.update(barring, barred);
					locked.countDown();
					await(release);
				}));
				await(locked);
				var create = executor.submit(() -> eventService.createEvent(barred, request(5)));
				awaitLockWaiters(1);
				assertThat(create.isDone()).isFalse();
				release.countDown();
				bar.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
				assertThatThrownBy(() -> create.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as(barring)
						.hasCauseInstanceOf(AccessDeniedException.class);
			}
			assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM event WHERE host_id = ?", Long.class, barred))
					.as(barring).isZero();
		}
	}

	/**
	 * Tests that an unlimited event confirms every parallel join and never has a
	 * waitlist (D-013 rule 3).
	 */
	@Test
	void testUnlimitedConfirmsEveryone() throws Exception {
		var event = event(null);
		var guests = guests(50);

		var results = parallel(guests.stream()
				.<Callable<RsvpResponse>>map(guest -> () -> rsvpService.join(guest, event.publicId())).toList());

		assertThat(results).allMatch(r -> r.status() == RsvpResponse.Status.CONFIRMED);
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(51);
		assertThat(count(event, "WAITLISTED")).isZero();
	}

	/**
	 * Seeds a user holding ADMIN.
	 *
	 * @return the user ID
	 */
	private UUID admin() {
		var admin = fixtures.user("Admin");
		jdbcTemplate.update("INSERT INTO user_role (user_id, role) VALUES (?, 'ADMIN')", admin);
		return admin;
	}

	/**
	 * Returns a user's Discord ID, as a ban names it.
	 *
	 * @param userId the user ID
	 * @return the Discord ID
	 */
	private String discordId(UUID userId) {
		return jdbcTemplate.queryForObject("SELECT discord_id FROM app_user WHERE id = ?", Long.class, userId)
				.toString();
	}

	/**
	 * Waits for a latch, failing the test if it takes too long.
	 *
	 * @param latch the latch
	 */
	private static void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("latch").isTrue();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Waits until this many other database sessions are waiting for a lock.
	 *
	 * @param waiters the number of waiters
	 */
	private void awaitLockWaiters(int waiters) throws InterruptedException {
		var deadline = Instant.now().plusSeconds(TIMEOUT_SECONDS);
		while (jdbcTemplate.queryForObject("""
				SELECT count(*) FROM pg_stat_activity
				WHERE datname = current_database() AND wait_event_type = 'Lock'""", Long.class) < waiters) {
			assertThat(Instant.now()).as("lock waiters").isBefore(deadline);
			Thread.sleep(10);
		}
	}

	/**
	 * Seeds an event, starting tomorrow, for everyone in the server.
	 *
	 * @param capacity the headcount, or {@code null} for unlimited
	 * @return the event
	 */
	private SeededEvent event(Integer capacity) {
		var event = fixtures.rsvpEvent(host, capacity, "now() + interval '1 day'", "NULL");
		fixtures.audience(event.id(), "GUILD", guildId, null, null);
		return event;
	}

	/**
	 * Seeds guests who share the server with the host.
	 *
	 * @param count how many
	 * @return their user IDs
	 */
	private List<UUID> guests(int count) {
		var guests = new ArrayList<UUID>();
		for (var i = 0; i < count; i++) {
			var guest = fixtures.user("Guest " + i);
			fixtures.guild(guest, guildId, true);
			guests.add(guest);
		}
		return guests;
	}

	/**
	 * Runs tasks at once on 32 threads and returns their results in task order. Any
	 * task's exception fails the test, and interrupts the tasks still running so a
	 * failure can't hang the suite.
	 *
	 * @param <T>   the result type
	 * @param tasks the tasks
	 * @return the results
	 */
	private static <T> List<T> parallel(List<? extends Callable<T>> tasks) throws Exception {
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(32)) {
			try {
				var futures = tasks.stream().map(task -> executor.submit(() -> {
					start.await();
					return task.call();
				})).toList();
				start.countDown();
				var results = new ArrayList<T>();
				for (var future : futures)
					results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
				return results;
			} finally {
				executor.shutdownNow();
			}
		}
	}

	/**
	 * Returns the guests' RSVPs on an event, in queue order.
	 *
	 * @param event the event
	 * @return each guest's user ID and status
	 */
	private List<Row> queue(SeededEvent event) {
		return jdbcTemplate.query("SELECT user_id, status FROM rsvp WHERE event_id = ? AND user_id <> ? ORDER BY id",
				(rs, row) -> new Row(rs.getObject(1, UUID.class), rs.getString(2)), event.id(), host);
	}

	/**
	 * Returns some guests' current statuses, in the order given.
	 *
	 * @param event the event
	 * @param rows  the guests
	 * @return their statuses
	 */
	private List<String> statuses(SeededEvent event, List<Row> rows) {
		return rows.stream().map(row -> fixtures.rsvpStatus(event.id(), row.userId())).toList();
	}

	/**
	 * Counts an event's RSVPs with one status, the host's included.
	 *
	 * @param event  the event
	 * @param status the status
	 * @return the count
	 */
	private int count(SeededEvent event, String status) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM rsvp WHERE event_id = ? AND status = ?", Integer.class,
				event.id(), status);
	}

	/**
	 * Builds the host's edit of the seeded event with a new capacity.
	 *
	 * @param capacity the new capacity
	 * @return the {@link EventRequest}
	 */
	private EventRequest request(int capacity) {
		return new EventRequest("Seeded", null, "Hall A", null,
				Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS), null, "UTC", capacity, null,
				null, LocationVisibility.CONFIRMED,
				List.of(new EventRequest.AudienceEntry(AudienceType.GUILD, Long.toString(guildId), null, null)));
	}

	/**
	 * Returns {@code from} to {@code to} inclusive.
	 *
	 * @param from the first number
	 * @param to   the last number
	 * @return the numbers
	 */
	private static List<Integer> range(int from, int to) {
		var numbers = new ArrayList<Integer>();
		for (var i = from; i <= to; i++)
			numbers.add(i);
		return numbers;
	}

	/**
	 * Returns {@code first} repeated {@code n} times, then {@code second} repeated
	 * {@code m} times.
	 *
	 * @param n      how many of the first
	 * @param first  the first value
	 * @param m      how many of the second
	 * @param second the second value
	 * @return the list
	 */
	private static List<String> nCopiesThen(int n, String first, int m, String second) {
		var list = new ArrayList<>(Collections.nCopies(n, first));
		list.addAll(Collections.nCopies(m, second));
		return list;
	}

	/**
	 * A guest's RSVP.
	 *
	 * @param userId the guest
	 * @param status the status
	 * @author mcawful
	 */
	private record Row(UUID userId, String status) {
	}
}
