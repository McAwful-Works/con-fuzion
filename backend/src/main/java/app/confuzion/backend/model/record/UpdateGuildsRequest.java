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

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code PUT /api/me/guilds}: sets discoverability for several of
 * the user's servers at once, such as onboarding's "select all".
 *
 * @param guilds the changes, one per server, each server at most once
 * @author mcawful
 */
public record UpdateGuildsRequest(
		@Schema(requiredMode = RequiredMode.REQUIRED, description = "One entry per server; a guildId may appear only once.") @NotNull @Size(min = 1, max = UpdateGuildsRequest.MAX_GUILDS) List<@NotNull @Valid GuildUpdate> guilds) {
	/**
	 * The most entries in one request: the most servers a Discord account (with
	 * Nitro) can be in.
	 */
	public static final int MAX_GUILDS = 200;

	/**
	 * Returns whether every {@code guildId} appears at most once. Bean Validation
	 * reports a {@code false} here as a 400.
	 *
	 * @return {@code true} when there are no duplicates
	 */
	@JsonIgnore
	@Schema(hidden = true)
	@AssertTrue(message = "guildId values must be unique")
	public boolean isGuildIdsUnique() {
		if (guilds == null)
			return true;
		var ids = guilds.stream().filter(Objects::nonNull).map(GuildUpdate::guildId).filter(Objects::nonNull).toList();
		return ids.stream().distinct().count() == ids.size();
	}

	/**
	 * One server's change.
	 *
	 * @param guildId      the Discord server ID
	 * @param discoverable whether people in this server may find the user
	 * @author mcawful
	 */
	public record GuildUpdate(
			@Schema(requiredMode = RequiredMode.REQUIRED, pattern = GuildResponse.SNOWFLAKE_PATTERN, example = "81384788765712384") @NotNull @Pattern(regexp = GuildResponse.SNOWFLAKE_PATTERN) String guildId,
			@Schema(requiredMode = RequiredMode.REQUIRED) @NotNull Boolean discoverable) {
	}
}
