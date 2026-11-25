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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import app.confuzion.backend.exception.InvalidInputException;
import app.confuzion.backend.model.record.NotificationPayload;
import app.confuzion.backend.model.record.NotificationResponse;
import app.confuzion.backend.model.record.NotificationResponse.Type;
import app.confuzion.backend.repository.NotificationRepository;
import app.confuzion.backend.repository.NotificationRepository.DueEvent;

/**
 * Unit tests for the {@link NotificationService}: paging, cursors and the
 * reminder hand-off.
 *
 * @author mcawful
 */
class NotificationServiceTest {
	/**
	 * The mocked {@link NotificationRepository}.
	 */
	private final NotificationRepository repository = mock(NotificationRepository.class);

	/**
	 * The service under test.
	 */
	private final NotificationService service = new NotificationService(repository);

	/**
	 * The caller.
	 */
	private final UUID userId = UUID.randomUUID();

	/**
	 * Builds a notification with an ID.
	 *
	 * @param id the ID
	 * @return the notification
	 */
	private static NotificationResponse notification(long id) {
		return new NotificationResponse(id, Type.EVENT_CANCELLED, UUID.randomUUID(),
				new NotificationPayload("Walk", null, null, null, null), Instant.now(), false);
	}

	/**
	 * Tests that a full page returns a cursor naming its last ID, which then asks
	 * for older notifications, and that the extra row isn't returned.
	 */
	@Test
	void testPagesWithACursor() {
		when(repository.countUnread(userId, 100)).thenReturn(7);
		when(repository.findPage(userId, null, 3))
				.thenReturn(List.of(notification(9), notification(8), notification(7)));

		var first = service.getInbox(userId, null, 2);

		assertThat(first.items()).extracting(NotificationResponse::id).containsExactly(9L, 8L);
		assertThat(first.unreadCount()).isEqualTo(7);
		assertThat(new String(Base64.getUrlDecoder().decode(first.nextCursor()), StandardCharsets.UTF_8))
				.isEqualTo("8");

		when(repository.findPage(userId, 8L, 3)).thenReturn(List.of(notification(7)));
		var last = service.getInbox(userId, first.nextCursor(), 2);

		assertThat(last.items()).extracting(NotificationResponse::id).containsExactly(7L);
		assertThat(last.nextCursor()).isNull();
	}

	/**
	 * Tests that a cursor that this service didn't issue is rejected before any
	 * query.
	 *
	 * @param cursor the cursor
	 */
	@ParameterizedTest
	@ValueSource(strings = { "bm90", "MA", "LTU", "!!!", "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAx" })
	void testRejectsForeignCursors(String cursor) {
		assertThatThrownBy(() -> service.getInbox(userId, cursor, 20)).isInstanceOf(InvalidInputException.class);

		verify(repository, org.mockito.Mockito.never()).findPage(any(), any(), anyInt());
	}

	/**
	 * Tests that marking read passes the caller's own ID, so another user's rows
	 * can't match.
	 */
	@Test
	void testMarkReadIsScopedToTheCaller() {
		service.markRead(userId, 42);

		verify(repository).markRead(userId, 42L);
	}

	/**
	 * Tests that an event that isn't due gets no reminders, and a due one gets one
	 * with only its title and start.
	 */
	@Test
	void testRemindWritesOnlyForADueEvent() {
		var startsAt = Instant.parse("2027-01-02T15:00:00Z");
		when(repository.lockDueEvent(eq(1L), any())).thenReturn(Optional.empty());
		when(repository.lockDueEvent(eq(2L), any())).thenReturn(Optional.of(new DueEvent("Walk", startsAt)));
		when(repository.insertReminders(eq(2L), any())).thenReturn(3);

		assertThat(service.remind(1)).isZero();
		assertThat(service.remind(2)).isEqualTo(3);

		verify(repository).insertReminders(2L, new NotificationPayload("Walk", null, startsAt, null, null));
	}
}
