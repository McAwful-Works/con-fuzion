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

import java.time.Instant;
import java.util.UUID;

import app.confuzion.backend.model.record.EventResponse.LocationVisibility;
import app.confuzion.backend.model.record.EventResponse.Status;
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
import lombok.Setter;

/**
 * A meetup ({@code event}). Its audience entries are {@link EventAudience}
 * rows. Columns that M2 doesn't use yet ({@code confirmed_count},
 * {@code confirm_window_minutes}, {@code created_at}) aren't mapped, so inserts
 * take their database defaults.
 *
 * @author mcawful
 */
@Entity
@Table(name = "event")
@Getter
@Setter
@NoArgsConstructor
public class Event {
	/**
	 * The internal ID. Never sent to clients.
	 */
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * The public ID, used in URLs and cursors.
	 */
	@Column(name = "public_id", nullable = false, updatable = false)
	private UUID publicId;

	/**
	 * The host's user ID.
	 */
	@Column(name = "host_id", nullable = false, updatable = false)
	private UUID hostId;

	/**
	 * The title.
	 */
	@Column(nullable = false)
	private String title;

	/**
	 * The description, or {@code null}.
	 */
	@Column
	private String description;

	/**
	 * Where the event is.
	 */
	@Column(name = "location_text", nullable = false)
	private String locationText;

	/**
	 * A short label, or {@code null}.
	 */
	@Column
	private String tag;

	/**
	 * When the event starts.
	 */
	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	/**
	 * When it ends, or {@code null}.
	 */
	@Column(name = "ends_at")
	private Instant endsAt;

	/**
	 * The IANA time zone the host shows times in.
	 */
	@Column(name = "time_zone", nullable = false)
	private String timeZone;

	/**
	 * The number of spots, or {@code null} for unlimited.
	 */
	@Column
	private Integer capacity;

	/**
	 * When sign-up opens, or {@code null} for at once.
	 */
	@Column(name = "signup_opens_at")
	private Instant signupOpensAt;

	/**
	 * Who may see the location.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "location_visibility", nullable = false)
	private LocationVisibility locationVisibility;

	/**
	 * Whether the event is on.
	 */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	/**
	 * When the event was last changed.
	 */
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
