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
package app.confuzion.backend.model.entity;

import java.util.UUID;

import app.confuzion.backend.model.record.EventResponse.AudienceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One audience entry of an event ({@code event_audience}). Exactly the
 * reference that matches {@link #type} is set, and none for
 * {@link AudienceType#FRIENDS}; a CHECK in the table enforces it. Entries are
 * only ever added or removed, never changed.
 *
 * @author mcawful
 */
@Entity
@Table(name = "event_audience")
@Getter
@NoArgsConstructor
public class EventAudience {
	/**
	 * The internal ID, which also orders an event's entries.
	 */
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * The event's internal ID.
	 */
	@Column(name = "event_id", nullable = false, updatable = false)
	private long eventId;

	/**
	 * The kind of entry.
	 */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private AudienceType type;

	/**
	 * The Discord server ID, for {@link AudienceType#GUILD}.
	 */
	@Column(name = "guild_id", updatable = false)
	private Long guildId;

	/**
	 * The circle's internal ID, for {@link AudienceType#CIRCLE}.
	 */
	@Column(name = "circle_id", updatable = false)
	private Long circleId;

	/**
	 * The person's user ID, for {@link AudienceType#USER}.
	 */
	@Column(name = "user_id", updatable = false)
	private UUID userId;

	/**
	 * Creates an entry.
	 *
	 * @param eventId the event's internal ID
	 * @param ref     what the entry names
	 */
	public EventAudience(long eventId, Ref ref) {
		this.eventId = eventId;
		this.type = ref.type();
		this.guildId = ref.guildId();
		this.circleId = ref.circleId();
		this.userId = ref.userId();
	}

	/**
	 * Returns what this entry names.
	 *
	 * @return the {@link Ref}
	 */
	public Ref ref() {
		return new Ref(type, guildId, circleId, userId);
	}

	/**
	 * What an audience entry names, by internal IDs, so two entries can be compared
	 * for equality.
	 *
	 * @param type     the kind of entry
	 * @param guildId  the Discord server ID, for {@link AudienceType#GUILD}
	 * @param circleId the circle's internal ID, for {@link AudienceType#CIRCLE}
	 * @param userId   the person's user ID, for {@link AudienceType#USER}
	 * @author mcawful
	 */
	public record Ref(AudienceType type, Long guildId, Long circleId, UUID userId) {
	}
}
