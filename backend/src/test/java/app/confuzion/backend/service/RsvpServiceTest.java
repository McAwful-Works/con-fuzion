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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import app.confuzion.backend.model.entity.Event;
import app.confuzion.backend.repository.EventRepository;
import app.confuzion.backend.repository.RsvpRepository;
import app.confuzion.backend.repository.RsvpRepository.LockedEvent;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;

/**
 * Unit tests for {@link RsvpService} paths that only a race reaches.
 *
 * @author mcawful
 */
class RsvpServiceTest {
	/**
	 * Tests that a join whose event was cancelled after its checks passed, and
	 * before its claim took the lock, is a 404 that writes nothing (D-013).
	 */
	@Test
	@SuppressWarnings("unchecked")
	void testJoinOnEventCancelledBeforeTheClaim() {
		var eventRepository = mock(EventRepository.class);
		var rsvpRepository = mock(RsvpRepository.class);
		var transactionTemplate = mock(TransactionTemplate.class);
		doAnswer(invocation -> {
			invocation.<Consumer<TransactionStatus>>getArgument(0).accept(mock(TransactionStatus.class));
			return null;
		}).when(transactionTemplate).executeWithoutResult(any());
		var userId = UUID.randomUUID();
		var hostId = UUID.randomUUID();
		var event = new Event();
		event.setId(7L);
		event.setPublicId(UUID.randomUUID());
		event.setHostId(hostId);
		event.setStartsAt(Instant.now().plus(1, ChronoUnit.DAYS));
		when(eventRepository.findVisible(event.getPublicId(), userId)).thenReturn(Optional.of(event));
		when(rsvpRepository.findMine(7L, userId)).thenReturn(Optional.empty());
		when(rsvpRepository.lock(7L)).thenReturn(new LockedEvent(hostId, "CANCELLED", 5, 1));

		var proxyManager = mock(ProxyManager.class);
		var bucket = mock(BucketProxy.class);
		when(proxyManager.getProxy(any(), any())).thenReturn(bucket);
		when(bucket.tryConsume(1)).thenReturn(true);

		var service = new RsvpService(eventRepository, rsvpRepository, transactionTemplate, proxyManager, 1);

		assertThatThrownBy(() -> service.join(userId, event.getPublicId())).isInstanceOf(NoSuchElementException.class);
		verify(rsvpRepository, never()).insert(anyLong(), any(), any(), anyBoolean());
	}
}
