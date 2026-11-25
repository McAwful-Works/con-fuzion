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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import app.confuzion.backend.exception.RateLimitException;
import app.confuzion.backend.model.entity.Event;
import app.confuzion.backend.model.record.MyStatus;
import app.confuzion.backend.model.record.RsvpResponse;
import app.confuzion.backend.repository.EventRepository;
import app.confuzion.backend.repository.RsvpRepository;
import app.confuzion.backend.repository.RsvpRepository.Counts;
import app.confuzion.backend.repository.RsvpRepository.LockedEvent;
import app.confuzion.backend.repository.RsvpRepository.Mine;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;

/**
 * Unit tests for {@link RsvpService}: paths that only a race reaches, and what
 * the {@code /spots} counts cache shares.
 *
 * @author mcawful
 */
class RsvpServiceTest {
	/**
	 * The mocked {@link EventRepository}.
	 */
	private final EventRepository eventRepository = mock(EventRepository.class);

	/**
	 * The mocked {@link RsvpRepository}.
	 */
	private final RsvpRepository rsvpRepository = mock(RsvpRepository.class);

	/**
	 * The mocked rate-limit {@link ProxyManager}.
	 */
	@SuppressWarnings("unchecked")
	private final ProxyManager<String> proxyManager = mock(ProxyManager.class);

	/**
	 * The mocked join bucket.
	 */
	private final BucketProxy bucket = mock(BucketProxy.class);

	/**
	 * The host.
	 */
	private final UUID hostId = UUID.randomUUID();

	/**
	 * A published event starting tomorrow.
	 */
	private final Event event = new Event();

	/**
	 * Wires the mocks and the event.
	 */
	@BeforeEach
	void setUp() {
		event.setId(7L);
		event.setPublicId(UUID.randomUUID());
		event.setHostId(hostId);
		event.setStartsAt(Instant.now().plus(1, ChronoUnit.DAYS));
		when(proxyManager.getProxy(any(), any())).thenReturn(bucket);
	}

	/**
	 * Tests that a join turned away by the limit, whose twin from another device
	 * has just claimed, gets the twin's RSVP as a repeat instead of a 429; and that
	 * without such a twin it is the 429.
	 */
	@Test
	void testLimitedJoinAnswersATwinsClaim() {
		var userId = UUID.randomUUID();
		when(eventRepository.findVisible(event.getPublicId(), userId)).thenReturn(Optional.of(event));
		when(rsvpRepository.findMine(7L, userId)).thenReturn(Optional.empty())
				.thenReturn(Optional.of(new Mine(MyStatus.WAITLISTED, 3, null))).thenReturn(Optional.empty());
		when(bucket.tryConsume(1)).thenReturn(false);
		var service = new RsvpService(eventRepository, rsvpRepository, mock(TransactionTemplate.class), proxyManager,
				1);

		assertThat(service.join(userId, event.getPublicId()))
				.isEqualTo(new RsvpResponse(RsvpResponse.Status.WAITLISTED, 3, null));
		assertThatThrownBy(() -> service.join(userId, event.getPublicId())).isInstanceOf(RateLimitException.class);
		verify(rsvpRepository, never()).lock(anyLong());
	}

	/**
	 * Tests that two callers polling one event share a single counts load but each
	 * get their own status, and that an unlimited event has no spots left to count
	 * and no waitlist (D-013 rules 3 and 16).
	 */
	@Test
	void testSpotsShareOnlyTheCounts() {
		var confirmed = UUID.randomUUID();
		var other = UUID.randomUUID();
		for (var user : List.of(confirmed, other))
			when(eventRepository.findVisible(event.getPublicId(), user)).thenReturn(Optional.of(event));
		when(rsvpRepository.findMine(7L, confirmed)).thenReturn(Optional.of(new Mine(MyStatus.CONFIRMED, null, null)));
		when(rsvpRepository.findMine(7L, other)).thenReturn(Optional.empty());
		when(rsvpRepository.findCounts(7L)).thenReturn(new Counts(null, 12, 0));
		var service = new RsvpService(eventRepository, rsvpRepository, mock(TransactionTemplate.class), proxyManager,
				1);

		var mine = service.getSpots(confirmed, event.getPublicId());
		var theirs = service.getSpots(other, event.getPublicId());

		assertThat(mine.myStatus()).isEqualTo(MyStatus.CONFIRMED);
		assertThat(theirs.myStatus()).isEqualTo(MyStatus.NONE);
		assertThat(theirs.myPosition()).isNull();
		assertThat(theirs.spotsLeft()).isNull();
		assertThat(theirs.capacity()).isNull();
		assertThat(theirs.waitlisted()).isZero();
		verify(rsvpRepository, times(1)).findCounts(7L);
	}

	/**
	 * Tests that a join whose event was cancelled after its checks passed, and
	 * before its claim took the lock, is a 404 that writes nothing (D-013).
	 */
	@Test
	void testJoinOnEventCancelledBeforeTheClaim() {
		var transactionTemplate = mock(TransactionTemplate.class);
		doAnswer(invocation -> {
			invocation.<Consumer<TransactionStatus>>getArgument(0).accept(mock(TransactionStatus.class));
			return null;
		}).when(transactionTemplate).executeWithoutResult(any());
		var userId = UUID.randomUUID();
		when(eventRepository.findVisible(event.getPublicId(), userId)).thenReturn(Optional.of(event));
		when(rsvpRepository.findMine(7L, userId)).thenReturn(Optional.empty());
		when(rsvpRepository.lock(7L)).thenReturn(new LockedEvent(hostId, "CANCELLED", 5, 1));

		when(bucket.tryConsume(1)).thenReturn(true);

		var service = new RsvpService(eventRepository, rsvpRepository, transactionTemplate, proxyManager, 1);

		assertThatThrownBy(() -> service.join(userId, event.getPublicId())).isInstanceOf(NoSuchElementException.class);
		verify(rsvpRepository, never()).insert(anyLong(), any(), any(), anyBoolean());
	}
}
