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

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import app.confuzion.backend.model.record.GuildListResponse;
import app.confuzion.backend.model.record.GuildResponse;
import app.confuzion.backend.model.record.MeResponse;
import app.confuzion.backend.model.record.ResponseRecord;
import app.confuzion.backend.model.record.UpdateGuildRequest;
import app.confuzion.backend.model.record.UpdateMeRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

/**
 * The {@link RestController} for the signed-in user's own profile and Discord
 * servers. Every method is a contract stub that answers 501 until #11
 * implements it; request validation already runs, so a bad request is a 400.
 *
 * @author mcawful
 */
@RestController
@RequestMapping("/api/me")
@Tag(name = "me", description = "The signed-in user's profile and Discord servers")
public class MeController {
	/**
	 * Returns the signed-in user's profile.
	 *
	 * @return the {@link MeResponse} envelope
	 */
	@GetMapping
	@Operation(summary = "Get the signed-in user's profile")
	public ResponseRecord<MeResponse> getMe() {
		throw notImplemented();
	}

	/**
	 * Updates the signed-in user's settings.
	 *
	 * @param request the {@link UpdateMeRequest}
	 * @return the updated {@link MeResponse} envelope
	 */
	@PatchMapping
	@Operation(summary = "Update the signed-in user's time zone or push setting", description = "A field that is missing or null leaves that setting unchanged.")
	public ResponseRecord<MeResponse> updateMe(@Valid @RequestBody UpdateMeRequest request) {
		throw notImplemented();
	}

	/**
	 * Returns the signed-in user's Discord servers as of their last login sync.
	 *
	 * @return the {@link GuildListResponse} envelope
	 */
	@GetMapping("/guilds")
	@Operation(summary = "List the signed-in user's Discord servers", description = "As of the last login sync, sorted by name.")
	public ResponseRecord<GuildListResponse> getGuilds() {
		throw notImplemented();
	}

	/**
	 * Sets whether the signed-in user is discoverable in one of their servers.
	 *
	 * @param guildId the Discord server ID
	 * @param request the {@link UpdateGuildRequest}
	 * @return the updated {@link GuildResponse} envelope
	 */
	@PutMapping("/guilds/{guildId}")
	@Operation(summary = "Set whether the signed-in user is discoverable in one of their servers", description = "404 when the server isn't one of the user's synced servers.")
	public ResponseRecord<GuildResponse> updateGuild(
			@Parameter(description = "Discord server ID (a snowflake, as a string)") @PathVariable @Pattern(regexp = GuildResponse.SNOWFLAKE_PATTERN) String guildId,
			@Valid @RequestBody UpdateGuildRequest request) {
		throw notImplemented();
	}

	/**
	 * Creates the 501 a stub answers with, which the
	 * {@code GlobalControllerExceptionHandler} turns into the standard error
	 * envelope.
	 *
	 * @return a {@link ResponseStatusException} for {@code NOT IMPLEMENTED}
	 */
	private static ResponseStatusException notImplemented() {
		return new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
	}
}
