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
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import app.confuzion.backend.TestcontainersConfiguration;
import app.confuzion.backend.controller.EventFixtures.SeededEvent;
import app.confuzion.backend.exception.ConflictException;
import app.confuzion.backend.exception.ConflictException.Conflict;
import app.confuzion.backend.model.record.EventRequest;
import app.confuzion.backend.model.record.EventResponse.AudienceType;
import app.confuzion.backend.model.record.EventResponse.LocationVisibility;
import app.confuzion.backend.model.record.RsvpResponse;
import app.confuzion.backend.service.BlockService;
import app.confuzion.backend.service.EventService;
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
	 * The {@link BlockService}, for blocks racing joins.
	 */
	@Autowired
	private BlockService blockService;

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
	 * Three confirmed guests leave and the first three waitlisted are confirmed in
	 * order, raising the capacity by 2 confirms the next two, and cutting it below
	 * the confirmed count is a 409 that saves nothing.
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
					.containsExactlyElementsOf(nCopiesThen(i + 1, "CONFIRMED", 3 - i, "WAITLISTED"));
			assertThat(fixtures.confirmedCount(event.id())).isEqualTo(11);
		}

		eventService.updateEvent(host, event.publicId(), request(13));
		assertThat(statuses(event, queue.subList(13, 16))).containsExactly("CONFIRMED", "CONFIRMED", "WAITLISTED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(13);
		assertThat(rsvpService.getSpots(queue.get(15).userId(), event.publicId()).myPosition()).isEqualTo(1);

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

		var confirmed = count(event, "CONFIRMED");
		var waitlisted = count(event, "WAITLISTED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(confirmed);
		assertThat(confirmed).isEqualTo(14);
		assertThat(waitlisted).isEqualTo(120 - 30 + 1 - 14);
		assertThat(count(event, "LEFT")).isEqualTo(30);
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
				"SELECT user_id FROM rsvp WHERE event_id = ? AND status IN ('CONFIRMED', 'WAITLISTED')", UUID.class,
				event.id());
		assertThat(active).doesNotContainAnyElementsOf(blocked).hasSize(80 - 20 - blocked.size() + 1);
		var confirmed = count(event, "CONFIRMED");
		assertThat(fixtures.confirmedCount(event.id())).isEqualTo(confirmed);
		assertThat(confirmed).isEqualTo(5);
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
	 * task's exception fails the test.
	 *
	 * @param <T>   the result type
	 * @param tasks the tasks
	 * @return the results
	 */
	private static <T> List<T> parallel(List<? extends Callable<T>> tasks) throws Exception {
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(32)) {
			var futures = tasks.stream().map(task -> executor.submit(() -> {
				start.await();
				return task.call();
			})).toList();
			start.countDown();
			var results = new ArrayList<T>();
			for (var future : futures)
				results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
			return results;
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
