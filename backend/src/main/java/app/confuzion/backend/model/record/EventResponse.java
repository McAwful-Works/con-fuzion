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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import app.confuzion.backend.model.ResponseBodyObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * An event as the viewer may see it (D-012). The location is withheld from
 * everyone but the host when it is {@link LocationVisibility#CONFIRMED} (until
 * RSVP in M3 lets confirmed attendees see it too), and only the host gets the
 * audience.
 *
 * @param id                 the event's public ID, used in URLs
 * @param host               the host's public profile
 * @param title              the title
 * @param description        the description as plain text, or {@code null}
 * @param location           where the event is, or {@code null} when
 *                           {@code locationHidden}
 * @param locationHidden     whether the location is withheld from this viewer
 * @param locationVisibility who may see the location
 * @param tag                a short label such as {@code Panel}, or
 *                           {@code null}
 * @param startsAt           when the event starts
 * @param endsAt             when it ends, or {@code null} when open-ended
 * @param timeZone           the IANA time zone the host shows times in
 * @param signupOpensAt      when sign-up opens, or {@code null} when it opens
 *                           as soon as the event is published
 * @param capacity           the number of spots, or {@code null} for unlimited
 * @param status             whether the event is on or cancelled
 * @param isHost             whether the viewer is the host
 * @param audience           who the event is for: the host's entries, or
 *                           {@code null} for every other viewer
 * @author mcawful
 */
public record EventResponse(@Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
		@Schema(requiredMode = RequiredMode.REQUIRED) PersonResponse host,
		@Schema(requiredMode = RequiredMode.REQUIRED, example = "Cosplay photo walk") String title,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String description,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, example = "Hall B fountain") String location,
		@Schema(requiredMode = RequiredMode.REQUIRED) boolean locationHidden,
		@Schema(requiredMode = RequiredMode.REQUIRED) LocationVisibility locationVisibility,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, example = "Photoshoot") String tag,
		@Schema(requiredMode = RequiredMode.REQUIRED) Instant startsAt,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) Instant endsAt,
		@Schema(requiredMode = RequiredMode.REQUIRED, example = "America/Chicago") String timeZone,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) Instant signupOpensAt,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, minimum = "1", maximum = "10000") Integer capacity,
		@Schema(requiredMode = RequiredMode.REQUIRED) Status status,
		@JsonProperty("isHost") @Schema(name = "isHost", requiredMode = RequiredMode.REQUIRED) boolean isHost,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "Only the host gets this; it is null for everyone else.") List<AudienceEntry> audience)
		implements ResponseBodyObject {
	/**
	 * Who may see an event's location.
	 *
	 * @author mcawful
	 */
	public enum LocationVisibility {
		/**
		 * Everyone who can see the event.
		 */
		VIEWERS,

		/**
		 * The host and, from M3, confirmed attendees.
		 */
		CONFIRMED
	}

	/**
	 * Whether an event is on.
	 *
	 * @author mcawful
	 */
	public enum Status {
		/**
		 * The event is on.
		 */
		PUBLISHED,

		/**
		 * The host cancelled it. Only the host still sees it.
		 */
		CANCELLED
	}

	/**
	 * The kind of an audience entry.
	 *
	 * @author mcawful
	 */
	public enum AudienceType {
		/**
		 * People who are discoverable in a Discord server where the host is
		 * discoverable too.
		 */
		GUILD,

		/**
		 * The host's in-app friends.
		 */
		FRIENDS,

		/**
		 * The members of one of the host's circles.
		 */
		CIRCLE,

		/**
		 * One person the host can pick.
		 */
		USER
	}

	/**
	 * One audience entry, as the host sees it. Exactly the field that matches
	 * {@code type} is set; a {@code FRIENDS} entry has none.
	 *
	 * @param type     the kind of entry
	 * @param guildId  the Discord server ID, for {@code GUILD}
	 * @param circleId the circle's public ID, for {@code CIRCLE}
	 * @param userId   the person's user ID, for {@code USER}
	 * @param label    the server name, circle name or person's display name, or
	 *                 {@code null} for {@code FRIENDS} or when the host is no
	 *                 longer in the server
	 * @author mcawful
	 */
	@Schema(name = "AudienceEntryResponse")
	public record AudienceEntry(@Schema(requiredMode = RequiredMode.REQUIRED) AudienceType type,
			@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, pattern = GuildResponse.SNOWFLAKE_PATTERN, example = "81384788765712384") String guildId,
			@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) UUID circleId,
			@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) UUID userId,
			@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, example = "Example Con Community") String label) {
	}
}
