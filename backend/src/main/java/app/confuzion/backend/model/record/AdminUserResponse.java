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
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * A user as moderators see them (D-015): who they are on Discord, when they
 * joined and last signed in, any suspension or ban, and how often they've been
 * reported. Never their servers, friends, circles or RSVPs.
 *
 * @param id             the user's ID
 * @param discordId      the Discord user ID
 * @param username       the Discord username
 * @param globalName     the Discord display name, or {@code null}
 * @param createdAt      when they first signed in
 * @param lastLoginAt    when they last signed in
 * @param suspendedUntil when their suspension ends, or {@code null}
 * @param ban            their ban, or {@code null}
 * @param reportCount    how many reports name them, in any status
 * @author mcawful
 */
public record AdminUserResponse(@Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
		@Schema(requiredMode = RequiredMode.REQUIRED, pattern = GuildResponse.SNOWFLAKE_PATTERN, example = "80351110224678912", description = "The Discord user ID, as a string. Bans are by this ID.") String discordId,
		@Schema(requiredMode = RequiredMode.REQUIRED, example = "con_goer") String username,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, example = "Con Goer") String globalName,
		@Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
		@Schema(requiredMode = RequiredMode.REQUIRED) Instant lastLoginAt,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "When the suspension ends. Null when not suspended; a past time means it has ended.") Instant suspendedUntil,
		@Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "Null when the Discord ID isn't banned.") Ban ban,
		@Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0", description = "Reports naming this user, open or resolved.") int reportCount) {
	/**
	 * A ban on a Discord ID.
	 *
	 * @param reason    the admin's reason
	 * @param createdAt when the ban was made
	 * @author mcawful
	 */
	@Schema(name = "AdminUserBanResponse")
	public record Ban(@Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 500) String reason,
			@Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt) {
	}
}
