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
package app.confuzion.backend.model.record;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import app.confuzion.backend.model.record.EventResponse.AudienceType;
import app.confuzion.backend.model.record.EventResponse.LocationVisibility;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /api/events} and {@code PATCH /api/events/{eventId}}.
 * Both take the whole event: an update replaces every editable field. Text
 * fields other than the description have no leading or trailing whitespace.
 *
 * @param title              the title
 * @param description        the description as plain text, or {@code null}.
 *                           Empty or whitespace-only text is stored as
 *                           {@code null}
 * @param location           where the event is
 * @param tag                a short label, or {@code null}
 * @param startsAt           when the event starts: in the future on create, and
 *                           within a year of now either way
 * @param endsAt             when it ends, after {@code startsAt} and at most 7
 *                           days later, or {@code null}
 * @param timeZone           the IANA time zone to show times in
 * @param capacity           the number of spots, 1 to 10,000, or {@code null}
 *                           for unlimited
 * @param signupOpensAt      when sign-up opens, before {@code startsAt}, or
 *                           {@code null} to open it at once
 * @param locationVisibility who may see the location
 * @param audience           who the event is for, 1 to 50 distinct entries
 * @author mcawful
 */
public record EventRequest(
		@Schema(requiredMode = RequiredMode.REQUIRED, example = "Cosplay photo walk") @NotNull @Size(min = 1, max = 100) @Pattern(regexp = CircleRequest.TRIMMED) String title,
		@Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) @Size(max = 2000) String description,
		@Schema(requiredMode = RequiredMode.REQUIRED, example = "Hall B fountain") @NotNull @Size(min = 1, max = 200) @Pattern(regexp = CircleRequest.TRIMMED) String location,
		@Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, example = "Photoshoot") @Size(min = 1, max = 30) @Pattern(regexp = CircleRequest.TRIMMED) String tag,
		@Schema(requiredMode = RequiredMode.REQUIRED, description = "In the future when creating, and within a year of now either way. An update may keep a start that has passed, but can't move the start into the past.") @NotNull @Future(groups = EventRequest.Create.class) Instant startsAt,
		@Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, description = "After startsAt and at most 7 days later.") Instant endsAt,
		@Schema(requiredMode = RequiredMode.REQUIRED, example = "America/Chicago", maxLength = IanaTimeZone.MAX_LENGTH) @NotNull @IanaTimeZone String timeZone,
		@Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) @Min(1) @Max(10000) Integer capacity,
		@Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, description = "Before startsAt and at most a year earlier.") Instant signupOpensAt,
		@Schema(requiredMode = RequiredMode.REQUIRED) @NotNull LocationVisibility locationVisibility,
		@Schema(requiredMode = RequiredMode.REQUIRED, description = "1 to 50 entries, each at most once.") @NotNull @Size(min = 1, max = EventRequest.MAX_AUDIENCE) List<@NotNull @Valid AudienceEntry> audience) {

	/**
	 * The most audience entries on one event.
	 */
	public static final int MAX_AUDIENCE = 50;

	/**
	 * The longest an event may last.
	 */
	public static final Duration MAX_LENGTH = Duration.ofDays(7);

	/**
	 * How far from now an event may start, and how long before its start sign-up
	 * may open.
	 */
	public static final Duration MAX_HORIZON = Duration.ofDays(365);

	/**
	 * The validation group for rules that apply only when creating an event.
	 *
	 * @author mcawful
	 */
	public interface Create {
	}

	/**
	 * Returns whether the end, if any, is after the start and at most
	 * {@link #MAX_LENGTH} later.
	 *
	 * @return {@code true} when the times are in order or not given
	 */
	@JsonIgnore
	@Schema(hidden = true)
	@AssertTrue(message = "endsAt must be after startsAt, and at most 7 days later")
	public boolean isEndAfterStart() {
		return endsAt == null || startsAt == null
				|| endsAt.isAfter(startsAt) && !endsAt.isAfter(startsAt.plus(MAX_LENGTH));
	}

	/**
	 * Returns whether sign-up, if scheduled, opens before the start and at most
	 * {@link #MAX_HORIZON} earlier.
	 *
	 * @return {@code true} when the times are in order or not given
	 */
	@JsonIgnore
	@Schema(hidden = true)
	@AssertTrue(message = "signupOpensAt must be before startsAt, and at most a year earlier")
	public boolean isSignupBeforeStart() {
		return signupOpensAt == null || startsAt == null
				|| signupOpensAt.isBefore(startsAt) && !signupOpensAt.isBefore(startsAt.minus(MAX_HORIZON));
	}

	/**
	 * Returns whether the start is within {@link #MAX_HORIZON} of now, either way.
	 * This also keeps every time inside the database's range.
	 *
	 * @return {@code true} when the start is in range or not given
	 */
	@JsonIgnore
	@Schema(hidden = true)
	@AssertTrue(message = "startsAt must be within a year of now")
	public boolean isStartWithinHorizon() {
		var now = Instant.now();
		return startsAt == null
				|| !startsAt.isBefore(now.minus(MAX_HORIZON)) && !startsAt.isAfter(now.plus(MAX_HORIZON));
	}

	/**
	 * Returns whether every audience entry appears at most once.
	 *
	 * @return {@code true} when there are no duplicates
	 */
	@JsonIgnore
	@Schema(hidden = true)
	@AssertTrue(message = "audience entries must be unique")
	public boolean isAudienceUnique() {
		if (audience == null)
			return true;
		var entries = audience.stream().filter(Objects::nonNull).toList();
		return entries.stream().distinct().count() == entries.size();
	}

	/**
	 * One audience entry. Exactly the field that matches {@code type} is set; a
	 * {@code FRIENDS} entry has none. The host may name only a server where they
	 * are discoverable, one of their own circles, or a person they can pick
	 * (D-011).
	 *
	 * @param type     the kind of entry
	 * @param guildId  the Discord server ID, for {@code GUILD}
	 * @param circleId the circle's public ID, for {@code CIRCLE}
	 * @param userId   the person's user ID, for {@code USER}
	 * @author mcawful
	 */
	@Schema(name = "AudienceEntryRequest")
	public record AudienceEntry(@Schema(requiredMode = RequiredMode.REQUIRED) @NotNull AudienceType type,
			@Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, pattern = GuildResponse.SNOWFLAKE_PATTERN, example = "81384788765712384") @Pattern(regexp = GuildResponse.SNOWFLAKE_PATTERN) String guildId,
			@Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) UUID circleId,
			@Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) UUID userId) {
		/**
		 * Returns whether exactly the field that matches {@code type} is set.
		 *
		 * @return {@code true} when the entry is well formed, or has no type (which
		 *         {@code @NotNull} reports)
		 */
		@JsonIgnore
		@Schema(hidden = true)
		@AssertTrue(message = "set exactly the field that matches type")
		public boolean isReferenceMatchingType() {
			if (type == null)
				return true;
			return (guildId != null) == (type == AudienceType.GUILD)
					&& (circleId != null) == (type == AudienceType.CIRCLE)
					&& (userId != null) == (type == AudienceType.USER);
		}
	}
}
